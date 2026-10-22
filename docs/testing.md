# Foundation test guide

Run from the repository root. Java 21 and Node 24 are the reference toolchains. Test outcomes are reported separately in [Phase 1 verification](verification/phase-1.md).

## Backend

```sh
./mvnw -B -ntp verify
```

Windows: `mvnw.cmd -B -ntp verify`. The committed Maven Wrapper downloads Maven 3.9.9. Gateway tests start a real reactive HTTP server; Customer tests exercise its own security filter chain. Both decode signed RSA JWTs and test issuer, audience, expiry, role, missing/malformed token, route restrictions and correlation handling. These use synthetic test keys, not Keycloak secrets.

`DatabaseIsolationIT` requires a real Docker engine. It runs PostgreSQL 17, executes the actual container bootstrap script, migrates with Flyway and verifies runtime read permissions, denied writes/DDL and denied Keycloak database access. It fails rather than silently skipping when Docker is unavailable.

For Java compilation, packaging and the 8 security tests without Docker:

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

No financial correctness, payment, Kafka, load or resilience result can be claimed in Phase 1. Those tests follow the ledger/payments roadmap.

## CI

Backend quality runs Maven `verify` including Testcontainers. Frontend quality runs type checks, ESLint, components, production builds and npm vulnerability checks. Foundation integration builds all application images, generates disposable local configuration, starts the real Compose stack, tests private service rejection and runs real browser PKCE journeys. No workflow merges branches or deploys externally. Backend artifacts contain test reports only; generated env/realm files and browser traces must never be uploaded.
