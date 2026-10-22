# Phase 1 — foundation implementation and verification

Date: 2026-10-22 (UTC). Architecture approved by the user's instruction to start Phase 1. This branch is based on `docs/fincore-architecture`; the architecture branch has not been merged into main.

## A. Current state

Phase 0 supplied an architecture only. This milestone introduces the first Java/React application modules, identity configuration, isolated databases, local deployment and CI. No financial product or ledger code is in scope.

## B. Implementation

Java 21, Spring Boot 3.5.16 and compatible Spring Cloud 2025.0.3; independent Gateway and Customer resource servers. `/api/v1/customers/me` reports only a validated subject and recognized roles. Keycloak 26.8.1 uses public clients with PKCE S256; direct password grants are disabled. React 19.3.1 portal shells share UI/authentication code, use memory-only tokens and distinguish actual authenticated API responses from upcoming features.

PostgreSQL 17.11 uses separate service databases/roles. A separate one-shot migration command owns schema changes; runtime Customer receives no migration credentials. Correlation IDs are sanitized and propagated; logs are structured ECS JSON. Readiness includes Customer DB connectivity; only health endpoints are exposed through Actuator. The Gateway applies exact-origin CORS, request/header limits, timeouts and a bounded process-local request budget.

## C. Files and modules

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

## D. Risks and limits

This is a local foundation, not a banking MVP. There are no accounts, funds, transfers, cards, ledger, Kafka or admin data APIs. Admin roles grant no implicit access to customer APIs. Staff MFA/step-up must be enforced before sensitive administrative operations are introduced. Generated secrets are local only and must remain with their persisted dataset. Migration/bootstrap credentials exist only in their responsible containers; compromise of the bootstrap Postgres administrator remains a privileged infrastructure threat.

Gateway's local request budget is not distributed and does not survive restart. Keycloak development HTTP and loopback ports require a later hardened deployment. Public realm registration is deliberately disabled until onboarding exists. Spring Boot 3.x follows the user's stack requirement; verify the release train's support lifecycle before any production deployment. No production readiness or financial/security certification is claimed.

## E. Test strategy

Signed-JWT service tests; real PostgreSQL/bootstrap/Flyway privilege tests; UI type/lint/component/build checks; npm dependency audit; real browser Authorization Code + PKCE and independent backend rejection. See [testing instructions](../testing.md).

## F. Implementation status

Source is implemented for Phase 1 only. Final verification results and CI links are recorded after execution; an implemented test is not evidence that it passed.

## G. Verification

Local Java security tests: 8 passed in the initial run. Local UI checks: typecheck, lint, 7 component tests and both production builds passed after fixing duplicate React versions. npm audit reported zero vulnerabilities after updating Vite/Vitest to patched versions. `docker compose config --quiet` passed with generated local configuration. Full final results are pending the remaining validation pass.

Docker Engine calls on the Windows host timed out during initial readiness checks, so local Compose startup and Testcontainers have not yet been established. GitHub Actions is configured to run both with a real Linux Docker engine. This limitation must remain explicit unless a later successful run supplies evidence.

Initial corrected failures: PowerShell split an unquoted Maven wrapper property; the quoted rerun succeeded. Component tests found duplicate React copies; aligning/pinning React and deduplicating fixed all seven. The initial npm audit found vulnerable Vite/Vitest versions; the updated pinned versions passed audit. These results are separate from future integration outcomes.

## H. Next milestone

Phase 2 implements the immutable ledger only after this foundation's acceptance gates have real passing evidence. Financial invariants, transaction rollback, concurrent funds checks, durable idempotency and compensating reversals require PostgreSQL integration tests before payment features can be presented as working.
