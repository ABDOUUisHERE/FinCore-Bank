# Phase 2 — ledger kernel implementation and verification

Implementation: 2026-10-22 UTC. Final evidence: 2026-10-23 UTC. Branch `feature/fincore-ledger`, based on the completed foundation documentation commit `9fb4cb58a32b3260bfeee5d4aa97619a854a47bd`. Branches remain stacked and unmerged.

## A. Current state

The foundation's signed identity, portal shells and isolated services were verified in Phase 1. This milestone introduces the authoritative financial kernel. Public browser account/payment flows remain future work.

## B. Implementation

New Java 21 `ledger-service` with exact positive Money values, private JWT-protected commands/read APIs, Flyway migration-only entry point and non-root runtime. A separate ledger database has restricted runtime SELECT/function execution and a separate migration owner. Runtime credentials cannot mutate balances or financial records directly.

Atomic SQL functions implement zero-balance provisioning, versioned guards, two-entry deposits/transfers/withdrawals/merchant payments and exact full compensation. Sorted account/balance locks protect against overspending and restriction races. Durable issuer/reference identity complements issuer/kind/transport keys; normalized payloads replay, changed payloads conflict, and definitive rejections survive later funding. Money, original journals, postings, outcomes, identities and outbox facts are immutable after commit. Deferred constraints check balances and outcome/journal consistency at commit. Reconciliation compares aggregate balances with postings in one repeatable-read snapshot.

## C. Files and modules

The [path manifest](phase-2-files.txt) lists this milestone relative to the completed foundation.

- `services/ledger-service/`: application, exact Money, private HTTP/JDBC adapters, security/correlation/body limits, schema/functions/triggers, Dockerfile and meaningful unit/security/database tests.
- `pom.xml`: add the independent ledger module to the Maven reactor.
- `infrastructure/docker/postgres/init-ledger.sh`, `compose.yaml`: repeatable database bootstrap, one-shot migrations/identity configuration, private runtime and optional check runner.
- `scripts/bootstrap-local.py`, `.env.example`: append missing ledger secrets while preserving the existing dataset's credentials.
- `scripts/configure-ledger-client.py`, `scripts/check-ledger.py`: idempotent fresh/persisted-realm technical client setup and real signed HTTP financial journey.
- `.github/workflows/foundation.yml`, `scripts/diagnostics.py`: execute the ledger journey in CI and provide redacted failure diagnostics. Backend verify automatically includes the new module.
- `contracts/openapi/ledger-v1.yaml`, ADR 0005, README, setup/testing guides, this report: public contract, design rationale, reproducible operation and evidence.

## D. Risks and limits

Internal synthetic tooling only. No Payment/Account business service, customer delegation, portal money screens, holds, partial refunds, Kafka publisher, audit service, fraud engine, backups/load targets or production deployment. One immutable subject owns each ledger account; multiple-owner entitlements and distributed restriction workflow recovery belong to Phase 3. The technical operator has explicit all-ledger roles for the isolated journey; narrower service clients must precede customer integration. A deposit is capped at 1,000,000.00 synthetic units per command; no velocity/risk-policy claim is made. Available equals booked. Reconciliation reports discrepancies and never repairs immutable history. Financial IDs/outcomes are retained for the dataset lifetime; outbox delivery metadata is deferred to Phase 5. DBA/migration-owner compromise remains outside the runtime privilege boundary.

## E. Test strategy

Exact decimal edge cases; real signed JWT validation/authorization; actual PostgreSQL constraints and independent concurrent connections; forced rollback and an injected mid-operation database failure; real Keycloak/private HTTP journey; unchanged foundation regression checks. No H2 or mocked database is used to establish financial correctness.

## F. Implementation status

Implementation and acceptance checks complete. All three final workflows passed for source commit `10f955eee6f146964ab4e97dcdb2d9826195464a`. Documentation-only follow-up changes record these results and the repository rename; they do not alter application behavior. No merge or external deployment is performed.

GitHub now identifies this same repository (ID `1138747020`) as `ABDOUUisHERE/FinCore`; the previous `task-app-management` URLs redirect to it. The Git remote and current clone instructions use the canonical name.

## G. Verification

| Check | Observed result |
| --- | --- |
| Ledger module `verify` against isolated native PostgreSQL 16.9 | PASS: 16 database tests, no failures/errors/skips; 11 Money cases and the final 9 signed-JWT/body/guard tests also pass |
| Real local Keycloak 26.8.1 + private native Ledger HTTP journey | PASS: signed service account, funding, transfer, replay, lookup, key conflict, insufficient funds, exact reversal and replay, original preservation, guard version conflicts and reconciliation |
| Root reactor `verify -DskipITs` | PASS: all three modules packaged, 29 unit/security cases; database suites explicitly omitted in this command |
| Actual native ledger JVM restart | PASS: exact committed outcome recovered using the original business reference after process replacement |
| Existing dataset upgrades | PASS: second bootstrap retains identical `.env`; technical client setup reruns against persisted Keycloak |
| Python script compilation | PASS |
| Compose configuration | PASS: `docker compose config --quiet`; this is configuration validation only |
| OpenAPI YAML parse | PASS: eight paths, all authored flow descriptions checked |
| Local Docker engine | UNAVAILABLE: bounded probes to both contexts time out despite Desktop launch; no volume reset or broad Docker/WSL termination attempted |
| [Backend quality](https://github.com/ABDOUUisHERE/FinCore/actions/runs/39826976519) | PASS: all modules packaged; 46 tests, no failures/errors/skips, including 16 ledger PostgreSQL 17.11 tests and 1 Customer isolation test |
| [Frontend quality](https://github.com/ABDOUUisHERE/FinCore/actions/runs/39826976487) | PASS: typecheck, lint, 7 components, both builds and dependency audit |
| [Foundation integration](https://github.com/ABDOUUisHERE/FinCore/actions/runs/39826976489) | PASS: all Compose images/one-shots/readiness, private Customer rejection, real Keycloak/private Ledger journey, 4 browser PKCE journeys and clean shutdown |

The [machine-readable evidence](../evidence/phase-2-checks.json) records the exact source SHA, workflow metadata and environment distinctions. Docker image package stages explicitly skip tests because the independent Backend quality workflow executes the complete suite.

The native lab uses its own repository-local cluster on port 5548, separate from the existing Windows PostgreSQL service. Native verification is not Compose evidence. After final verification, the scoped native lab processes and its port-5548 cluster were stopped; data was preserved and the unrelated Windows PostgreSQL service was untouched. Generated secrets/tokens are not committed or printed. The initial SQL privilege assertion was corrected to check PostgreSQL permission SQLSTATE rather than a translated top-level message. A Windows reactor packaging attempt encountered open runtime JAR handles; stopping only the scoped native verification processes and restarting from copied JARs resolved it. Final checks passed. Earlier failed tests were corrected and rerun; the final local and CI results above supersede those attempts.

## H. Next milestone

Phase 3: Customer onboarding, Account ownership/provisioning/restrictions and Payment lifecycle/recovery. Integrate narrowly authorized technical identities and verified delegated customer entitlements before any browser financial endpoint. Demonstrate a complete simulated transfer/recovery journey against the authoritative ledger before building the customer money UI.
