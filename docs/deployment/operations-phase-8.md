# Phase 8 lot 1: Audit and incidents

The backend is Java 21/Spring Boot. Existing Python scripts only provision local identities/configuration or run acceptance checks. Audit introduces no financial writer, OAuth machine client or external integration.

## Existing local installation

```powershell
python scripts/bootstrap-local.py
docker compose build audit-service api-gateway customer-portal admin-portal
docker compose up -d --wait --wait-timeout 600
```

Bootstrap appends missing Audit database/Kafka secrets and two synthetic support identities. It preserves existing secrets, credentials and the imported realm. The idempotent `business-identity` job adds support users to a persisted Keycloak realm without resetting existing passwords. Kafka configuration copies the added consumer credential and grants read/describe on six domain topic prefixes plus its own group. Audit cannot produce any domain fact. If updating an already-running broker, run `docker compose up -d --force-recreate kafka-config`, then `docker compose restart kafka`, then `docker compose up -d --force-recreate kafka-init`; do not remove broker data. Existing deployments must recreate `business-identity` after its environment changes.

Audit has an isolated `audit` database, `audit_migrator` and `audit_runtime`. The runtime holds no other database password or financial service credential. No Audit database/service port is published. Access is through Gateway on `http://localhost:8280`; staff UI remains `http://localhost:5174`. All data and actions use a local banking simulation.

## Roles and workflow

- `admin.demo`: ADMIN, global retained audit/quarantine metadata and all incidents. No implicit finance or fraud permissions.
- `support.demo`, `support-second.demo`: SUPPORT_AGENT, only incidents opened by the signed subject. Another support identity receives 404 for detail/history/update. Global audit returns 403.
- CUSTOMER, FRAUD_ANALYST and FINANCE_OPERATOR do not inherit operations privileges.

Generated passwords stay in ignored `.local/credentials.json`. Sign in with hosted Keycloak Authorization Code + PKCE. The portal verifies its subject/scope with `/api/v1/admin/operations/me`. Gateway and Audit independently check signed identity/roles. Owners are never selected by request body or an actor header.

Open an incident with a reported resource UUID, kind, severity and structured reason. Review and confirm it, then move OPEN → INVESTIGATING → RESOLVED. RESOLVED may reopen to INVESTIGATING. Every change checks the current version and creates an immutable audit record and command receipt in the same database transaction. Resource references do not prove business ownership or current financial state.

If a response is lost, keep the request reference and retry the same command. Session storage retains only the signed-subject-scoped command, original key and structured body across reloads. A replay returns its original recorded result, even when the incident later changes; the separate current detail refresh shows authoritative state. Do not replace an unresolved request with a new key.

## Reading audit and quarantine

ADMIN can filter retained facts by exact event type and/or aggregate UUID, inspect producer time separately from local observation time, and load older 50-record cursor pages. Kafka provenance includes topic, partition and offset; the source actor is a producer assertion, not independent proof of who initiated a financial operation. Unknown/free-text payload fields are omitted before persistence. Hashes refer to original source bodies; bodies themselves are not retained.

Source event IDs deduplicate only identical body hashes. Conflicting identities, unsupported schema or invalid metadata go to local durable quarantine with source coordinates, SHA-256 and a fixed reason. Database failures are retried without acknowledgment. Quarantine failures do not acknowledge poison records. Default consumer offsets follow committed effects; transport is at least once. There is no replay API because quarantine intentionally does not store the raw body. Broker retention currently limits later investigation to retained records.

Incident worklists are live mutable lists ordered by UUID, not historical snapshots. Audit/history sequences are serialized before allocation and committed under the same lock so older-page cursors do not hide a concurrently delayed append. This deliberately favors simple correctness for a low-volume local demonstration; no throughput/availability claim is made.

## Verification and remaining scope

```powershell
.\mvnw.cmd -B -ntp verify
npm run typecheck
npm run lint
npm test
npm run build
python scripts/check-foundation.py
npx playwright test tests/e2e/operations.spec.ts
```

The default Foundation CI includes the operations journey and all existing journeys. The operations journey uses real hosted PKCE, a fact created by Account, lost-response replay, version conflict, foreign-support denial, finance/customer denial, read-only Kafka identity and redacted durable quarantine. PostgreSQL tests independently cover immutable permissions, receipt/state/audit rollback, concurrent updates and cursor order.

This lot does **not** complete Phase 8: scoped customer/payment/ledger inspection, missing critical-action/security audit sources, distributed traces and six Grafana dashboards remain. Audit does not reconstruct every prior service-local audit table or events older than Kafka retention. See [acceptance evidence](../verification/phase-8.md).
