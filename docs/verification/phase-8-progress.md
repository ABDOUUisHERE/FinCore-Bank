# Phase 8 requirement coverage and remaining acceptance

Checkpoint: November 9, 2026 UTC. Application source `ced831a9750084e50e5e1fd269997e07fb7116df`; integrated as `90874ca99b3a02732f92b1bdee50e5413291283e` with all independent main checks passing. Phase 8 is incomplete. The [Account/Payment administrative read audit report](phase-8-owner-read-audit.md) separates feature and main validation; previous lot reports retain their own measurements.

The baseline is the supplied [specification](../fincore/specification.md), especially user roles, Observability and Phase 8. Earlier phase reports remain the evidence for earlier functionality.

| Requirement | Delivered evidence | Remaining acceptance |
| --- | --- | --- |
| Administrative operations | [Audit/Incidents](phase-8.md): scoped incident workflows, retained fact summary/history and quarantine metadata | Complete the remaining customer/payment administration and operational overview requirements without inheriting financial roles |
| Customer management | [Exact-reference inspection](phase-8-inspection.md) and [bounded ADMIN name search](phase-8-customer-search.md) | [Incident-authorized customer discovery](phase-8-support-customer.md) is delivered; remaining customer administration needs explicit scope and its own acceptance |
| Payment monitoring | ADMIN exact-reference status/history and [temporary support observation](phase-8-support-payment.md) | Aggregate processing/failure monitoring and observed payment dashboard remain pending |
| Ledger inspection | [Role-separated journal inspection](phase-8-inspection.md), with earlier [reconciliation/reporting](phase-7.md) | Observed Ledger operations metrics/dashboard; existing FINANCE_OPERATOR authority must remain separate |
| Fraud monitoring | [Analyst cases and explanations](phase-6.md), with card preauthorization covered in [Phase 7](phase-7.md) | Observed Fraud monitoring dashboard and its operational evidence |
| Audit history | Retained published domain facts for six domain prefixes, local incident/grant commands, admission/disclosure decisions and [Customer](phase-8-customer-read-audit.md) / [Account/Payment ADMIN read receipts](phase-8-owner-read-audit.md) | Inventory and complete missing critical actions, sensitive reads and authentication/authorization failures across services; existing central ingestion does not reconstruct all previous service-local history |
| Scoped support | [Account grants](phase-8-support-access.md), [Payment grants](phase-8-support-payment.md) and [Customer grants/discovery](phase-8-support-customer.md): immutable target/recipient, expiry/revoke/resolution, current signed role | Remaining account-assistance/administration behavior; incident references alone never establish ownership or permission |
| Distributed tracing | Existing JSON logs/correlation IDs and tracing bridge dependencies in Gateway/Customer are prerequisites | Demonstrate collected cross-service HTTP and asynchronous Kafka traces, with consistent propagation and no sensitive payloads |
| Grafana dashboards | Actuator and existing messaging metrics are prerequisites | Provision and observe all six required dashboards with actual synthetic traffic and reproducible evidence |

## Next delivery order

1. **Scoped customer discovery is delivered in lot 7.** Its incident-authorized set, no/foreign/revoked scope denial and original Account/Payment receipt compatibility are measured in the linked report. ADMIN directory access remains separate.
2. **Continue closing the [audit coverage inventory](phase-8-audit-coverage.md).** Customer ADMIN search/inspection receipts are delivered in lot 8; Account/Payment ADMIN inspection receipts follow in lot 9 with independent transactions preserving financial snapshots. Map each remaining required critical action, sensitive read and security denial to its producing boundary, signed actor, structured outcome and retained record. Verify transaction/recovery/deduplication behavior and redaction. Grant denial facts already recorded by Audit do not establish full platform security audit coverage.
3. **Collect an end-to-end trace.** Use an actual hosted PKCE transaction through the required owner services and its asynchronous publication/consumption. Test propagation and outage behavior. A correlation header, JSON field or dependency declaration alone is not trace acceptance.
4. **Deliver observed dashboards.** The specification requires System overview, Service performance, Payment processing, Kafka monitoring, Ledger operations and Fraud detection. Define bounded metric labels, generate synthetic traffic and retain query/export evidence for each. Do not use customer/payment identifiers or financial contents as metric labels.

The Phase 8 exit requires operational inspection, correlated service traces and auditable critical actions, together with the six observed dashboards. Kubernetes/Helm, deployment hardening, performance acceptance and portfolio release remain Phases 9–11. Existing Compose images and passing CI are prerequisites for those milestones, not their acceptance.
