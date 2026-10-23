import { useEffect, useState } from "react";
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowRight, Check, Send } from "lucide-react";
import { useSession } from "../auth";
import { type Account, type Balance, type Beneficiary, type Payment, ApiError, page, pending, posted, query, reason, request, uuid } from "./api";
import { type Attempt, clearAttempt, readAttempt, saveAttempt } from "./attempt";
import { amount, minor, money } from "./money";
import { Badge, Confirm, Empty, Failure, Loading } from "./Common";

export function Transfers({ subject, accounts, beneficiaries, navigate }: { subject: string; accounts: Account[]; beneficiaries: Beneficiary[]; navigate: (page: string) => void }) {
  const session = useSession();
  const [attempt, setAttempt] = useState<Attempt | null>(() => readAttempt(subject));
  const [stage, setStage] = useState(1); const [sourceId, setSource] = useState(""); const [destinationId, setDestination] = useState("");
  const [value, setValue] = useState(""); const [reference, setReference] = useState(""); const [error, setError] = useState<Error | null>(null);
  const [reviewing, setReviewing] = useState(false);
  const active = accounts.filter(a => a.status === "ACTIVE");
  const source = active.find(a => a.accountId === sourceId) ?? active[0];
  const destinations = [...beneficiaries.filter(b => b.status === "ACTIVE" && b.currency === source?.currency && b.destinationAccountId !== source?.accountId).map(b => ({ id: b.destinationAccountId, label: b.label })),
    ...active.filter(a => a.currency === source?.currency && a.accountId !== source.accountId && !beneficiaries.some(b => b.destinationAccountId === a.accountId && b.status === "ACTIVE")).map(a => ({ id: a.accountId, label: "Your account · " + a.accountId.slice(-8) }))];
  const balance = useQuery({ queryKey: ["bank", subject, "balance", source?.accountId], enabled: !!source && !attempt && stage === 3, retry: false,
    queryFn: ({ signal }) => request<Balance>(session.token, "/accounts/" + source?.accountId + "/balance", { signal }) });
  const commit = (next: Attempt) => { saveAttempt(next); setAttempt(next); };
  const send = useMutation({
    mutationFn: async (current: Attempt) => {
      const locked = { ...current, submitted: true };
      commit(locked);
      const result = await request<Payment>(session.token, "/transfers", { method: "POST", key: locked.key, body: locked.body });
      if (!uuid.test(result.paymentId)) throw new ApiError(502);
      commit({ ...locked, paymentId: result.paymentId });
      return result;
    },
  });
  const review = async () => {
    setError(null); setReviewing(true);
    try {
      if (!source || !destinations.some(d => d.id === destinationId)) throw new Error("Choose an active source and a recipient in the same currency.");
      const normalized = amount(value);
      const latest = await balance.refetch();
      if (!latest.data || latest.error) throw latest.error ?? new Error("We could not verify the available balance.");
      if (minor(normalized) > minor(latest.data.available)) throw new Error("The amount exceeds the current available balance.");
      if (minor(normalized) > 10000000n) throw new Error("The amount exceeds the 100,000.00 simulation transfer limit.");
      commit({ owner: subject, key: crypto.randomUUID(), body: { sourceAccountId: source.accountId, destinationAccountId: destinationId,
        amount: normalized, currency: source.currency, reference: reference.trim() }, submitted: false });
    } catch (failure) { setError(failure instanceof Error ? failure : new Error("Check the transfer details.")); }
    finally { setReviewing(false); }
  };
  const reset = () => { clearAttempt(subject); setAttempt(null); send.reset(); setStage(1); setValue(""); setReference(""); setError(null); };
  // A refusal on a later retry cannot prove that the first ambiguous submission was rejected.
  const definitiveRejection = send.variables?.submitted === false && send.error instanceof ApiError && [400,403,404,422].includes(send.error.status);
  return <>
    <div className="section-heading"><h2>A thoughtful way to send</h2><span className="small-label">INTERNAL TRANSFER</span></div>
    <section className="surface bank-panel bank-transfer">
      {!attempt ? !source ? <Empty title="An active account comes first"><p>Open or unfreeze an account before sending money.</p><button className="text-button" onClick={() => navigate("Accounts")}>Manage accounts <ArrowRight size={14} /></button></Empty> :
        <><ol className="bank-steps" aria-label="Transfer steps">{["From", "To", "Amount", "Review"].map((name, index) => <li key={name} className={stage === index + 1 ? "current" : stage > index + 1 ? "done" : ""}><span>{stage > index + 1 ? <Check size={12} /> : index + 1}</span>{name}</li>)}</ol>
          {stage === 1 && <div className="bank-form"><h3>Choose your source account</h3><label>From account<select value={source.accountId} onChange={e => { setSource(e.target.value); setDestination(""); }}>{active.map(a => <option value={a.accountId} key={a.accountId}>{a.currency} · {a.accountId.slice(-8)}</option>)}</select></label>
            <button className="button" onClick={() => setStage(2)}>Continue to recipient <ArrowRight size={16} /></button></div>}
          {stage === 2 && <div className="bank-form"><h3>Who is it for?</h3>{!destinations.length ? <Empty title="Save a recipient first"><p>Add an active beneficiary in {source.currency}, or open another account in that currency.</p><button className="text-button" onClick={() => navigate("Beneficiaries")}>Add a beneficiary</button></Empty> :
            <><label>Recipient<select required value={destinationId} onChange={e => setDestination(e.target.value)}><option value="">Choose a recipient</option>{destinations.map(d => <option key={d.id} value={d.id}>{d.label} · {d.id.slice(-8)}</option>)}</select></label>
              {destinationId && <p className="bank-reference">{destinationId}</p>}<button className="button" disabled={!destinationId} onClick={() => setStage(3)}>Continue to amount <ArrowRight size={16} /></button></>}
            <button className="text-button" onClick={() => setStage(1)}>Back to source</button></div>}
          {stage === 3 && <form className="bank-form" onSubmit={event => { event.preventDefault(); void review(); }}><h3>A little detail goes a long way</h3>
            <label>Amount ({source.currency})<input required inputMode="decimal" value={value} onChange={e => setValue(e.target.value)} placeholder="0.00" autoComplete="off" /></label>
            {balance.data && <small>Available: {money(balance.data.available, source.currency)}</small>}<Failure error={balance.error} retry={() => void balance.refetch()} />
            <label>Reference (optional)<input maxLength={140} value={reference} onChange={e => setReference(e.target.value)} /></label>
            <small>Amounts above 10,000.00 require review. No currency conversion is performed.</small>
            <div className="bank-actions"><button className="button" disabled={reviewing}>{reviewing ? "Checking details..." : "Review transfer"}</button><button className="text-button" type="button" onClick={() => setStage(2)}>Back to recipient</button></div></form>}
          <Failure error={error} /></> :
        <><h3>{attempt.paymentId ? "Your transfer" : attempt.submitted ? "Check your submission" : "Review before you send"}</h3>
          <dl className="bank-facts"><dt>From account</dt><dd>{attempt.body.sourceAccountId}</dd><dt>To account</dt><dd>{attempt.body.destinationAccountId}</dd>
            <dt>Amount</dt><dd className="bank-large">{money(attempt.body.amount, attempt.body.currency)}</dd><dt>Reference</dt><dd>{attempt.body.reference || "No reference"}</dd></dl>
          {!attempt.paymentId && <>
            <p>{attempt.submitted ? "A response has not been confirmed. Retrying uses the original request identity and unchanged details." : "Check the full recipient reference. The final balance and account checks happen when the transfer executes."}</p>
            <button className="button" disabled={send.isPending} onClick={() => send.mutate(attempt)}><Send size={16} />{send.isPending ? "Submitting..." : attempt.submitted ? "Retry same transfer" : "Confirm and send"}</button>
            {!attempt.submitted && <button className="text-button" disabled={send.isPending} onClick={() => { clearAttempt(subject); setAttempt(null); setStage(3); }}>Edit transfer details</button>}
            {attempt.submitted && definitiveRejection && <button className="text-button" onClick={reset}>Start a new draft after rejection</button>}
            <Failure error={send.error} /></>}
          {attempt.paymentId && <PaymentResult subject={subject} id={attempt.paymentId} finish={reset} />}
        </>}
    </section>
    <TransferList subject={subject} />
  </>;
}
export function PaymentResult({ subject, id, finish }: { subject: string; id: string; finish?: () => void }) {
  const session = useSession(); const cache = useQueryClient(); const [confirmCancel, setConfirmCancel] = useState(false);
  const payment = useQuery({ queryKey: ["bank", subject, "payment", id], queryFn: ({ signal }) => request<Payment>(session.token, "/transfers/" + id, { signal }), retry: false,
    refetchInterval: state => state.state.data && (pending(state.state.data.status) || (state.state.data.status === "COMPLETED" && !posted(state.state.data))) ? 2000 : false });
  const cancel = useMutation({ mutationFn: () => request<Payment>(session.token, "/transfers/" + id + "/cancel",
    { method: "POST", body: { expectedVersion: payment.data?.version } }),
    onSuccess: result => { cache.setQueryData(["bank", subject, "payment", id], result); setConfirmCancel(false); void cache.invalidateQueries({ queryKey: ["bank", subject, "transfers"] }); },
    onError: () => { void payment.refetch(); } });
  useEffect(() => {
    if (payment.data && ["COMPLETED", "REVERSED", "FAILED", "REJECTED"].includes(payment.data.status)) {
      for (const area of ["balance", "history", "activity", "transfers"]) void cache.invalidateQueries({ queryKey: ["bank", subject, area] });
    }
  }, [payment.data?.status, payment.data?.journalId, cache, subject]); // State transitions, not polling attempts, refresh read views.
  const current = payment.data;
  return <section className="bank-result" aria-label="Transfer result"><p className="bank-reference">Transfer reference: {id}</p>
    {payment.isPending ? <Loading text="Checking the recorded transfer..." /> : <>
      <Failure error={payment.error} retry={() => void payment.refetch()} />
      {current && <>{payment.error && <p role="status">Showing the last confirmed record. The latest refresh is unavailable.</p>}<Badge status={current.status === "COMPLETED" && !posted(current) ? "UNCONFIRMED" : current.status} />
        <h3>{posted(current) ? "Transfer completed" : current.code === "CANCELLED" && current.status === "REJECTED" ? "Transfer cancelled" :
          current.status === "PENDING_RISK_REVIEW" ? "Your transfer is under review" :
          current.status === "FAILED" ? "Transfer failed" : current.status === "REJECTED" ? "Transfer rejected" :
          current.status === "REVERSED" ? "Transfer reversed" : current.status === "REVERSAL_PENDING" ? "Reversal in progress" : "Waiting for final confirmation"}</h3>
        <p>{posted(current) ? "The ledger has confirmed the posting." : current.status === "PENDING_RISK_REVIEW" ? "No money is reserved by review. A later decision will recheck funds and restrictions." :
          ["FAILED", "REJECTED"].includes(current.status) ? reason(current.code) :
          current.status === "REVERSED" ? "A separate compensating journal reversed this transfer. Its original journal remains unchanged." :
          "This status does not confirm that money has moved. The recorded outcome will appear here."}</p>
        {current.journalId && <p className="bank-reference">Journal reference: {current.journalId}</p>}
        <button className="text-button" onClick={() => void payment.refetch()}>Refresh transfer status</button>
        {["CREATED", "VALIDATING", "PENDING_RISK_REVIEW"].includes(current.status) && !payment.error &&
          <button className="text-button danger" onClick={() => setConfirmCancel(true)}>Cancel transfer</button>}
        {confirmCancel && <Confirm title="Confirm transfer cancellation" busy={cancel.isPending} confirm={() => cancel.mutate()} cancel={() => setConfirmCancel(false)}>
          <p>Cancellation is available only before execution starts. The latest server version decides the result.</p></Confirm>}
        <Failure error={cancel.error} />
        {finish && ["COMPLETED", "FAILED", "REJECTED", "REVERSED"].includes(current.status) && !payment.error && (current.status !== "COMPLETED" || posted(current)) &&
          <button className="button secondary" onClick={finish}>Start another transfer</button>}
      </>}
    </>}
  </section>;
}
function TransferList({ subject }: { subject: string }) {
  const session = useSession(); const [status, setStatus] = useState(""); const [selected, setSelected] = useState("");
  const transfers = useInfiniteQuery({ queryKey: ["bank", subject, "transfers", status], initialPageParam: undefined as string | undefined, retry: false,
    queryFn: ({ signal, pageParam }) => page<Payment>(session.token, "/transfers" + query({ after: pageParam, status }), signal),
    getNextPageParam: last => last.nextCursor ?? undefined });
  const items = transfers.data?.pages.flatMap(p => p.items) ?? [];
  return <section className="surface bank-panel"><div className="section-heading"><h2>Your transfers</h2><label>Status filter<select value={status} onChange={e => setStatus(e.target.value)}><option value="">All statuses</option>
    {["CREATED","VALIDATING","PROCESSING","PENDING_RISK_REVIEW","COMPLETED","FAILED","REJECTED","REVERSAL_PENDING","REVERSED"].map(s => <option key={s} value={s}>{s.replaceAll("_"," ").toLowerCase()}</option>)}</select></label></div>
    {transfers.isPending ? <Loading /> : transfers.error ? <Failure error={transfers.error} retry={() => void transfers.refetch()} /> :
      !items.length ? <Empty title="No transfers to show"><p>Your accepted transfer requests will appear here with their current status.</p></Empty> :
        <ul className="bank-transfer-list">{items.map(item => <li key={item.paymentId}><div><strong>{money(item.amount, item.currency)}</strong><span>{item.reference || "Internal transfer"}</span><Badge status={item.status === "COMPLETED" && !posted(item) ? "UNCONFIRMED" : item.status} /></div>
          <button className="text-button" onClick={() => setSelected(item.paymentId)}>View transfer {item.paymentId.slice(-8)}</button></li>)}</ul>}
    {transfers.hasNextPage && <button className="button secondary" disabled={transfers.isFetchingNextPage} onClick={() => void transfers.fetchNextPage()}>Load more transfers</button>}
    {selected && <><PaymentResult key={selected} subject={subject} id={selected} /><button className="text-button" onClick={() => setSelected("")}>Close transfer details</button></>}
  </section>;
}
