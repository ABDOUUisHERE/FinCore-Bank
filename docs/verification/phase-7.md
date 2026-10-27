# Phase 7: synthetic cards, reservations and reconciled reports

## A. Outcome and scope

Implement synthetic card lifecycle, transaction/daily limits, merchant authorization, Ledger-owned reservations, full capture/release/refund and monthly statements with CSV/PDF exports. Add independently authorized Card and Reporting services and connected customer screens. No PAN, CVV, real card network, FX or real money. Stop after Phase 7; operational administration and telemetry remain Phase 8.

## B. Decisions and boundaries

Ledger remains the sole spending authority. Card controls are spending guards replicated into Ledger, not balances in Card. Reservations and every existing debit share the same ordered account locks. All spending paths subtract live reservations; capture releases its own hold and posts in one transaction. Expired holds stop reducing available funds by database time. Closing an account with a live reservation is forbidden.

Card commands use the signed customer's delegated identity. Canonical authorization receipts persist before Fraud evaluation, allowing recovery without retaining browser tokens. Fraud reads the owned canonical card context through a narrow technical scope; no caller-supplied risk facts or permissive fallback. Ledger validates the bound short-lived ALLOW before establishing the hold. Capture consumes that established authorization, and release never posts money. Full refunds compensate the exact original journal once, without resetting daily spend. Freeze/limits, holds and capture serialize inside Ledger.

Daily limits use UTC and count captured spending plus live reservations admitted on that UTC day. Partial captures/refunds and incremental authorizations are outside this milestone. A failed refund remains a recorded definitive outcome for that financial identity. No guessed failure after an ambiguous posting, no new identity on timeout.

Reporting reads owner-authorized Ledger snapshots at REPEATABLE READ. Opening balance plus signed period postings must equal closing balance. Historical month-end booked balance is distinct from current booked/available funds. Oversized exports fail explicitly instead of silently truncating. Reporting retains immutable snapshots and their provenance; projections are not presented as authoritative financial totals. Customer statements and finance-only reconciliation use separate privileges.

## C. Planned modules and files

- Ledger additive migrations/functions for card spending guards, immutable authorization receipts/actions, live holds and reservation-aware debits; private card/statement endpoints and database/security regressions.
- Card service with independent database/runtime/migrator, lifecycle metadata, durable authorization recovery, merchant simulation and audited idempotent actions. Nonfunctional card labels only.
- Fraud narrow card context/assessment path and merchant rules; existing transfer path preserved.
- Reporting service with independent snapshot storage, owner statements, bounded CSV/PDF generation and finance-only reconciliation.
- Gateway, Compose, generated configuration, identity scopes and any required event integration; preserve volumes, secrets and other projects.
- Customer card/statement screens with confirmation, real pending/error states, authoritative balances and exact decimal displays.
- Contracts, ADR, deployment/demo guidance, real PostgreSQL contention/rollback/expiry/replay tests, genuine hosted-PKCE acceptance and exact-source GitHub evidence.

## D. Limits

Synthetic identities, merchants and money only. Full capture/release/refund only; no payment network, PAN/CVV, partial settlement or instant global revocation. No production deployment or performance claim. Keep the known Phase 6 local inbox display observation visible in its historical report.

## E. Required validation

Preserve existing suites. Race holds against transfers and other holds, capture against release/freeze/expiry, and concurrent refunds. Check exact funds, immutable journal/audit permissions, UTC limits, full rollback, signed ownership/scope boundaries, lost-response recovery and fail-closed Fraud outages. Reconcile statements against real journals, including opening/closing balances and compensations. Verify CSV/PDF content and actual visual layout. Run customer and finance role acceptance through real hosted PKCE; reject cross-owner and implicit ADMIN access. All three workflows must pass on the final application source before merging to main.

## F. Status

In progress on `feature/fincore-cards-reports`, based on `a1a91cc755d18e0fd3686d2271ef27aa16508662`. No Phase 7 acceptance claimed.

## G. Evidence

Pending implementation and execution. Record exact commands, source SHA, pass/fail/skip counts, local/CI provenance, migration evidence, changed-file list and limitations. Commits are grouped by real implementation changes and pushed regularly.

### Ledger implementation checkpoint (2026-10-27 UTC)

The additive V4 migration and private card/report boundaries are implemented. The current local Ledger unit suite passes 36 tests. The targeted PostgreSQL suites pass 33 tests (22 existing financial regressions and 11 card/reservation/statement cases), with zero failures, errors or skips. Command: `mvnw.cmd -B -ntp -pl services/ledger-service -am verify -Dit.test=CardReservationsIT,LedgerDatabaseIT -Dfailsafe.failIfNoSpecifiedTests=false`, followed by `mvnw.cmd -B -ntp -pl services/ledger-service -am test` after the private HTTP security additions. This targeted command does not claim to rerun Messaging integration; the preceding full dependency run passed Messaging but initially failed to compile two new test assertions, which were corrected. No persistent local database was migrated at this checkpoint.

Policy clarified: freezing blocks new holds; an already established, unexpired merchant hold can still be captured. Termination requires release or expiry of all live holds. A posting-point deadline trigger rolls an expired capture back in full. Historical statements exclude holds from booked month-end totals and expose current reserved/available balances separately.

## H. Next milestone

Phase 8: operational administration, dedicated Audit and observed telemetry dashboards.
