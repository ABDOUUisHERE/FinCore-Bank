# Phase 5: Kafka and owned notifications

## A. Outcome and scope

Publish Customer, Account, Ledger and Payment facts to Kafka. Add an independently owned Notification service, inbox, preferences, read state, local synthetic email, authenticated SSE, bounded retry/DLQ and audited replay.

## B. Decisions

See [ADR 0008](../fincore/adr/0008-durable-event-delivery-and-notifications.md). Financial requests commit independently of broker availability. Publication follows commit/ack. Event IDs, uniqueness and aggregate versions prevent duplicate effects. Invalidations refresh authoritative owner APIs.

## C. Files and modules

Shared libraries/messaging; four additive producer migrations; notification-service and its private database; Kafka bootstrap/ACL configuration; customer notification UI; real broker/PostgreSQL, component, hosted-login and private operator acceptance checks; event/API contracts and deployment guidance.

## D. Limits

Synthetic money and identities only. One Kafka node is not high availability. Local SASL is not TLS. Simulated email is a local database sink. Historical facts without routing metadata do not invent retroactive messages. Topic retention is seven days; acknowledged source facts remain retained, and later archival/rebuild requires an explicit operation. Events do not book money. Full Fraud, cards, Reporting, operations telemetry and production hardening remain later phases.

## E. Test strategy

Preserve the financial suite. Add real broker pause/recovery, post-ack crash and parallel lease tests; PostgreSQL atomic inbox/offset-window replay, semantic deduplication, monotonic versions, preferences, ownership and sink permissions; signed customer/technical authorization; SSE framing/header authentication; genuine two-customer hosted PKCE with a broker outage during a confirmed transfer; Kafka ACL denial and poison/DLQ/audited unchanged replay.

## F. Status

Implementation and verification in progress. Actual source commit, totals and remote workflow links will be recorded after checks finish.

## G. Observed verification

Pending final execution record. An initial WSL startup failure was recovered by stopping Docker Desktop, releasing stopped WSL instances and restarting. Ubuntu and Engine 29.1.2 then responded. No data or credential reset was performed. Early checks exposed Kafka volume ownership, a migration version collision and Windows worker startup contention. Corrections preserve assertions; local frontend tests can run serially.

## H. Next milestone

Phase 6: extract the synchronous risk-policy port into Fraud Service; versioned rules, velocity reservations, explainable ALLOW/REVIEW/BLOCK, analyst cases and fail-closed dependencies.
