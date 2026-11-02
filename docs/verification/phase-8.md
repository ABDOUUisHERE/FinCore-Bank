# Phase 8 — operational administration and monitoring

## A. Outcome and current lot

Date: 2026-11-02 UTC. **Lot 1 is accepted; Phase 8 remains in progress.** Application source: `4ef95f6036d0dc9d0479f544cd6cfb4a97200128`, starting from completed Phase 7 `994724d`.

This lot adds a Java 21/Spring Boot Audit service, retained published domain facts, immutable incident history and connected staff screens. Complete Phase 8 still requires scoped customer/payment/ledger inspection, missing critical-action/security audit sources, distributed tracing and six observed Grafana dashboards. Kubernetes remains Phase 9.

## B. Boundaries

Audit has its own database/runtime/migrator and no published service/database port, financial scope, other business database password or producer identity. Kafka read/describe permissions cover the six domain prefixes and Audit's own consumer group. Domain event ingestion is asynchronous and limited by broker retention; it does not reconstruct every previous service-local audit table or security action.

ADMIN reads retained global audit/quarantine metadata and manages all incidents. SUPPORT_AGENT creates, reads and changes only incidents opened by its signed JWT subject. List, detail, history, cursor queries, status updates and original-receipt replay apply the same scope. Other financial/customer roles gain no implicit operations access. Gateway and Audit independently validate signed issuer, audience, expiry and role. A body/header cannot choose the actor or owner.

Incident resource UUIDs are reported references, not proof of ownership or current financial state. Confirmed changes use expected versions, actor-global stable keys and structured reasons. State, immutable audit fact and original command receipt commit together. A response replay reports the original recorded result; current details refresh separately. Unknown-response requests persist under the service-verified subject across reloads.

## C. Implemented changes

- `services/audit-service`: signed resource server, Flyway migration, 50-record cursors, transactional incidents/receipts, ingestion/digest-only quarantine and real PostgreSQL/security/recovery regressions.
- Separate Audit storage and read-only broker grants; append-only local bootstrap additions and two support demo identities for both fresh and persisted realms. Existing secrets, imported realm and volumes were retained.
- Scoped Operations Audit/Incidents screens, confirmation, exact-request recovery, current-state refresh, empty/error states and inspected desktop/mobile forms.
- Eight-path OpenAPI contract, [operations guide](../deployment/operations-phase-8.md), [boundary ADR](../fincore/adr/0011-retained-audit-and-scoped-incident-receipts.md) and a real hosted-PKCE browser journey.
- Customer/Account Gateway response budgets aligned with existing bounded Card/Reporting chains after observed cold 504s. No new Python application service or business logic; Python remains existing setup/check tooling.

The [complete changed-file list](phase-8-files.txt) and [machine-readable evidence](../evidence/phase-8-checks.json) accompany this report.

## D. Limits

Unknown/free-text payload fields, names/contact details and raw quarantine bodies are not persisted. Retained facts contain allowlisted operational metadata, provenance and a SHA-256 digest of consumed body text. Producer time/actor assertions remain separate from local observation time. Re-delivered source IDs require an identical body hash; invalid schema/source/metadata and identity conflicts go to durable quarantine. Storage/recovery failures prevent acknowledgment. No exactly-once transport claim.

Audit/history sequence allocation is serialized under a transaction lock so delayed commits cannot disappear behind an older-page cursor. This limits throughput and makes no performance claim. Incident worklists are mutable UUID-ordered lists, not snapshots. Database grants/triggers reject rewriting; a privileged database administrator can change schema/triggers, so evidence is not tamper-proof against database administration.

There is no raw-body replay API; investigation needs the original broker-retained record. No financial mutation, role assignment, MFA administration, external messaging, production deployment, complete audit/security coverage, six dashboards or tracing is claimed in this lot. Historical Phase 6/7 observations remain preserved.

## E. Executed validation

