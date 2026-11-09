import { useState, type ReactNode } from "react";
import { useQuery } from "@tanstack/react-query";
import { useSession } from "../auth";
import { ApiError, request } from "../banking/api";
import { Badge, Failure, Loading } from "../banking/Common";
import type { IncidentDraft } from "./Operations";

type GrantDetails = { incidentId: string; recipient: string; version: string; state: "NONE" | "ACTIVE" | "EXPIRED" | "REVOKED" | "RESOLVED"; issuedBy: string | null; issuedAt: string | null; expiresAt: string | null; revokedAt: string | null; reason: string | null };
export type Grant = GrantDetails & ({ permission: "ACCOUNT_STATUS_READ"; accountId: string } | { permission: "PAYMENT_STATUS_READ"; paymentId: string });
export type SupportKind = "account" | "payment";
export type GrantPanelProps = { subject: string; incident: { incidentId: string; resourceId: string; createdBy: string; status: string }; draft: (value: IncidentDraft) => void; disabled: boolean };

export function SupportGrant({ subject, incident, draft, disabled, kind, children }: GrantPanelProps & { kind: SupportKind; children: (grant: Grant, disabled: boolean) => ReactNode }) {
  const session = useSession(); const admin = session.roles.includes("ADMIN"); const [minutes, setMinutes] = useState("15");
  const label = kind === "account" ? "Account" : "Payment";
  const grant = useQuery({ queryKey: ["operations", subject, kind + "-access", incident.incidentId], retry: false, gcTime: 0,
    queryFn: async ({ signal }) => {
      const result = await request<Grant>(session.token, "/admin/incidents/" + incident.incidentId + "/" + kind + "-access", { signal });
      const target = kind === "account" ? result.permission === "ACCOUNT_STATUS_READ" && result.accountId : result.permission === "PAYMENT_STATUS_READ" && result.paymentId;
      if (target !== incident.resourceId || result.incidentId !== incident.incidentId || result.recipient !== incident.createdBy ||
          typeof result.version !== "string" || !/^(0|[1-9][0-9]{0,18})$/.test(result.version) || !["NONE", "ACTIVE", "EXPIRED", "REVOKED", "RESOLVED"].includes(result.state) ||
          result.state === "ACTIVE" && (!result.expiresAt || !Number.isFinite(Date.parse(result.expiresAt)))) throw new ApiError(502);
      return result;
    } });
  function review(action: "ISSUE" | "REVOKE") {
    if (!grant.data || grant.error) return;
    draft({ path: "/admin/incidents/" + incident.incidentId + "/" + kind + "-access", method: "PUT",
      body: { expectedVersion: grant.data.version, action, ...(action === "ISSUE" ? { minutes: Number(minutes) } : {}), reason: action === "ISSUE" ? "INCIDENT_INVESTIGATION" : "ACCESS_NO_LONGER_REQUIRED" },
      title: action === "ISSUE" ? "Grant temporary " + kind + " access" : "Revoke " + kind + " access",
      description: `${action === "ISSUE" ? `Allow status consultation for ${minutes} minutes` : "End status consultation"}. Recipient: ${incident.createdBy}. ${label}: ${incident.resourceId}. Incident: ${incident.incidentId}. Expected access version: ${grant.data.version}.` });
  }
  const g = grant.error ? undefined : grant.data;
  return <section aria-label={"Temporary " + kind + " access"} className="support-account-access"><h3>Temporary {kind} access</h3>
    <p>Access is fixed to this incident's creator and {kind}. Resolution, revocation or expiry ends permission. Every consultation is audited.</p>
    <Failure error={grant.error} retry={() => void grant.refetch()} />{grant.isPending ? <Loading text="Checking incident permission..." /> : g && <>
      <dl className="bank-facts"><dt>Last checked access state</dt><dd><Badge status={g.state} /></dd><dt>Access version</dt><dd>{g.version}</dd><dt>Recipient</dt><dd>{g.recipient}</dd><dt>{label} in scope</dt><dd>{incident.resourceId}</dd><dt>Expires</dt><dd>{g.expiresAt ? new Date(g.expiresAt).toLocaleString() : "No permission issued"}</dd></dl>
      {admin ? <><label>Access duration<select value={minutes} disabled={disabled} onChange={e => setMinutes(e.target.value)}>{[5, 15, 30, 60].map(m => <option key={m} value={m}>{m} minutes</option>)}</select></label>
        <div className="bank-actions"><button className="button" disabled={disabled || incident.status === "RESOLVED"} onClick={() => review("ISSUE")}>Review temporary access</button>
          <button className="button secondary" disabled={disabled || ["NONE", "REVOKED"].includes(g.state)} onClick={() => review("REVOKE")}>Review access revocation</button></div></> :
        <div key={g.version + incident.status + grant.dataUpdatedAt}>{children(g, disabled || incident.status === "RESOLVED")}</div>}
    </>}
    <button className="text-button" disabled={grant.isFetching} onClick={() => void grant.refetch()}>Refresh access permission</button>
  </section>;
}
