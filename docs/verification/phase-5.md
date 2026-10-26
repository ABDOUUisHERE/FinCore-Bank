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

Local acceptance and all three GitHub workflows passed for source commit `b0cca942ff91cd5efb862dbc8f071267133607d3`. Main integration fast-forwards this validated source plus verification documentation.

- [Backend quality](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/40322494942): SUCCESS; full default Maven reactor, 109 tests, zero failures/errors/skips.
- [Frontend quality](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/40322495002): SUCCESS; typecheck, lint, 20 component cases, both builds and zero audit vulnerabilities.
- [Foundation integration](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/40322494911): SUCCESS; fresh Compose startup, independent authentication, private ledger journey, six browser journeys and private Kafka ACL/DLQ/replay acceptance.

Machine-readable results: [phase-5-checks.json](../evidence/phase-5-checks.json). Exact changed-file list: [phase-5-files.txt](phase-5-files.txt). The source base is the completed Phase 4 commit `bf5ec14577337fdce5bc5fc0a4e11dcf66be281c`.

## G. Observed verification

| Executed check | Result |
| --- | --- |
| Focused default Maven verify, Messaging and Notification with dependencies | PASS: four real Kafka/PostgreSQL delivery tests, seven processor/security cases and six Notification PostgreSQL cases |
| Gateway Maven verify | PASS: eight cases, including three quota regression tests |
| Latest local backend reports across runs | PASS: 109 cases, zero failures/errors/skips; prior unchanged financial suites plus the successful focused checks above |
| Frontend components, typecheck, lint and audit | PASS: 20 cases; serial workers locally on Windows, unchanged normal default in GitHub; zero vulnerabilities |
| Application images and existing-volume startup | PASS; 12 runtime services healthy on Engine 29.1.2 |
| Existing Notification schema upgrade | PASS: Flyway V1 to V2, with retained watermark rows; only Notification was quiesced for its key change |
| Full final local browser command, retries disabled | PASS: six journeys in 4.5m after the Gateway quota fix |
| Private broker ACL and poison/replay command | PASS: foreign Payment publication denied to Customer; bounded retry, persistent DLQ and audited unchanged replay |
| Visual inspection | PASS: actual synthetic notification inbox/preferences/email at 1440 and 390 pixels; overflow assertions also at 320 pixels |

Local backend totals combine reports from separate runs. The earlier full local reactor stopped on a Notification read-body assertion; subsequent focused checks passed. This is not presented as one successful full local reactor. The final GitHub Backend workflow independently executed the complete default reactor and all 109 cases on the exact source above.

The broker outage journey stops only FinCore Kafka, completes and independently verifies a real synthetic transfer while publication is unavailable, reloads and reuses the original request/key, then resumes the same persistent broker. It requires one notification and one simulated email for that transfer. The recipient remains on the same page and receives the update through authenticated SSE. Review/cancellation, history/export, account guards and beneficiary edits retain their prior checks.

Early execution exposed Kafka volume ownership, Windows CRLF in an init script, a colliding Payment migration version, missing empty JSON in the read request, and readiness checks racing cold Java startup. These were corrected without deleting data, changing earlier financial migrations or bypassing health checks. Windows frontend worker contention was handled by serial local execution; normal CI execution passes the same cases.

One GitHub attempt counted the same simulated email twice because a locator selected both nested sections; selecting the actual email panel preserved the expected count of one. A later fast CI run exposed the original shared 300-request Gateway quota: one customer's traffic denied another customer's normal request with 429. Quotas now retain 300 requests per minute per verified subject, reject forged identity/forwarding headers, expire old buckets and bound storage. Three new tests cover these properties. The final unchanged browser assertions and all workflows pass.

Watermark review also found that unrelated producer streams could share an opaque UUID. V2 keys versions by producer kind and aggregate ID; a database regression test proves that a Customer version cannot suppress a Payment notification. SSE timeout completion and concurrent per-subject stream admission were tightened.

An initial WSL startup failure was recovered by stopping Desktop, releasing stopped WSL instances and restarting. A later interrupted attempt received Docker's explicit 503 'manually paused' response; it did not execute the integration tests. The successful checks ran after Engine readiness returned. One earlier local beneficiary attempt had an unavailable response, and one CLI ACL probe lacked the expected diagnostic; no separate root cause is claimed for these two attempts. Explicit 201 beneficiary checks, direct authorization denial and subsequent strict local/GitHub acceptance passed without automatic retries or relaxed assertions.

The stack remains running with matching private configuration and synthetic datasets. No named volumes were deleted, existing credentials rotated or unrelated Commerce containers modified. Credentials, bearer values and authenticated captures are excluded from committed evidence.

## H. Next milestone

Phase 6: extract the synchronous risk-policy port into Fraud Service; versioned rules, velocity reservations, explainable ALLOW/REVIEW/BLOCK, analyst cases and fail-closed dependencies.
