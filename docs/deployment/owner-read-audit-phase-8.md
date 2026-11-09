# Account and Payment administrative read audit — Phase 8 lot 9

The existing ADMIN Account and Payment inspection routes now use the same buffered receipt mechanism as Customer. Each owner independently verifies the incoming JWT and commits a local immutable receipt before copying its bounded synchronous response. Existing support grants and narrow internal machine routes remain separate.

| Owner route category | Action | Optional reference |
| --- | --- | --- |
| `/api/v1/admin/accounts/{reference}` | ACCOUNT_INSPECT | Canonical account UUID |
| `/api/v1/admin/transactions/{reference}` | PAYMENT_INSPECT | Canonical payment UUID |

All methods on each single-segment category are recorded when they reach the owner security chain; only the existing ADMIN GET is authorized. Malformed references retain the action without inventing an identifier. Root/list/suffix routes, other owner APIs, Gateway rejections and early servlet/firewall/body-limit failures are outside this registry. Payment cursor values and raw query strings never enter receipts.

The finite outcome mapping is shared with Customer: 2xx RESPONSE_READY; 400/405/409/415/422 INVALID_REQUEST; 401 UNAUTHENTICATED; 403 FORBIDDEN; 404 NOT_FOUND; other statuses SERVER_ERROR. RESPONSE_READY describes a prepared response, not transport delivery or financial success. In particular, an Account 200 can contain an explicitly unavailable Ledger observation. No receipt asserts a distributed snapshot or an effective account state.

The actor comes from the verified current staff JWT, never the account owner, original payment actor or caller-supplied identity header. A safe opaque subject has SIGNED_JWT provenance; otherwise actor is absent with UNAVAILABLE provenance. Central provenance remains the producing owner's assertion, not independent JWT verification by Audit. Correlation is a technical assertion and does not establish identity.

`AdministrativeReadAudit` uses a three-second REQUIRES_NEW transaction. It can commit independently of an outer read-only financial snapshot or rollback. Both JDBC failures and transaction begin/commit failures cause the filter to discard the prepared body and return sanitized `503 AUDIT_UNAVAILABLE`. That attempt is not claimed as retained. The Payment inspection query remains read-only/repeatable with its four-second bound and string bigint cursor. Gateway allows ten seconds for Customer/Payment inspection and twenty for Account's separate Ledger observation; receipt transaction timeout does not bound every possible connection acquisition or network failure.

Account V3 and Payment V5 add append-only receipt tables and union their envelopes into the existing owner outbox view. They do not backfill historical reads or rewrite financial rows, old domain envelopes or acknowledged delivery metadata. Customer remains on V4 with the same event shape and uses the shared Java implementation. Runtime roles may insert/select receipts but cannot update/delete/truncate them or disable guards; migration/database administration remains trusted.

Topics are `fincore.account.access.recorded.v1` and `fincore.payment.access.recorded.v1`, using existing owner producer-prefix and Audit consumer-prefix ACLs. Receipt UUID is both event and aggregate ID; version is 1. The [event contract](../../contracts/events/owner-access-recorded-v1.json) contains only fixed action, method, status, outcome, actor/provenance, optional own-resource UUID and technical envelope metadata. Audit rejects foreign owner actions/references, strips other payload fields and retains existing digest/deduplication/conflict-quarantine behavior. Customer's previously accepted unknown-field redaction stays compatible.

Local retention permits asynchronous retry during broker/Audit outages. A repeated HTTP request creates another receipt; only redelivery of the same event identity/body is deduplicated. Kafka retention/capacity limits and trusted administrators remain relevant; this mechanism adds no independent immutable backup or indefinite retention claim.

## Upgrade an existing installation

Preserve `.env`, credentials, realm and all volumes. Capture business rows and historical envelopes/delivery receipts while their owners are stopped. Build images before migration: SQL resources are inside the new runtime image.

```sh
docker compose build customer-service account-service payment-service audit-service api-gateway
docker compose stop customer-service account-service payment-service audit-service
docker compose exec -T -e "KAFKA_HEAP_OPTS=-Xms32m -Xmx128m" kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --command-config /etc/kafka/secrets/admin.properties --create --if-not-exists --topic fincore.account.access.recorded.v1 --partitions 3 --replication-factor 1 --config retention.ms=604800000 --config max.message.bytes=131072
docker compose exec -T -e "KAFKA_HEAP_OPTS=-Xms32m -Xmx128m" kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --command-config /etc/kafka/secrets/admin.properties --create --if-not-exists --topic fincore.payment.access.recorded.v1 --partitions 3 --replication-factor 1 --config retention.ms=604800000 --config max.message.bytes=131072
docker compose run --rm --no-deps account-migrations
docker compose run --rm --no-deps payment-migrations
docker compose up -d --no-deps --wait --wait-timeout 300 customer-service account-service payment-service audit-service api-gateway
node scripts/check-owner-read-audit.mjs
npx playwright test tests/e2e/inspection.spec.ts tests/e2e/customer-search.spec.ts tests/e2e/support-access.spec.ts tests/e2e/support-customer.spec.ts
```

Fresh installations create the topics through `kafka-init`. The probe checks real direct-owner denial, delivery, redaction, identical replay, conflicting/invalid quarantine and cross-owner producer denial for the three owners. `--exercise-storage-failure` briefly revokes each runtime's audit INSERT privilege, expects a withheld 503, restores it in `finally` and verifies retained recovery. Run this fault probe before other administrative traffic. It targets no profile, payment, Ledger or grant data and prints no raw event or credential.

Measured execution belongs in the [lot 9 verification report](../verification/phase-8-owner-read-audit.md). Other critical actions/exports/security events, collected tracing and six observed dashboards remain Phase 8 work.
