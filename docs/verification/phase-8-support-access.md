# Phase 8 lot 5 — temporary support account access

Date: 2026-11-05 UTC. Lot 5 is accepted and merged on main. Phase 8 remains in progress.

Application source: `5eff6db63f2b9360e3a649fae48f62d5896455e5`, based on the previously accepted customer search lot. All business changes use Java 21/Spring Boot. The existing Python Keycloak setup utility gains token renewal and two provisioning-only regression tests.

Scope: ADMIN grants or revokes ACCOUNT_STATUS_READ for an active ACCOUNT incident, fixed to its creator and account UUID. SUPPORT_AGENT may explicitly request a redacted status observation through that incident. Expiry, foreign ownership, resolution and revocation deny access. No support customer search, payment inspection, journal or financial mutation is included.

ADMIN reviews the recipient, exact account, duration and expected string version before a confirmed command. Receipt keys are actor-global across incident and grant mutations. A lost response is replayed with the same persisted key/body after reload, while current permission is fetched separately. SUPPORT_AGENT has an explicit audited consultation button and cannot choose another account or recipient. A failed attempt removes its prior observation; a partial Ledger observation replaces effective status with an availability notice.

Audit commits admission, releases database locks for the Account call, and commits a new authorization decision before disclosing its validated projection. Both checks use incident ownership, active state, current grant version and database-clock expiry after acquiring the fact lock. Revocation, replacement, resolution or expiry during the call withholds its payload. A final audit-write failure also withholds it. Resolution atomically revokes even an expired grant; reopening requires another ADMIN command.

The independently protected Account route requires signed client `fincore-audit` and `account.support.read`. The broker is trusted for dynamic object authorization; its machine identity can technically read redacted status outside a grant if compromised. It has no financial writer authority, Account database login, balances, customer directory, journal or Keycloak admin credentials. See [ADR 0012](../fincore/adr/0012-online-support-account-access.md), [contract](../../contracts/openapi/support-account-access-v1.yaml) and [upgrade guide](../deployment/support-access-phase-8.md).

## Measured checks

| Check | Observed result |
| --- | --- |
| [Feature backend](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/42016766038) | 255 cases in 50 classes; no failures, errors or skips |
| [Feature frontend](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/42016766075) | 49 cases in 10 files; typecheck, lint, both builds and high-severity dependency gate pass |
| Focused native Java | 32 unique cases: Gateway 10, Account signed inspection 3, existing Audit signed routes 5, support signed routes 2, broker module 3, PostgreSQL support grants 9; module class deduplicated across commands |
| Native frontend | 49 passed in 10 files; one worker and increased hardware wait budgets, assertions unchanged |
| Provisioning | 2 regression cases pass; persisted Keycloak setup succeeds with renewed tokens |
| Native migration | Audit V2 applied over retained V1; profile/account/journal/incident counts unchanged before browser fixtures |
| Secrets | All existing secret values and user credential bytes unchanged; only AUDIT_CLIENT_SECRET appended; volumes retained |
| Production dependencies | 0 vulnerabilities; CI development audit reports 7 moderate, 0 high/critical on the unchanged lockfile |
| Native hosted PKCE | 2 journeys passed: original Operations regression and new support grant/consult/revoke/resolution/reopening; 1440/390/320 px layout assertions, private desktop/mobile visual inspection |
| [Full feature integration](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/42016766139) | 12 journeys passed; fresh Compose, direct authentication, Ledger smoke and Kafka ACL/DLQ/replay checks passed |

All three workflows use the exact application source above. The [machine-readable evidence](../evidence/phase-8-support-access-checks.json) records their job IDs, measured counts, native upgrade and explicit limits. Twelve cohesive scope, implementation, test and repair commits are associated with ABDOUUisHERE by GitHub. Acceptance documentation is a further commit; no application code changes after the tested source.

The nine real PostgreSQL cases cover V1-to-V2 upgrade, fixed target/recipient, foreign/missing incident denial, absence/expiry, original receipts/global key conflict, resolution/reopening, in-flight invalidation, concurrent versions, receipt rollback/immutable grant identity, no remote lock and failed final audit writes. Application commands always use the restricted runtime role. The remote-revocation test commits from another transaction while Account responds; the audit-failure test injects a retained-fact write failure. No Account response contents enter retained attempt facts.

## Resolved verification failures

Native identity setup initially failed with HTTP 401 after 65 seconds. A fresh token succeeded on the same endpoint and reported a 60-second lifetime. Two deterministic regressions reproduced expiry/unexpected 401 through the actual provisioning path before the fix. The utility now renews before expiry and retries one unauthorized response with a fresh token. Persisted-realm setup then completed. Temporary probes were removed; tokens and diagnostic response bodies were never printed.

Initial Windows frontend runs exceeded default five-second test/one-second asynchronous DOM waits during local build contention. A complete 49-case run passed with one worker, a 20-second test budget and a 10-second DOM wait through ignored local configuration. Repository defaults remain unchanged; the same 49 assertions pass on GitHub. A Java nested-lambda variable conflict was corrected and the affected modules rebuilt cleanly before the successful signed HTTP run.

## Scope limits

Admission is not disclosure; a DISCLOSED fact records a decision rather than proof of browser receipt. Account and Ledger are separate observations. Revocation cannot retract delivered data. Grant changes and consultation decisions are retained locally; authentication/role/malformed-body denials precede this domain check. Historical grant receipts retain original expiry after reissue or revocation. Complete cross-service critical-action/read/security audit, additional customer/payment support scope, tracing and six Grafana dashboards remain Phase 8 work. Later roadmap phases are not accepted by this lot.

Generated credentials, authenticated identifiers/screenshots, raw logs and temporary diagnostics remain private. No force push, history rewrite, empty commit or artificial contribution was used.

## Main integration

Merged as `62bb8f28d3eb2bdc3ccebc33c87f624e87140d8d` on November 5 UTC. Application, configuration and tests are identical to feature-tested source `5eff6db63f2b9360e3a649fae48f62d5896455e5`; subsequent acceptance/proof changes contain documentation only. All independent main checks passed:

| Workflow | Observed main result |
| --- | --- |
| [Backend quality](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/42019456850) | 255 passed in 50 classes; no failures, errors or skips |
| [Frontend quality](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/42019456866) | 49 passed; types, lint, both builds and high-severity audit gate pass |
| [Foundation integration](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/42019456955) | 12 journeys passed; fresh Compose, authentication, Ledger smoke and Kafka ACL/DLQ/replay pass |

A further native probe used actual Keycloak client credentials: fincore-audit has only the account.support.read business authority and no staff/customer realm role. Its redacted Account route succeeds; Account lookup/list/ADMIN routes, the existing lookup worker at the new route, and Ledger audience access are denied. No token or account payload is published. All 16 local runtimes remained healthy after acceptance.

At the merge, GitHub associated all 14 new commits with ABDOUUisHERE and its API reported 17 contributions for November 5 UTC, up from 3 before merging. This final proof is one additional documentation commit. The contribution target of 100 has not been reached. Phase 8 remains in progress.
