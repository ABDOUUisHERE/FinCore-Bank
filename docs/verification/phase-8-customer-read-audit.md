# Phase 8 lot 8 — Customer administrative read audit

Accepted feature application source `e0d2a60ddf26e3571ca675849e59151b6458a7e8` on November 9, 2026 UTC. Phase 8 remains incomplete. Integrated as `11707a27c3f9c8b6b781ad53611b70faa717907e` on main; all three independent main checks pass. [Machine-readable evidence](../evidence/phase-8-customer-read-audit-checks.json).

Customer ADMIN name search and single-reference inspection now buffer their bounded synchronous responses until an immutable owner audit receipt commits. Anonymous/invalid credentials, signed role denials, invalid input, missing references and server outcomes use a finite registry. Audit storage failure replaces the prepared response with sanitized 503; broker/central outages leave a durable outbox source for retry. Names, prefixes, bodies, credentials and raw URLs are absent from the source projection.

Only a verified, safe opaque JWT subject becomes a signed actor. Unknown identity never falls back to a forged header or producer-reported owner. Central provenance remains the trusted Customer producer assertion. RESPONSE_READY is a pre-transport decision, not proof that a browser received the data. The [contract and upgrade guide](../deployment/customer-access-audit-phase-8.md) defines exact route exclusions, outcomes, trust and recovery limits; the [event schema](../../contracts/events/customer-access-recorded-v1.json) defines its versioned shape.

| Check | Measured result |
| --- | --- |
| Backend quality | 303 Java cases / 60 classes; zero failures, errors or skips |
| Frontend quality | 64 cases / 13 files; typecheck, lint, both builds and severity gate pass |
| Fresh Compose integration | 14 hosted PKCE browser journeys, direct-owner enforcement, Ledger smoke, Kafka ACL and DLQ/replay checks pass |
| Native Java | 53 unique cases / 14 classes across focused successful class results; existing failed container startup was rerun successfully |
| Native broker/storage | Actual owner receipt delivery, byte-identical duplicate, conflicting/invalid quarantine, 503 on unavailable INSERT, restored privilege and retained recovery pass |
| Native browser | Customer name search/inspection receipt, business inspection with Ledger outage/restore, and scoped Customer support: all 3 pass |
| Native preserved upgrade | Customer V3 to V4; profile bytes, historical domain envelope/delivery bytes, three grant families, 60 original receipts, environment and credentials unchanged before browser fixtures |
| Final native health | 16 running services healthy |

- [Frontend quality](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/42615122374) — success.
- [Backend quality](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/42615122591) — success.
- [Foundation integration](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/42615122445) — success.

## Failure investigation and correction

The [initial feature integration](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/42612701260) passed storage/broker recovery and 13 browser journeys, but the new inspection receipt assertion failed. Gateway and Customer each added the same response correlation header; the browser combined its two values while the local and central receipt held one valid identifier. The native browser reproduced the failure, and a direct query confirmed the 200 inspection receipt already existed centrally. Two fast Gateway tests reproduced the duplicated response-header pattern before the fix. Gateway now normalizes its forwarded correlation value once at final response commit. All 15 native Gateway cases pass, and the original hosted browser path passes with an explicit one-header/valid-format assertion. No matching assertion or timeout was weakened.

An early native PostgreSQL assertion examined the wrapper exception instead of its SQL cause; the corrected test verifies SQLSTATE 42501. A separate existing PostgreSQL Testcontainer exceeded readiness during concurrent image compilation. After compilation, its five cases and the six central metadata cases passed in a clean focused invocation. Native totals use the latest successful result per class and do not count retries as new tests.

## Scope and remaining work

Customer migration V4 preserves the old domain envelope and acknowledged delivery bytes, adding a separate append-only receipt source to the existing publisher. The runtime cannot update/delete/truncate receipts or disable their guards; schema/database administrators remain trusted. Synthetic conflict and invalid-record probes retain only hashes/coordinates in central quarantine. This acceptance does not certify Gateway-denied attempts, early servlet/firewall/body-limit rejections, other owner reads/actions, identity-provider events, historical reconstruction, independent immutable backups, tracing or dashboards. The [audit inventory](phase-8-audit-coverage.md) and [Phase 8 coverage](phase-8-progress.md) retain those gaps.

## Independent main integration

Merged and pushed as [11707a2](https://github.com/ABDOUUisHERE/FinCore-Bank/commit/11707a27c3f9c8b6b781ad53611b70faa717907e). Main independently reran 303 Java cases / 60 classes, 64 frontend cases / 13 files and all 14 hosted browser journeys, plus the actual audit storage/replay probe, Ledger smoke and broker ACL/DLQ/replay checks. All passed. Final native observation still has 16 healthy running services and the original environment/credential bytes. No financial/source/runtime changes were introduced by this documentation proof.

- [Frontend quality on main](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/42642070434) — success.
- [Backend quality on main](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/42642070457) — success.
- [Foundation integration on main](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/42642070440) — success.

GitHub REST attributes all 13 commits since the previous main to ABDOUUisHERE on the non-fork default branch. The contribution calendar is an independently observed value recorded with its timestamp in the evidence; it is not inferred from the number of commits. The final documentation proof is created after that merge observation.
