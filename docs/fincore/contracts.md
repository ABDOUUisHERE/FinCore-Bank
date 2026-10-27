# Proposed API and event contracts

This page preserves the original architecture proposal. Implemented APIs are defined by the versioned contracts: [Account/Payment](../../contracts/openapi/business-v1.yaml), [Fraud](../../contracts/openapi/fraud-v1.json), [Card](../../contracts/openapi/cards-v1.json) and [Reporting](../../contracts/openapi/reporting-v1.json). [Card/Reporting boundaries](cards-and-reporting.md) describe the Phase 7 behavior, including versioned `PUT /cards/{id}` controls rather than the originally proposed freeze/unfreeze routes below. Milestone verification reports establish which implementations have passed acceptance; unimplemented proposal examples are not available endpoints.

## Public API

All business routes are under `/api/v1`, behind Gateway, with independent service JWT and object authorization. Use UUID resources, UTC ISO 8601 timestamps, decimal money strings and bounded cursor pagination. Collection responses contain `items` and `nextCursor`. Account/transaction pages require explicit currency; source and destination must match. Use version/ETag checks for mutable metadata and beneficiary changes.

| Endpoint | Owner | Expected contract |
| --- | --- | --- |
| `GET /api/v1/customers/me` | Customer | Phase 1 minimal authenticated subject response; profile data only after onboarding exists |
| `PATCH /api/v1/customers/me` | Customer | Validated own-profile update; no role/status escalation |
| `GET /api/v1/accounts` | Account | Only accounts accessible to current subject |
| `GET /api/v1/accounts/{id}` | Account | Metadata, product status and pending guard workflow |
| `GET /api/v1/accounts/{id}/balance` | Account | Ledger-backed query or explicitly tagged projection with version/asOf |
| `GET /api/v1/accounts/{id}/transactions` | Account read API | Authorized ledger-sourced history/read view with freshness |
| `POST /api/v1/transfers` | Payment | Required Idempotency-Key; durable payment resource |
| `GET /api/v1/transfers/{id}` | Payment | Owner/authorized staff only; actual known state |
| `POST /api/v1/transfers/{id}/reversal` | Payment | Privileged full compensating operation; required idempotency key |
| `GET/POST/PATCH /api/v1/beneficiaries[/{id}]` | Beneficiary | Own objects; confirmed sensitive mutations |
| `GET/POST /api/v1/cards` | Card | Synthetic, nonfunctional identifiers only |
| `POST /api/v1/cards/{id}/freeze` and `/unfreeze` | Card | Own card, state precondition and audit |
| `GET /api/v1/admin/transactions` | Payment | Scoped operational fields; no unrestricted support access |
| `GET /api/v1/admin/fraud-alerts` | Fraud | FRAUD_ANALYST and investigation scope |
| `GET /api/v1/admin/ledger/journals` | Ledger | FINANCE_OPERATOR; paginated immutable records |
| `GET /api/v1/admin/operations/health` | Operations aggregation | ADMIN; redacted health, no configuration dump |

Transfer request:

```json
{
  "sourceAccountId": "63a5c26f-81bb-4576-99ab-81c4b560e910",
  "destinationAccountId": "b095b807-5654-4658-a8d0-5ca5e347c633",
  "amount": "100.00",
  "currency": "MAD",
  "reference": "Synthetic household transfer"
}
```

The authenticated actor comes from a validated token, never a JSON `customerId` or browser header. Return 201 with Location when a newly created payment has a definitive state, or 202 with Location and Retry-After when pending. Neither status alone means financial success; only `status: COMPLETED` with a confirmed ledger reference does. The recorded initial response is replayed for an identical key; the caller polls the resource for current status.

Use 400 for invalid syntax/missing required idempotency key; 401 for missing/invalid credentials; 403 for prohibited function/role; 404 for inaccessible objects to limit enumeration; 409 for fingerprint conflicts or incompatible lifecycle state; 422 for validly formed but financially rejected commands; 429 for throttling; and 503 for unavailable required dependencies before an outcome is known. Once intent exists, retain its ID and pending state through ambiguity. All errors include an RFC 9457 problem body with `type`, `title`, `status`, `detail`, `instance`, stable `code` and `correlationId`; omit internal SQL and secrets. Sanitize caller correlation IDs and generate one if absent.

## Internal commands

| API | Authorization and semantics |
| --- | --- |
| `POST /internal/v1/ledger/accounts` | Account client scope; idempotent provisioning and zero opening balance |
| `PUT /internal/v1/ledger/accounts/{id}/guard` | Account client scope; versioned entitlements/status and lock serialization |
| `POST /internal/v1/ledger/operations` | Payment client scope plus verified originating actor; unique payment identity and canonical command |
| `GET /internal/v1/ledger/operations/{businessReference}` | Authenticated issuer scope; resolve ambiguous posting outcomes |
| `POST /internal/v1/ledger/operations/{id}/reversal` | Payment reversal scope; original reference and actor authorization |
| `POST /internal/v1/risk-assessments` | Payment client scope; explainable ALLOW/REVIEW/BLOCK, ruleset version, expiry and request fingerprint |

