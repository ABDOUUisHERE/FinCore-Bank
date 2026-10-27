# FinCore test guide

Run from the repository root. Java 21 and Node 24 are the reference toolchains. Historical outcomes are preserved in the verification reports. Current Card/Reporting evidence belongs in [Phase 7](verification/phase-7.md); [Phase 6](verification/phase-6.md) retains the earlier Fraud results and its local inbox observation.

## Cards, reservations and reporting

The Phase 7 backend reactor contains 191 cases. New real-PostgreSQL checks exercise reservations against other holds and existing debits, capture/release/expiry, exact full refunds, posting-point deadlines, unknown admission recovery, immutable receipts, reused trigger connections and statement snapshots. Security tests independently verify signed owner delegation and narrow worker scopes. Run the normal full `verify` gate; a focused financial regression command is:

```sh
./mvnw -B -ntp -pl services/ledger-service -am verify -Dit.test=CardReservationsIT,LedgerDatabaseIT -Dfailsafe.failIfNoSpecifiedTests=false
```

The UI suite contains 26 cases. On a Windows host where fork startup times out, `npm test -- --pool=threads --maxWorkers=1` runs the same assertions serially. Component stubs do not establish financial correctness.

The full browser command now contains eight journeys. `npx playwright test tests/e2e/cards.spec.ts` focuses the genuine hosted-PKCE Card/Reporting acceptance: lost-response creation replay across reload, merchant risk review, a held balance blocking an ordinary transfer, capture/refund journal identity, retained daily usage, release/freeze, a fail-closed Fraud outage, owner-only CSV/PDF buttons and finance-only reconciliation. It uses private fixtures to independently check real Ledger funds and journals. Retries remain disabled. Run the Kafka ACL/DLQ checker only after browser outage probes have finished.

`FINCORE_VISUAL_QA=1` additionally captures test-owned Card/Statement screens at desktop/mobile widths under ignored `.local/visual`. No identity form, token, authenticated screenshot or raw diagnostics is uploaded by CI. Reporting export tests generate a multi-page PDF under the module's ignored `target` directory; render and inspect its first and last pages after layout changes. See the [Card/Reporting demo and upgrade](deployment/cards-phase-7.md).

## Phase 4 customer acceptance

The complete reactor executes 89 cases: 49 unit/security and 40 real PostgreSQL 17.11 integration cases. Beneficiary coverage includes independent signed-JWT/subject binding, private runtime grants, immutable identities, version conflicts, transactional audit rollback, owner pagination/search and eight pairs of concurrent identical inserts. Ledger read tests cover monthly totals, server-side filters, account-scoped chronological pagination and compensation links. Existing financial tests remain mandatory.

The frontend suite has 17 cases: exact large decimal formatting, separate currency totals, unavailable balances, journal-confirmed success, immutable response-loss/remount recovery, a later-denied retry, a definitive first rejection, storage-write failure, cancellation conflict and inaccessible payment data. Stubbed component fetches do not establish real OIDC or financial execution.

`npm run test:e2e` runs six real browser journeys, retries disabled. The customer case creates two synthetic identities through the private fixture and signs both in with Code + PKCE. It onboards, opens accounts, funds privately, saves/searches/renames/disables a beneficiary, sends a transfer and deliberately loses its accepted response. Reload must reuse the same key/body and produce one confirmed transfer. Private checks independently verify both balances, one outgoing journal, balanced postings, reconciliation and wrong-owner rejection. The UI also tests risk-review cancellation, filtered CSV download, effective freeze/unfreeze, profile persistence, 1440/768/390/320 layouts and dark mode. Logout requires confirmation.

Fresh fixture credentials remain in memory/private process pipes; bearer tokens remain in memory/stdin. Do not print the identity fixture's stdout or start all tools-profile services together. The portals and business service runtimes receive no setup/funding credentials. CI uploads only anonymous public PNG captures.

For manual visual QA on exclusively test-owned synthetic records, `FINCORE_VISUAL_QA=1` writes ignored `.local/visual/phase-4-dashboard-*.png` and public account UUIDs to ignored `.local/phase-4-demo-fixture.json`. This opt-in is off in CI and never captures identity forms, traces or bearer values. These captures are not committed or uploaded.

