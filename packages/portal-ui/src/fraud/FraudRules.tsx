import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useSession } from "../auth";
import { ApiError, request, type Currency } from "../banking/api";
import { Failure, Loading } from "../banking/Common";

type Rules = { reviewAmounts: Record<Currency, string>; blockAmounts: Record<Currency, string>; newBeneficiaryAmounts: Record<Currency, string>; frequencyReview: number; frequencyBlock: number; rapidCount: number; velocityCount: number; failedCount: number; newBeneficiaryHours: number; flaggedCountries: string[] };
type Versioned = { version: number; rules: Rules; actor: string; reason: string; createdAt: string };
export function FraudRules() {
  const session = useSession();
  const rules = useQuery({ queryKey: ["fraud", session.username, "rules"], queryFn: ({ signal }) => request<Versioned>(session.token, "/admin/fraud-rules", { signal }), retry: false });
  return <section className="surface bank-panel"><h2>Fraud rules</h2><p>Versioned simulation thresholds. Amounts are independent in each currency; no exchange rate is implied.</p>
    {rules.isPending ? <Loading /> : rules.error ? <Failure error={rules.error} retry={() => void rules.refetch()} /> : <RulesForm key={rules.data.version} current={rules.data} />}</section>;
}
function RulesForm({ current }: { current: Versioned }) {
  const session = useSession(); const cache = useQueryClient(); const [draft, setDraft] = useState<Rules>(current.rules); const [countries, setCountries] = useState(current.rules.flaggedCountries.join(","));
  const [reason, setReason] = useState(""); const [intent, setIntent] = useState<{ expectedVersion: number; rules: Rules; reason: string } | null>(null);
  const save = useMutation({ mutationFn: (body: NonNullable<typeof intent>) => request(session.token, "/admin/fraud-rules", { method: "PUT", body }), onSuccess: () => { setIntent(null); void cache.invalidateQueries({ queryKey: ["fraud", session.username] }); } });
  const currencyFields = { reviewAmounts: "Review amount", blockAmounts: "Strict block amount", newBeneficiaryAmounts: "New beneficiary review amount" } as const;
  const counters = { frequencyReview: "Minute frequency review count", frequencyBlock: "Minute frequency block count", rapidCount: "Same recipient count in ten seconds", velocityCount: "Velocity change minimum minute count", failedCount: "Failed payments in ten minutes", newBeneficiaryHours: "New beneficiary age in hours" } as const;
  return <><p>Current rules version: <strong>{current.version}</strong>. Recorded by {current.actor}, reason {current.reason}.</p>
    <Failure error={save.error} />
    <form className="bank-form" onSubmit={e => { e.preventDefault(); setIntent({ expectedVersion: current.version, rules: { ...draft, flaggedCountries: countries.split(",").map(s => s.trim()).filter(Boolean) }, reason }); save.reset(); }}>
      <fieldset disabled={intent !== null}><legend>Amount thresholds</legend><div className="fraud-rules-grid">{(["MAD", "EUR", "USD"] as Currency[]).map(currency => <div key={currency}><h3>{currency}</h3>{Object.entries(currencyFields).map(([field, label]) => <label key={field}>{label} ({currency})<input required inputMode="decimal" pattern="[0-9]{1,12}\.[0-9]{2}" value={draft[field as keyof typeof currencyFields][currency]} onChange={e => setDraft({ ...draft, [field]: { ...draft[field as keyof typeof currencyFields], [currency]: e.target.value } })} /></label>)}</div>)}</div></fieldset>
      <fieldset disabled={intent !== null}><legend>Activity rules</legend><div className="fraud-rules-grid">{Object.entries(counters).map(([field, label]) => <label key={field}>{label}<input type="number" required min={field === "failedCount" || field === "newBeneficiaryHours" ? 1 : 2} max={field === "newBeneficiaryHours" ? 168 : field === "failedCount" ? 100 : field === "frequencyBlock" ? 1000 : 500} step={1} value={draft[field as keyof typeof counters]} onChange={e => setDraft({ ...draft, [field]: Number(e.target.value) })} /></label>)}</div>
        <label>Flagged simulated country codes<input value={countries} onChange={e => setCountries(e.target.value)} placeholder="ZZ" /></label><p>Comma-separated uppercase two-letter codes. ZZ is a synthetic unknown-region example. This is not sanctions screening or physical location detection.</p>
        <label>Rule change reason code<input required minLength={3} maxLength={100} pattern="[A-Z_]{3,100}" value={reason} onChange={e => setReason(e.target.value)} placeholder="POLICY_TUNING" /></label></fieldset>
      {!intent && <button className="button" type="submit">Review rule changes</button>}</form>
    {intent && <section className="bank-confirm" aria-label="Confirm fraud rule changes"><h3>Confirm fraud rule changes</h3><p>Publish the displayed thresholds after version {intent.expectedVersion}, with reason {intent.reason}.</p><p>Fresh assessments use the new version. An already issued authorization remains bounded by its short expiry.</p>
      <div className="bank-actions"><button className="button" disabled={save.isPending} onClick={() => save.mutate(intent)}>{save.isPending ? "Publishing..." : save.isError ? "Retry same rule change" : "Confirm rule changes"}</button>
        <button className="button secondary" disabled={save.isPending || save.isError && !(save.error instanceof ApiError && [400, 409].includes(save.error.status))} onClick={() => { setIntent(null); save.reset(); }}>Edit thresholds</button>
        {save.isError && <button className="text-button" onClick={() => void cache.invalidateQueries({ queryKey: ["fraud", session.username, "rules"] })}>Refresh recorded rules</button>}</div></section>}
  </>;
}
