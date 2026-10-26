import { useState, type FormEvent } from "react";
import { useInfiniteQuery, useMutation, useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowRight, Plus, RefreshCw, Wallet } from "lucide-react";
import { useSession } from "../auth";
import { type Account, type Activity, type Balance, type Beneficiary, type Currency, type Profile, page, profile, query, request, reason } from "./api";
import { decimal, minor, money } from "./money";
import { AccountSelect, Badge, Confirm, Empty, Failure, Loading } from "./Common";
import { Beneficiaries } from "./Beneficiaries";
import { Transfers } from "./Transfers";
import { LiveNotifications, Notifications } from "./Notifications";
import { History } from "./History";
import "./banking.css";

export const bankPages = ["Overview", "Accounts", "Transfers", "History", "Beneficiaries", "Notifications", "Profile"];
export function Banking({ subject, section, navigate }: { subject: string; section: string; navigate: (page: string) => void }) {
  const session = useSession();
  const cache = useQueryClient();
  const key = ["bank", subject];
  const customer = useQuery({ queryKey: [...key, "profile"], queryFn: ({ signal }) => profile(session.token, signal), retry: false });
  const accounts = useInfiniteQuery({
    queryKey: [...key, "accounts"], initialPageParam: undefined as string | undefined,
    queryFn: ({ signal, pageParam }) => page<Account>(session.token, "/accounts" + query({ after: pageParam }), signal),
    getNextPageParam: last => last.nextCursor ?? undefined, enabled: !!customer.data, retry: false,
    refetchInterval: data => data.state.data?.pages.some(p => p.items.some(a => ["PROVISIONING", "GUARD_PENDING"].includes(a.status))) ? 3000 : false,
  });
  const [beneficiarySearch, setBeneficiarySearch] = useState("");
  const term = section === "Beneficiaries" ? beneficiarySearch : "";
  const beneficiaries = useInfiniteQuery({
    queryKey: [...key, "beneficiaries", term], initialPageParam: undefined as string | undefined,
    queryFn: ({ signal, pageParam }) => page<Beneficiary>(session.token, "/beneficiaries" + query({ after: pageParam, search: term }), signal),
    getNextPageParam: last => last.nextCursor ?? undefined, enabled: !!customer.data, retry: false,
  });
  const [selectedId, setSelected] = useState("");
  const items = accounts.data?.pages.flatMap(p => p.items) ?? [];
  const saved = beneficiaries.data?.pages.flatMap(p => p.items) ?? [];
  const selected = items.find(a => a.accountId === selectedId) ?? items[0];
  const refresh = () => { void cache.invalidateQueries({ queryKey: key }); };
  if (customer.isPending) return <Loading />;
  if (customer.isError) return <Failure error={customer.error} retry={() => void customer.refetch()} />;
  if (!customer.data) return <section className="bank-workspace"><span className="bank-verified">Identity verified by the customer service</span><ProfileForm subject={subject} current={null} /></section>;
  return <section className="bank-workspace" aria-label="Customer banking">
    <div className="bank-toolbar"><span className="bank-verified">Identity verified by the customer service</span>
      <button className="text-button" onClick={refresh}><RefreshCw size={14} /> Refresh data</button></div>
    <LiveNotifications subject={subject} />
    <Failure error={accounts.error} retry={() => void accounts.refetch()} />
    {section === "Notifications" ? <Notifications subject={subject} /> : section === "Profile" ? <ProfileForm key={customer.data.version} subject={subject} current={customer.data} /> :
      section === "Beneficiaries" ? <><Failure error={beneficiaries.error} retry={() => void beneficiaries.refetch()} />
        {beneficiaries.isPending ? <Loading /> : <Beneficiaries subject={subject} items={saved} search={beneficiarySearch} searchFor={setBeneficiarySearch} />}</> :
      section === "Transfers" ? <><Failure error={beneficiaries.error} retry={() => void beneficiaries.refetch()} />
        {beneficiaries.isPending || accounts.isPending ? <Loading /> : <Transfers subject={subject} accounts={items} beneficiaries={saved} navigate={navigate} />}</> :
      section === "History" ? <History subject={subject} account={selected} accounts={items} select={setSelected} /> :
      accounts.isPending ? <Loading /> : accounts.isError && !accounts.data ? <Failure error={accounts.error} retry={() => void accounts.refetch()} /> :
      section === "Accounts" ? <Accounts subject={subject} items={items} selected={selected} select={setSelected} /> :
      <Overview subject={subject} customer={customer.data} accounts={items} complete={!accounts.hasNextPage && !accounts.isError}
        selected={selected} select={setSelected} navigate={navigate} />}
    {accounts.hasNextPage && <button className="button secondary" disabled={accounts.isFetchingNextPage} onClick={() => void accounts.fetchNextPage()}>Load more accounts</button>}
    {beneficiaries.hasNextPage && ["Beneficiaries", "Transfers"].includes(section) &&
      <button className="button secondary" disabled={beneficiaries.isFetchingNextPage} onClick={() => void beneficiaries.fetchNextPage()}>Load more beneficiaries</button>}
  </section>;
}
function ProfileForm({ subject, current }: { subject: string; current: Profile | null }) {
  const session = useSession(); const cache = useQueryClient();
  const [name, setName] = useState(current?.displayName ?? "");
  const [country, setCountry] = useState(current?.countryCode ?? "MA");
  const [review, setReview] = useState(false);
  const mutation = useMutation({
    mutationFn: () => request<Profile>(session.token, current ? "/customers/me/profile" : "/customers/me/onboarding",
      { method: current ? "PATCH" : "POST", body: { displayName: name.trim(), countryCode: country, ...(current ? { expectedVersion: current.version } : {}) } }),
    onSuccess: result => { cache.setQueryData(["bank", subject, "profile"], result); setReview(false); },
  });
  return <section className="surface bank-panel"><div className="section-heading"><h2>{current ? "Your profile" : "Make yourself at home"}</h2><Badge status={current?.status ?? "NEW"} /></div>
    <p>{current ? "Your personal details for this simulation." : "Complete your profile to open accounts and send simulated money."}</p>
    <form className="bank-form" onSubmit={event => { event.preventDefault(); setReview(true); mutation.reset(); }}>
      <label>Display name<input required maxLength={80} value={name} onChange={e => { setName(e.target.value); setReview(false); }} autoComplete="name" /></label>
      <label>Country code<input required pattern="[A-Z]{2}" maxLength={2} value={country} onChange={e => { setCountry(e.target.value.toUpperCase()); setReview(false); }} autoComplete="country" /></label>
      <small>Two-letter country code, for example MA, FR or US.</small>
      <button className="button" disabled={!name.trim() || !/^[A-Z]{2}$/.test(country)}>Review profile</button>
    </form>
    {review && <Confirm title={current ? "Confirm profile update" : "Confirm your profile"} busy={mutation.isPending}
      confirm={() => mutation.mutate()} cancel={() => setReview(false)}><p>{name.trim()} · {country}</p></Confirm>}
    <Failure error={mutation.error} retry={() => { void cache.invalidateQueries({ queryKey: ["bank", subject, "profile"] }); }} />
    {mutation.isSuccess && <p role="status">Your profile is saved.</p>}
    {current && <dl className="bank-facts"><dt>Customer reference</dt><dd>{current.customerId}</dd><dt>Identity reference</dt><dd>{subject}</dd></dl>}
  </section>;
}
function Overview({ subject, customer, accounts, complete, selected, select, navigate }: {
  subject: string; customer: Profile; accounts: Account[]; complete: boolean; selected?: Account; select: (id: string) => void; navigate: (page: string) => void;
}) {
  const session = useSession();
  const ready = accounts.filter(a => a.status !== "PROVISIONING");
  const balances = useQueries({ queries: ready.map(account => ({
    queryKey: ["bank", subject, "balance", account.accountId],
    queryFn: ({ signal }: { signal: AbortSignal }) => request<Balance>(session.token, "/accounts/" + account.accountId + "/balance", { signal }), retry: false,
  })) });
  const totals: Partial<Record<Currency, bigint>> = {};
  for (const balance of balances) if (balance.data) totals[balance.data.currency] = (totals[balance.data.currency] ?? 0n) + minor(balance.data.booked);
  const allKnown = complete && balances.every(b => b.isSuccess) && ready.length === accounts.length;
  return <>
    <section className="bank-welcome"><div><span className="small-label">YOUR FINANCIAL OVERVIEW</span><h2>A clearer picture, {customer.displayName}.</h2><p>Balances confirmed by your accounts. Every currency stays separate.</p></div>
      <button className="button" onClick={() => navigate("Transfers")}>Send money <ArrowRight size={16} /></button></section>
    <div className="bank-total-grid" aria-label="Balances by currency">
      {!accounts.length ? <Empty title="Your first account starts here"><p>Open an account to start your simulation.</p><button className="text-button" onClick={() => navigate("Accounts")}>Open an account <Plus size={16} /></button></Empty> :
        allKnown ? (Object.entries(totals) as [Currency, bigint][]).map(([currency, total]) => <article className="surface bank-total" key={currency}>
          <span className="bank-currency">{currency}</span><span>Total booked balance</span><strong>{money(decimal(total), currency)}</strong><small>Across your {currency} accounts</small></article>) :
          <div className="bank-message" role="status">Complete account and balance data is required to show totals. Load any remaining accounts or refresh unavailable balances.</div>}
    </div>
    <div className="section-heading"><h2>Your accounts</h2><button className="text-button" onClick={() => navigate("Accounts")}>Manage accounts <ArrowRight size={14} /></button></div>
    <div className="bank-account-grid">{accounts.slice(0, 6).map(account => <AccountCard key={account.accountId} subject={subject} account={account} choose={() => { select(account.accountId); navigate("Accounts"); }} />)}</div>
    {selected && <ActivityView subject={subject} account={selected} accounts={accounts} select={select} />}
    {selected && <History subject={subject} account={selected} accounts={accounts} select={select} compact />}
  </>;
}
function AccountCard({ subject, account, choose }: { subject: string; account: Account; choose: () => void }) {
  const session = useSession();
  const balance = useQuery({ queryKey: ["bank", subject, "balance", account.accountId],
    queryFn: ({ signal }) => request<Balance>(session.token, "/accounts/" + account.accountId + "/balance", { signal }), retry: false, enabled: account.status !== "PROVISIONING" });
  return <article className="surface bank-account"><div className="bank-card-top"><span className="tile-icon mint"><Wallet size={20} /></span><Badge status={account.status} /></div>
    <h3>{account.currency} account</h3><span className="bank-muted">Ending {account.accountId.slice(-8)}</span>
    {account.status === "PROVISIONING" ? <p role="status">Opening your account...</p> : balance.data && !balance.error ? <><strong className="bank-account-balance">{money(balance.data.available, account.currency)}</strong><small>Available · booked {money(balance.data.booked, account.currency)}</small></> :
      balance.error ? <Failure error={balance.error} retry={() => void balance.refetch()} /> : <Loading text="Checking balance..." />}
    <button className="text-button" onClick={choose}>Account details <ArrowRight size={14} /></button></article>;
}
function Accounts({ subject, items, selected, select }: { subject: string; items: Account[]; selected?: Account; select: (id: string) => void }) {
  const session = useSession(); const cache = useQueryClient();
  const [currency, setCurrency] = useState<Currency>("MAD"); const [key, setKey] = useState(() => crypto.randomUUID());
  const [opening, setOpening] = useState(false);
  const [guard, setGuard] = useState<{ account: Account; status: string } | null>(null);
  const create = useMutation({ mutationFn: () => request<Account>(session.token, "/accounts", { method: "POST", key, body: { currency } }),
    onSuccess: account => { select(account.accountId); setOpening(false); setKey(crypto.randomUUID()); void cache.invalidateQueries({ queryKey: ["bank", subject, "accounts"] }); } });
  const restrict = useMutation({ mutationFn: () => {
    if (!guard) throw new Error("Choose an account first.");
    return request<Account>(session.token, "/accounts/" + guard.account.accountId + "/guard", { method: "PUT", body: { status: guard.status, expectedVersion: guard.account.version } });
  }, onSuccess: () => { setGuard(null); void cache.invalidateQueries({ queryKey: ["bank", subject] }); } });
  const submit = (event: FormEvent) => { event.preventDefault(); create.mutate(); };
  return <>
    <div className="section-heading"><h2>Accounts, at a glance</h2><button className="button" onClick={() => setOpening(true)}><Plus size={16} /> Open account</button></div>
    {opening && <form className="surface bank-panel bank-form" onSubmit={submit}><h3>Open a simulated account</h3>
      <label>Account currency<select value={currency} disabled={create.isPending || create.isError} onChange={e => setCurrency(e.target.value as Currency)}>{["MAD","EUR","USD"].map(c => <option key={c}>{c}</option>)}</select></label>
      <p>Your account starts at zero. Funding is available through the private simulation tooling.</p>
      <div className="bank-actions"><button className="button" disabled={create.isPending}>{create.isPending ? "Opening..." : create.isError ? "Retry same account request" : "Confirm open account"}</button>
        {!create.isPending && !create.isError && <button type="button" className="text-button" onClick={() => setOpening(false)}>Cancel</button>}</div><Failure error={create.error} /></form>}
    {!items.length && <Empty title="No accounts yet"><p>Choose a currency and open your first account.</p></Empty>}
    <div className="bank-account-grid">{items.map(account => <AccountCard key={account.accountId} subject={subject} account={account} choose={() => select(account.accountId)} />)}</div>
    {selected && <section className="surface bank-panel" aria-label="Selected account details"><h2>Account details</h2><AccountSelect accounts={items} selected={selected.accountId} select={select} />
      <dl className="bank-facts"><dt>Account reference</dt><dd data-testid="account-reference">{selected.accountId}</dd><dt>Currency</dt><dd>{selected.currency}</dd><dt>Status</dt><dd><Badge status={selected.status} /></dd></dl>
      <p>Share this account reference with another simulated customer to receive money.</p>
      {selected.pendingStatus && <p role="status">Requested status: {selected.pendingStatus.toLowerCase()}. Waiting for confirmation.</p>}
      {selected.guardError && <p role="alert">{reason(selected.guardError)}</p>}
      <div className="bank-actions">{["ACTIVE","FROZEN"].includes(selected.status) && <>
        <button className="button secondary" onClick={() => { restrict.reset(); setGuard({ account: selected, status: selected.status === "ACTIVE" ? "FROZEN" : "ACTIVE" }); }}>{selected.status === "ACTIVE" ? "Freeze account" : "Unfreeze account"}</button>
        <button className="text-button danger" onClick={() => { restrict.reset(); setGuard({ account: selected, status: "CLOSED" }); }}>Close account</button></>}</div>
      {guard && <Confirm title="Confirm account restriction" busy={restrict.isPending} confirm={() => restrict.mutate()} cancel={() => setGuard(null)}>
        <p>Change account {guard.account.accountId} to {guard.status.toLowerCase()}.</p><p>{guard.status === "CLOSED" ? "Closing is permanent and requires a zero balance." : "Transfers will follow the account's effective status."}</p></Confirm>}
      <Failure error={restrict.error} retry={() => void cache.invalidateQueries({ queryKey: ["bank", subject] })} />
    </section>}
  </>;
}
function ActivityView({ subject, account, accounts, select }: { subject: string; account: Account; accounts: Account[]; select: (id: string) => void }) {
  const session = useSession(); const [month, setMonth] = useState(() => new Date().toISOString().slice(0, 7));
  const activity = useQuery({ queryKey: ["bank", subject, "activity", account.accountId, month], retry: false, enabled: account.status !== "PROVISIONING",
    queryFn: ({ signal }) => request<Activity>(session.token, "/accounts/" + account.accountId + "/activity" + query({ month }), { signal }) });
  const days = activity.data?.days ?? [];
  const incoming = days.reduce((total, day) => total + minor(day.incoming), 0n);
  const outgoing = days.reduce((total, day) => total + minor(day.outgoing), 0n);
  const max = days.reduce((value, day) => minor(day.outgoing) > value ? minor(day.outgoing) : value, 1n);
  return <section className="surface bank-panel"><div className="section-heading"><h2>Monthly activity</h2><span className="small-label">UTC · CONFIRMED ENTRIES</span></div>
    <div className="bank-filter-row"><AccountSelect accounts={accounts} selected={account.accountId} select={select} label="Activity account" /><label>Month<input type="month" value={month} onChange={e => { if (e.target.value) setMonth(e.target.value); }} /></label></div>
    {activity.isPending ? <Loading /> : activity.error ? <Failure error={activity.error} retry={() => void activity.refetch()} /> : <>
      <div className="bank-flow"><div><span>Money in</span><strong>{money(decimal(incoming), account.currency)}</strong></div><div><span>Money out</span><strong>{money(decimal(outgoing), account.currency)}</strong></div></div>
      {!days.length ? <Empty title="A quiet month"><p>No confirmed activity for this account in the selected month.</p></Empty> :
        <div className="bank-chart" aria-label="Daily outgoing amounts">{days.map(day => <div className="bank-chart-row" key={day.date}><span>{day.date.slice(5)}</span>
          <div className="bank-chart-track"><div style={{ width: Number(minor(day.outgoing) * 10000n / max) / 100 + "%" }} /></div><span>{money(day.outgoing, account.currency)}</span></div>)}</div>}
      <small>Includes simulated funding, transfers and reversals. These are account flows, not spending categories.</small>
    </>}
  </section>;
}
