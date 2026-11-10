# Reporting access audit — Phase 8 lot 10

Scope: CUSTOMER statement request/list/detail/CSV/PDF and separate FINANCE_OPERATOR reconciliation/journal inspection. Existing ownership and role checks remain authoritative. Every registered bounded synchronous response must have an immutable owner receipt committed before disclosure. Signed role denials and absent/invalid credentials reaching the registry are included; Gateway, early servlet/firewall rejection and other routes remain outside it.

| Route category | Action | Optional reference |
| --- | --- | --- |
| POST `/api/v1/statements` | STATEMENT_CREATE_REQUEST | None; never request body/account/month |
| Other methods `/api/v1/statements` | STATEMENT_LIST | None; never cursor |
| `/api/v1/statements/{id}` | STATEMENT_INSPECT | Canonical statement UUID |
| `/api/v1/statements/{id}/csv` | STATEMENT_EXPORT_CSV | Canonical statement UUID |
| `/api/v1/statements/{id}/pdf` | STATEMENT_EXPORT_PDF | Canonical statement UUID |
| `/api/v1/admin/reports/reconciliation` | RECONCILIATION_INSPECT | None |
| `/api/v1/admin/ledger/journals/{id}` | JOURNAL_INSPECT | Canonical journal UUID |

Categories record attempts for all methods; they add no authorization. The signed current caller is distinct from a statement owner or Ledger technical client. Actor provenance, finite outcomes and independent three-second receipt transaction follow the [owner read contract](owner-read-audit-phase-8.md). RESPONSE_READY describes a prepared response, not delivery, financial success or a newly created statement. Creation can reuse an existing immutable snapshot. A committed snapshot may survive subsequent receipt failure; the prepared response must be withheld, and retry against an unchanged authoritative source reuses its original content/hash and identity. Existing actor/content-hash deduplication governs reuse: a changed source can produce another immutable snapshot.

Reporting responses have a four MiB buffering ceiling. An oversized prepared response is discarded with sanitized 503 RESPONSE_TOO_LARGE and a server-error receipt, rather than returning partial content. Receipt INSERT/transaction failure returns sanitized 503 AUDIT_UNAVAILABLE, without a private attachment filename or prepared body. Broker/Audit outages leave the local immutable receipt for retry. No raw URLs, body/month/filter text, statement content/hash, amounts, credentials or tokens enter the event.

Additive Reporting V2 preserves V1 snapshots and adds a separate immutable receipt/outbox source plus mutable delivery metadata. A dedicated Reporting Kafka producer can write only its own prefix; central Audit admits only the typed reporting.access.recorded event for this new prefix. It does not accept arbitrary Reporting domain facts. Existing owner event shapes and producer authority remain unchanged. Migration/database administration stays trusted, and existing Kafka capacity/retention and transport limits still apply.

Acceptance requires real signed HTTP and PostgreSQL tests, committed-snapshot/failed-receipt retry, append-only and lost-ack replay checks, preserved-data upgrade, actual producer isolation and central redaction/quarantine, and hosted PKCE CSV/PDF/finance regression. Results will be recorded after execution; this document does not accept the lot or complete Phase 8.

## Preserved installation upgrade

Keep the existing volumes, `.env`, realm and credentials. The bootstrap appends only the missing Reporting Kafka key and regenerates broker configuration from existing values. It must not rotate existing credentials. The broker must be restarted to load its additional SASL identity; its data volume is retained. Capture existing immutable statement rows/content hashes and other owner source/delivery rows while their owners are stopped, and compare them before browser fixtures create new synthetic data.

Build all six affected Java images before migration because each image carries its SQL/library resources. Existing Customer, Account and Payment schema versions remain V4/V3/V5. Reporting upgrades V1→V2 with no reconstructed historical access records. Fresh Compose installations provision the identity/topic/ACLs automatically.

```sh
python scripts/bootstrap-local.py
docker compose build customer-service account-service payment-service reporting-service audit-service api-gateway
docker compose stop customer-service account-service payment-service reporting-service audit-service
docker compose stop kafka
docker compose run --rm --no-deps kafka-config
docker compose up -d --no-deps --wait --wait-timeout 300 kafka
docker compose exec -T -e "KAFKA_HEAP_OPTS=-Xms32m -Xmx128m" kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --command-config /etc/kafka/secrets/admin.properties --create --if-not-exists --topic fincore.reporting.access.recorded.v1 --partitions 3 --replication-factor 1 --config retention.ms=604800000 --config max.message.bytes=131072
docker compose exec -T -e "KAFKA_HEAP_OPTS=-Xms32m -Xmx128m" kafka /opt/kafka/bin/kafka-acls.sh --bootstrap-server kafka:9092 --command-config /etc/kafka/secrets/admin.properties --add --allow-principal User:reporting --operation Write --operation Describe --topic fincore.reporting. --resource-pattern-type prefixed
docker compose exec -T -e "KAFKA_HEAP_OPTS=-Xms32m -Xmx128m" kafka /opt/kafka/bin/kafka-acls.sh --bootstrap-server kafka:9092 --command-config /etc/kafka/secrets/admin.properties --add --allow-principal User:reporting --operation IdempotentWrite --cluster
docker compose exec -T -e "KAFKA_HEAP_OPTS=-Xms32m -Xmx128m" kafka /opt/kafka/bin/kafka-acls.sh --bootstrap-server kafka:9092 --command-config /etc/kafka/secrets/admin.properties --add --allow-principal User:audit --operation Read --operation Describe --topic fincore.reporting. --resource-pattern-type prefixed
docker compose run --rm --no-deps reporting-migrations
docker compose up -d --no-deps --wait --wait-timeout 300 customer-service account-service payment-service reporting-service audit-service api-gateway
node scripts/check-owner-read-audit.mjs --exercise-storage-failure
npx playwright test tests/e2e/cards.spec.ts tests/e2e/inspection.spec.ts tests/e2e/customer-search.spec.ts
```

The fault probe briefly revokes only each owner's audit INSERT privilege and restores it in `finally` before checking recovery. Run it before other administrative/report traffic. It also checks that Reporting cannot publish Payment receipts or Ledger financial facts, Payment cannot publish Reporting receipts and Audit has no Reporting producer authority. It prints no raw event or credential. The [Reporting event schema](../../contracts/events/reporting-access-recorded-v1.json) describes the producer shape; central source hashes, replay/conflict handling and redaction are verified separately.

Statement reads/list/save have four-second database transaction bounds. Existing outbound token/account/Ledger calls each retain their five-second request limit and occur outside the snapshot-save transaction. Gateway allows forty seconds for statement/reconciliation routes and twenty for journal inspection, including separate receipt work. These settings do not establish a universal end-to-end deadline under connection acquisition, CPU or network contention. The four MiB ceiling bounds the disclosure buffer, not all upstream response-generation memory. Broker/database capacity, trusted administrators and transport uncertainty remain explicit limits.
