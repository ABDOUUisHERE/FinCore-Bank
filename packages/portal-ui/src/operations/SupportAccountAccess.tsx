import { Failure } from "../banking/Common";
import { SupportGrant, type Grant, type GrantPanelProps } from "./SupportGrant";
import { useSupportObservation } from "./useSupportObservation";

export type { Grant } from "./SupportGrant";
type Status = { accountId: string; currency: string; recordedStatus: string; accountVersion: string; pendingStatus: string | null; lastGuardError: string | null; recordedGuardRevision: string; accountObservedAt: string; ledgerState: "OBSERVED" | "UNAVAILABLE" | "NOT_LINKED"; ledger: { status: string; guardRevision: string; observedAt: string } | null };
export function SupportAccountAccess(props: GrantPanelProps) {
  return <SupportGrant {...props} kind="account">{(grant, disabled) => <Observation subject={props.subject} grant={grant} disabled={disabled} />}</SupportGrant>;
}
function Observation({ subject, grant, disabled }: { subject: string; grant: Grant; disabled: boolean }) {
  const { result, error, busy, expired, inspect } = useSupportObservation<Status>(subject, grant, "account");
  return <><button className="button" disabled={disabled || busy || expired || grant.state !== "ACTIVE"} onClick={() => void inspect()}>{busy ? "Consulting account..." : "Consult scoped account status"}</button>
    <p>Each click checks current permission. Observations are held in this view until you leave or refresh it.</p><Failure error={error} />
    {result && !expired && <section aria-label="Scoped account observation"><h3>Scoped account observation</h3><p>Account and Ledger are observed separately. Recorded intent does not confirm the effective restriction.</p>
      <dl className="bank-facts"><dt>Account</dt><dd>{result.accountId}</dd><dt>Currency</dt><dd>{result.currency}</dd><dt>Recorded status</dt><dd>{result.recordedStatus}</dd><dt>Account version</dt><dd>{result.accountVersion}</dd><dt>Pending requested status</dt><dd>{result.pendingStatus ?? "None"}</dd><dt>Last restriction result</dt><dd>{result.lastGuardError ?? "No recorded rejection"}</dd><dt>Recorded guard revision</dt><dd>{result.recordedGuardRevision}</dd><dt>Account observed</dt><dd>{new Date(result.accountObservedAt).toLocaleString()}</dd></dl>
      {result.ledgerState === "OBSERVED" && result.ledger ? <dl className="bank-facts"><dt>Effective Ledger status</dt><dd>{result.ledger.status}</dd><dt>Effective guard revision</dt><dd>{result.ledger.guardRevision}</dd><dt>Ledger observed</dt><dd>{new Date(result.ledger.observedAt).toLocaleString()}</dd></dl> : <p role="status">{result.ledgerState === "NOT_LINKED" ? "No Ledger link is recorded yet." : "Ledger status is unavailable. Recorded status does not confirm its effective state."}</p>}
    </section>}
  </>;
}
