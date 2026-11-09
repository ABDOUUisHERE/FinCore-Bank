import { useEffect, useRef, useState, type FormEvent } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { useSession } from "../auth";
import { ApiError, request, uuid } from "../banking/api";
import { Badge, Empty, Failure } from "../banking/Common";

type Match = { customerId: string; displayName: string; status: string; incidentId: string };
type Results = { items: Match[]; hasMore: boolean; expiresAt: string; observedAt: string };
export function CustomerDiscovery({ subject, open }: { subject: string; open: (id: string) => void }) {
  const session = useSession(), cache = useQueryClient(); const [prefix, setPrefix] = useState("");
  const [result, setResult] = useState<Results | null>(null), [error, setError] = useState<Error | null>(null), [busy, setBusy] = useState(false);
  const pending = useRef<AbortController | null>(null);
  useEffect(() => () => pending.current?.abort(), []);
  useEffect(() => {
    if (!result) return;
    const timer = setTimeout(() => setResult(null), Math.max(0, Date.parse(result.expiresAt) - Date.now()));
    return () => clearTimeout(timer);
  }, [result]);
  async function search(event: FormEvent) {
    event.preventDefault(); pending.current?.abort(); setResult(null); setError(null);
    const value = prefix.trim(); if (value.length < 3 || value.length > 80 || Array.from(value).some(char => { const code = char.charCodeAt(0); return code < 32 || code >= 127 && code <= 159; })) { setError(new Error("Enter a name prefix of 3 to 80 characters.")); return; }
    setBusy(true); const controller = new AbortController(); pending.current = controller;
    try {
      const found = await request<Results>(session.token, "/admin/support/customers/search", { method: "POST", body: { namePrefix: value }, signal: controller.signal });
      if (!Array.isArray(found.items) || found.items.length > 20 || typeof found.hasMore !== "boolean" || !Number.isFinite(Date.parse(found.expiresAt)) || Date.parse(found.expiresAt) <= Date.now() ||
        found.items.some(item => !uuid.test(item.customerId) || !uuid.test(item.incidentId) || typeof item.displayName !== "string" || item.status !== "ACTIVE")) throw new ApiError(502);
      if (!controller.signal.aborted) setResult(found);
    } catch (failure) { if (!controller.signal.aborted) setError(failure instanceof Error ? failure : new Error("Customer discovery is unavailable.")); }
    finally { setBusy(false); void cache.invalidateQueries({ queryKey: ["operations", subject, "history"] }); }
  }
  return <section className="surface bank-panel" aria-label="Authorized customer discovery"><h3>Authorized customer discovery</h3>
    <p>Search only customers with a current administrator grant on your active incidents. No permission means no search. Results are checked again before display.</p>
    <form className="bank-form" onSubmit={event => void search(event)}><label>Authorized customer name prefix<input value={prefix} maxLength={80} onChange={event => setPrefix(event.target.value)} autoComplete="off" /></label>
      <button className="button" disabled={busy}>{busy ? "Searching authorized customers..." : "Search authorized customers"}</button></form><Failure error={error} />
    {result && <section aria-label="Authorized customer results"><p>Observed {new Date(result.observedAt).toLocaleString()}. Permission must still be checked when opening a profile.</p>
      {!result.items.length ? <Empty title="No matching customers in your current scope" /> : result.items.map(item => <article key={item.customerId} className="operations-fact"><h4>{item.displayName}</h4><Badge status={item.status} /><small className="bank-reference">{item.customerId}</small>
        <button className="text-button" onClick={() => { setResult(null); open(item.incidentId); }}>Open authorized customer incident</button></article>)}
      {result.hasMore && <p>20 authorized matches are shown. Refine the prefix to narrow the results.</p>}
      <button className="text-button" onClick={() => setResult(null)}>Clear customer results</button>
    </section>}
  </section>;
}