Use client credentials for service identity and an audience-restricted delegated actor token when acting on behalf of a customer. Ledger verifies both; a service's arbitrary `X-User-ID` is not proof of ownership. The exact Keycloak delegation configuration is a Phase 3 acceptance gate. A constrained synthetic system actor is permitted only for explicitly scoped simulation funding and audited operations.

## Payment state machine

| Current state | Allowed next states | Guard |
| --- | --- | --- |
| CREATED | VALIDATING, REJECTED | Persisted unique intent; cancellation maps to REJECTED with CANCELLED reason |
| VALIDATING | PENDING_RISK_REVIEW, PROCESSING, REJECTED, FAILED | Definitive validation outcome; dependency outage retries remain nonterminal |
| PENDING_RISK_REVIEW | VALIDATING, REJECTED | Authorized audited decision; revalidation on approval |
| PROCESSING | COMPLETED, FAILED | Only confirmed ledger success/rejection; timeout stays PROCESSING |
| COMPLETED | REVERSAL_PENDING | Authorized reversal request; unique original operation |
| REVERSAL_PENDING | REVERSED, COMPLETED | Confirmed compensation, or definitive reversal failure preserving original |
| FAILED, REJECTED, REVERSED | None | Terminal outcome; a new business action requires a new key |

State updates use versioned compare-and-set plus append-only history, atomically with outbox inserts. Dependency retry budgets, attempt metadata and next-attempt time do not require inventing a terminal failure. No transition from PROCESSING back to CREATED is allowed.

## Event envelope and ownership

```json
{
  "eventId": "d98fe5f4-82de-47df-82c9-c3657745403a",
  "eventType": "ledger.journal.posted",
  "schemaVersion": 1,
  "aggregateId": "63a5c26f-81bb-4576-99ab-81c4b560e910",
  "aggregateVersion": 12,
  "correlationId": "94b83f56-c79f-41a2-b5db-12c8f60dc9ae",
  "occurredAt": "2026-10-22T12:00:00Z",
  "payload": {
    "journalId": "e8250319-05ac-49a4-8093-90ffabf19f71",
    "operationId": "a74b8d54-fb47-4b90-a5cd-4388d4cdef48",
    "accountId": "63a5c26f-81bb-4576-99ab-81c4b560e910",
    "currency": "MAD",
    "bookedBalance": "900.00",
    "availableBalance": "900.00",
    "ledgerVersion": 12
  }
}
```

The ledger emits one account-keyed snapshot event per affected account for each posting, with a shared journal/operation reference and its own event ID. Topic `ledger.journal.posted.v1` carries these events keyed by account ID. `aggregateId` is that account and `aggregateVersion` its ledger balance version. Consumers accept newer snapshots and ignore older versions; a gap can be refreshed from the authorized ledger API. This avoids relying on global journal ordering or applying deltas twice. During hold support, available-funds events follow the same account version stream.

| Event type | Producer | Main consumers |
| --- | --- | --- |
| customer.created | Customer | Account onboarding, Audit |
| account.created | Account | Customer read views, Audit; only after ledger provisioning |
| ledger.journal.posted | Ledger | Account balances, Reporting, Audit |
| payment.initiated | Payment | Audit, Fraud signals |
| payment.completed / payment.failed / payment.reversed | Payment | Notification, Reporting, Audit |
| fraud.alert.created | Fraud | Analyst dashboard, Notification, Audit |
| card.frozen | Card | Notification, Audit |
| notification.requested | Authorized workflow service | Notification |

Use versioned topics `fincore.<event-type>.v1`, with `fincore.ledger.journal.posted.v1` for the example above. Topic naming/versioning is distinct from `eventType`. No names, email addresses, raw tokens or card details in general domain topics. Schemas are additive within a version, incompatible changes use a new version and explicit migration. Raw metadata never serves as proof that an event came from an authorized producer; Kafka ACLs enforce producer/consumer identity.

## Delivery and replay

Outbox rows commit with their producer's business transaction starting with the financial milestones, even though the Kafka publisher is added in Phase 5. Multiple publishers claim rows using bounded leases/`SKIP LOCKED`, await Kafka acknowledgment, then mark sent. A crash between broker acknowledgment and marking sent produces a duplicate; marking before acknowledgment is forbidden. Retry with capped exponential backoff and jitter, preserve event IDs, and alert on backlog age and repeated failures. Poison payloads are retained for investigation rather than silently discarded.

Consumers write `(consumer,eventId)` and their database effect in one transaction; acknowledge offsets only after commit. Notification's external simulated delivery uses a stable delivery ID and a local delivery outbox because a database transaction cannot atomically include email. Retries have an attempt limit and a dead-letter topic; replay reuses the original identity and is audited. Partition ordering helps, but publisher races and redelivery require aggregate versions regardless. End-to-end delivery is **at least once**, with idempotent effects where implemented and tested.
