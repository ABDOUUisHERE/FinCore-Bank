# Phase 8 lot 3: operational account status

The Java 21/Spring Boot Account module exposes `GET /api/v1/admin/accounts/{id}` for ADMIN, independently checked at Gateway and Account. Other staff roles gain no implicit privilege. A reported incident UUID grants no business authorization. List, balance, transaction, restriction and other unlisted administrative routes remain denied.

The staff **Inspection** screen adds **Account** for ADMIN. Enter an exact account UUID. The response contains recorded status/version, pending restriction, a bounded known rejection code, currency/customer reference and the separately observed effective Ledger status/revision. Versions are decimal strings. It excludes the owner subject, key, internal Ledger ID, balances, postings and raw dependency responses.

## Read semantics

| ledgerState | Meaning |
| --- | --- |
| OBSERVED | Ledger returned a validated linked customer-liability account with effective status, guard revision and its statement observation time |
| NOT_LINKED | Account is still PROVISIONING and holds no Ledger link. A lost provisioning response may already have created the Ledger account; absence is not inferred |
| UNAVAILABLE | Account intent was read, but Ledger returned a 5xx error or transport failure. ledger is null; recorded status is not proof of effective state |

Upstream identity/link/owner/currency/type/status/revision/time mismatches and upstream 401/403/404 become 503 `INVALID_ACCOUNT_STATUS_SOURCE`, rather than an effective state. A failed UI refresh hides the cached effective state until successful retry. An explicit partial response replaces previously observed effective state with an availability notice.

Account and Ledger are separate sequential observations. They can differ while a restriction is being acknowledged or another command changes state. There is no atomic distributed snapshot, freshness SLA or invariant that both versions match. The timestamps identify observation times, not historical accounting facts. A GET does not provision, retry, acknowledge, reconcile or publish an event. Only the existing explicit customer commands/recovery worker change account state.

The internal Ledger route `/internal/v1/ledger/accounts/{id}/operational-status` requires the existing `ledger.read` technical authority **and** signed `azp=fincore-account`. It returns only link-verification and guard metadata. The credential already belongs to Account for its existing provisioning/restriction workflow; no role, client, password or database grant is added. The browser never calls Ledger directly.

## Existing local installation

The existing stack (including PostgreSQL, Keycloak and Kafka) must be healthy first. Retain `.env`, `.local/credentials.json`, imported realm and all persistent volumes. No schema migration or identity upgrade is needed.

```powershell
docker compose build account-service ledger-service api-gateway admin-portal
docker compose up -d --no-deps --wait --wait-timeout 600 account-service ledger-service api-gateway admin-portal
```

Gateway allows 20 seconds for the bounded machine-token/Ledger read chain; the browser permits 25 seconds. Individual remote calls remain bounded at five seconds. Invalid source data is never substituted with the recorded guard projection.

## Validation and limits

Tests cover pending intent versus applied guard, provisioning-response loss, real PostgreSQL reads without recovery writes, identity/source mismatches, version precision, role/method denial and cached-state replacement. The extended hosted-PKCE inspection journey freezes only its newly created synthetic account, verifies real Ledger status, stops only the FinCore Ledger runtime, checks explicit unavailability and restores it in a cleanup path. Public artifacts exclude credentials, raw logs and authenticated screenshots.

This lot does not add customer/name search, support business grants, account mutation for staff, complete read/security audit coverage, tracing or Grafana dashboards. See [verification](../verification/phase-8-account-status.md) and the additive [inspection contract](../../contracts/openapi/inspection-v1.yaml).
