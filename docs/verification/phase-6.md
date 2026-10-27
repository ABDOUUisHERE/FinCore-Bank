# Phase 6: synchronous fraud authorization and investigation

## A. Outcome and scope

Extract the production Payment risk port into an independently authorized Fraud service. Add explainable versioned rules, atomic per-actor velocity admission, immutable assessments/initial alerts, analyst cases/decisions and a connected analyst workspace. Ledger independently validates the grant against its own immutable instruction before executing a Payment. Synthetic money only. Stop after this milestone; cards, funds reservations and Reporting remain Phase 7.

## B. Decisions and boundaries

See [ADR 0009](../fincore/adr/0009-synchronous-fraud-and-investigation.md). Fraud accepts only a durable payment ID and obtains canonical identity, declared country, beneficiary age and financial failure history through narrow private owner APIs. Geography uses declared synthetic profile-country snapshots, not IP or physical location. Amounts are exact independent MAD/EUR/USD strings, without FX.

An actor advisory lock serializes distinct-payment admission and evaluation in Fraud's PostgreSQL transaction. Same-payment replay consumes one slot. A fresh grant renews its sixty-second admission before evaluation. A thirty-second ALLOW is bound to instruction identity, canonical risk context, rules version and case version. Velocity admission reserves no funds.

Payment durably polls reviews. Approval records an immutable signed-actor decision; fresh rules/velocity still apply. New signals or rule versions invalidate approval, and strict blocks cannot be overridden. Rejection remains binding. Relaxation without approval is CLEARED. Decisions preserve the original key/body/version through an unknown response. Rule changes append immutable versions using expected-version retry identity.

Ledger uses a verification-only client to bind the assessment UUID, payment UUID and its own stored instruction fingerprint. The HTTP check finishes before the posting transaction. Existing SQL posting-point expiry, balance, guard and uniqueness checks remain authoritative. Payment and Ledger resolve existing outcomes before consulting Fraud during ambiguous recovery. No required dependency has a permissive fallback.

FRAUD_ANALYST is explicit at Gateway and Fraud; ADMIN/customer/worker privileges do not imply investigation rights. Fraud cannot write money. Initial alert, assessment, case and outbox facts commit together. Notification projects a generic owned SECURITY notice asynchronously; it does not announce settlement.

## C. Files and modules

New `services/fraud-service`; Payment remote adapter, canonical context and additive V4 reference migration; Ledger owner-side grant verifier; narrow Customer/Beneficiary contexts; Gateway role routes and bounded upstream resolution; isolated database/identity/Kafka bootstrap; analyst cases/rules UI; real PostgreSQL/security/component/browser checks; API/event contracts, operations guidance and evidence.

The exact [changed-file list](phase-6-files.txt) is relative to completed Phase 5 base `080dc468e49b9a40ae78f3fa7b42ad2d27a08daa`. Existing financial migrations were preserved. The [upgrade and analyst demo](../deployment/fraud-phase-6.md) describes the persisted-volume cutover.

## D. Limits

Synthetic money and identities only. No funds holds, distributed transaction, instantaneous revocation of issued grants, physical geolocation, ML or sanctions claim. Failed-payment input counts financial FAILED transfers in ten minutes, not login failures. The case feed is a current work queue and can move during pagination; the immutable initial-alert feed has a stable sequence cursor. Private legacy Ledger simulations retain their explicit technical scopes; public customer payments use the authorized-instruction boundary.

Single-node Kafka and local non-TLS boundaries retain the Phase 5 limits. Credentials and authenticated synthetic captures remain ignored. Cards, Reporting, dedicated Audit and production hardening are later milestones. A local inbox display assertion failed once despite correct stored owner effects; the strict focused rerun and exact-source GitHub suite passed. No separate cause or universal absence of intermittent UI failures is claimed.

## E. Validation strategy

Preserve the financial/ownership/recovery suites. Exercise high value, new beneficiary, frequency, rapid repeat, velocity change, declared-country change and failed-payment explanations. Real PostgreSQL checks cover serialized concurrent admission, replay/conflict, expired-slot renewal, rule/decision versions, immutable permissions, rollback atomicity and exact unexpired Ledger binding. Signed security checks reject forged contexts and role/scope escalation.

Browser acceptance uses genuine hosted customer and analyst PKCE. It requires a review with no posting, one balanced journal after audited approval, the matching assessment reference and confirmed journal displayed by the UI, a strict block without money movement, a Fraud outage without debit, recovery of the same payment exactly once, confirmed rules publication/restoration and customer/ordinary-ADMIN denial. The existing customer journey also exercises a broker outage, SSE, one notice/email, cancellation, history, beneficiary edits and account guards.

