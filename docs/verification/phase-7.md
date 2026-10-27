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

Phase 7 acceptance passed for application source `998ac020b039c10676e827b2e96462fd87066ae3` on `feature/fincore-cards-reports`, based on completed Phase 6 `a1a91cc755d18e0fd3686d2271ef27aa16508662`. Main integration retains this validated application plus documentation; its independent workflow observation is recorded separately below when complete.

- [Backend quality](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/40538581340): full default reactor, 191 cases with zero failures/errors/skips.
- [Frontend quality](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/40538581056): typecheck, lint, 26 component cases, both portal builds and npm audit.
- [Foundation integration](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/40538581055): fresh Compose, independent authentication, private Ledger acceptance, eight real hosted-PKCE journeys in one command and Kafka ACL/DLQ/replay.

Sanitized machine-readable evidence: [phase-7-checks.json](../evidence/phase-7-checks.json). Exact [changed-file list](phase-7-files.txt) is relative to the Phase 6 base. See [contracts](../fincore/cards-and-reporting.md), [ADR 0010](../fincore/adr/0010-card-reservations-and-statement-snapshots.md) and [upgrade/demo](../deployment/cards-phase-7.md).

## G. Observed verification

| Executed check | Result |
| --- | --- |
| Exact-source GitHub full Maven reactor | PASS: 191 tests, zero failures/errors/skips |
| Latest focused local Ledger verify | PASS: 36 unit/security and 37 PostgreSQL cases, including 15 Card/reservation cases |
| Latest focused local Card verify | PASS: 9 unit/security and 3 PostgreSQL cases; BUILD SUCCESS and zero-failure XML |
| Local frontend | PASS: typecheck/lint, 26 component cases with one thread worker; both portal images built |
| Retained-volume migration | PASS: Ledger V3 to V6, Fraud V1 to V2, Card V1 then additive V2, Reporting V1; fifteen runtimes healthy |
| Local foundation/private Ledger | PASS: independent authentication and actual financial replay/reversal/guards/reconciliation |
| Latest complete local browser command | Seven pass, one sender inbox display assertion fails; Card/Reporting passes, retries disabled |
| Strict focused customer rerun | PASS: unchanged assertions, one journey, retries disabled |
| Card-specific browser assertions | Lost creation response/reload with same key/body; canonical merchant review; real held funds reject an overlapping transfer; exact capture/refund journals and replay; daily limit retained; release/freeze; fail-closed Fraud outage and recovery of the same authorization |
| Statement/finance browser assertions | Opening + three real postings = closing; JSON/CSV/PDF share a snapshot; actual download buttons; cross-owner 404; genuine FINANCE_OPERATOR reconciliation and ordinary ADMIN 403 |
| Isolated local messaging after browser | PASS: Customer cannot publish Payment facts; Card cannot publish Ledger facts; bounded poison retries, durable DLQ and audited unchanged replay |
| Visual checks | Actual test-owned Card/Statement screens inspected at 1440/390; overflow also checked at 320. Three-page PDF rendered and first/last pages inspected |

Local backend provenance is deliberately limited to the executed focused runs; no fully successful final local root reactor is claimed. Earlier root attempts exposed a refund fixture expectation and an expiry-contention test that started its deadline inside a late executor. The stronger test observes the actual PostgreSQL backend waiting for the account lock, then lets the database deadline expire before unlock. It still requires no debit, ASSESSMENT_EXPIRED and a successful fresh retry. Exact-source GitHub independently supplies the full 191-case gate.

Early Card browser attempts found an Account currency selector and premature removal of a replay route handler. Both harness errors were corrected without weakening financial assertions. The first local creation returned Gateway 504 before the planned injected response loss; a later unchanged attempt passed creation. The first full local command passed seven journeys but received 504 during statement generation, after the complete Card financial/outage assertions. Card/Reporting routes now allow a bounded 20-second Gateway response and 25-second browser request, accommodating sequential owner calls individually bounded at five seconds. Other route budgets and exact financial recovery assertions are retained. The subsequent Card/Reporting journey passed after a controlled Reporting restart, including exports and finance roles. This corrects the earlier budget mismatch; no cold-start latency guarantee is claimed. Stale anonymous "cards coming soon" text was also replaced with the actual synthetic card scope.

Local and GitHub acceptance also reproduced `record "new" has no field "card_id"`: the shared identity trigger combined a table-name check and fields belonging only to another table in a SQL AND expression. Reused connections exposed a different cached trigger record shape. Card V2 replaces it with explicit table-specific branches, preserving identities and immutable receipts. A real PostgreSQL regression alternates authorization/projection updates on one connection. After migration, the previously pending frozen authorization recovered to CARD_RESTRICTED without a hold. Full acceptance passed with the correction.

The latest complete local command on `eb4bed9fd558d3d98e81b5901d502f386ce563ec` passed seven journeys but reproduced the historical sender inbox display miss. PostgreSQL contained exactly the two owner notifications and one sender synthetic email. The strict, unchanged focused customer rerun passed, including broker outage/recovery, recipient live update, sender notice/email, cancellation, history and account guards. No separate root cause is claimed. The final source differs from that full local command only by a statement-label presentation correction, verified through a separate native-PKCE visual capture. Exact-source GitHub independently ran all eight together successfully. Local success across a full command and focused rerun is not presented as one fully successful local command.

One Windows component launch executed sixteen cases but failed to start the remaining fork worker; it is not counted as a full pass. The same 26 assertions subsequently passed with `--pool=threads --maxWorkers=1`. Windows PowerShell also reports NativeCommandError for redirected native stderr; the Card verification result is supported by Maven BUILD SUCCESS and its zero-failure Failsafe XML, rather than that wrapper status. The historical Phase 6 inbox display observation remains in [its report](phase-6.md).

The upgrade retained named volumes, matching `.env`/credential hashes and unrelated Commerce container identities. No bearer value, generated secret, authenticated capture or raw private diagnostic is committed. Synthetic scope and the 1,000-entry explicit export cap remain; no production/performance guarantee is claimed.

## H. Next milestone

Phase 8: operational administration, dedicated Audit and observed telemetry dashboards. Not started by this milestone.
