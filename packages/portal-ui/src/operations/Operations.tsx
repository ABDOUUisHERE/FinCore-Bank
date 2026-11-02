import { useState, type FormEvent } from "react";
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useSession } from "../auth";
import { ApiError, page, query, request, uuid } from "../banking/api";
import { Badge, Empty, Failure, Loading } from "../banking/Common";
import { clearIncidentAttempt, readIncidentAttempt, saveIncidentAttempt, type IncidentAttempt } from "./incidentAttempt";
import "../banking/banking.css";
import "./operations.css";

type Identity = { subject: string; scope: "ALL_INCIDENTS" | "OWN_INCIDENTS" };
type Incident = { incidentId: string; createdBy: string; resourceKind: string; resourceId: string; severity: string; status: string; version: number; reason: string; createdAt: string; updatedAt: string };
type Fact = { recordId: string; sequence: string; source: string; eventType: string; aggregateId: string; aggregateVersion: number; actor: string | null; correlationId: string; occurredAt: string; observedAt: string; details: Record<string, string>; bodyHash: string; sourceTopic: string | null; sourcePartition: number | null; sourceOffset: string | null };
type Quarantine = { sequence: string; sourceTopic: string; sourcePartition: number; sourceOffset: string; code: string; bodyHash: string; observedAt: string };
type Draft = Pick<IncidentAttempt, "path" | "method" | "body" | "title" | "description">;
const reasons = ["CUSTOMER_REPORTED", "SERVICE_DEGRADATION", "CONTROL_REVIEW", "INVESTIGATION_UPDATE", "ISSUE_CONFIRMED", "ISSUE_RESOLVED", "REOPENED_FOR_REVIEW"];
function Reason({ value, change, disabled = false }: { value: string; change: (value: string) => void; disabled?: boolean }) {
  return <label>Reason code<select disabled={disabled} value={value} onChange={e => change(e.target.value)}>{reasons.map(reason => <option key={reason}>{reason}</option>)}</select></label>;
}
export function Operations({ section }: { section: string }) {
  const session = useSession();
  const permitted = session.roles.some(role => ["ADMIN", "SUPPORT_AGENT"].includes(role));
  const identity = useQuery({ queryKey: ["operations-identity", session.username], enabled: permitted && session.status === "authenticated", retry: false,
    queryFn: ({ signal }) => request<Identity>(session.token, "/admin/operations/me", { signal }) });
  if (!permitted) return <Empty title="Operations access is required" />;
  if (identity.error) return <Failure error={identity.error} retry={() => void identity.refetch()} />;
  if (!identity.data) return <Loading text="Verifying your operations scope..." />;
  return <><p className="verified-line">Authorized staff session</p>{section === "Audit" ? identity.data.scope === "ALL_INCIDENTS" ? <Audit subject={identity.data.subject} /> : <Empty title="Audit access requires ADMIN" /> :
    <Incidents key={identity.data.subject + identity.data.scope} identity={identity.data} />}</>;
}
function Incidents({ identity }: { identity: Identity }) {
  const { subject } = identity; const session = useSession(); const cache = useQueryClient();
  const [selected, select] = useState(""); const [attempt, setAttempt] = useState<IncidentAttempt | null>(() => readIncidentAttempt(subject));
  const [error, setError] = useState<Error | null>(null); const [notice, setNotice] = useState("");
  const records = useInfiniteQuery({ queryKey: ["operations", subject, "incidents"], initialPageParam: undefined as string | undefined, retry: false,
    queryFn: ({ signal, pageParam }) => page<Incident>(session.token, "/admin/incidents" + query({ after: pageParam }), signal), getNextPageParam: last => last.nextCursor ?? undefined });
  const commit = (next: IncidentAttempt) => { saveIncidentAttempt(next); setAttempt(next); };
  const draft = (next: Draft) => { try { setError(null); setNotice(""); commit({ ...next, owner: subject, key: crypto.randomUUID(), submitted: false }); } catch { setError(new Error("This browser could not retain the request. No submission was sent.")); } };
  const send = useMutation({ mutationFn: async (current: IncidentAttempt) => { const locked = { ...current, submitted: true }; commit(locked); return request<Incident>(session.token, locked.path, { method: locked.method, key: locked.key, body: locked.body }); },
    onSuccess: result => { clearIncidentAttempt(subject); setAttempt(null); select(result.incidentId); setNotice("Recorded result: " + result.status.toLowerCase() + " at version " + result.version + ". Current incident details are refreshed below."); void cache.invalidateQueries({ queryKey: ["operations", subject] }); } });
  const definitive = send.variables?.submitted === false && send.error instanceof ApiError && [400, 403, 404, 409, 422].includes(send.error.status);
  const items = records.data?.pages.flatMap(p => p.items) ?? [];
  return <section className="bank-workspace operations-workspace" aria-label="Incident operations">
    <div className="section-heading"><h2>Incident operations</h2><button className="text-button" onClick={() => void cache.invalidateQueries({ queryKey: ["operations", subject] })}>Refresh incidents</button></div>
    <p>{identity.scope === "ALL_INCIDENTS" ? "You can manage all incidents." : "You can manage only incidents opened by your identity."} Resource references are reported by staff and do not confirm ownership or financial state.</p>
    {attempt ? <section className="surface bank-panel" aria-label="Incident confirmation"><h3>{attempt.title}</h3><p>{attempt.description}</p>
      {attempt.submitted && <p role="status">A response is not confirmed. Retry the same request; its details and reference are retained across page reloads.</p>}
      <small className="bank-reference">Request reference: {attempt.key}</small><div className="bank-actions"><button className="button" disabled={send.isPending} onClick={() => send.mutate(attempt)}>{send.isPending ? "Submitting..." : attempt.submitted ? "Retry same incident request" : "Confirm incident request"}</button>
        {!send.isPending && (!attempt.submitted || definitive) && <button className="button secondary" onClick={() => { clearIncidentAttempt(subject); setAttempt(null); send.reset(); }}>{definitive ? "Close rejected request" : "Keep current details"}</button>}</div><Failure error={send.error} /></section> : <CreateIncident draft={draft} />}
    <Failure error={error} />{notice && <p className="bank-message" role="status">{notice}</p>}<Failure error={records.error} retry={() => void records.refetch()} />
    {records.isPending ? <Loading /> : !items.length ? <Empty title="No incidents in your scope" /> : <div className="operations-list">{items.map(item => <article className="surface bank-panel" key={item.incidentId}>
      <div className="section-heading"><h3>{item.resourceKind} · {item.severity}</h3><Badge status={item.status} /></div><small className="bank-reference">{item.incidentId}</small><p>{item.reason}</p><button className="text-button" onClick={() => select(item.incidentId)}>Open incident {item.incidentId.slice(-8)}</button>
    </article>)}</div>}
    {records.hasNextPage && <button className="button secondary" disabled={records.isFetchingNextPage} onClick={() => void records.fetchNextPage()}>Load more incidents</button>}
    {selected && <IncidentDetail key={selected} subject={subject} id={selected} draft={draft} disabled={!!attempt} />}
  </section>;
}
function CreateIncident({ draft }: { draft: (value: Draft) => void }) {
  const [kind, setKind] = useState("ACCOUNT"), [resource, setResource] = useState(""), [severity, setSeverity] = useState("MEDIUM"), [reason, setReason] = useState(reasons[0]);
  const [error, setError] = useState<Error | null>(null);
  function submit(e: FormEvent) { e.preventDefault(); if (!uuid.test(resource)) { setError(new Error("Enter a valid resource UUID.")); return; } draft({ path: "/admin/incidents", method: "POST", body: { resourceKind: kind, resourceId: resource, severity, reason }, title: "Open an incident", description: `${severity} ${kind} incident for ${resource}. Reason: ${reason}.` }); }
  return <section className="surface bank-panel"><h3>Open an incident</h3><form className="bank-form" onSubmit={submit}>
    <label>Resource kind<select value={kind} onChange={e => setKind(e.target.value)}>{["CUSTOMER", "ACCOUNT", "PAYMENT", "JOURNAL", "CARD", "SERVICE"].map(kind => <option key={kind}>{kind}</option>)}</select></label>
    <label>Resource UUID<input required value={resource} onChange={e => setResource(e.target.value)} /></label><label>Severity<select value={severity} onChange={e => setSeverity(e.target.value)}>{["LOW", "MEDIUM", "HIGH"].map(value => <option key={value}>{value}</option>)}</select></label>
    <Reason value={reason} change={setReason} /><button className="button">Review incident</button><Failure error={error} /></form></section>;
}
function IncidentDetail({ subject, id, draft, disabled }: { subject: string; id: string; draft: (value: Draft) => void; disabled: boolean }) {
  const session = useSession(); const [reason, setReason] = useState("INVESTIGATION_UPDATE");
  const current = useQuery({ queryKey: ["operations", subject, "incident", id], retry: false, queryFn: ({ signal }) => request<Incident>(session.token, "/admin/incidents/" + id, { signal }) });
  const history = useInfiniteQuery({ queryKey: ["operations", subject, "history", id], initialPageParam: undefined as string | undefined, retry: false,
    queryFn: ({ signal, pageParam }) => page<Fact>(session.token, "/admin/incidents/" + id + "/history" + query({ before: pageParam }), signal), getNextPageParam: last => last.nextCursor ?? undefined });
  const change = (status: string) => { if (current.data) draft({ path: "/admin/incidents/" + id + "/status", method: "PUT", body: { expectedVersion: current.data.version, status, reason }, title: "Change incident status", description: `Incident ${id}: ${current.data.status} to ${status}, expected version ${current.data.version}. Reason: ${reason}.` }); };
  return <section className="surface bank-panel" aria-label="Selected incident"><h3>Selected incident</h3><Failure error={current.error} retry={() => void current.refetch()} />
    {current.isPending ? <Loading /> : current.data && !current.error && <><dl className="bank-facts"><dt>Reference</dt><dd>{id}</dd><dt>Opened by</dt><dd>{current.data.createdBy}</dd><dt>Resource</dt><dd>{current.data.resourceKind} {current.data.resourceId}</dd><dt>Status</dt><dd><Badge status={current.data.status} /></dd><dt>Version</dt><dd>{current.data.version}</dd><dt>Reason</dt><dd>{current.data.reason}</dd><dt>Updated</dt><dd>{new Date(current.data.updatedAt).toLocaleString()}</dd></dl>
      <div className="bank-form"><Reason disabled={disabled} value={reason} change={setReason} /></div><div className="bank-actions">{current.data.status === "INVESTIGATING" ? <button className="button" disabled={disabled} onClick={() => change("RESOLVED")}>Review resolution</button> : <button className="button" disabled={disabled} onClick={() => change("INVESTIGATING")}>{current.data.status === "RESOLVED" ? "Review reopening" : "Review investigation"}</button>}</div></>}
    <h3>Immutable incident history</h3><Failure error={history.error} retry={() => void history.refetch()} />{history.isPending ? <Loading /> : <Facts items={history.data?.pages.flatMap(p => p.items) ?? []} />}
    {history.hasNextPage && <button className="button secondary" disabled={history.isFetchingNextPage} onClick={() => void history.fetchNextPage()}>Load older incident history</button>}
  </section>;
}
function Facts({ items }: { items: Fact[] }) {
  return !items.length ? <Empty title="No retained facts for this query" /> : <div className="operations-facts">{items.map(fact => <details key={fact.recordId} className="operations-fact"><summary><strong>{fact.eventType}</strong><span>#{fact.sequence} · observed {new Date(fact.observedAt).toLocaleString()}</span></summary>
    <dl className="bank-facts"><dt>Aggregate</dt><dd>{fact.aggregateId} · version {fact.aggregateVersion}</dd><dt>Actor reported by source</dt><dd>{fact.actor ?? "Not supplied"}</dd><dt>Source-reported time</dt><dd>{fact.occurredAt}</dd><dt>Observed locally</dt><dd>{fact.observedAt}</dd><dt>Correlation</dt><dd>{fact.correlationId}</dd><dt>Source</dt><dd>{fact.sourceTopic ? `${fact.sourceTopic} / ${fact.sourcePartition} / ${fact.sourceOffset}` : "Local incident operation"}</dd><dt>SHA-256</dt><dd>{fact.bodyHash}</dd>{Object.entries(fact.details).map(([key, value]) => <div className="operations-detail" key={key}><dt>{key}</dt><dd>{value}</dd></div>)}</dl>
  </details>)}</div>;
}
function Audit({ subject }: { subject: string }) {
  const session = useSession(); const cache = useQueryClient(); const [aggregate, setAggregate] = useState(""), [eventType, setEventType] = useState("");
  const [filter, setFilter] = useState<{ aggregateId?: string; eventType?: string }>({}); const [error, setError] = useState<Error | null>(null);
  const summary = useQuery({ queryKey: ["operations", subject, "summary"], retry: false, queryFn: ({ signal }) => request<{ retainedDomainFacts: number; recordedOperations: number; quarantinedRecords: number; openIncidents: number; observedAt: string }>(session.token, "/admin/audit/summary", { signal }) });
  const records = useInfiniteQuery({ queryKey: ["operations", subject, "audit", filter], initialPageParam: undefined as string | undefined, retry: false,
    queryFn: ({ signal, pageParam }) => page<Fact>(session.token, "/admin/audit/records" + query({ ...filter, before: pageParam }), signal), getNextPageParam: last => last.nextCursor ?? undefined });
  const quarantine = useInfiniteQuery({ queryKey: ["operations", subject, "quarantine"], initialPageParam: undefined as string | undefined, retry: false,
    queryFn: ({ signal, pageParam }) => page<Quarantine>(session.token, "/admin/audit/quarantine" + query({ before: pageParam }), signal), getNextPageParam: last => last.nextCursor ?? undefined });
  function search(e: FormEvent) { e.preventDefault(); if (aggregate && !uuid.test(aggregate) || eventType && !/^[a-z]+(\.[a-z_]+){1,3}$/.test(eventType)) { setError(new Error("Use an aggregate UUID and an exact event type such as account.created.")); return; } setError(null); setFilter({ aggregateId: aggregate || undefined, eventType: eventType || undefined }); }
  return <section className="bank-workspace operations-workspace" aria-label="Central audit"><div className="section-heading"><h2>Central audit</h2><button className="text-button" onClick={() => void cache.invalidateQueries({ queryKey: ["operations", subject] })}>Refresh audit</button></div>
    <p>Retained domain facts and local incident operations. Ingestion is asynchronous and limited by broker retention. Source-reported times and actors are producer assertions; this view does not reconstruct every historical service audit.</p>
    <Failure error={summary.error} retry={() => void summary.refetch()} />{summary.isPending ? <Loading /> : summary.data && !summary.error && <section className="surface bank-panel"><dl className="bank-facts"><dt>Retained domain facts</dt><dd>{summary.data.retainedDomainFacts}</dd><dt>Recorded incident operations</dt><dd>{summary.data.recordedOperations}</dd><dt>Quarantined records</dt><dd>{summary.data.quarantinedRecords}</dd><dt>Open incidents</dt><dd>{summary.data.openIncidents}</dd><dt>Observed</dt><dd>{new Date(summary.data.observedAt).toLocaleString()}</dd></dl></section>}
    <section className="surface bank-panel"><h3>Retained facts</h3><form className="bank-form" onSubmit={search}><label>Aggregate UUID<input value={aggregate} onChange={e => setAggregate(e.target.value)} /></label><label>Exact event type<input value={eventType} onChange={e => setEventType(e.target.value)} placeholder="account.created" /></label><button className="button">Filter audit</button></form><Failure error={error} /><Failure error={records.error} retry={() => void records.refetch()} />
      {records.isPending ? <Loading /> : <Facts items={records.data?.pages.flatMap(p => p.items) ?? []} />}{records.hasNextPage && <button className="button secondary" disabled={records.isFetchingNextPage} onClick={() => void records.fetchNextPage()}>Load older audit facts</button>}</section>
    <section className="surface bank-panel"><h3>Quarantine metadata</h3><p>Only digest, reason and broker coordinates are retained. Investigation requires the original record to remain available at the broker.</p><Failure error={quarantine.error} retry={() => void quarantine.refetch()} />
      {quarantine.isPending ? <Loading /> : !quarantine.data?.pages.some(p => p.items.length) ? <Empty title="No quarantined records" /> : quarantine.data.pages.flatMap(p => p.items).map(record => <details className="operations-fact" key={record.sequence}><summary>{record.code} · #{record.sequence}</summary><dl className="bank-facts"><dt>Source</dt><dd>{record.sourceTopic} / {record.sourcePartition} / {record.sourceOffset}</dd><dt>SHA-256</dt><dd>{record.bodyHash}</dd><dt>Observed</dt><dd>{record.observedAt}</dd></dl></details>)}
      {quarantine.hasNextPage && <button className="button secondary" disabled={quarantine.isFetchingNextPage} onClick={() => void quarantine.fetchNextPage()}>Load older quarantine metadata</button>}</section>
  </section>;
}
