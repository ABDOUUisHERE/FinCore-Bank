# Phase 6: synchronous fraud authorization and investigation

## A. Outcome and scope

Extract the local Payment risk port into an independently authorized Fraud service. Add explainable versioned rules, atomic velocity admission, assessments, alerts, analyst cases/decisions and a connected analyst workspace. Synthetic money only. Stop after this milestone; cards/reporting remain Phase 7.

## B. Decisions and boundaries

Fraud reads canonical Payment context through a narrow technical endpoint; a customer cannot supply an actor, country, historical count or analyst approval in the assessment request. Private Customer/Beneficiary context endpoints expose only the fields required by the rules. Geographic rules use declared synthetic profile-country snapshots, not inferred physical location or IP geolocation.

The assessment transaction serializes each actor's velocity admission in Fraud's database. Repeating one payment cannot consume multiple simultaneous slots. A fresh assessment renews expired admission before a new short-lived ALLOW. Risk admission is not a ledger funds hold. The existing Ledger posting-point expiry check remains required.

No required dependency has a permissive fallback. For ambiguous PROCESSING payments, Payment first resolves the existing ledger outcome; an already posted payment is not retroactively rejected when Fraud is unavailable. An unresolved instruction needs a fresh valid ALLOW before another execution attempt.

Analyst approval is recorded by Fraud and does not replace a fresh rules/velocity assessment. New blocking facts remain blocking. Payment durably polls pending reviews so an accepted analyst decision does not depend on a cross-database atomic update or one successful HTTP response. Existing private review acceptance is bridged to the same audited Fraud decisions rather than trusting Payment's old approved boolean.

## C. Planned files and modules

- `services/fraud-service/`: Java resource server, independent migration/runtime roles, rule evaluation/store, velocity admission, investigation API, event publication, PostgreSQL/security/unit checks and non-root image.
- `services/payment-service/`: replace the production local policy bean with a synchronous Fraud adapter; canonical risk-context endpoint, durable assessment references, pending-review recovery and private review bridge; additive migration only.
- Customer and Beneficiary services: narrowly scoped private read models for declared country, beneficiary age and status; independent authorization checks.
- `packages/portal-ui/src/fraud/` and portal navigation: analyst-only alert/case/rule screens, recorded explanations, versioned confirmation and actual loading/error states; component checks.
- Gateway: explicit FRAUD_ANALYST routes, with no implicit ADMIN/customer access to investigation mutations.
- Compose/bootstrap/identity/Kafka: one new private service/database/client/producer, generated secrets appended without rotation, narrow scopes/ACLs, additive setup jobs and readiness.
- Event/API contracts, ADR, deployment/operations guidance, hosted-PKCE analyst/customer acceptance and exact-source GitHub evidence.

Explain additional necessary files as implementation findings arise. Preserve prior financial migrations and unrelated containers/data.

## D. Limits

No real financial institution, sanctions screening, machine-learning claim or physical-location verification. No funds reservation until the Ledger hold milestone. Rules and counters operate on observed synchronous service facts; no global distributed transaction is claimed. Existing local single-node Kafka and non-TLS boundaries remain as documented in Phase 5.

## E. Required validation

1. Preserve the financial, ownership, notification and recovery suites.
2. Exercise high-value, new-beneficiary, frequency, rapid-repeat, velocity-change, declared-country/change and failed-payment rules with explainable results and bounded configuration.
3. Real PostgreSQL concurrent admission, same-payment replay/conflict, expired admission renewal, immutable audit/assessment permissions, optimistic rules/case decisions and stale approval checks.
4. Independently reject wrong issuer/audience, customer/staff privilege escalation and forged context; only the narrow technical Payment identity can assess.
5. Fraud outage before authorization moves no money; a lost response or already committed Ledger outcome recovers without a second posting.
6. Genuine customer and FRAUD_ANALYST hosted PKCE, generated alert, investigation, versioned decision and recorded Payment outcome; ordinary ADMIN/customer denied.
7. Rule changes, interrupted decisions, cancellation/review races and broker outage preserve authorization/audit facts. All three GitHub workflows must pass on the exact application source before main integration.

## F. Status

Implementation started on `feature/fincore-fraud`, based on `080dc468e49b9a40ae78f3fa7b42ad2d27a08daa`. No Phase 6 acceptance results are claimed yet.

## G. Evidence

Pending execution. Record actual pass/fail/skip counts, commands, source SHA, workflow URLs, changed-file list and remaining limitations after validation.

## H. Next milestone

Phase 7: cards, ledger funds reservations/capture/release, merchant simulations, refunds and reconciled reports/statements.
