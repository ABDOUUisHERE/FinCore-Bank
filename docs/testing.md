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

## Phase 8 first lot

`AuditDatabaseIT` uses a dedicated PostgreSQL 17.11 Testcontainer with restricted runtime/migrator identities. It verifies source duplicate/conflict handling, digest-only quarantine, immutable tables and incident identity, signed-subject owner scope, original-receipt replay, concurrent version checks, state/audit/receipt rollback, committed cursor order and bounded fact/owner pagination. Its observer uses the test administrator only to inspect PostgreSQL lock waits; application commands always use the runtime role. `AuditSecurityTest` and Gateway independently reject wrong signed issuer/audience/expiry and unauthorized roles. `AuditMessagingTest` exercises the actual Kafka recovery handler: quarantine success permits recovery; quarantine storage failure leaves delivery unresolved, and framework record formatting excludes keys/bodies.

`npx playwright test tests/e2e/operations.spec.ts` exercises hosted PKCE for ADMIN, two support identities, customer and finance. It creates a new real Account fact, filters central audit, confirms and recovers an incident after a deliberately lost response/reload, completes investigation/resolution, checks immutable history/receipt replay and rejects foreign support access/version conflicts. It also proves the Audit broker identity cannot publish Ledger facts, publishes an authorized poison fixture and observes its digest-only quarantine without the private canary appearing in Audit logs. The full default suite includes this alongside existing journeys. Traces remain off and authenticated visual checks, if explicitly enabled by `FINCORE_VISUAL_QA=1`, stay in ignored `.local/visual`.

These are assertions for Phase 8 lot 1; executed counts and limitations belong in [its report](verification/phase-8.md). They do not establish complete historic/security audit coverage, six dashboards or distributed tracing.

## Phase 8 business inspection lot

The [inspection contract](../contracts/openapi/inspection-v1.yaml) initially defined three exact-reference reads; lot 3 adds Account as the fourth. Signed HTTP tests cover the ADMIN customer/payment and FINANCE_OPERATOR journal permission matrix, unsupported methods/list routes, malformed references, and Ledger's separate machine scope plus Reporting issuer. PostgreSQL checks exercise the actual restricted Customer runtime projection and Payment's >50-version processing history, cursor bounds, redaction and unchanged financial state. Existing Ledger integration tests retain immutable journal and double-entry checks.

Frontend tests verify denied roles issue no inspection fetch, role-specific resource choices, UUID validation, exact decimal strings and a version cursor above JavaScript's safe integer range. `tests/e2e/inspection.spec.ts` uses hosted PKCE, real Customer/Account/Payment writes and an authoritative posted Ledger journal. It checks metadata redaction, role denials, missing references, immutable read results, unchanged balances and 1440/390/320 px layouts. Optional authenticated screenshots stay in ignored `.local/visual`; public workflow artifacts do not include them.

Executed acceptance and any local infrastructure failures are recorded in the [lot report](verification/phase-8-inspection.md). These assertions do not establish customer search, scoped support business grants, complete read/security audit coverage or monitoring completion.

## Phase 8 account status lot

Signed tests independently enforce ADMIN at Gateway/Account and `ledger.read` plus `azp=fincore-account` at the internal Ledger route. They reject other roles, unsupported methods/list/balance paths, invalid audience and malformed or mismatched source observations. Account module tests preserve bigint strings, allowlist rejection codes, distinguish recorded intent from effective state and verify no recovery interaction. Actual PostgreSQL tests cover lost provisioning responses, unapplied Account acknowledgements, effective Ledger guard revisions and unchanged financial rows/outbox during inspection.

Frontend cases verify independent observations, no false absence claim for NOT_LINKED, explicit UNAVAILABLE state, removal of stale effective status after partial or failed refresh, and successful retry. The existing hosted-PKCE inspection journey now freezes only its newly created synthetic account, compares recorded/effective status, stops only the Compose-selected FinCore Ledger container, observes unavailability and restores it in a finally block with afterEach fallback. It polls restoration before checking unchanged booked money and metadata, and asserts 1440/390/320 px layouts. This extends one journey; it does not inflate the suite's ten-journey count.

The [lot 3 report](verification/phase-8-account-status.md) distinguishes the full 231-case CI backend run from 88 unique native cases across two focused runs, and records initial failures separately. Account and Ledger timestamps describe two sequential observations; no atomic distributed snapshot, freshness guarantee or inspection-triggered repair is tested or claimed.

## Phase 8 customer name search lot

The [search contract](../contracts/openapi/customer-search-v1.yaml) adds one ADMIN-only POST, independently checked at Gateway and Customer. Signed tests cover other staff roles, technical identity, actor-header spoofing, wrong audience, unsupported methods, strict/malformed JSON and redacted storage failures. Module tests reject short/long/control-bearing prefixes and malformed UUID cursors before storage. Actual PostgreSQL checks exercise 23 profiles across 20/3 pages, case-insensitive literal prefixes, exact bigint versions, apostrophe/percent/underscore/exclamation escaping and unchanged profiles/outbox.