## F. Status

Phase 6 acceptance passed for application source `194e046c7346509f207cc78632310e9f543ecadf` on `feature/fincore-fraud`. Main integration fast-forwards this validated source plus documentation. All three exact-source GitHub workflows succeeded:

- [Backend quality](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/40488434873): full default Maven reactor; 144 tests, zero failures/errors/skips.
- [Frontend quality](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/40488435254): typecheck, lint, 24 component cases, both portal builds and zero npm audit vulnerabilities.
- [Foundation integration](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/40488434943): fresh Compose, independent authentication, private Ledger acceptance, all seven browser journeys in one command, and Kafka ACL/DLQ/replay.

Sanitized machine-readable results: [phase-6-checks.json](../evidence/phase-6-checks.json). Local and remote provenance are deliberately recorded separately.

## G. Observed verification

| Executed check | Result |
| --- | --- |
| Earlier full default local Maven reactor | PASS: 137 cases before independent Ledger grant binding |
| Subsequent Ledger/Payment/Fraud and final Gateway verify | PASS; latest local XML reports total 144 cases, zero failures/errors/skips across runs |
| Exact-source full GitHub Maven reactor | PASS: all 144 in one default verify command |
| Local frontend typecheck/lint/components/build/audit | PASS: 24 cases with one Windows worker; both builds; zero vulnerabilities at low audit threshold |
| Existing-volume Docker upgrade | PASS: Fraud V1 and Payment V3 to V4; thirteen runtime services healthy on Engine 29.1.2 |
| Local foundation and independent private Ledger checks | PASS |
| Latest full local browser command | Six pass, one inbox display assertion fails; real Fraud review/block/outage/rules journey passes |
| Strict focused customer browser rerun | PASS, unchanged assertions, retries disabled; one sender notice/email and live recipient update |
| Final isolated local Kafka command | PASS: foreign publication denied, bounded poison retries, durable DLQ and audited unchanged replay |
| Visual inspection | PASS: actual analyst case/alert/explanations/confirmed journal at 1440 and 390 pixels; overflow assertions also at 320 |

The local browser successes cover seven journeys across the full command and focused rerun. They are not presented as one fully successful local seven-journey command. GitHub independently ran all seven successfully together on the exact final source. Likewise, local backend totals combine the earlier full reactor with later focused runs; exact-source GitHub supplies the full-reactor gate. Payment's isolated SQL recovery lab explicitly provides its local risk fixture; production wiring uses the remote Fraud adapter, exercised by the real HTTP/PKCE acceptance.

Early checks found an obsolete DEMO_HARD_LIMIT expectation after extraction; it now strictly expects HARD_AMOUNT_LIMIT. The insufficient-funds example is 4999.00 rather than 9999.00 so it remains below the new-beneficiary review threshold while still exceeding the 900.00 balance; its INSUFFICIENT_FUNDS assertion is preserved. Plain Compose start traversed preparation dependencies during the outage probe; the test now resumes only the existing Fraud container. The desktop viewport is restored after mobile overflow checks before using desktop navigation. An initial parallel Windows component launch could not start its workers; it executed no cases and is not counted as passing. Serial execution subsequently passed all 24.

The first local Fraud outage recovered Payment but the existing Gateway continued returning 503 to the analyst route while Fraud was healthy and a fresh private lookup succeeded. Bounded upstream DNS caching was added, and subsequent local/GitHub public-route recovery passed without restarting Gateway. Stale resolution is an inference; the repeated local outage reported an unchanged address, so deterministic address churn was not proven.

One subsequent full local browser run missed the sender inbox entry within its existing ninety-second UI assertion. PostgreSQL independently contained exactly the sender and recipient effects plus the sender's synthetic email. The unchanged strict focused customer journey then passed, including SSE, single notice/email, cancellation, filters and guards. No separate cause is claimed for that display failure. Final private Kafka verification ran after the browser outage checks; an earlier prematurely launched operator check was stopped and is not counted as passing.

Named volumes, existing secrets and unrelated Commerce containers were retained. The stack remains running with matching private configuration. No bearer values, passwords, authenticated captures or raw private diagnostics are included in committed evidence.

## H. Next milestone

Phase 7: cards, Ledger funds reservations/capture/release, merchant simulations, refunds and reconciled reports/statements. Not started by this milestone.
