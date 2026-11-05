import { useEffect, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useSession } from "../auth";
import { request } from "../banking/api";
import { Badge, Failure, Loading } from "../banking/Common";
import type { IncidentDraft } from "./Operations";

export type Grant = { incidentId: string; accountId: string; recipient: string; permission: "ACCOUNT_STATUS_READ"; version: string; state: "NONE" | "ACTIVE" | "EXPIRED" | "REVOKED" | "RESOLVED"; issuedBy: string | null; issuedAt: string | null; expiresAt: string | null; revokedAt: string | null; reason: string | null };
type Status = { accountId: string; currency: string; recordedStatus: string; accountVersion: string; pendingStatus: string | null; lastGuardError: string | null; recordedGuardRevision: string; accountObservedAt: string; ledgerState: "OBSERVED" | "UNAVAILABLE" | "NOT_LINKED"; ledger: { status: string; guardRevision: string; observedAt: string } | null };
type Incident = { incidentId: string; resourceId: string; createdBy: string; status: string };

export function SupportAccountAccess({ subject, incident, draft, disabled }: { subject: string; incident: Incident; draft: (value: IncidentDraft) => void; disabled: boolean }) {
  const session = useSession(); const admin = session.roles.includes("ADMIN"); const [minutes, setMinutes] = useState("15");
  const grant = useQuery({ queryKey: ["operations", subject, "account-access", incident.incidentId], retry: false, gcTime: 0,
    queryFn: ({ signal }) => request<Grant>(session.token, "/admin/incidents/" + incident.incidentId + "/account-access", { signal }) });
  function review(action: "ISSUE" | "REVOKE") {
    if (!grant.data) return;
    draft({ path: "/admin/incidents/" + incident.incidentId + "/account-access", method: "PUT",
      body: { expectedVersion: grant.data.version, action, ...(action === "ISSUE" ? { minutes: Number(minutes) } : {}), reason: action === "ISSUE" ? "INCIDENT_INVESTIGATION" : "ACCESS_NO_LONGER_REQUIRED" },
      title: action === "ISSUE" ? "Grant temporary account access" : "Revoke account access",
      description: `${action === "ISSUE" ? `Allow status consultation for ${minutes} minutes` : "End status consultation"}. Recipient: ${incident.createdBy}. Account: ${incident.resourceId}. Incident: ${incident.incidentId}. Expected access version: ${grant.data.version}.` });
  }
  const g = grant.error ? undefined : grant.data;
  return <section aria-label="Temporary account access" className="support-account-access"><h3>Temporary account access</h3>
    <p>Access is fixed to this incident's creator and account. Resolution, revocation or expiry ends permission. Every consultation is audited.</p>
    <Failure error={grant.error} retry={() => void grant.refetch()} />{grant.isPending ? <Loading text="Checking incident permission..." /> : g && <>
      <dl className="bank-facts"><dt>Access state</dt><dd><Badge status={g.state} /></dd><dt>Access version</dt><dd>{g.version}</dd><dt>Recipient</dt><dd>{g.recipient}</dd><dt>Account in scope</dt><dd>{g.accountId}</dd><dt>Expires</dt><dd>{g.expiresAt ? new Date(g.expiresAt).toLocaleString() : "No permission issued"}</dd></dl>
      {admin ? <><label>Access duration<select value={minutes} disabled={disabled} onChange={e => setMinutes(e.target.value)}>{[5, 15, 30, 60].map(m => <option key={m} value={m}>{m} minutes</option>)}</select></label>
        <div className="bank-actions"><button className="button" disabled={disabled || incident.status === "RESOLVED"} onClick={() => review("ISSUE")}>Review temporary access</button>
          <button className="button secondary" disabled={disabled || ["NONE", "REVOKED"].includes(g.state)} onClick={() => review("REVOKE")}>Review access revocation</button></div></> :
        <Observation key={g.version + incident.status + grant.dataUpdatedAt} subject={subject} grant={g} disabled={disabled || incident.status === "RESOLVED"} />}
    </>}
    <button className="text-button" disabled={grant.isFetching} onClick={() => void grant.refetch()}>Refresh access permission</button>
  </section>;
}
function Observation({ subject, grant, disabled }: { subject: string; grant: Grant; disabled: boolean }) {
  const session = useSession(), cache = useQueryClient();
  const [result, setResult] = useState<Status | null>(null), [error, setError] = useState<Error | null>(null), [busy, setBusy] = useState(false);
  const [expired, setExpired] = useState(() => !grant.expiresAt || Date.parse(grant.expiresAt) <= Date.now());
  useEffect(() => { if (!grant.expiresAt) return; const timer = setTimeout(() => { setExpired(true); setResult(null); }, Math.max(0, Date.parse(grant.expiresAt) - Date.now())); return () => clearTimeout(timer); }, [grant.expiresAt]);
  async function inspect() {
    setResult(null); setError(null); setBusy(true);
    try { setResult(await request<Status>(session.token, "/admin/incidents/" + grant.incidentId + "/account-status", { method: "POST", body: {} })); }
    catch (failure) { setError(failure instanceof Error ? failure : new Error("Account status is unavailable.")); }
    finally { setBusy(false); void cache.invalidateQueries({ queryKey: ["operations", subject, "history", grant.incidentId] }); }
  }
  return <><button className="button" disabled={disabled || busy || expired || grant.state !== "ACTIVE"} onClick={() => void inspect()}>{busy ? "Consulting account..." : "Consult scoped account status"}</button>
    <p>Each click checks current permission. Observations are held in this view until you leave or refresh it.</p><Failure error={error} />
    {result && !expired && <section aria-label="Scoped account observation"><h3>Scoped account observation</h3><p>Account and Ledger are observed separately. Recorded intent does not confirm the effective restriction.</p>
      <dl className="bank-facts"><dt>Account</dt><dd>{result.accountId}</dd><dt>Currency</dt><dd>{result.currency}</dd><dt>Recorded status</dt><dd>{result.recordedStatus}</dd><dt>Account version</dt><dd>{result.accountVersion}</dd><dt>Pending requested status</dt><dd>{result.pendingStatus ?? "None"}</dd><dt>Last restriction result</dt><dd>{result.lastGuardError ?? "No recorded rejection"}</dd><dt>Recorded guard revision</dt><dd>{result.recordedGuardRevision}</dd><dt>Account observed</dt><dd>{new Date(result.accountObservedAt).toLocaleString()}</dd></dl>
      {result.ledgerState === "OBSERVED" && result.ledger ? <dl className="bank-facts"><dt>Effective Ledger status</dt><dd>{result.ledger.status}</dd><dt>Effective guard revision</dt><dd>{result.ledger.guardRevision}</dd><dt>Ledger observed</dt><dd>{new Date(result.ledger.observedAt).toLocaleString()}</dd></dl> : <p role="status">{result.ledgerState === "NOT_LINKED" ? "No Ledger link is recorded yet." : "Ledger status is unavailable. Recorded status does not confirm its effective state."}</p>}
    </section>}
  </>;
}
