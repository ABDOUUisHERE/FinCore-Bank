# Phase 3 — owned accounts and recoverable simulated payments

Date: 2026-10-23 UTC. Branch `feature/fincore-accounts-payments`, based on main commit `fc77af44434a546070d970a945342ceb6e94a1d4`. The baseline's three main workflows were green before this milestone.

## A. Current state

The ledger kernel and authenticated portal shells were merged into main. This milestone adds real customer/account/payment backend behavior. Banking forms and dashboards remain Phase 4; no fake financial UI data is introduced.

## B. Implementation

Customer supports idempotent synthetic onboarding and versioned own-profile updates. Account owns metadata and validates ownership independently of Gateway; it creates zero-balance ledger accounts and reconciles durable provisioning/guard intent after an ambiguous response. Balance/history reads come from Ledger, with bounded UUID cursors. A pending restriction is reported as pending until the ledger confirms its effective revision.

Payment uses Keycloak standard token exchange and immutable Ledger authorization receipts. The ledger validates the signed originating customer, authorized party, audience and prepare scopes. The worker has separate execute/read scopes and cannot submit arbitrary new customer debits. Tokens are never retained in databases. Orphan authorized instructions can be imported if the process dies before Payment persistence; scans wrap rather than advancing an unsafe permanent high-water mark.

The workflow persists state, immutable intent/initial response, optimistic versions, status facts/outbox rows, retry times and fenced leases. No remote call runs inside its local transaction. Ambiguous outcomes remain PROCESSING and resume using the same ledger identity. Initial responses replay exactly even if account eligibility later changes. Cancellation races serialize before PROCESSING. The local risk port explicitly returns ALLOW/REVIEW/BLOCK; approval revalidates the command, and the ledger refuses an expired assessment at journal insertion. Private technical operators can fund simulations, review and request full compensation. A reversal never edits the original journal.

## C. Files and modules

The [path manifest](phase-3-files.txt) lists this milestone relative to the merged baseline.

- `libraries/service-support/`: stateless HTTP/token/security/JSON/error infrastructure and test-only database/signed-token fixtures; no shared financial domain entities.
- `services/account-service/`, `services/payment-service/`: independent applications, migrations, private runtime images and signed-JWT/PostgreSQL recovery tests.
- `services/customer-service/`: profile APIs, migration, strict JSON/body guards and independent authorization.
- `services/ledger-service/`: additive authorized-instruction migration, scope/issuer checks, guarded execution, technical history and meaningful delegation/expiry tests. Phase 2 migration is unchanged.
- `services/api-gateway/`: explicit Customer/Account/Payment routing, CUSTOMER authorization, bounded bodies and required CORS methods/headers. Private technical routes remain absent.
- `compose.yaml`, `infrastructure/docker/postgres/init-business.sh`, bootstrap/identity/diagnostic scripts: repeatable upgrades on existing volumes with separate secrets and no credential rotation.
- `scripts/check-business.py`, `tests/e2e/business.spec.ts`: real two-customer PKCE and private-network financial journey with memory/stdin-only tokens.
- OpenAPI contracts, ADR 0006, README, setup/testing guides and this report: current interfaces, decisions and actual evidence.

## D. Risks and limits

Synthetic users and money only; activation is not real KYC. Local demo thresholds apply to base currency units without FX: up to 10,000.00 allows, larger values through 100,000.00 require review, above that blocks. These are not a production fraud/velocity policy. Risk review reserves no funds. Staff browser review/reversal and MFA are deferred; the current privileged endpoints require separate private technical scopes.

There are no card holds, partial refunds, Kafka publishing/consumers, notifications, operational dashboards or production deployment. Available equals booked. The original ledger, payment store and authorization receipts require consistent backups; repeated receipt discovery is process-crash recovery, not permission to recreate a lost Payment database and replay historical cancelled instructions. Database owner/administrator compromise remains privileged infrastructure risk. Cursor scans and single-process Gateway rate budgets have no production scaling/performance claim.

## E. Test strategy

Signed-RSA direct-service authorization, actor/header spoofing and strict decimal JSON checks. Actual isolated PostgreSQL for ownership, ordered monetary locks, receipt immutability, expired assessments, local transaction state, lease fencing, response loss and cancellation races. Real Keycloak/PKCE/token exchange plus the full HTTP financial journey. Separate browser-shell regressions remain in place.

## F. Implementation status

Implementation complete locally; final source SHA and Linux CI metadata will be recorded after push. Integration into main follows passing checks, matching the requested GitHub workflow.

## G. Verification

| Executed check | Observed result |
| --- | --- |
| Root Maven `test` | PASS: 43 unit/security cases; this command does not run database integration tests |
| Ledger native `verify` | PASS: 19 actual PostgreSQL 16.9 invariant/concurrency/delegation/expiry tests, plus its then-current unit/security suite |
| Account/Payment native `verify` | PASS: 3 account recovery/isolation and 8 payment recovery tests on separate real PostgreSQL databases; unit/security checks pass |
| Real native Keycloak + two-customer PKCE/HTTP journey | PASS: onboarding, owned accounts, technical funding, signed token exchange, transfer/replay/conflict, history, review/block, cancellation, guards, compensation, original preservation and reconciliation |
| Final packages, migrations and readiness | PASS: all five refreshed application packages, existing-volume migrations and native readiness |
| Final complete browser suite | PASS: all 5 journeys in 26.4s; no retries |
| TypeScript typecheck and ESLint | PASS |
| Python compilation, Compose configuration, OpenAPI YAML and generated-secret scan | PASS |
| Linux PostgreSQL 17.11, full Compose and browser CI | Pending push; no pass claimed yet |

Native verification uses the dedicated repository-local PostgreSQL 16.9 cluster on port 5548. Docker Engine on the workstation remained unresponsive during initial checks; native results are not Compose evidence. Secrets, credentials, bearer tokens and authenticated traces are excluded from committed evidence. A native browser attempt initially timed out waiting for the anonymous sign-in control after application refresh. The test now explicitly waits for visibility and bounds action timeouts; the complete final rerun passed all five journeys with retries disabled. The transient initialization cause was not conclusively reproduced. Exact CI counts and workflow links will supersede the pending entry.

## H. Next milestone

Phase 4: customer dashboard, account/balance views, transfer form/confirmation and transaction history using these actual APIs. Preserve pending/error/review states, ownership checks and authoritative confirmation; notification delivery remains Phase 5.
