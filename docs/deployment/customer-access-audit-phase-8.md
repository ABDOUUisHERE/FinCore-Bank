# Customer administrative read audit — Phase 8 lot 8

Scope: Customer owner's `/api/v1/admin/customers/search` and single-segment `/api/v1/admin/customers/{reference}` routes. All methods on these paths are categorized; only the existing ADMIN POST search and GET inspection remain authorized. This lot does not accept platform-wide security audit, Gateway rejections, identity-provider events, other owner routes, tracing or dashboards.

| Action | Reference retained | Outcomes |
| --- | --- | --- |
| CUSTOMER_SEARCH | None; never a search prefix or cursor | RESPONSE_READY, INVALID_REQUEST, UNAUTHENTICATED, FORBIDDEN, NOT_FOUND, SERVER_ERROR |
| CUSTOMER_INSPECT | Canonical UUID if present, without asserting existence | Same finite outcomes |

The owner buffers these bounded synchronous responses. An append-only Customer database record must commit before any response body is copied to the client. `RESPONSE_READY` means the authorized response was prepared; it does not prove receipt by a browser. A failed local audit write replaces the response with sanitized `503 AUDIT_UNAVAILABLE`, including for a denial. Such an unavailable attempt is not claimed as retained. A broker or central Audit outage does not prevent local retention: the same immutable record is an outbox source and retries asynchronously.

The actor is captured only after successful JWT verification in the owner's security chain. A safe opaque signed subject has provenance SIGNED_JWT; absent, invalid or unretained subjects have provenance UNAVAILABLE and no actor. Forged identity/role headers, unverified token contents, names, query strings, raw paths, request/response bodies and credentials never enter the record. Correlation is a bounded caller-supplied technical assertion, not identity evidence.

Customer migration V4 adds a separate immutable table and unions its envelopes into the existing outbox view without changing prior domain facts or delivery receipts. Events use `customer.access.recorded`, event UUID as aggregate ID and aggregate version 1. Existing producer ACLs remain scoped to `fincore.customer.`. Audit projects only the finite action/outcome/provenance/method/status metadata and optional customer UUID, and retains its existing deduplication, hash-conflict quarantine and broker coordinates. The central record remains a producer assertion; it does not independently verify the source JWT.

Early servlet container/firewall/body-size rejection, asynchronous responses, transport disconnects and requests rejected at Gateway before reaching Customer are outside this finite registry. Existing rate/retention/capacity limits still apply; this lot adds no claim of indefinite Kafka retention or independent immutable backup.

Acceptance requires signed HTTP success/denial/invalid-credential tests, withheld data on audit storage failure, restricted-role PostgreSQL immutability and migration tests, actual outbox delivery plus central replay/redaction, and hosted PKCE regression. Evidence is recorded only after execution.

## Preserved installation upgrade

Keep the existing `.env`, realm, credentials and volumes. Build both new Java images before invoking the migration runner; it carries its own SQL resources. The Customer owner must be stopped while its schema is upgraded. The new topic uses the existing Customer producer and Audit consumer prefix ACLs; no new identity or credential is required.

```sh
docker compose build customer-service audit-service
docker compose stop customer-service audit-service
docker compose exec -T -e "KAFKA_HEAP_OPTS=-Xms32m -Xmx128m" kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --command-config /etc/kafka/secrets/admin.properties --create --if-not-exists --topic fincore.customer.access.recorded.v1 --partitions 3 --replication-factor 1 --config retention.ms=604800000 --config max.message.bytes=131072
docker compose run --rm --no-deps customer-migrations
docker compose up -d --no-deps --wait --wait-timeout 300 customer-service audit-service
node scripts/check-customer-audit.mjs
npx playwright test tests/e2e/customer-search.spec.ts tests/e2e/inspection.spec.ts tests/e2e/support-customer.spec.ts
```

Fresh Compose installations create the topic through `kafka-init` automatically. The probe sends synthetic duplicate/conflicting/invalid metadata through the broker and checks the direct owner's retained anonymous denial, central digest/redaction and quarantine. It prints no raw event or credential. See [the versioned event contract](../../contracts/events/customer-access-recorded-v1.json).

The optional `--exercise-storage-failure` probe briefly revokes INSERT on this one audit table from `customer_runtime`. Administrative reads return 503 during that interval. Its `finally` block restores the required INSERT privilege and verifies a retained request after recovery. CI runs it before browser traffic in its isolated installation. Profiles, financial rows and grants are not test fault targets.

The runtime role can insert/select receipts but cannot update/delete/truncate them or disable their guards. Migration/database administration remains trusted; this is not an independently tamper-proof archive. Each new read request creates a new receipt, even if its correlation ID is reused. Only broker redelivery of the same event UUID/body is deduplicated. Historical domain envelope bytes and acknowledged delivery rows remain unchanged by V4.
