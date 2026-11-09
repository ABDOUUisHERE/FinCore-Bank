# Phase 8 lot 9 — Account and Payment administrative read audit

Accepted feature application source `ced831a9750084e50e5e1fd269997e07fb7116df` on November 9, 2026 UTC. Phase 8 remains incomplete. Integrated as `90874ca99b3a02732f92b1bdee50e5413291283e` on main; all three independent main checks pass. [Machine-readable evidence](../evidence/phase-8-owner-read-audit-checks.json).

Account and Payment ADMIN single-reference inspections now retain a committed immutable owner receipt before response disclosure, including signed role denials and invalid/absent credentials reaching their finite registry. Current staff provenance is independent of the account owner and original payer. Payment history pages retain no cursor, amount, instruction or reference text. Customer keeps its accepted event/schema shape while sharing the tested Java mechanism.

A separate three-second REQUIRES_NEW receipt transaction preserves actual read-only financial snapshots and can survive their rollback. Both JDBC and transaction begin/commit failure withhold the prepared body as sanitized 503. The [operational contract and upgrade](../deployment/owner-read-audit-phase-8.md) defines route exclusions, timeout/trust limits and recovery; the [versioned event schema](../../contracts/events/owner-access-recorded-v1.json) binds actions and references to their owner. RESPONSE_READY means a pre-transport prepared response, including an Account 200 with an unavailable Ledger observation; it asserts neither delivery nor an effective financial state.

| Check | Measured result |
| --- | --- |
| Backend quality | 323 Java cases / 63 classes; zero failures, errors or skips |
| Frontend quality | 64 cases / 13 files; typecheck, lint, both builds and severity gate pass |
| Fresh Compose | 14 hosted PKCE browser journeys, all three owner receipt/replay/storage-recovery probes, cross-owner producer denials, Ledger smoke and Kafka ACL/DLQ/replay checks pass |
| Native Java | 52 targeted cases / 11 classes, including Customer compatibility, two signed owner security chains, real PostgreSQL read-only/rollback and a real signed Payment inspection with revoked/restored INSERT |
| Native broker/storage | Customer/Account/Payment direct-owner enforcement, immutable source delivery, central digest/redaction, identical replay, conflicting/invalid quarantine and real 503/recovery; Account↔Payment publication denied |
| Native browser | All 5 hosted PKCE journeys pass: business inspection, Customer search and scoped Account/Payment/Customer support |
| Preserved upgrade | Account V2→V3 and Payment V4→V5; Customer remains V4. Business rows, old source/delivery bytes, Customer receipts, three grant families, 67 original command receipts and credential/environment bytes unchanged before browser fixtures; no read receipt backfill |
| Native health | 16 running services healthy |

- [Backend quality](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/42648209420) — success.
- [Frontend quality](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/42648209679) — success.
- [Foundation integration](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/42648209179) — success.

## Scope and remaining acceptance

The shared filter buffers only registered bounded synchronous responses. Existing support admission/final audit decisions and internal machine scopes retain their separate authority. Runtime receipt rows are immutable; trusted schema/database administration, Kafka capacity/retention and network/transport uncertainty remain explicit limits. Central Audit verifies owner-specific typed metadata but trusts the producing owner for signed actor provenance. Duplicate HTTP requests create separate receipts; broker redelivery deduplicates one event identity/body.

The [coverage inventory](phase-8-audit-coverage.md) retains missing actions/exports, Gateway/early servlet denials and identity-provider event retention. Collected HTTP/Kafka tracing, six observed dashboards and remaining administration are separate Phase 8 gates. Kubernetes/Helm, deployment hardening, performance acceptance and portfolio release remain Phases 9–11.

## Independent main integration

Merged and pushed as [90874ca](https://github.com/ABDOUUisHERE/FinCore-Bank/commit/90874ca99b3a02732f92b1bdee50e5413291283e). Main independently reran 323 Java cases / 63 classes, 64 frontend cases / 13 files and all 14 hosted PKCE browser journeys, plus the three real owner receipt/replay/storage-recovery probes, cross-owner producer denials, Ledger smoke and Kafka ACL/DLQ/replay checks. All passed. Final native observation still has 16 healthy running services and the original environment/credential bytes. This documentation proof introduces no runtime change.

- [Backend quality on main](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/42651203598) — success.
- [Frontend quality on main](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/42651203554) — success.
- [Foundation integration on main](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/42651203481) — success.

GitHub REST attributes all 12 commits since the previous main to ABDOUUisHERE on the non-fork default branch. The contribution calendar is observed independently with its timestamp in the evidence, not inferred from commit count. The final documentation proof follows that merge observation. GitHub [documents an indexing delay of up to 24 hours](https://docs.github.com/en/account-and-profile/how-tos/contribution-settings/troubleshooting-missing-contributions); an observed calendar value is not replaced by an expected count.
