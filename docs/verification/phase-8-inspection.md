# Phase 8 lot 2 — business inspection

Date: 2026-11-04 UTC. **Lot 2 is accepted on the feature branch; main integration is pending. Phase 8 remains in progress.** Application source: `8ac48dc7b055f453cd3496e0f196d02f1b690f4b`; starting main: `93afcc4b9d8874fc697142f203023bda59243457`.

## Delivered scope

ADMIN can inspect an exact customer UUID for onboarding status/version/timestamps, or an exact payment UUID for state/version, structured result codes, journal references and bounded processing history. FINANCE_OPERATOR can inspect an authoritative posted journal, with exact decimal debit/credit amounts and currency. ADMIN alone cannot read financial journals. Gateway and destination independently enforce roles; Ledger additionally requires Reporting's narrow machine scope and signed issuer.

The connected staff **Inspection** screen has role-specific resource choices, UUID validation, refresh/error/empty states, version-cursor history and responsive layouts. Customer responses exclude name/subject/country. Payment responses exclude actor, amount, currency, account IDs, free reference, keys, instructions and worker/risk internals. No new Python business service, schema migration, financial command or public Ledger route was added.

See the [scope and local upgrade guide](../deployment/inspection-phase-8.md), [three-route contract](../../contracts/openapi/inspection-v1.yaml) and [machine-readable evidence](../evidence/phase-8-inspection-checks.json). The earlier [Audit/Incidents report](phase-8.md) remains historical evidence for lot 1.

## Executed validation

| Check | Observed result |
| --- | --- |
| Full backend CI | **220 passed**, zero failure/error/skip; 42 test classes, actual PostgreSQL/Kafka included |
| Native focused Java | **114 passed**, zero failure/error/skip; all unit/security tests in five affected services and selected Customer, Ledger, Payment, Reporting PostgreSQL suites |
| HTTP permissions | Explicit role/method/list denial, malformed references/audience, no actor-header trust, Reporting issuer plus ledger.journal.read; no inherited ADMIN finance access |
| Actual PostgreSQL | Runtime Customer projection, exact bigint strings, missing references, 54 recorded payment versions across 50/4 pages, redaction, immutable Ledger reads and unchanged money |
| Frontend | Type checks, lint, **34 cases**, both production builds passed locally and in CI |
| Hosted PKCE CI | **10 full browser journeys passed**, including the new real posted transfer/journal inspection, all legacy journeys, ACL denial, durable DLQ and audited replay |
| Native Docker | Six affected images rebuilt; bounded identity upgrade and replacement; **16 healthy runtimes**. Existing local configuration/credential file hashes unchanged; volumes retained; unrelated projects untouched |
| Native new journey | **1 passed**: real customer/payment/journal, **10.01 MAD** exact postings, metadata redaction, missing references, role denials, unchanged journal and balances |
| Responsive/visual | Customer, Payment and Journal fit **1440/390/320 px**. Desktop/mobile captures inspected; authenticated images remain private in ignored .local/visual |
| Contract/configuration | 3 paths, 24 resolved schema references, relative document links, Compose configuration, provisioning syntax and staged generated-secret scans passed |
| Dependencies | High-severity gate passes; **11 moderate development advisories** remain on the unchanged lockfile. Separate production-only audit reports **0 advisories** |

Native Java command: `mvnw.cmd -B -ntp -pl services/customer-service,services/payment-service,services/ledger-service,services/reporting-service,services/api-gateway -am verify '-Dit.test=DatabaseIsolationIT,PaymentRecoveryIT,LedgerDatabaseIT,ReportingDatabaseIT' '-Dfailsafe.failIfNoSpecifiedTests=false'`. This explicitly selects four PostgreSQL suites; it is not a claim that the full native reactor passed. Full CI runs ordinary `mvnw verify`. Browser acceptance uses `npx playwright test tests/e2e/inspection.spec.ts`; default CI runs all ten journeys.

## Findings retained

The repository folder rename left stale npm workspace junctions, repaired by npm ci. Docker was manually paused and resumed by the user. An initial negative HTTP fixture lacked a body and hit the size guard before authorization; it now supplies a bounded body. An initial native full verify stopped at MessagingDeliveryIT's Kafka startup timeout; exact-source CI later passed that unchanged suite. An initial CI run found the new history fixture could be claimed by another worker test; it now ends in REJECTED and full CI passes. Superseded failures are not reported as successful runs.

## Limits and following work

Exact-reference lookup does not implement customer/name search, account-status inspection, aggregate dashboards or scoped support business grants. An incident reference alone never grants business access. Payment state/history are one repeatable snapshot per page; later pages are later observations. Bigint versions/cursors remain decimal strings. Reason/result codes are syntactically structured producer/operator assertions. A journal read does not assert global reconciliation; finance retains its separate reconciliation report.

No financial mutation, reversal workflow, historic request correlation timeline or new inspection-read audit event is claimed. Complete critical-action/security/read auditing, distributed tracing and six observed Grafana dashboards remain Phase 8 work. Kubernetes remains Phase 9.

## Exact-source GitHub evidence

All three runs use `8ac48dc7b055f453cd3496e0f196d02f1b690f4b`:

| Workflow | Result |
| --- | --- |
| [Frontend quality](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/41813946956) | Passed |
| [Backend quality](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/41813946773) | Passed |
| [Foundation integration](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/41813946802) | Passed |

Generated secrets, tokens, raw logs and authenticated screenshots are excluded from public evidence. All commits represent implementation, tests or measured documentation; no empty/artificial contribution commits were created.