Six new frontend cases exercise explicit body-only submission, minimum prefix validation, denied roles with no request, UUID pagination, exact versions, failure/retry, clearing prior results and focus when opening the existing inspection. `tests/e2e/customer-search.spec.ts` signs in through real hosted PKCE, searches an actual owner-created profile, verifies no-store and no term in the URL, checks direct role denials/literal wildcard/empty results, opens the status and compares the owner profile before/after. It asserts 1440/390/320 px layouts; optional authenticated screenshots remain private. The original inspection journey also remains in acceptance because its shared result container changed. Measured outcomes and remaining audit/performance limits belong in the [lot 4 report](verification/phase-8-customer-search.md).

## Phase 8 temporary support account access

`SupportAccessDatabaseIT` uses a real V1-to-V2 PostgreSQL upgrade and the restricted Audit runtime identity. It covers fixed recipient/resource, denied absent or foreign scope, expiry, actor-global receipt conflicts, original replay after revocation, concurrent versions, resolution/reopening, rollback and immutable grant identity. Separate transactions prove that remote observation holds no incident lock and that an in-flight revocation or failed final audit write prevents disclosure. Signed Gateway/Audit/Account tests check distinct ADMIN grant management, SUPPORT_AGENT consultation, machine authority plus signed client, unknown JSON fields and unsupported roles/methods.

Frontend tests cover explicit review, string versions, persisted same-key recovery after a lost response/reload, no automatic consultation, no account payload in query/command persistence, denial masking and explicit partial Ledger availability. `tests/e2e/support-access.spec.ts` uses real hosted PKCE for customer, ADMIN and two support users, creates a real account and incident, replays an original grant receipt, consults actual Account/Ledger status, rejects foreign scope and unrestricted account routes, revokes through the UI and proves resolution/reopening never restores permission. The original Operations journey remains a regression check. Layout assertions cover 1440/390/320 px; optional authenticated screenshots remain private.

`python -m unittest discover -s scripts/tests` tests only the existing Keycloak provisioning utility. It reproduces a slow setup that outlives the 60-second administration token and an unexpected 401, then verifies fresh-token continuation. Business services remain Java 21/Spring Boot. Executed results and limits belong in the [lot 5 report](verification/phase-8-support-access.md).

## Phase 8 temporary support payment access

The same support browser specification now runs Account and Payment journeys. Payment uses an actual unfunded transfer that reaches a terminal rejection/failure. It verifies the exact six-field observation, unchanged customer Payment and source balance, same-key recovery, denial, revocation masking and resolution/reopening. The Account journey remains a regression for the shared grant frame and disclosure sequence.

The PostgreSQL suite upgrades an existing V2 grant/receipt to V3 and proves unchanged Account fingerprints and original receipts, distinct resource scopes, actor-global keys, concurrent versions and transactional resolution rollback. Signed Payment tests require both the narrow authority and the signed Audit client, with no broad inspection or financial action. Broker tests reject mismatched resources, unknown states/kinds, numeric or overflowing versions and invalid timestamps; admission/final storage failure withholds contents. Frontend tests include wrong-resource/permission grants and local observation clearing. See the [lot 6 measurements and limits](verification/phase-8-support-payment.md).

## Phase 8 scoped support customer discovery

The PostgreSQL grant suite now preserves original Account/Payment receipts through V4 and covers Customer policy isolation, actor-global keys, bounded cohort selection, in-flight invalidation and failed final audit storage. Customer PostgreSQL checks prove literal bounded search inside the supplied authorized IDs, string versions and unchanged profile/outbox rows. Signed Customer/Audit/Gateway tests check machine identity plus narrow authority, roles, methods, caller-selected scope rejection and redacted failures.

Frontend tests cover local-only discovery results, explicit submit/read, expired responses, masking, wrong-resource grants and exact same-key Customer grant recovery after a lost response/reload. The hosted PKCE support-customer journey discovers an actual authorized profile, checks six-field observation, rejects no/foreign scope and ADMIN directory access, revokes through the UI and proves resolution/reopening and old receipt replay cannot restore permission. The Account and Payment support journeys remain regressions. Executed results and limits are recorded in the [lot 7 report](verification/phase-8-support-customer.md); [full audit coverage remains a separate inventory](verification/phase-8-audit-coverage.md).

## Phase 8 Customer administrative read audit

`CustomerAccessAuditSecurityTest` crosses the signed HTTP security chain for success, anonymous/invalid-audience credentials, signed role denial, malformed JSON and unavailable audit persistence. It checks that body bytes remain buffered at retention, forged headers never replace the verified actor, decoded servlet paths retain their finite category, and container/unhandled errors cannot flush a private partial body before retention. Other owner routes do not inherit this finite action registry.