Transfer key/body recovery lasts for the current browser tab and is scoped to the verified subject. An ambiguous operation stays immutable even if a later retry is refused. Closing its tab requires inspecting the owned transfer list before creating a new request. CSV export explicitly includes loaded filtered rows; whole-month activity does not depend on history pagination.

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
docker compose run --rm --no-deps ledger-check
```

The Maven module runs Money precision/normalization/overflow tests, signed-RSA HTTP authorization tests and `LedgerDatabaseIT` on a real PostgreSQL 17.11 Testcontainer. The integration suite creates its own randomly named database, migrates with the owner and invokes financial commands with the restricted runtime role. It covers opposing/concurrent debits, duplicate keys and changed transport keys, definitive rejection replay after funding, restriction races, competing full reversals, forbidden SQL mutations, empty/unbalanced/late postings, reconciliation corruption detection, explicit rollback and an injected database failure after balances change. It fails if PostgreSQL cannot start; no automatic Docker-unavailable skip is configured.

The HTTP smoke uses real Keycloak client credentials and the private service. It funds 1,000.00 synthetic MAD, transfers 100.00, checks balances and identity replay/conflict, refuses insufficient funds, compensates exactly once without changing the original journal, applies/reopens a versioned freeze and checks reconciliation. It creates a new synthetic dataset on each run. This legacy ledger-only check uses private endpoints. Customer payment APIs and UI journeys are tested separately; funding remains private.

For a deliberately isolated native PostgreSQL test lab, `LedgerDatabaseIT` also accepts `LEDGER_TEST_ADMIN_URL`, `LEDGER_TEST_ADMIN_USER`, `LEDGER_TEST_ADMIN_PASSWORD`, `LEDGER_TEST_RUNTIME_PASSWORD` and `LEDGER_TEST_MIGRATION_PASSWORD` via the environment. Use a dedicated lab cluster, never an unrelated application database. The suite creates/drops only its own `ledger_test_<random UUID>` database and preserves existing role passwords. Native PostgreSQL 16.9 results are labeled separately from PostgreSQL 17.11 container evidence. This alternative does not bypass Customer's separate Docker-based isolation test; a full default reactor verify still requires Docker.

## CI

Backend quality runs Maven `verify` including both PostgreSQL integration suites. Frontend quality runs type checks, ESLint, components, production builds and npm vulnerability checks. Foundation integration builds all application images, generates disposable configuration, starts Compose, runs the signed technical ledger smoke, checks private Customer rejection and runs browser PKCE journeys. No workflow merges branches or deploys externally. Backend artifacts contain test reports only; generated env/realm files and browser traces must never be uploaded.

## Owned accounts and recoverable payments

The complete reactor includes signed-RSA service tests and isolated actual PostgreSQL 17.11 databases for Customer, Ledger, Account and Payment. `DatabaseLab` is test infrastructure packaged only in a test JAR; it shares no domain entities. `AccountRecoveryIT` injects lost provisioning/guard responses after real ledger writes. `PaymentRecoveryIT` exercises orphan authorization import, crash before the ledger call, ledger commit followed by a lost response, response received before local commit, lease fencing, cancellation races and append-only history/outbox consistency. HTTP transport failures are injected at the port while the ledger and payment databases execute real independent transactions; they are not full operating-system crash/load tests.

`business.spec.ts` adds one genuine two-customer PKCE browser journey to the four existing foundation journeys. Its private runner checks owned accounts/balances/history, wrong-owner access, restricted simulation funding, exchanged actor authorization, replay/conflict, financial rejection, risk review/block, cancellation, effective guards, full compensation and reconciliation. Tokens remain in memory/stdin and traces/screenshots remain disabled for authenticated work.

For a dedicated native lab, the new database fixtures accept `FINCORE_TEST_ADMIN_URL`, `FINCORE_TEST_ADMIN_USER`, `FINCORE_TEST_ADMIN_PASSWORD` and per-service `FINCORE_TEST_<LEDGER|ACCOUNT|PAYMENT>_<RUNTIME|MIGRATOR>_PASSWORD` environment values. They create/drop only uniquely named test databases and never rotate existing roles. PostgreSQL 16.9 native results are distinct from PostgreSQL 17.11 Linux-container results. `FINCORE_NATIVE_BUSINESS=1` runs the same Python journey on explicit loopback development endpoints rather than Compose; this flag is not enabled in CI.

A focused native backend run is `mvnw.cmd -B -ntp -pl services/account-service,services/payment-service -am verify` with those isolated-lab settings. Default `./mvnw verify` still requires Docker for all Testcontainers checks. Do not run financial tests against unrelated service data or interpret simulated restart tests as proof of production disaster recovery.

## Phase 5 messaging acceptance

Default Maven verify also runs MessagingDeliveryIT with real Kafka 4.2.2 and PostgreSQL, plus Notification transaction/security checks. Pause/unpause affects only the dedicated test broker. Post-ack fault injection repeats the original event identity; rollback, concurrent claims and stale projections retain their assertions.

The hosted-PKCE customer journey stops only the FinCore broker, confirms real ledger money during the outage, then restarts its persistent broker. It requires one sender notification, one local simulated email and a recipient update without reloading that page. A cleanup hook restores the broker after test failure. Other containers remain untouched.

After browser checks, run `python scripts/check-messaging.py` for real producer ACL denial, bounded poison retry/DLQ persistence and versioned replay. The deliberately unsupported schema remains quarantined after unchanged replay. Operator credentials stay private. CI stores service/library test XML and anonymous screenshots only.

Windows may run the complete frontend suite using `npm test -- --maxWorkers=1` to reduce worker startup contention. CI retains its normal command. The assertions are identical. Stream framing, bearer-header authentication and explicit preference confirmation are independently covered.

Gateway quota tests preserve 300 accepted API requests per minute for each authenticated subject, isolation between subjects, rejection of forged identity/forwarding headers, bounded bucket storage and renewal. The security chain chooses the subject only after JWT validation. This prevents fast multi-customer live-update journeys from consuming one shared process-wide allowance.
