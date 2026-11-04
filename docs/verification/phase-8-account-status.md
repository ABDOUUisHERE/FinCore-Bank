# Phase 8 lot 3 — account status inspection

Date: 2026-11-04 UTC. **Lot 3 is accepted and merged into main; all three main workflows passed. Phase 8 remains in progress.** Application/test source: `ae0fb2a47f52172fdce07894c04b0c242cf9fafc`; starting main: `29cb2b871280cc59afc6764dfc14138be6e4d7e6`.

## Delivered scope

ADMIN can inspect an exact account UUID through the Java 21/Spring Boot Gateway and Account service. The staff Inspection screen shows Account's recorded status/version, pending restriction and recorded guard revision separately from Ledger's observed effective status/revision. Versions remain decimal strings. The response excludes owner subject, internal Ledger ID, keys, balances, postings and raw dependency payloads. List, staff restriction commands and other roles receive no new permission.

The internal Ledger metadata read requires both existing `ledger.read` authority and signed `azp=fincore-account`. Account validates the source identity, link, owner, currency, type, status, revision and observation timestamp. This lot adds no business service in Python, schema migration, identity upgrade, password or database grant. Existing provisioning/test tooling remains separate from the Java application.

See the [operational guide](../deployment/account-status-phase-8.md), additive [four-route contract](../../contracts/openapi/inspection-v1.yaml) and [machine-readable evidence](../evidence/phase-8-account-status-checks.json). Prior [Audit/Incidents](phase-8.md) and [business inspection](phase-8-inspection.md) reports retain their original acceptance evidence.

## Executed validation

| Check | Observed result |
| --- | --- |
| Full backend CI | **231 passed**, 45 classes, zero failure/error/skip; actual PostgreSQL/Kafka included |
| Native focused Java | **88 unique cases passed** across two focused runs: Gateway 11, Ledger 61 and Account 16; 13 classes, zero unresolved failure/error/skip |
| HTTP security | Signed audience/role/method/list denial, no actor-header trust, technical issuer plus scope, malformed references and upstream authorization failures |
| Actual PostgreSQL | Ledger guard/status metadata and exact revisions; unchanged balances, journals and postings; lost provisioning response and pending guard projection read without recovery writes |
| Frontend | Type checks, lint, **38 cases in 8 files**, both production builds passed locally and in CI |
| Stale-state handling | Partial unavailable response and failed HTTP refresh hide previously observed effective status; explicit retry restores it; precision above JavaScript's safe integer range retained |
| Hosted PKCE CI | **10 full browser journeys passed**, including the extended inspection scenario, fresh Compose, direct authentication, Ledger smoke, ACL denial, durable DLQ and audited replay |
| Native Docker | Four affected images rebuilt/replaced; **16 healthy runtimes** after verification. Configuration/credential hashes unchanged during upgrade; volumes retained; unrelated projects untouched |
| Native original journey | **1 passed** with the unmodified fixture: real customer/payment/journal/account, synthetic source freeze, real Ledger stop/restore, role denials, unchanged versions/guard revision and booked balances |
| Responsive/visual | Account inspection fits **1440/390/320 px**; desktop/mobile observed and unavailable captures inspected, retained privately in ignored `.local/visual` |
| Contract and evidence | **4 paths, 33 resolved references**, relative document links, whitespace checks and staged generated-secret scans |
| Dependencies | High-severity gate passes; **11 moderate development advisories** remain on the unchanged lockfile. Separate production-only audit: **0 advisories** |

Native Maven first targeted Gateway, Ledger and Account with `-am verify`, selecting `AccountRecoveryIT,LedgerDatabaseIT` and `-Dfailsafe.failIfNoSpecifiedTests=false`. Gateway and Ledger passed; an Account mock test failed. After its test-only correction, Account's focused `verify` passed all 16 cases with `AccountRecoveryIT` selected. The 88 count combines the latest successful result for each distinct class; the initial invocation is not reported as a successful reactor. Full CI runs ordinary `mvnw verify`, including additional integration suites. The already-installed Java 21 compiler was selected with a process-only JAVA_HOME override.

Native frontend acceptance finished with the unchanged 38-case suite using one worker after image builds stopped. Native browser acceptance uses `node node_modules/@playwright/test/cli.js test tests/e2e/inspection.spec.ts`. The final run used the original fixture and completed in 1.3 minutes. CI executes all ten journeys.

## Findings retained

The initial native Maven command inherited Java 11 from JAVA_HOME and stopped before application compilation (`release version 21 not supported`). A subsequent new Ledger security test referenced a helper unavailable to that module; it now uses the existing Ledger RSA helpers. An Account mock re-stubbing expression invoked the previous configured exception; `doThrow` isolates successive upstream-failure assertions. These were tooling/test corrections, not changes to the production authorization contract.

Two frontend attempts during local image builds timed out in the existing incident-remount test at its unchanged five-second limit. The idle native run and exact-source CI both passed all 38 cases without weakening assertions or deadlines.

The first native browser attempt stopped in the existing funding fixture before reaching the new account inspection. A later read-only probe found that synthetic funding had completed and posted. A private diagnostic adapter, then a final run with the original fixture, each passed the entire journey including real Ledger outage and restoration. The initial cause was not established and is not claimed as fixed. Temporary diagnostic instrumentation was removed; the original test was restored before final verification. Failed attempts remain failures and are not counted as successful runs.

## Semantics and remaining work

Account and Ledger are two sequential observations, not an atomic distributed snapshot. Pending intent can differ from effective status. `NOT_LINKED` means Account has no recorded Ledger link while provisioning; it does not prove that Ledger creation never occurred. `UNAVAILABLE` has a null Ledger observation and makes no effective-state assertion. Invalid or mismatched source data yields 503 rather than a fabricated status. GET performs no provisioning, recovery, acknowledgement, reconciliation or event publication.

This lot does not finish customer/name search, scoped support business grants, aggregate dashboards, complete critical-action/security/read auditing, distributed tracing or six Grafana dashboards. An incident reference alone never grants business access. Kubernetes remains Phase 9. No freshness SLA, production readiness or complete project acceptance is claimed.

## Exact-source GitHub evidence

All three feature runs use `ae0fb2a47f52172fdce07894c04b0c242cf9fafc`:

| Workflow | Feature result |
| --- | --- |
| [Backend quality](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/41868046124) | 231 passed; zero failure/error/skip |
| [Frontend quality](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/41868045913) | 38 passed; types, lint, both builds and high-severity audit gate passed |
| [Foundation integration](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/41868045937) | 10 journeys; fresh Compose, Ledger, ACL/DLQ/replay passed |

Generated secrets, tokens, raw logs and authenticated screenshots are excluded from public evidence. Commits represent implementation, tests or measured documentation; no empty contribution commits are created.

## Main integration

Merged as `0d1e89e3060cea2548516c1e797b1739675229e2`. Application and tests are identical to feature-tested source `ae0fb2a47f52172fdce07894c04b0c242cf9fafc`; the intervening feature acceptance commit and this final proof update contain documentation only. Independent main checks passed:

| Workflow | Main result |
| --- | --- |
| [Backend quality](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/41873406336) | 231 passed; zero failure/error/skip |
| [Frontend quality](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/41873406454) | 38 passed; types, lint, both builds and high-severity audit gate passed |
| [Foundation integration](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/41873406506) | 10 journeys; fresh Compose, direct auth, Ledger, ACL/DLQ/replay passed |

The branch contains six cohesive scope, implementation, test and verification commits. Main retains their history through a merge commit; this final documentation proof does not alter the tested application. No force push or history rewrite was used.
