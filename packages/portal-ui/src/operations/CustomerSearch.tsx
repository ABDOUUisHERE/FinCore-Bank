import { useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useSession } from "../auth";
import { request } from "../banking/api";
import { Failure, Loading } from "../banking/Common";

type Match = { customerId: string; displayName: string; status: string; profileVersion: string; createdAt: string };
type Page = { items: Match[]; nextAfterId: string | null; observedAt: string };
type Criteria = { prefix: string; afterId: string | null; page: number; serial: number };

export function CustomerSearch({ inspect }: { inspect: (id: string) => void }) {
  const session = useSession();
  if (session.status !== "authenticated" || !session.roles.includes("ADMIN")) return null;
  return <SearchForm key={session.username} inspect={inspect} />;
}
function SearchForm({ inspect }: { inspect: (id: string) => void }) {
  const [name, setName] = useState("");
  const [active, setActive] = useState<Criteria | null>(null);
  const [error, setError] = useState("");
  const serial = useRef(0);
  return <section className="surface bank-panel" aria-label="Customer name search"><h2>Find a customer</h2>
    <p>Enter at least three characters from the start of a customer name. Names are matched literally, without accent or spelling correction.</p>
    <form className="bank-form" noValidate onSubmit={e => {
      e.preventDefault(); const prefix = name.trim();
      if (prefix.length < 3 || prefix.length > 80 || Array.from(name).some(c => c.charCodeAt(0) < 32 || (c.charCodeAt(0) >= 127 && c.charCodeAt(0) <= 159))) { setError("Enter 3–80 name characters without control characters."); setActive(null); return; }
      setError(""); setActive({ prefix, afterId: null, page: 1, serial: ++serial.current });
    }}>
      <label>Name prefix<input value={name} onChange={e => { setName(e.target.value); setActive(null); setError(""); }} maxLength={80} autoComplete="off" spellCheck={false} required /></label>
      <div className="bank-actions"><button className="button" type="submit">Search customers</button><button className="button secondary" type="button" onClick={() => { setName(""); setActive(null); setError(""); }}>Clear search</button></div>
    </form>
    {error && <p role="alert">{error}</p>}
    {active && <Matches key={active.serial + ":" + active.afterId} criteria={active} inspect={inspect} move={setActive} />}
  </section>;
}
function Matches({ criteria, inspect, move }: { criteria: Criteria; inspect: (id: string) => void; move: (criteria: Criteria) => void }) {
  const session = useSession();
  const query = useQuery({ queryKey: ["customer-search", session.username, criteria.prefix, criteria.afterId, criteria.serial],
    queryFn: ({ signal }) => request<Page>(session.token, "/admin/customers/search", { method: "POST", body: { namePrefix: criteria.prefix, afterId: criteria.afterId }, signal }),
    retry: false, gcTime: 0, refetchOnWindowFocus: false, refetchOnReconnect: false });
  return <div role="region" aria-label="Customer matches">
    <Failure error={query.error} retry={() => void query.refetch()} />
    {query.isPending ? <Loading /> : !query.error && query.data && <>
      <p role="status">{query.data.items.length ? `${query.data.items.length} customers on this page. Page ${criteria.page}.` : "No customers match this name prefix on this page."}</p>
      <p>Observed {new Date(query.data.observedAt).toLocaleString()}. Pages are separate observations.</p>
      <div className="operations-list customer-search-matches">{query.data.items.map(match => <article className="surface bank-panel" key={match.customerId} aria-label={"Customer " + match.customerId}>
        <h3>{match.displayName}</h3><dl className="bank-facts"><dt>Customer reference</dt><dd><code>{match.customerId}</code></dd><dt>Onboarding status</dt><dd>{match.status}</dd><dt>Profile version</dt><dd>{match.profileVersion}</dd><dt>Created</dt><dd>{new Date(match.createdAt).toLocaleString()}</dd></dl>
        <button className="button secondary" onClick={() => inspect(match.customerId)}>Inspect customer</button>
      </article>)}</div>
      <div className="bank-actions">
        {criteria.afterId && <button className="button secondary" disabled={query.isFetching} onClick={() => move({ ...criteria, afterId: null, page: 1 })}>First matches</button>}
        {query.data.nextAfterId && <button className="button secondary" disabled={query.isFetching} onClick={() => move({ ...criteria, afterId: query.data!.nextAfterId, page: criteria.page + 1 })}>Next matches</button>}
      </div>
    </>}
  </div>;
}
