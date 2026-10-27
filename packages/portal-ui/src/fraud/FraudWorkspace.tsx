import { useState } from "react";
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useSession } from "../auth";
import { ApiError, query, request, type Currency } from "../banking/api";
import { Badge, Empty, Failure, Loading } from "../banking/Common";
import { money } from "../banking/money";
import { FraudRules } from "./FraudRules";
import "./fraud.css";

export type Explanation = { code: string; score: number; observed: string; threshold: string };
type Context = { kind?: string; actor: string; amount: string; currency: Currency; countryCode: string; recentFailures: number; beneficiary: { saved: boolean; createdAt: string | null } };
type CaseItem = { caseId: string; paymentId: string; version: number; status: string; createdAt: string; sequence: string; context: Context; assessment: { verdict: string; score: number; reasons: Explanation[] } };
type CaseDetail = CaseItem & { rulesVersion: number; alerts?: { alertId: string; verdict: string; score: number; rulesVersion: number; createdAt: string }[]; payment?: { status: string; version: number; journalId: string | null; riskAssessmentId: string | null } | null; paymentUnavailable?: boolean; decisions: { decisionId: string; actor: string; approve: boolean; reason: string; createdAt: string }[] };
export function FraudWorkspace({ section }: { section: string }) {
  const session = useSession();
  // Rendering is gated as well as Gateway and Fraud authorization; ADMIN alone grants no investigation access.
  if (!session.roles.includes("FRAUD_ANALYST")) return <Empty title="Fraud analyst access required" />;
  return <div className="bank-workspace fraud-workspace"><div className="fraud-heading"><div><span className="small-label">SYNTHETIC PAYMENT MONITORING</span><h2>Fraud investigation</h2>
    <p>Inspect recorded risk signals for transfers and merchant authorizations. Decisions are audited and payments are checked again before execution.</p></div></div>
    {section === "Fraud rules" ? <FraudRules /> : <FraudCases />}</div>;
}
function FraudCases() {
  const session = useSession(); const [status, setStatus] = useState(""); const [selected, setSelected] = useState<string | null>(null);
  const key = ["fraud", session.username];
  const cases = useInfiniteQuery({ queryKey: [...key, "cases", status], initialPageParam: undefined as string | undefined,
    queryFn: ({ signal, pageParam }) => request<{ items: CaseItem[]; nextCursor: string | null }>(session.token, "/admin/fraud-cases" + query({ status: status || undefined, before: pageParam }), { signal }),
    getNextPageParam: page => page.nextCursor ?? undefined, retry: false, refetchInterval: 10_000 });
  const detail = useQuery({ queryKey: [...key, "case", selected], enabled: selected !== null, retry: false,
    queryFn: ({ signal }) => request<CaseDetail>(session.token, "/admin/fraud-cases/" + selected, { signal }), refetchInterval: 10_000 });
  const items = cases.data?.pages.flatMap(page => page.items) ?? [];
  return <><section className="surface bank-panel"><div className="section-heading"><div><h2>Latest fraud cases</h2><p>Recorded cases, ordered by their latest assessment.</p></div>
    <label>Case status<select value={status} onChange={e => setStatus(e.target.value)}><option value="">All statuses</option>{["OPEN", "APPROVED", "REJECTED", "BLOCKED", "CLEARED"].map(s => <option key={s} value={s}>{s.toLowerCase()}</option>)}</select></label>
    <button className="button secondary" onClick={() => void cases.refetch()}>Refresh cases</button></div>
    {cases.isPending ? <Loading /> : cases.error ? <Failure error={cases.error} retry={() => void cases.refetch()} /> : !items.length ? <Empty title="No cases in this view"><p>Cases appear when an observed payment requires review or a strict block.</p></Empty> :
      <ul className="fraud-case-list">{items.map(item => <li key={item.caseId}><div><h3>Payment {item.paymentId.slice(-8)}</h3><span>{money(item.context.amount, item.context.currency)}</span><small>{new Date(item.createdAt).toLocaleString()}</small></div>
        <div><Badge status={item.status} /><span>Risk score {item.assessment.score}/100</span></div><button className="button secondary" aria-label={"Investigate payment " + item.paymentId} onClick={() => setSelected(item.caseId)}>Investigate</button></li>)}</ul>}
    {cases.hasNextPage && <button className="button secondary" disabled={cases.isFetchingNextPage} onClick={() => void cases.fetchNextPage()}>Load more cases</button>}
  </section>
  {selected && <section className="surface bank-panel" aria-label="Fraud case investigation">
    {detail.isPending ? <Loading /> : detail.error ? <Failure error={detail.error} retry={() => void detail.refetch()} /> : detail.data ? <CaseInvestigation key={selected} item={detail.data} /> : null}
  </section>}</>;
}
function CaseInvestigation({ item }: { item: CaseDetail }) {
  const session = useSession(); const cache = useQueryClient();
  const [reason, setReason] = useState(""); const [approve, setApprove] = useState(true); const [recorded, setRecorded] = useState(false);
  const [intent, setIntent] = useState<{ expectedVersion: number; approve: boolean; reason: string; key: string } | null>(null);
  const decision = useMutation({ mutationFn: (input: NonNullable<typeof intent>) => request(session.token, "/admin/fraud-cases/" + item.caseId + "/decisions", {
    method: "POST", key: input.key, body: { expectedVersion: input.expectedVersion, approve: input.approve, reason: input.reason },
  }), onSuccess: () => { setIntent(null); setRecorded(true); setReason(""); void cache.invalidateQueries({ queryKey: ["fraud", session.username] }); } });
  const eligible = ["OPEN", "BLOCKED"].includes(item.status);
  const canApprove = item.status === "OPEN" && item.assessment.verdict === "REVIEW";
  return <><div className="section-heading"><h2>Investigate payment</h2><Badge status={item.status} /></div>
    <dl className="bank-facts"><dt>Payment reference</dt><dd className="bank-reference">{item.paymentId}</dd><dt>Case reference</dt><dd className="bank-reference">{item.caseId}</dd>
      <dt>Customer subject</dt><dd className="bank-reference">{item.context.actor}</dd><dt>Amount</dt><dd>{money(item.context.amount, item.context.currency)}</dd>
      <dt>Declared country</dt><dd>{item.context.countryCode || "Not applicable"}</dd><dt>Recorded failed payments</dt><dd>{item.context.recentFailures} in the preceding ten-minute window</dd>
      <dt>Rules version</dt><dd>{item.rulesVersion}</dd><dt>Case version</dt><dd>{item.version}</dd><dt>Latest assessment</dt><dd>{item.assessment.verdict.toLowerCase()} / score {item.assessment.score} of 100</dd>
      <dt>Current payment state</dt><dd>{item.payment ? item.payment.status.toLowerCase().replaceAll("_", " ") : "Unavailable from Payment"}</dd>
      {item.payment?.journalId && <><dt>Confirmed journal</dt><dd className="bank-reference">{item.payment.journalId}</dd></>}
      {item.payment?.riskAssessmentId && <><dt>Payment assessment reference</dt><dd className="bank-reference">{item.payment.riskAssessmentId}</dd></>}</dl>
    {item.alerts && <><h3>Initial fraud alert</h3><ul className="fraud-reasons">{item.alerts.map(a => <li key={a.alertId}><strong>{a.verdict.toLowerCase()} / original score {a.score}</strong><span className="bank-reference">Alert: {a.alertId}</span><small>Rules version {a.rulesVersion} / {new Date(a.createdAt).toLocaleString()}</small></li>)}</ul></>}
    <h3>Recorded risk explanations</h3><p>Scores summarize the signals. A strict blocking rule cannot be approved. Declared country is a simulation input, not physical geolocation.</p>
    <ul className="fraud-reasons">{item.assessment.reasons.map((r, i) => <li key={r.code + i}><strong>{r.code.replaceAll("_", " ").toLowerCase()}</strong><span>Observed: {r.observed}</span><span>Rule: {r.threshold}</span><small>Score contribution: {r.score}</small></li>)}</ul>
    {recorded && <p className="bank-message" role="status">Decision recorded. Payment authorization is rechecked separately.</p>}
    <Failure error={decision.error} />
    {eligible && !intent && <form className="bank-form" onSubmit={e => { e.preventDefault(); setRecorded(false); setIntent({ expectedVersion: item.version, approve: canApprove && approve, reason, key: crypto.randomUUID() }); decision.reset(); }}>
      <h3>Investigation decision</h3><label>Investigation outcome<select value={canApprove && approve ? "approve" : "reject"} onChange={e => setApprove(e.target.value === "approve")}>{canApprove && <option value="approve">Approve this review</option>}<option value="reject">Reject the payment</option></select></label>
      <label>Decision reason code<input required minLength={3} maxLength={100} pattern="[A-Z_]{3,100}" value={reason} onChange={e => setReason(e.target.value)} placeholder="REVIEW_CONFIRMED" /></label>
      <button className="button" type="submit">Review investigation decision</button></form>}
    {intent && <section className="bank-confirm" aria-label="Confirm investigation decision"><h3>Confirm investigation decision</h3><p>{intent.approve ? "Approve the recorded review" : "Reject the payment"} at case version {intent.expectedVersion}, with reason {intent.reason}.</p>
      <p>This records an analyst decision. It does not confirm settlement or override a fresh strict block.</p>
      <div className="bank-actions"><button className="button" disabled={decision.isPending} onClick={() => decision.mutate(intent)}>{decision.isPending ? "Recording..." : decision.isError ? "Retry same decision" : "Confirm decision"}</button>
        <button className="button secondary" disabled={decision.isPending || decision.isError && !(decision.error instanceof ApiError && [400, 409].includes(decision.error.status))} onClick={() => { setIntent(null); decision.reset(); }}>Edit decision</button>
        {decision.isError && <button className="text-button" onClick={() => void cache.invalidateQueries({ queryKey: ["fraud", session.username] })}>Refresh recorded case</button>}</div></section>}
    <h3>Investigation history</h3>{!item.decisions.length ? <p>No investigation decisions recorded.</p> : <ul className="fraud-reasons">{item.decisions.map(d => <li key={d.decisionId}><strong>{d.approve ? "Review approved" : "Payment rejected"}</strong><span>{d.reason}</span><small>Actor: {d.actor}</small><small>{new Date(d.createdAt).toLocaleString()}</small></li>)}</ul>}
  </>;
}