`CustomerAccessAuditIT` uses restricted PostgreSQL identities to verify immutable receipts, metadata constraints, one redacted immutable outbox source, identical replay after a lost acknowledgement and a real V3-to-V4 upgrade preserving old domain envelope/delivery bytes. `AuditEventTest` rejects inconsistent typed metadata and projects only the access allowlist; unknown actor provenance cannot fall back to an unverified producer-reported owner.

`node scripts/check-customer-audit.mjs` tests the direct owner, actual publication/central retention, duplicate/conflicting replay and digest-only quarantine. Its optional `--exercise-storage-failure` briefly revokes and restores the runtime audit INSERT privilege; CI runs that recovery check before browser traffic. The hosted PKCE customer-search journey checks the signed actor, absence of the search prefix and central receipt for both search and selected inspection, using a single response correlation header. `RequestFilterTest` reproduces owner/proxy response-header duplication and checks the Gateway's final single-value header against its forwarded identifier for generated, valid and invalid caller input. Existing inspection and scoped support customer journeys remain regressions. See the [operational contract and upgrade](deployment/customer-access-audit-phase-8.md); measured results belong in the lot report, not this assertion inventory.

## Phase 8 Account and Payment administrative read audit

`AccountAccessAuditSecurityTest` and `PaymentAccessAuditSecurityTest` cross real signed security chains for prepared success, invalid/absent credentials, role/method denial, malformed references, owner query errors and unavailable audit INSERT/transaction begin/commit. Assertions verify current staff identity, no-store, withheld private bodies and exclusion of unregistered routes. Customer's existing HTTP and PostgreSQL suites remain regressions for the shared implementation.

`OwnerReadAuditIT` uses restricted real PostgreSQL roles for both owner schemas: immutable receipts, typed constraints, source redaction, independent commit inside an enforced read-only transaction followed by outer rollback, lost-ack replay, and additive Account V2→V3 / Payment V4→V5 upgrades preserving old row/envelope/delivery bytes. `PaymentRecoveryIT` additionally invokes the real signed inspection controller against its actual read-only page, checks invalid cursor decisions, revokes/restores the runtime audit INSERT permission and compares financial rows/history/outbox and source balances. `AuditEventTest` enforces own-owner actions/references and verified/unavailable actor consistency without falling back to the original payer.

`node scripts/check-owner-read-audit.mjs --exercise-storage-failure` runs the actual broker/storage probe for Customer, Account and Payment and proves Account/Payment cannot publish each other's receipts. Foundation integration runs it before browser traffic. The extended hosted PKCE inspection journey checks current signed staff and exact redacted central receipts for actual Account/Payment pages, string bigint cursors, invalid cursors, missing references and a successful Account response with explicit Ledger unavailability. Original support Account/Payment/Customer journeys verify that the shared mechanism adds no authority to scoped support routes. See the [contract and upgrade](deployment/owner-read-audit-phase-8.md); the [lot report](verification/phase-8-owner-read-audit.md) records executed results.

## Phase 8 Reporting access audit

`AdministrativeReadAuditFilterTest` exercises oversized bytes, chunked writers and declared length, valid length after a partial body, withheld attachment metadata on failed commit and existing Customer character responses. `ReportingAccessAuditSecurityTest` crosses the signed owner security chain for all seven categories, current caller identity, role separation, invalid credentials/references/bodies/cursors and INSERT/transaction begin/commit failure. A correctly framed wrong-method request must reach the role denial; early HTTP framing failures remain outside the registry.

`ReportingAccessAuditIT` uses a real restricted PostgreSQL installation and the signed HTTP controller. It commits an actual snapshot while audit INSERT is revoked, proves a withheld response and unchanged same-snapshot recovery, then revokes/restores INSERT for actual CSV/PDF exports. Other cases prove immutable receipts, a separate commit inside enforced read-only rollback, identical lost-ack replay, typed metadata constraints and an additive V1-to-V2 upgrade preserving original statement bytes/content hashes. Existing `ReportingDatabaseIT`, export rendering and three owner HTTP audit suites remain regressions.

The Compose probe now includes Reporting direct-owner denial, actual central digest/redaction/replay/quarantine and storage recovery. Producer checks deny Reporting writes to Payment receipts and actual Ledger financial facts, Payment writes to Reporting receipts and Audit producer authority. The extended hosted card/statement journey verifies exact redacted central facts for list/detail/create-request/CSV/PDF, foreign-owner 404, finance reconciliation/journal inspection and ADMIN denial, and compares the original immutable snapshot after repeated reads/creation requests. Its existing PKCE, real financial/card/Fraud-outage and responsive export checks remain intact. See the [operational contract](deployment/reporting-access-audit-phase-8.md); executed results belong in the [lot report](verification/phase-8-reporting-access-audit.md).

Reporting scope checks distinguish a Gateway refusal, which never reaches the owner, from a signed direct-owner 403 retained in real PostgreSQL. Strict HTTP export assertions inspect all Cache-Control header values; the owner filter supplies the policy once. Native browser evidence consolidates successful journeys across focused reruns and does not count attempts as extra cases.
