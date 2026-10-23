import { useState } from "react";
import { useMutation, useQueryClient, type InfiniteData } from "@tanstack/react-query";
import { Plus, Users } from "lucide-react";
import { useSession } from "../auth";
import { type Beneficiary, type Page, request, uuid } from "./api";
import { Badge, Confirm, Empty, Failure } from "./Common";

export function Beneficiaries({ subject, items, search, searchFor }: { subject: string; items: Beneficiary[]; search: string; searchFor: (search: string) => void }) {
  const session = useSession(); const cache = useQueryClient();
  const [draftSearch, setSearch] = useState(search); const [adding, setAdding] = useState(false);
  const [label, setLabel] = useState(""); const [destination, setDestination] = useState("");
  const [key, setKey] = useState(() => crypto.randomUUID()); const [review, setReview] = useState(false);
  const [editing, setEditing] = useState<Beneficiary | null>(null);
  const [editLabel, setEditLabel] = useState(""); const [editStatus, setEditStatus] = useState<"ACTIVE" | "DISABLED">("ACTIVE");
  const [editReview, setEditReview] = useState(false);
  const refresh = () => { void cache.invalidateQueries({ queryKey: ["bank", subject, "beneficiaries"] }); };
  const create = useMutation({ mutationFn: () => request<Beneficiary>(session.token, "/beneficiaries",
    { method: "POST", key, body: { label: label.trim(), destinationAccountId: destination.trim() } }),
    onSuccess: () => { refresh(); setAdding(false); setReview(false); setLabel(""); setDestination(""); setKey(crypto.randomUUID()); } });
  const update = useMutation({ mutationFn: () => {
    if (!editing) throw new Error("Select a beneficiary.");
    return request<Beneficiary>(session.token, "/beneficiaries/" + editing.beneficiaryId,
      { method: "PUT", body: { label: editLabel.trim(), status: editStatus, expectedVersion: editing.version } });
  }, onSuccess: updated => {
    cache.setQueriesData<InfiniteData<Page<Beneficiary>>>({ queryKey: ["bank", subject, "beneficiaries"] },
      current => current ? { ...current, pages: current.pages.map(p => ({ ...p, items: p.items.map(item => item.beneficiaryId === updated.beneficiaryId ? updated : item) })) } : current);
    refresh(); setEditing(null); setEditReview(false);
  } });
  const visible = items;
  return <>
    <div className="section-heading"><h2>People you pay</h2><button className="button" onClick={() => setAdding(true)}><Plus size={16} /> Add beneficiary</button></div>
    <p>Save an internal account reference so you can check it clearly before every transfer.</p>
    <form className="bank-filter-row" onSubmit={e => { e.preventDefault(); searchFor(draftSearch); }}><label className="bank-search">Search beneficiaries<input type="search" maxLength={140} value={draftSearch} onChange={e => setSearch(e.target.value)} placeholder="Name or full account reference" /></label><button className="button secondary">Search beneficiaries</button></form>
    {adding && <section className="surface bank-panel"><h3>Add a beneficiary</h3>
      <form className="bank-form" onSubmit={e => { e.preventDefault(); setReview(true); }}>
        <label>Beneficiary name<input required maxLength={80} value={label} disabled={create.isPending || create.isError} onChange={e => { setLabel(e.target.value); setReview(false); }} /></label>
        <label>Recipient account reference<input required value={destination} disabled={create.isPending || create.isError} onChange={e => { setDestination(e.target.value.trim()); setReview(false); }} placeholder="Account UUID shared by the recipient" /></label>
        <button className="button" disabled={!label.trim() || !uuid.test(destination)}>{create.isError ? "Review same beneficiary request" : "Review beneficiary"}</button>
      </form>
      {review && <Confirm title="Confirm beneficiary details" busy={create.isPending} confirm={() => create.mutate()} cancel={() => { if (!create.isError) setReview(false); }}>
        <p>{label.trim()}</p><p className="bank-reference">{destination}</p><p>Confirm the account reference with your recipient before saving.</p></Confirm>}
      <Failure error={create.error} retry={refresh} />
      {!create.isPending && <button className="text-button" onClick={() => { setAdding(false); setReview(false); setLabel(""); setDestination(""); setKey(crypto.randomUUID()); create.reset(); refresh(); }}>Cancel adding beneficiary</button>}
    </section>}
    {!visible.length ? <Empty title={search ? "No matching beneficiaries" : "Your circle starts here"}><p>Add a recipient using their simulated account reference.</p></Empty> :
      <div className="bank-beneficiary-grid">{visible.map(item => <article className="surface bank-beneficiary" key={item.beneficiaryId}>
        <div className="bank-card-top"><span className="tile-icon lavender"><Users size={19} /></span><Badge status={item.status} /></div>
        <h3>{item.label}</h3><p>{item.currency} · {item.destinationAccountId.slice(-8)}</p><span className="bank-reference">{item.destinationAccountId}</span>
        <button className="text-button" onClick={() => { setEditing(item); setEditLabel(item.label); setEditStatus(item.status); setEditReview(false); update.reset(); }}>Edit {item.label}</button>
      </article>)}</div>}
    {editing && <section className="surface bank-panel"><h3>Edit beneficiary</h3><p className="bank-reference">{editing.destinationAccountId}</p>
      <p>The account reference stays fixed. Add a separate beneficiary to pay a different account.</p>
      <form className="bank-form" onSubmit={e => { e.preventDefault(); setEditReview(true); }}>
        <label>Updated beneficiary name<input required maxLength={80} value={editLabel} onChange={e => { setEditLabel(e.target.value); setEditReview(false); }} /></label>
        <label>Beneficiary status<select value={editStatus} onChange={e => { setEditStatus(e.target.value as "ACTIVE" | "DISABLED"); setEditReview(false); }}><option value="ACTIVE">Active</option><option value="DISABLED">Disabled</option></select></label>
        <button className="button" disabled={!editLabel.trim() || update.isPending}>Review beneficiary change</button>
      </form>
      {editReview && <Confirm title="Confirm beneficiary change" busy={update.isPending} confirm={() => update.mutate()} cancel={() => setEditReview(false)}>
        <p>{editLabel.trim()} · {editStatus.toLowerCase()}</p><p>Disabled beneficiaries cannot be selected for new transfers. Existing transfers keep their confirmed destination.</p></Confirm>}
      <Failure error={update.error} retry={refresh} />
      {!update.isPending && <button className="text-button" onClick={() => setEditing(null)}>Close editing</button>}
    </section>}
  </>;
}
