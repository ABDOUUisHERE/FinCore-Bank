# Phase 8 lot 2: exact-reference business inspection

Backend implementation remains Java 21/Spring Boot. This lot implements a deliberately narrow first inspection surface; it does not finish customer management or Phase 8.

| Signed role | Available lookup | Excluded data |
| --- | --- | --- |
| ADMIN | Customer UUID: onboarding status, profile version and timestamps | Subject, name, country/contact details |
| ADMIN (lot 4, separate search route) | Literal name prefix: up to 20 names and references, then existing status inspection | Subject, country/contact, accounts and financial data |
| ADMIN (lot 3) | Account UUID: recorded intent and separately observed Ledger status/revision | Subject, internal Ledger ID, balance, postings, keys and raw dependency responses |
| ADMIN | Payment UUID: status/version, structured result/reason codes, journal/original payment references and 50-transition pages | Actor, amounts, currency, account IDs, free reference, idempotency key, instruction/risk/lease details |
| FINANCE_OPERATOR | Journal UUID: authoritative operation reference, exact decimal debit/credit postings and currency | Customer profile, payment intent, financial mutation |
| SUPPORT_AGENT, CUSTOMER, FRAUD_ANALYST | No new inspection access | An incident resource UUID is not a business authorization grant |

Multi-role users receive only the union of their explicit roles. ADMIN alone still cannot read journals or reconciliation. Support retains its existing own-incident access. Scoped support grants, further customer/payment administration, aggregate financial dashboards and reversal workflows are subsequent work. The additive lot 3 account read has its own [upgrade and observation semantics](account-status-phase-8.md); lot 4 has a separate [bounded name-search guide](customer-search-phase-8.md).

## Local upgrade

The existing provisioning script adds `ledger.journal.read` to Reporting's service account on both fresh and persisted realms. It adds no new client, password or browser financial scope. Existing Python remains provisioning/test tooling only.

```powershell
docker compose build customer-service payment-service ledger-service reporting-service api-gateway admin-portal
docker compose run --rm --no-deps -T business-identity
docker compose up -d --no-deps --wait --wait-timeout 600 customer-service payment-service ledger-service reporting-service api-gateway admin-portal
```

Retain the existing `.env`, `.local/credentials.json`, realm and volumes. No schema migration is needed. Do not remove persistent volumes. After role provisioning, the recreated Reporting runtime obtains a fresh machine token with the new read scope.

These upgrade commands assume the existing stack is healthy, including Keycloak/PostgreSQL/Kafka. Fresh installations still use the repository's full bootstrap and default Compose startup. The one-shot identity upgrade must complete successfully before replacing the six affected runtimes.

Staff portal: `http://localhost:5174`, **Inspection**. ADMIN selects Customer, Account or Payment; FINANCE_OPERATOR selects Journal. Enter an exact UUID from an investigation. There is no unrestricted search/list endpoint. The Gateway and destination independently enforce the roles. Reporting delegates journal reads using its own scoped machine identity; Ledger additionally checks the signed `azp=fincore-reporting`. Ledger's general `ledger.read` permission cannot satisfy this report route, and the new scope cannot post, reverse or inspect arbitrary Ledger endpoints.

## Observations and consistency

Customer status is read directly from Customer storage. Payment current state and history share one PostgreSQL repeatable-read snapshot **per page**. `beforeVersion` is exclusive and history pages contain at most 50 transitions, newest first. `version` and `nextCursor` are decimal strings; browsers do not round them to JavaScript numbers. Later pages are later snapshots; refresh to observe a newer current state. Result/reason codes are syntactically structured producer/operator codes, not an independently verified vocabulary. Arbitrary free-text codes are suppressed.

Journal reads use immutable authoritative Ledger postings and preserve decimal strings, currency and the original accounting timestamps. `observedAt` is a response observation time, not a posting time. Reporting checks the returned journal identity/source and creates no statement snapshot. Reads cannot change a posted entry or a balance. Reconciliation remains the separate FINANCE_OPERATOR report; a journal lookup alone does not assert global reconciliation.

The response `X-Correlation-ID` identifies the current HTTP request. Payment does not store historic request correlation IDs in its status table, so the inspection does not invent a historical correlation timeline. Existing retained domain facts remain available through Audit. Inspection reads do not yet generate new audit events; complete sensitive-read/security auditing belongs to the remaining Phase 8 audit work.

The [inspection OpenAPI contract](../../contracts/openapi/inspection-v1.yaml) documents four exact-reference routes, including the additive Account read. The separate [search contract](../../contracts/openapi/customer-search-v1.yaml) adds the POST name-prefix route. [Lot 2 verification](../verification/phase-8-inspection.md) records its original three-route acceptance separately from the earlier Audit/Incidents lot and later [Account verification](../verification/phase-8-account-status.md) and [search verification](../verification/phase-8-customer-search.md).
