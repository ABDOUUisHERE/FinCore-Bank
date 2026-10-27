# Prioritized implementation roadmap

Implementation checkpoint: Phase 7 cards, reservations and statements passed acceptance; see the [verification report](../verification/phase-7.md). Phase 8 is the next milestone and has not started.

The original proposal and acceptance gates are preserved below. Status of the original proposal: approved before implementation. Architecture approval precedes all application code. Each milestone must explain its file/module changes before work, remain runnable, report actual commands/results, and document remaining limitations. No milestone is done solely because a container starts.

## Dependency order and acceptance gates

| Phase | Scope | Required exit evidence |
| --- | --- | --- |
| 0 — this delivery | Repository assessment, boundaries, financial schema, security, contracts, diagrams, ADRs and roadmap | Documentation checks plus user architecture approval before code |
| 1 — foundation | Maven/Java 21, gateway, minimal Customer resource server, Keycloak, isolated DBs, React shells, Compose and basic CI | Clean clone starts; real OIDC/PKCE login; browser calls own authenticated endpoint; unauthenticated/wrong-audience calls rejected; builds/tests pass |
| 2 — ledger | Money, chart, SQL write functions/constraints, immutable journals/postings, balances, guards, idempotency, outbox storage and reversal | Real PostgreSQL invariant/concurrency/rollback tests pass with restricted runtime roles |
| 3 — accounts and payments | Customer onboarding, account provisioning/ownership/restrictions, simulated funding, transfers, lifecycle, risk-policy port, durable recovery, reversals and transaction history | Authorized transfer commits balanced postings; retries/timeouts/restarts do not duplicate; restriction races and funds checks pass |
| 4 — customer experience | Dashboard, account views, complete transfer form/confirmation, history, beneficiary service, profile and responsive light/dark UI | Browser login through transfer confirmation and ledger verification; loading/errors/ownership tested; UI reflects actual pending/result states |
| 5 — messaging | Kafka, outbox publishers, idempotent consumers, notification service, synthetic email, SSE, retries and DLQ | Broker outage/recovery and post-ack crashes lose no committed facts; redelivery and stale events safe; notifications follow confirmation |
| 6 — fraud | Extract risk port, rules and thresholds, velocity reservation, explanations, analyst cases and synchronous preauthorization | High-value/new-beneficiary/velocity/location/failure rules exercised; ALLOW/REVIEW/BLOCK auditable; unavailable risk fails closed |
| 7 — cards and reports | Card service, synthetic authorizations, limits, ledger reservations/capture/release, merchant payments, refunds, statements, Reporting service and exports | No PAN/CVV generation; holds prevent overspend; captures/refunds are idempotent; statements reconcile to ledger |
| 8 — operations | Admin portal workflows, least-privilege customer/payment/fraud/ledger inspection, incidents, Audit service and full telemetry dashboards | Privilege separation and audit coverage tested; traces correlate; six required dashboards use real observed data |
| 9 — deployment hardening | Multi-stage images, vulnerability scanning, kind deployment, Helm/manifests, policies, Secret references, resources and justified HPA | Local cluster rollout/probes/ingress verified; configuration externalized; restore procedures documented; no automatic production deploy |
| 10 — resilience and performance | k6/Gatling scenarios, failures, restarts, contention, Kafka outages and optimization | Reproducible measured baseline with hardware/dataset/duration/concurrency and p50/p95/p99/error rate; invariants survive failures |
| 11 — portfolio release | README, actual screenshots, demo guide/video plan, API collection, release notes and evidence | Recruiter can reproduce synthetic demo from clean clone using published commands and known limitations |

The first end-to-end money-moving MVP is Phases 1–4, with outbox rows durable but delivery/UI projections limited until Phase 5. Before Kafka, financial views query owner APIs; do not display stale projection balances as current. No notification or full fraud feature is claimed early. The full master specification remains the target through Phase 11.

## Exact first implementation milestone: Phase 1

Deploy Gateway, Customer Service, Keycloak, PostgreSQL and two React application shells. Do not add Ledger, Payment or fictitious balances to meet a dashboard appearance. Gateway routes one minimal `/api/v1/customers/me` endpoint that returns the authenticated subject and authorized roles as an explicit foundation response, without inventing a customer profile. Profile onboarding and real API domain fields arrive later. Include isolated customer and Keycloak databases and a trivial infrastructure migration proving Customer-only access.

Files planned for Phase 1; paths under Java/React source directories are a scoped set, not placeholders to create in Phase 0:

