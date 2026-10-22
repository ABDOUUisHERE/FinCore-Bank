# Foundation and ledger test guide

Run from the repository root. Java 21 and Node 24 are the reference toolchains. Actual outcomes are reported in [Phase 1](verification/phase-1.md) and [Phase 2](verification/phase-2.md) verification.

## Backend

```sh
./mvnw -B -ntp verify
```

Windows: `mvnw.cmd -B -ntp verify`. The committed Maven Wrapper downloads Maven 3.9.9. Gateway tests start a real reactive HTTP server; Customer tests exercise its own security filter chain. Both decode signed RSA JWTs and test issuer, audience, expiry, role, missing/malformed token, route restrictions and correlation handling. These use synthetic test keys, not Keycloak secrets.

`DatabaseIsolationIT` requires a real Docker engine. It runs PostgreSQL 17, executes the actual container bootstrap script, migrates with Flyway and verifies runtime read permissions, denied writes/DDL and denied Keycloak database access. It fails rather than silently skipping when Docker is unavailable.

For Java compilation, packaging and unit/security tests without Docker:

```sh
./mvnw -B -ntp verify -DskipITs
```

This command explicitly skips database integration checks and cannot establish that the full foundation is working. Maven compilation also enables Java lint diagnostics. Local checks and GitHub CI must identify this distinction.

## Frontend

```sh
npm ci
npm run typecheck
npm run lint
npm test
npm run build
npm audit --audit-level=high
```

Vitest/Testing Library cover hosted-login actions, expired sessions, identity-provider failure, backend success/failure and staff-route denial. API fetches are stubbed in component tests; they do not prove real OIDC. React is pinned once across workspaces to prevent duplicate-renderer hook failures. No tokens are stored in component-test output.

## Real browser and stack

Start the stack using the [setup guide](deployment/local-setup.md), then:

```sh
python scripts/check-foundation.py
npx playwright install chromium
npm run test:e2e
```

The browser tests use generated synthetic credentials read from the ignored local file. They assert the real Keycloak login URL has PKCE S256, exercise browser login and the authenticated `/me` API, reject a customer on the operations portal, reject a real staff access token on the customer API, verify logout and check 1440/768/390/320-pixel layouts. The stack checker tests Customer's unauthenticated rejection over the private Docker network. Signed access tokens remain in memory and are never printed or saved to reports. Browser traces/screenshots are disabled by default because OIDC exchanges contain credentials and tokens.

Authentication traces and screenshots remain disabled. The responsive-layout test explicitly captures only a fresh anonymous public workspace at desktop/mobile sizes for the PNG CI artifact; it never captures sign-in forms or authenticated sessions.

Phase 1 evidence makes no financial correctness claim. The Phase 2 ledger tests below establish only the kernel's tested behavior, not a complete payment workflow, Kafka delivery or production resilience.

## Ledger kernel

```sh
./mvnw -B -ntp -pl services/ledger-service -am verify
docker compose run --rm ledger-check
```

The Maven module runs Money precision/normalization/overflow tests, signed-RSA HTTP authorization tests and `LedgerDatabaseIT` on a real PostgreSQL 17.11 Testcontainer. The integration suite creates its own randomly named database, migrates with the owner and invokes financial commands with the restricted runtime role. It covers opposing/concurrent debits, duplicate keys and changed transport keys, definitive rejection replay after funding, restriction races, competing full reversals, forbidden SQL mutations, empty/unbalanced/late postings, reconciliation corruption detection, explicit rollback and an injected database failure after balances change. It fails if PostgreSQL cannot start; no automatic Docker-unavailable skip is configured.

The HTTP smoke uses real Keycloak client credentials and the private service. It funds 1,000.00 synthetic MAD, transfers 100.00, checks balances and identity replay/conflict, refuses insufficient funds, compensates exactly once without changing the original journal, applies/reopens a versioned freeze and checks reconciliation. It creates a new synthetic dataset on each run. No public funding or customer payment endpoint exists yet.

For a deliberately isolated native PostgreSQL test lab, `LedgerDatabaseIT` also accepts `LEDGER_TEST_ADMIN_URL`, `LEDGER_TEST_ADMIN_USER`, `LEDGER_TEST_ADMIN_PASSWORD`, `LEDGER_TEST_RUNTIME_PASSWORD` and `LEDGER_TEST_MIGRATION_PASSWORD` via the environment. Use a dedicated lab cluster, never an unrelated application database. The suite creates/drops only its own `ledger_test_<random UUID>` database and preserves existing role passwords. Native PostgreSQL 16.9 results are labeled separately from PostgreSQL 17.11 container evidence. This alternative does not bypass Customer's separate Docker-based isolation test; a full default reactor verify still requires Docker.

## CI

Backend quality runs Maven `verify` including both PostgreSQL integration suites. Frontend quality runs type checks, ESLint, components, production builds and npm vulnerability checks. Foundation integration builds all application images, generates disposable configuration, starts Compose, runs the signed technical ledger smoke, checks private Customer rejection and runs browser PKCE journeys. No workflow merges branches or deploys externally. Backend artifacts contain test reports only; generated env/realm files and browser traces must never be uploaded.
