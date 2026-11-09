import { Failure } from "../banking/Common";
import { SupportGrant, type Grant, type GrantPanelProps } from "./SupportGrant";
import { useSupportObservation } from "./useSupportObservation";

type Status = { paymentId: string; kind: string; status: string; version: string; createdAt: string; observedAt: string };
export function SupportPaymentAccess(props: GrantPanelProps) {
  return <SupportGrant {...props} kind="payment">{(grant, disabled) => <Observation subject={props.subject} grant={grant} disabled={disabled} />}</SupportGrant>;
}
function Observation({ subject, grant, disabled }: { subject: string; grant: Grant; disabled: boolean }) {
  const { result, error, busy, expired, inspect } = useSupportObservation<Status>(subject, grant, "payment");
  return <><button className="button" disabled={disabled || busy || expired || grant.state !== "ACTIVE"} onClick={() => void inspect()}>{busy ? "Consulting payment..." : "Consult scoped payment status"}</button>
    <p>Each click checks current permission. This is an observation of Payment processing, not confirmation of a Ledger posting.</p><Failure error={error} />
    {result && <section aria-label="Scoped payment observation"><h3>Scoped payment observation</h3>
      <dl className="bank-facts"><dt>Payment</dt><dd>{result.paymentId}</dd><dt>Kind</dt><dd>{result.kind}</dd><dt>Processing status</dt><dd>{result.status}</dd><dt>Payment version</dt><dd>{result.version}</dd><dt>Created</dt><dd>{new Date(result.createdAt).toLocaleString()}</dd><dt>Payment observed</dt><dd>{new Date(result.observedAt).toLocaleString()}</dd></dl>
    </section>}
  </>;
}