| Path | Purpose |
| --- | --- |
| `pom.xml`, `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties` | Java 21 reactor and reproducible Maven wrapper |
| `.gitignore`, `.dockerignore`, `.env.example` | Generated artifacts, ignored real env, placeholder-only configuration |
| `services/api-gateway/pom.xml` | Compatible Spring Boot/Cloud configuration |
| `services/api-gateway/src/main/java/com/fincore/gateway/GatewayApplication.java` | Gateway entry point |
| `services/api-gateway/src/main/java/com/fincore/gateway/configuration/SecurityConfiguration.java` | JWT issuer/audience, CORS and route protection |
| `services/api-gateway/src/main/resources/application.yml` | Routing, limits, health and configuration references |
| `services/api-gateway/src/test/java/com/fincore/gateway/GatewaySecurityTest.java` | Missing/invalid token, audience and routing tests |
| `services/customer-service/pom.xml` | Servlet resource server and persistence dependencies |
| `services/customer-service/src/main/java/com/fincore/customer/CustomerApplication.java` | Customer entry point |
| `services/customer-service/src/main/java/com/fincore/customer/configuration/SecurityConfiguration.java` | Independent JWT authorization |
| `services/customer-service/src/main/java/com/fincore/customer/adapter/in/web/CurrentSubjectController.java` | Minimal authenticated subject response |
| `services/customer-service/src/main/resources/application.yml` | Isolated DB, Actuator and service config |
| `services/customer-service/src/main/resources/db/migration/V1__foundation.sql` | Customer-owned infrastructure metadata, no money tables |
| `services/customer-service/src/test/java/com/fincore/customer/CurrentSubjectSecurityTest.java` | Own-subject claims and direct service rejection checks |
| `services/api-gateway/Dockerfile`, `services/customer-service/Dockerfile` | Multi-stage Java images with non-root runtime |
| `package.json`, `package-lock.json` | Frontend workspace and locked dependencies |
| `apps/customer-portal/package.json`, `apps/admin-portal/package.json` | Independent Vite applications |
| `apps/customer-portal/{index.html,tsconfig.json,vite.config.ts,Dockerfile}` | Customer build/container configuration |
| `apps/admin-portal/{index.html,tsconfig.json,vite.config.ts,Dockerfile}` | Admin build/container configuration |
| `apps/customer-portal/src/{main.tsx,App.tsx,index.css,auth.ts,api.ts}` | Hosted login, protected shell, real `/me` call and logout |
| `apps/admin-portal/src/{main.tsx,App.tsx,index.css,auth.ts}` | Hosted staff login and role-gated shell |
| `apps/customer-portal/src/App.test.tsx`, `apps/admin-portal/src/App.test.tsx` | Expired session, access denied and shell states |
| `infrastructure/docker/postgres/init-databases.sh` | Separate databases and roles with values from ignored env |
| `infrastructure/keycloak/fincore-realm.template.json` | Realm/client/role configuration without committed credentials |
| `scripts/bootstrap-local.ps1`, `scripts/bootstrap-local.sh` | Local generated secrets and synthetic identity provisioning |
| `compose.yaml` | Reference local foundation deployment, volumes and health checks |
| `.github/workflows/backend.yml`, `.github/workflows/frontend.yml` | Compile, test, static/type checks and production builds |
| `tests/e2e/foundation.spec.ts`, `playwright.config.ts` | Actual browser PKCE login, authenticated API and denied admin access |
| `contracts/openapi/customer-v1.yaml` | Accurate foundation contract, not future endpoints presented as implemented |
| `README.md`, `docs/deployment/local-setup.md`, `docs/testing.md`, `docs/verification/phase-1.md` | Exact start/stop/test commands and measured evidence |

If implementation reveals a necessary additional config/test file, explain it before including it. Ports must be documented and avoid the already observed Core5G lab ports. Configure browser/container Keycloak hostname consistently, not two conflicting issuers. Generate local credentials instead of embedding passwords in Compose, realm templates or repository history. README examples may name synthetic users; passwords are generated and shown only through documented local bootstrap handling.

## Phase 1 validation

1. Build the Maven reactor with `./mvnw verify` (Windows: `mvnw.cmd verify`); test JWT issuer/audience/role checks in Gateway and directly in Customer.
2. Use Testcontainers/PostgreSQL for migration and database isolation checks, with a real Docker engine. Verify Customer cannot read Keycloak tables and runtime cannot run privileged migrations.
3. Run `npm ci`, type checks, lint, Vitest/Testing Library and frontend production builds using scripts committed in the milestone.
4. From a clean checkout, generate ignored local env, run `docker compose up --build -d --wait`, and verify each health endpoint. Restrict detailed Actuator endpoints.
5. Execute Playwright using real Keycloak Authorization Code + PKCE browser login, logout/expiration and staff-route rejection. Test unauthorized requests both through Gateway and directly on the internal test network. Do not substitute a password grant for browser PKCE evidence.
6. Verify browser-to-Gateway-to-Customer communication and capture actual response/trace evidence without token dumps. Stop with `docker compose down`; persistent data should survive unless volumes are deliberately removed.

These commands are planned acceptance checks; none has run for an application in Phase 0. Do not add business money behavior in this milestone.

## Critical test matrix for Phases 2–3

| Area | Required tests |
| --- | --- |
| Money | Unknown currency, decimal precision, zero/negative/overflow amounts, cross-currency operations |
| Journals | Balanced succeeds; unbalanced/empty/single posting fails; all posting currencies match; journal total enforced |
| Atomicity | Inject failure after first posting, aggregate update and outbox insert; all changes roll back |
| Immutability | Update/delete/append to posted records and truncate denied under runtime role; migration role separate |
| Idempotency | Same request/key sequentially and concurrently; changed payload rejected; identical business reference with new transport key cannot duplicate |
| Contention | Two insufficient-funds debits; opposing transfers; multiple processes/instances and independent DB connections |
| Authorization | Other owner, forged actor, wrong service scope, closed/frozen account and guard-update races |
| Recovery | Crash before ledger call, after ledger commit/before response, and after response/before Payment commit; restart preserves one posting |
| Lifecycle | Every permitted/forbidden transition, cancellation vs worker race, REVIEW/BLOCK behavior, expired assessment revalidation |
| Reversal | Compensating entry, one full reversal only, repeated key, insufficient recipient funds and reversal timeout recovery |
| Reconciliation | Detect deliberately inconsistent aggregate/test fixtures at consistent snapshot; never silently rewrite journals |

Use JUnit 5, AssertJ, Mockito for isolated ports, Spring Boot Test, Testcontainers and REST Assured for real service/DB boundaries. Mocks cannot prove concurrency or SQL constraints. Use Playwright for end-to-end ledger verification, and k6 or Gatling only when a complete financial path exists. Record command, environment, pass/fail/skip and relevant limitations for every result.
