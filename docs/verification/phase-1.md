# Phase 1 — foundation implementation and verification

Date: 2026-10-22 (UTC). Architecture approved by the user's instruction to start Phase 1. This branch is based on `docs/fincore-architecture`; the architecture branch has not been merged into main.

## A. Current state

Phase 0 supplied an architecture only. This milestone introduces the first Java/React application modules, identity configuration, isolated databases, local deployment and CI. No financial product or ledger code is in scope.

## B. Implementation

Java 21, Spring Boot 3.5.16 and compatible Spring Cloud 2025.0.3; independent Gateway and Customer resource servers. `/api/v1/customers/me` reports only a validated subject and recognized roles. Keycloak 26.8.1 uses public clients with PKCE S256; direct password grants are disabled. React 19.3.1 portal shells share UI/authentication code, use memory-only tokens and distinguish actual authenticated API responses from upcoming features.

PostgreSQL 17.11 uses separate service databases/roles. A separate one-shot migration command owns schema changes; runtime Customer receives no migration credentials. Correlation IDs are sanitized and propagated; logs are structured ECS JSON. Readiness includes Customer DB connectivity; only health endpoints are exposed through Actuator. The Gateway applies exact-origin CORS, request/header limits, timeouts and a bounded process-local request budget.

## C. Files and modules

The [exact path manifest](phase-1-files.txt) lists the cumulative Phase 1 changes relative to the approved architecture commit.

- `pom.xml`, `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`: reproducible Java reactor.
- `services/api-gateway/`: application, JWT/CORS authorization, correlation/rate/size filter, route config, security tests and Dockerfile.
- `services/customer-service/`: application and migration command, validated-subject controller, independent authorization/correlation, foundation SQL, security/DB isolation tests and Dockerfile.
- `apps/customer-portal/`, `apps/admin-portal/`: Vite shells and component tests.
- `packages/portal-ui/`: shared responsive UI, authentication lifecycle, API adapter and styling; added to avoid duplicated identity handling.
- `package.json`, `package-lock.json`, `tsconfig.json`, `eslint.config.js`, `vitest.config.ts`, `playwright.config.ts`: frontend workspaces and checks.
- `infrastructure/docker/{postgres,nginx}/`, `infrastructure/keycloak/fincore-realm.template.json`, `compose.yaml`: local deployment and identity configuration.
- `scripts/bootstrap-local.{py,ps1,sh}`, `scripts/check-foundation.py`: generated private local config and stack validation.
- `tests/frontend/setup.ts`, `tests/e2e/foundation.spec.ts`: component setup and real-browser checks.
- `.github/workflows/{backend,frontend,foundation}.yml`: CI, no external deployment.
- `.gitignore`, `.dockerignore`, `.env.example`, `.gitattributes`: secret/artifact exclusion and portable script newlines.
- `contracts/openapi/customer-v1.yaml`, `README.md`, `docs/deployment/local-setup.md`, `docs/testing.md`, this report and ADR statuses: implementation and operating documentation.
- `docs/evidence/foundation-desktop.png`, `docs/evidence/foundation-mobile.png`: actual anonymous workspace captures, visually reviewed after disabling animation during capture.
- `scripts/diagnostics.py`: bounded startup diagnostics with generated-secret redaction.

## D. Risks and limits

This is a local foundation, not a banking MVP. There are no accounts, funds, transfers, cards, ledger, Kafka or admin data APIs. Admin roles grant no implicit access to customer APIs. Staff MFA/step-up must be enforced before sensitive administrative operations are introduced. Generated secrets are local only and must remain with their persisted dataset. Migration/bootstrap credentials exist only in their responsible containers; compromise of the bootstrap Postgres administrator remains a privileged infrastructure threat.

Gateway's local request budget is not distributed and does not survive restart. Keycloak development HTTP and loopback ports require a later hardened deployment. Public realm registration is deliberately disabled until onboarding exists. Spring Boot 3.x follows the user's stack requirement; verify the release train's support lifecycle before any production deployment. No production readiness or financial/security certification is claimed.

## E. Test strategy

Signed-JWT service tests; real PostgreSQL/bootstrap/Flyway privilege tests; UI type/lint/component/build checks; npm dependency audit; real browser Authorization Code + PKCE and independent backend rejection. See [testing instructions](../testing.md).

## F. Implementation status

Phase 1 acceptance gates are verified in Linux CI. The tested application commit is `0835a88e84806a6face02baf073901199fded55d`; subsequent evidence/documentation updates do not change application behavior. The public Gateway uses port 8280 because host port 8080 was already occupied; all portal calls, CORS/CSP connectivity, tests, setup and API contracts use the same public endpoint.