| Check | Result |
| --- | --- |
| Focused native Audit Maven verification | **18 passed**, zero failure/error/skip: 3 envelope/redaction, 1 actual Kafka recovery handler, 5 signed HTTP/security, 9 real PostgreSQL |
| PostgreSQL regressions | Source deduplication/conflict, digest-only quarantine, immutable grants/triggers, support isolation, original receipts/conflicting keys, concurrent versions, rollback of state/audit/receipt, commit-order cursors and bounded owner pagination |
| Gateway signed security | Independent issuer/audience/expiry and explicit Audit/incident role checks passed locally and in full CI |
| Frontend | Type checks, lint, **30 cases**, production images; all passed. The `npm audit --audit-level=high` gate passes with **11 moderate** advisories on the unchanged development dependency tree and no high/critical advisories. A separate native `npm audit --omit=dev --json` reports **zero production dependency advisories** |
| Local Docker | **16 healthy FinCore runtimes**; existing data/secrets preserved; unrelated Commerce services left running |
| Local private authentication | Direct Customer and Audit APIs reject missing identity; public Gateway/portal readiness passes |
| Local hosted PKCE Operations | **1 complete journey passed** on the final application, including real Account fact, lost-response/reload same-key recovery, investigation/resolution, original receipt/history, version conflict and support/finance/customer denial |
| Local broker/quarantine | Audit denied Ledger topic Write without relying on idempotent-producer cluster denial; authorized poison fixture observed by digest; raw canary absent from Audit logs |
| Local responsive/visual QA | Audit and Incidents fit **1440/390/320 px**; desktop/mobile captures inspected. Authenticated captures remain private in ignored `.local/visual` |
| Contract/configuration | Eight OpenAPI paths parsed, 43 references resolved, Compose validated, existing setup scripts compiled, staged generated-secret scan passed |

Commands included `mvnw.cmd -B -ntp -pl services/audit-service,services/api-gateway -am verify`, the final Audit-only `verify '-Dit.test=AuditDatabaseIT' '-Dfailsafe.failIfNoSpecifiedTests=false'`, npm typecheck/lint/tests/build, Docker image builds and persistent-volume startup, `python scripts/check-foundation.py`, and `npx playwright test tests/e2e/operations.spec.ts`. No PostgreSQL check was silently skipped for unavailable Docker.

Cold local startup initially outlasted a separate 600-second readiness probe; all runtimes subsequently became healthy. Initial sign-in readiness and first Customer/Account requests exposed cold timing limits. The sign-in check now waits for actual readiness, and owner-call route/browser budgets were aligned. An earlier new-journey CI run passed all eight existing journeys but failed an exact label selector; the corrected combobox selector and lifecycle regression pass. A first broker probe was correctly denied cluster IdempotentWrite before reaching the topic check; the final probe disables idempotence only to independently test topic Write denial. Visual QA found and corrected missing shared form styling before acceptance.

The final local run covers the new operations journey. The full legacy local notification journey was not rerun in this lot; its earlier Phase 7 observation is not erased or relabeled. The current full CI runs all nine journeys successfully, including broker recovery and notification delivery.

## F. Acceptance status

Lot 1 is accepted on `feature/fincore-audit-incidents` against the exact application source above. Phase 8 is not complete. Merge delivery and main-branch verification are recorded separately after integration; documentation-only evidence changes preserve the tested application source.

## G. Exact-source GitHub evidence

All three runs below use `4ef95f6036d0dc9d0479f544cd6cfb4a97200128`:

| Workflow | Observed result |
| --- | --- |
| [Backend quality](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/41443748847) | **210 passed**, zero failure/error/skip, full Maven reactor |
| [Frontend quality](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/41443748764) | Type checks, lint, **30 cases**, both builds and vulnerability check passed |
| [Foundation integration](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/41443748779) | Fresh Compose/migrations, direct authentication, technical Ledger smoke, **9 browser journeys**, producer ACL denial, durable DLQ and audited replay passed |

Generated credentials, tokens, raw diagnostic logs and authenticated screenshots are excluded from public evidence. GitHub associates the implementation commits with `ABDOUUisHERE`; no empty or artificial contribution commits were created.

## H. Following lots

Scoped customer/payment/ledger operations; complete critical-action/security audit sources; correlated distributed traces; six observed Grafana dashboards. Subsequent work needs its own measured acceptance. Kubernetes/deployment hardening remains Phase 9.
