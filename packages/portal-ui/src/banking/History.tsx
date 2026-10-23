import { useState } from "react";
import { useInfiniteQuery } from "@tanstack/react-query";
import { Download } from "lucide-react";
import { useSession } from "../auth";
import { type Account, type Entry, page, query } from "./api";
import { exportRows, minor, money } from "./money";
import { AccountSelect, Empty, Failure, Loading } from "./Common";

export function History({ subject, account, accounts, select, compact = false }: { subject: string; account?: Account; accounts: Account[]; select: (id: string) => void; compact?: boolean }) {
  const session = useSession();
  const [draft, setDraft] = useState<Record<string, string>>({});
  const [filters, setFilters] = useState<Record<string, string>>({});
  const [error, setError] = useState<Error | null>(null);
  const entries = useInfiniteQuery({ queryKey: ["bank", subject, "history", account?.accountId, filters],
    initialPageParam: undefined as string | undefined, retry: false, enabled: !!account && account.status !== "PROVISIONING",
    queryFn: ({ signal, pageParam }) => page<Entry>(session.token, "/accounts/" + account?.accountId + "/transactions" + query({ ...filters, before: pageParam }), signal),
    getNextPageParam: last => last.nextCursor ?? undefined });
  const items = entries.data?.pages.flatMap(p => p.items) ?? [];
  const update = (key: string, value: string) => setDraft(old => ({ ...old, [key]: value }));
  const apply = () => {
    setError(null);
    try {
      if (draft.from && draft.to && draft.from > draft.to) throw new Error("The start date must be before the end date.");
      if (draft.minimum) minor(draft.minimum);
      if (draft.maximum) minor(draft.maximum);
      if ((draft.minimum && minor(draft.minimum) < 0n) || (draft.maximum && minor(draft.maximum) < 0n)) throw new Error("Amount filters must be non-negative.");
      if (draft.minimum && draft.maximum && minor(draft.minimum) > minor(draft.maximum)) throw new Error("The minimum amount must not exceed the maximum.");
      setFilters({ ...draft });
    } catch (failure) { setError(failure instanceof Error ? failure : new Error("Check the filters.")); }
  };
  return <section className="surface bank-panel" aria-label={compact ? "Recent transactions" : "Transaction history"}>
    <div className="section-heading"><h2>{compact ? "Recent activity" : "Transaction history"}</h2><span className="small-label">CONFIRMED LEDGER ENTRIES</span></div>
    {!account ? <Empty title="No account selected"><p>Open an account to view its confirmed activity.</p></Empty> : <>
      {!compact && <><AccountSelect accounts={accounts} selected={account.accountId} select={id => { select(id); setFilters({}); setDraft({}); }} label="History account" />
        {filters.journal && <p role="status">Showing a linked journal. Clear filters to return to the full history.</p>}
        <form className="bank-filters" onSubmit={e => { e.preventDefault(); apply(); }}>
          <label>Search reference<input type="search" maxLength={140} value={draft.search ?? ""} onChange={e => update("search", e.target.value)} /></label>
          <label>From date (UTC)<input type="date" value={draft.from ?? ""} onChange={e => update("from", e.target.value)} /></label>
          <label>To date (UTC)<input type="date" value={draft.to ?? ""} onChange={e => update("to", e.target.value)} /></label>
          <label>Minimum amount<input inputMode="decimal" value={draft.minimum ?? ""} onChange={e => update("minimum", e.target.value)} /></label>
          <label>Maximum amount<input inputMode="decimal" value={draft.maximum ?? ""} onChange={e => update("maximum", e.target.value)} /></label>
          <label>Direction<select value={draft.side ?? ""} onChange={e => update("side", e.target.value)}><option value="">All directions</option><option value="CREDIT">Money in</option><option value="DEBIT">Money out</option></select></label>
          <label>Transaction type<select value={draft.kind ?? ""} onChange={e => update("kind", e.target.value)}><option value="">All types</option>{["DEPOSIT","TRANSFER","REVERSAL","WITHDRAWAL","MERCHANT_PAYMENT"].map(kind => <option key={kind} value={kind}>{kind.replaceAll("_", " ").toLowerCase()}</option>)}</select></label>
          <div className="bank-actions"><button className="button">Apply filters</button><button type="button" className="text-button" onClick={() => { setFilters({}); setDraft({}); setError(null); }}>Clear filters</button></div>
        </form><Failure error={error} /></>}
      {account.status === "PROVISIONING" ? <Loading text="History will be available once account opening is confirmed." /> : entries.isPending ? <Loading /> :
        entries.error ? <Failure error={entries.error} retry={() => void entries.refetch()} /> : !items.length ? <Empty title="No matching activity"><p>Only confirmed postings appear here. Pending requests are shown under Transfers.</p></Empty> :
          <><div className="bank-table-wrap"><table className="bank-table"><thead><tr><th scope="col">Transaction</th><th scope="col">Date (UTC)</th><th scope="col">Amount</th></tr></thead>
            <tbody>{(compact ? items.slice(0, 5) : items).map(entry => <tr key={entry.journalId} id={"entry-" + entry.journalId}><td>
              <strong>{entry.reference || entry.kind.toLowerCase()}</strong><small>{entry.kind.replaceAll("_", " ").toLowerCase()}{entry.reversalOf ? " · reversal" : ""}</small>
              {!compact && <details><summary>Entry details {entry.journalId.slice(-8)}</summary><dl className="bank-facts"><dt>Journal reference</dt><dd>{entry.journalId}</dd>
                <dt>Operation reference</dt><dd>{entry.operationId}</dd><dt>Entry status</dt><dd>Posted</dd><dt>Direction</dt><dd>{entry.side === "CREDIT" ? "Money in" : "Money out"}</dd>
                {entry.paymentId && <><dt>Payment reference</dt><dd>{entry.paymentId}</dd></>}
                {entry.reversalOf && <><dt>Original journal</dt><dd><button className="text-button" onClick={() => setFilters({ journal: entry.reversalOf! })}>View original entry</button><span>{entry.reversalOf}</span></dd></>}
                {entry.reversedBy && <><dt>Reversal journal</dt><dd><button className="text-button" onClick={() => setFilters({ journal: entry.reversedBy! })}>View reversal entry</button><span>{entry.reversedBy}</span></dd></>}</dl></details>}
            </td><td><time dateTime={entry.createdAt}>{new Date(entry.createdAt).toISOString().slice(0, 19).replace("T", " ")}</time></td>
              <td className={entry.side === "CREDIT" ? "bank-credit" : ""}>{entry.side === "CREDIT" ? "+" : "-"}{money(entry.amount, entry.currency)}</td></tr>)}</tbody></table></div>
            {!compact && <div className="bank-actions"><button className="button secondary" onClick={() => exportRows("fincore-transactions.csv",
              ["Journal","Operation","Date UTC","Type","Direction","Amount","Currency","Reference","Original journal"], items.map(entry => [entry.journalId,entry.operationId,entry.createdAt,entry.kind,entry.side,entry.amount,entry.currency,entry.reference,entry.reversalOf]))}>
              <Download size={15} /> Export these {items.length} entries</button>{entries.hasNextPage && <button className="button secondary" disabled={entries.isFetchingNextPage} onClick={() => void entries.fetchNextPage()}>Load more transactions</button>}</div>}
          </>}
      {!compact && <small>CSV includes the loaded, filtered entries above. Load more before exporting additional entries. Account statements arrive in a later phase.</small>}
    </>}
  </section>;
}
