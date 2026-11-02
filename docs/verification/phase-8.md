# Phase 8 — operational administration and monitoring

## A. Outcome and current lot

Phase 8 starts from completed Phase 7 `994724d`. This first lot implements a dedicated Java 21/Spring Boot Audit service, ingestion of existing published domain facts, a scoped incident workflow and connected Operations screens. It does not complete Phase 8. Subsequent lots must add scoped customer/payment/ledger inspection, missing critical-action/security audit sources, distributed tracing and all six observed Grafana dashboards before the whole phase can be accepted.

## B. Boundaries

Audit owns an isolated database and append-only records. It records producer provenance and allowlisted operational identifiers/statuses, never full event payloads, customer names/contact details, tokens or request bodies. Producer-reported event time is separate from local observation time. Domain event ingestion is asynchronous and limited by broker retention; it is not a complete reconstruction of all previous service-local audit tables.

Kafka identity `audit` may read domain topics using its own consumer group. It cannot produce Ledger or other domain facts. Duplicate event IDs require the same original body hash; conflicts and invalid/unsupported events are durably quarantined by source topic/partition/offset with hash and reason, without retaining untrusted payloads. Storage failures retry without acknowledging records. Offsets are committed after database effects; no exactly-once transport claim.

ADMIN may read retained audit/quarantine metadata and manage all incidents. SUPPORT_AGENT may create, read and update only its own incidents. Search, detail, history, pagination, updates and idempotent replay all apply the same scope. Finance/analyst/customer roles do not inherit these privileges. Audit has no financial write scope or business database credentials.

Incident creation/status changes use confirmed signed identity, expected versions, stable idempotency keys and structured reason codes. Immutable command receipts, incident state and local audit facts commit together. Support scope derives from the JWT, never a body owner. Audit facts are serialized before sequence allocation to avoid a later commit appearing behind an already-issued chronological cursor. Existing financial identities, journals, secrets and volumes are preserved.

## C. Planned changes

- `services/audit-service`: Spring Boot resource server, Flyway migration, bounded cursor queries, transactional incidents/receipts, Kafka ingestion/quarantine and PostgreSQL/security regressions.
- Gateway owner routes; independent database/runtime/migrator and Kafka read-only ACLs; append-only additions to existing bootstrap/identity tooling.
- Operations audit and incident screens with role-gated queries, explicit pending/errors, exact-response replay and confirmed state changes.
- Contracts, deployment guide and native hosted-PKCE acceptance. No new Python application service or business logic.

## D. Limits

No posted financial mutation, role assignment, MFA administration, external messaging, production deployment or performance claim in this lot. No complete audit/security coverage or dashboards claimed before subsequent lots. Quarantine retains hashes and provenance, not replay bodies; retained broker records are needed for later investigation. Historical Phase 6/7 local notification display observations remain documented.

## E. Validation

Real PostgreSQL: duplicate/conflicting source facts, invalid metadata, immutable permissions, exact command replay/conflict, optimistic concurrency, support isolation on every access path, rollback of incident/audit/receipt as one transaction and committed cursor ordering. Signed JWT tests independently reject wrong issuer/audience and unauthorized roles at Gateway and Audit. Real browser PKCE must exercise a retained domain fact, confirmed incident lifecycle, lost-response same-key recovery, support isolation and ordinary finance/customer denial. Kafka verification checks read-only producer denial and durable quarantine; all existing suites remain mandatory. Run focused local checks and exact-source CI; record failures and limitations separately.

## F. Status

Lot 1 in progress on `feature/fincore-audit-incidents`. No acceptance or complete Phase 8 claim.

## G. Evidence

Pending implementation and execution. Commit cohesive work, push it to GitHub and merge the validated lot when all required checks pass. Preserve exact source SHA, commands, counts and local/CI provenance.

## H. Following lots

Scoped customer/payment/ledger operations; complete critical-action audit sources; observed telemetry with six Grafana dashboards and correlated distributed traces. Kubernetes/deployment hardening remains Phase 9.
