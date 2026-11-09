import { Failure } from "../banking/Common";
import { SupportGrant, type Grant, type GrantPanelProps } from "./SupportGrant";
import { useSupportObservation } from "./useSupportObservation";

type Profile = { customerId: string; displayName: string; status: string; profileVersion: string; createdAt: string; observedAt: string };
export function SupportCustomerAccess(props: GrantPanelProps) {
  return <SupportGrant {...props} kind="customer">{(grant, disabled) => <Observation subject={props.subject} grant={grant} disabled={disabled} />}</SupportGrant>;
}
function Observation({ subject, grant, disabled }: { subject: string; grant: Grant; disabled: boolean }) {
  const { result, error, busy, expired, inspect } = useSupportObservation<Profile>(subject, grant, "customer");
  return <><button className="button" disabled={disabled || busy || expired || grant.state !== "ACTIVE"} onClick={() => void inspect()}>{busy ? "Consulting customer..." : "Consult scoped customer profile"}</button>
    <p>Each click checks current incident permission. This view has no account or financial information.</p><Failure error={error} />
    {result && <section aria-label="Scoped customer observation"><h3>Scoped customer observation</h3><dl className="bank-facts">
      <dt>Customer</dt><dd>{result.customerId}</dd><dt>Display name</dt><dd>{result.displayName}</dd><dt>Profile status</dt><dd>{result.status}</dd><dt>Profile version</dt><dd>{result.profileVersion}</dd><dt>Created</dt><dd>{new Date(result.createdAt).toLocaleString()}</dd><dt>Customer observed</dt><dd>{new Date(result.observedAt).toLocaleString()}</dd>
    </dl></section>}
  </>;
}