## G. Verification

Final CI results for the exact application commit above:

| Workflow | Result |
| --- | --- |
| [Backend quality](https://github.com/ABDOUUisHERE/task-app-management/actions/runs/39818097900) | PASS: build, 9 signed-JWT/service tests and 1 real PostgreSQL/Flyway/isolation test; no skipped tests |
| [Frontend quality](https://github.com/ABDOUUisHERE/task-app-management/actions/runs/39818097639) | PASS: typecheck, lint, 7 component tests, both production builds and npm audit |
| [Foundation integration](https://github.com/ABDOUUisHERE/task-app-management/actions/runs/39818097669) | PASS: all image builds, full Compose startup/readiness, migration/realm init, private Customer rejection, all 4 real browser journeys, public captures and clean shutdown |

The [machine-readable evidence](../evidence/phase-1-checks.json) records run IDs, URLs, application SHA, environment distinctions and test counts. No failed or skipped tests remain in these final CI runs. Earlier failures and superseded/cancelled runs are distinguished below. Documentation-only delivery changes are excluded from the workflows to avoid repeating unchanged application tests.

| Executed command/check | Observed result |
| --- | --- |
| `mvnw.cmd -B -ntp verify -DskipITs` | Java reactor and both executable JARs built; initial 8 security tests passed; DB integration explicitly skipped locally |
| `mvnw.cmd -B -ntp -pl services/api-gateway -am test` after adding dependency-error handling | 5 Gateway security/error tests passed; combined with unchanged 4 Customer tests, 9 service tests are covered |
| `npm run typecheck`, `npm run lint`, `npm test`, `npm run build` | Passed; 7 component tests and both production bundles; checks rerun when relevant source/dependencies changed |
| `npm audit --audit-level=high` | Passed; zero reported vulnerabilities at execution time |
| `docker compose config --quiet` | Passed with generated configuration, including the realm-init service and public Gateway port change |
| Bootstrap rerun with before/after byte comparison | All three existing generated config files preserved; credentials and realm/env files are Git-ignored |
| Native real-browser run, `FINCORE_API_URL=http://localhost:8280 npm run test:e2e` | All 4 journeys passed after identity readiness: real PKCE, Customer API, staff/customer isolation, logout and responsive widths |
| Native screenshot-only responsive rerun after capture stabilization | 1 responsive journey passed; desktop/mobile captures visually inspected |
| Documentation links, tracked JSON syntax, `git diff --check` | Passed in the working checkout |

Native environment: Windows, Java 21.0.2, Maven 3.9.9, Node 24.13.0, Keycloak 26.8.1 and an isolated PostgreSQL **16.9** cluster on port 5548 using installed binaries. The existing PostgreSQL Windows service was left running and unchanged. The native check used Vite's static preview and Gateway port 8280; it does not establish the Nginx/Compose environment. All temporary native servers and the isolated cluster were stopped after verification, releasing their ports. Ignored local configuration/data remains available.

Docker Engine calls on the Windows host timed out; local Compose/Testcontainers execution was not established. The successful Linux GitHub Actions run supplies the real PostgreSQL **17.11**, Nginx, migration-container and complete Compose verification. Running the reference stack on this Windows host still requires a responsive Docker Desktop engine.

Initial corrected failures: PowerShell split an unquoted Maven wrapper property; the quoted rerun succeeded. Component tests found duplicate React copies; aligning/pinning React and deduplicating fixed all seven. The initial npm audit found vulnerable Vite/Vitest versions; the updated pinned versions passed audit. These results are separate from future integration outcomes.

The first Compose attempts failed because Keycloak could not read a host-owned mode-0600 realm import; CI diagnostics confirmed `Permission denied`. The network-isolated realm-init volume/ownership step fixed startup. The next Compose run passed all service health and authentication checks, with 3 browser journeys passing and the responsive journey detecting mobile header overflow. The small-screen header fix passed all four native browser journeys. An initial native browser attempt started before Keycloak readiness and timed out twice; rerunning after the discovery endpoint returned 200 passed all four. No test assertion was removed to hide these failures.

## H. Next milestone

Phase 2 implements the immutable ledger only after this foundation's acceptance gates have real passing evidence. Financial invariants, transaction rollback, concurrent funds checks, durable idempotency and compensating reversals require PostgreSQL integration tests before payment features can be presented as working.
