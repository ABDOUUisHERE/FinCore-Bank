import { useState } from "react";
import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { useSession } from "../auth";
import { request, uuid } from "../banking/api";
import { Failure, Loading } from "../banking/Common";
import "./operations.css";

type Kind = "Customer" | "Payment" | "Journal";
type Customer = { customerId: string; status: string; profileVersion: string; createdAt: string; observedAt: string };
type Transition = { version: string; from: string | null; to: string; reason: string | null; occurredAt: string };
type Payment = { paymentId: string; kind: string; status: string; version: string; terminalCode: string | null; journalId: string | null; originalPaymentId: string | null; createdAt: string; observedAt: string; history: Transition[]; nextCursor: string | null };
type Journal = { journalId: string; operationId: string; currency: string; total: string; reversalOf: string | null; createdAt: string; observedAt: string; source: string; entries: { accountId: string; side: string; amount: string }[] };

export function Inspection() {
  const session = useSession();
  const kinds: Kind[] = [...(session.roles.includes("ADMIN") ? ["Customer", "Payment"] as const : []), ...(session.roles.includes("FINANCE_OPERATOR") ? ["Journal"] as const : [])];
  if (session.status !== "authenticated" || !kinds.length) return <div className="status-panel" role="alert">Inspection access is required</div>;
  return <Lookup key={session.username + kinds.join()} kinds={kinds} />;
}
function Lookup({ kinds }: { kinds: Kind[] }) {
  const [kind, setKind] = useState<Kind>(kinds[0]);
  const [input, setInput] = useState("");
  const [target, setTarget] = useState<{ kind: Kind; id: string } | null>(null);
  const [error, setError] = useState("");
  return <div className="bank-workspace operations-workspace inspection-workspace">
    <section className="surface bank-panel"><h2>Business inspection</h2><p>Look up an exact reference from your investigation. Results show the latest recorded state when observed.</p>
      <form className="bank-form" onSubmit={e => { e.preventDefault(); const id = input.trim().toLowerCase(); if (!uuid.test(id)) { setError("Enter a valid resource UUID."); return; } setError(""); setTarget({ kind, id }); }}>
        <label>Resource type<select value={kind} onChange={e => { setKind(e.target.value as Kind); setTarget(null); setError(""); }}>{kinds.map(k => <option key={k}>{k}</option>)}</select></label>
        <label>Resource UUID<input value={input} onChange={e => setInput(e.target.value)} required maxLength={36} autoComplete="off" spellCheck={false} /></label>
        <button className="button" type="submit">Inspect reference</button>
      </form>{error && <p role="alert">{error}</p>}
      <p>{kind === "Journal" ? "Journal postings require the finance role. Amounts include their currency." : "Operational status excludes personal profile details and payment amounts."}</p>
    </section>
    {target && (target.kind === "Customer" ? <CustomerResult key={target.id} id={target.id} /> : target.kind === "Payment" ? <PaymentResult key={target.id} id={target.id} /> : <JournalResult key={target.id} id={target.id} />)}
  </div>;
}
function CustomerResult({ id }: { id: string }) {
  const session = useSession();
  const query = useQuery({ queryKey: ["inspection", session.username, "customer", id], queryFn: ({ signal }) => request<Customer>(session.token, "/admin/customers/" + id, { signal }), retry: false });
  return <section className="surface bank-panel" aria-label="Inspected customer"><h3>Customer status</h3><Failure error={query.error} retry={() => void query.refetch()} />{query.isPending ? <Loading /> : query.data && <>
    <dl className="bank-facts"><dt>Customer reference</dt><dd><code>{query.data.customerId}</code></dd><dt>Onboarding status</dt><dd>{query.data.status}</dd><dt>Profile version</dt><dd>{query.data.profileVersion}</dd><dt>Created</dt><dd>{date(query.data.createdAt)}</dd><dt>Observed</dt><dd>{date(query.data.observedAt)}</dd></dl>
    <button className="button secondary" disabled={query.isFetching} onClick={() => void query.refetch()}>Refresh customer</button></>}
  </section>;
}
function PaymentResult({ id }: { id: string }) {
  const session = useSession();
  const query = useInfiniteQuery({ queryKey: ["inspection", session.username, "payment", id], initialPageParam: null as string | null,
    queryFn: ({ pageParam, signal }) => request<Payment>(session.token, "/admin/transactions/" + id + (pageParam ? "?beforeVersion=" + pageParam : ""), { signal }),
    getNextPageParam: p => p.nextCursor ?? undefined, retry: false });
  const payment = query.data?.pages[0];
  const history = query.data?.pages.flatMap(p => p.history) ?? [];
  return <section className="surface bank-panel" aria-label="Inspected payment"><h3>Payment status</h3><Failure error={query.error} retry={() => void query.refetch()} />{query.isPending ? <Loading /> : payment && <>
    <dl className="bank-facts"><dt>Payment reference</dt><dd><code>{payment.paymentId}</code></dd><dt>Kind</dt><dd>{payment.kind}</dd><dt>Status</dt><dd>{payment.status}</dd><dt>Version</dt><dd>{payment.version}</dd><dt>Result code</dt><dd>{payment.terminalCode ?? "No recorded result code"}</dd><dt>Journal reference</dt><dd><code>{payment.journalId ?? "No posted journal recorded"}</code></dd><dt>Original payment</dt><dd><code>{payment.originalPaymentId ?? "None"}</code></dd><dt>Created</dt><dd>{date(payment.createdAt)}</dd><dt>Observed</dt><dd>{date(payment.observedAt)}</dd></dl>
    <button className="button secondary" disabled={query.isFetching} onClick={() => void query.refetch()}>Refresh payment</button><h3>Processing history</h3><p>Newest versions first. Each page reflects its own observation; refresh for a newer current state.</p>
    {!history.length ? <p>No recorded transitions.</p> : <div className="inspection-table"><table><caption>Recorded payment transitions</caption><thead><tr><th>Version</th><th>From</th><th>To</th><th>Reason</th><th>Recorded</th></tr></thead><tbody>{history.map(h => <tr key={h.version}><td>{h.version}</td><td>{h.from ?? "Initial"}</td><td>{h.to}</td><td>{h.reason ?? "—"}</td><td>{date(h.occurredAt)}</td></tr>)}</tbody></table></div>}
    {query.hasNextPage && <button className="button secondary" disabled={query.isFetching} onClick={() => void query.fetchNextPage()}>Load older transitions</button>}</>}
  </section>;
}
function JournalResult({ id }: { id: string }) {
  const session = useSession();
  const query = useQuery({ queryKey: ["inspection", session.username, "journal", id], queryFn: ({ signal }) => request<Journal>(session.token, "/admin/ledger/journals/" + id, { signal }), retry: false });
  const j = query.data;
  return <section className="surface bank-panel" aria-label="Inspected journal"><h3>Posted journal</h3><Failure error={query.error} retry={() => void query.refetch()} />{query.isPending ? <Loading /> : j && <>
    <dl className="bank-facts"><dt>Journal reference</dt><dd><code>{j.journalId}</code></dd><dt>Operation reference</dt><dd><code>{j.operationId}</code></dd><dt>Total per side</dt><dd>{j.total} {j.currency}</dd><dt>Reversal of</dt><dd><code>{j.reversalOf ?? "None"}</code></dd><dt>Posted</dt><dd>{date(j.createdAt)}</dd><dt>Observed</dt><dd>{date(j.observedAt)}</dd></dl>
    <div className="inspection-table"><table><caption>Immutable debit and credit postings</caption><thead><tr><th>Ledger account</th><th>Side</th><th>Amount</th></tr></thead><tbody>{j.entries.map(e => <tr key={e.accountId + e.side}><td><code>{e.accountId}</code></td><td>{e.side}</td><td>{e.amount} {j.currency}</td></tr>)}</tbody></table></div>
    <button className="button secondary" disabled={query.isFetching} onClick={() => void query.refetch()}>Refresh journal</button></>}
  </section>;
}
function date(value: string) { return new Date(value).toLocaleString(); }
