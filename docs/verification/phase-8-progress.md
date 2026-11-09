# Phase 8 requirement coverage and remaining acceptance

Checkpoint: November 9, 2026 UTC. Application source `fac25b9eb641fca9d629fd893b82d27b3219701d`, integrated as `35549b53b4927eb0b7bd400b024b6c08ba1c4a53`. Phase 8 is incomplete. The [payment support report](phase-8-support-payment.md) separates feature validation from independent main validation. This inventory does not certify unimplemented requirements or estimate a completion percentage.

The baseline is the supplied [specification](../fincore/specification.md), especially user roles, Observability and Phase 8. Earlier phase reports remain the evidence for earlier functionality.

| Requirement | Delivered evidence | Remaining acceptance |
| --- | --- | --- |
| Administrative operations | [Audit/Incidents](phase-8.md): scoped incident workflows, retained fact summary/history and quarantine metadata | Complete the remaining customer/payment administration and operational overview requirements without inheriting financial roles |
| Customer management | [Exact-reference inspection](phase-8-inspection.md) and [bounded ADMIN name search](phase-8-customer-search.md) | Support customer discovery needs an explicit authorized scope; no unrestricted support directory exists |
| Payment monitoring | ADMIN exact-reference status/history and [temporary support observation](phase-8-support-payment.md) | Aggregate processing/failure monitoring and observed payment dashboard remain pending |
| Ledger inspection | [Role-separated journal inspection](phase-8-inspection.md), with earlier [reconciliation/reporting](phase-7.md) | Observed Ledger operations metrics/dashboard; existing FINANCE_OPERATOR authority must remain separate |
| Fraud monitoring | [Analyst cases and explanations](phase-6.md), with card preauthorization covered in [Phase 7](phase-7.md) | Observed Fraud monitoring dashboard and its operational evidence |
| Audit history | Retained published domain facts for six domain prefixes, local incident/grant commands and admission/disclosure decisions | Inventory and complete missing critical actions, sensitive reads and authentication/authorization failures across services; existing central ingestion does not reconstruct all previous service-local history |
| Scoped support | [Account grants](phase-8-support-access.md) and [Payment grants](phase-8-support-payment.md): immutable target/recipient, expiry/revoke/resolution, current signed role | Authorized customer discovery and remaining account-assistance behavior; incident references alone never establish ownership or permission |
| Distributed tracing | Existing JSON logs/correlation IDs and tracing bridge dependencies in Gateway/Customer are prerequisites | Demonstrate collected cross-service HTTP and asynchronous Kafka traces, with consistent propagation and no sensitive payloads |
| Grafana dashboards | Actuator and existing messaging metrics are prerequisites | Provision and observe all six required dashboards with actual synthetic traffic and reproducible evidence |

## Next delivery order

1. **Define and implement scoped customer discovery.** Specify what establishes its authorized set before exposing search. Prove that no-grant, expired/revoked, foreign staff and unrelated resources cannot be enumerated. Keep ADMIN name search separate and preserve existing Account/Payment receipt compatibility.
2. **Close the audit coverage inventory.** Map each required critical action, sensitive read and security denial to its producing boundary, signed actor, structured outcome and retained record. Verify transaction/recovery/deduplication behavior and redaction. Grant denial facts already recorded by Audit do not establish full platform security audit coverage.
3. **Collect an end-to-end trace.** Use an actual hosted PKCE transaction through the required owner services and its asynchronous publication/consumption. Test propagation and outage behavior. A correlation header, JSON field or dependency declaration alone is not trace acceptance.
4. **Deliver observed dashboards.** The specification requires System overview, Service performance, Payment processing, Kafka monitoring, Ledger operations and Fraud detection. Define bounded metric labels, generate synthetic traffic and retain query/export evidence for each. Do not use customer/payment identifiers or financial contents as metric labels.

The Phase 8 exit requires operational inspection, correlated service traces and auditable critical actions, together with the six observed dashboards. Kubernetes/Helm, deployment hardening, performance acceptance and portfolio release remain Phases 9–11. Existing Compose images and passing CI are prerequisites for those milestones, not their acceptance.
