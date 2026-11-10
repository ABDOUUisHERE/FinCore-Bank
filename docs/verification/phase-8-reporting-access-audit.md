# Phase 8 lot 10 — Reporting access audit

Accepted feature application source `fe0a7b4003fb24a6a30412177ac9044f493bf0cf` on November 9, 2026 UTC. Phase 8 remains incomplete. Integrated as `7f0998cd5e154ecb3784779cdb64129484ef7aa2` on main; all three independent main checks pass. [Machine-readable evidence](../evidence/phase-8-reporting-access-audit-checks.json).

Reporting now commits a redacted immutable receipt before disclosing registered statement list/detail/CSV/PDF/create-request and separate finance reconciliation/journal responses. Current signed caller identity never comes from an owner header. No statement bytes/hash, amounts, month/body/filter text or credentials enter the receipt. A four MiB disclosure-buffer ceiling discards oversized prepared content, and failed local receipt persistence discards the body and private attachment filename as sanitized 503. The [operational contract and preserved upgrade](../deployment/reporting-access-audit-phase-8.md) defines all seven categories, outcome/trust/timeout limits and recovery.

STATEMENT_CREATE_REQUEST and RESPONSE_READY assert a prepared creation-request response, not a newly created snapshot. Native signed HTTP against real PostgreSQL proves that a snapshot committed before audit failure survives the withheld response; retry against the unchanged source reuses its original identity, bytes and content hash. A changed authoritative source can produce another immutable snapshot under the existing content-hash deduplication policy. Actual CSV/PDF INSERT failure and restored export retention are also exercised. Receipt writes use a separate three-second transaction and preserve enforced read-only rollback. Central Audit accepts only reporting.access.recorded for the new prefix and rejects foreign action/reference claims. See the [versioned producer schema](../../contracts/events/reporting-access-recorded-v1.json).

| Check | Measured result |
| --- | --- |
| Backend quality | 342 Java cases / 66 classes; zero failures, errors or skips |
| Frontend quality | 64 cases / 13 files; typecheck, lint, both builds and severity gate pass |
| Fresh Compose | 14 hosted PKCE browser journeys, four real owner receipt/replay/storage-recovery probes, producer isolation, Ledger smoke and Kafka ACL/DLQ/replay pass |
| Native Java | 53 unique targeted cases / 10 classes, with seven focused reruns after the cache-header correction; includes buffer bytes/writer/declared length, all seven signed categories, committed-snapshot recovery, PostgreSQL immutability/read-only/lost-ack/upgrade and three previous owner regressions |
| Native broker/storage | Customer/Account/Payment/Reporting direct owner enforcement, source delivery, central digest/redaction, duplicate/conflicting/invalid replay and real INSERT loss/recovery; Reporting→Payment/Ledger, Payment→Reporting and Audit producer attempts denied |
| Native hosted browser | All 3 pass: card/statement/finance with exact redacted receipts and original snapshot reuse, business inspection with Ledger outage/restore, Customer search; existing responsive assertions remain |
| Preserved upgrade | Reporting V1→V2, no historical receipt backfill; statement snapshot bytes/hashes, existing business/source/delivery rows, three owner receipt tables, three grant families and 88 command receipts unchanged before browser fixtures. All old configuration values and credential/realm bytes preserved; only the new Reporting Kafka key appended |
| Native health | 16 running services healthy |

- [Backend quality](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/42666972299) — success.
- [Frontend quality](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/42666972272) — success.
- [Foundation integration](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/42666972955) — success.

## Failure investigation and corrections

The [initial feature integration](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/42658853292) passed all four owner storage/broker probes and 13 browser journeys, but the existing export assertion observed Cache-Control no-store twice. A direct signed MVC test using all header values reproduced the exact failure without Gateway. The receipt filter and export controller both supplied the header. Removing the redundant controller header leaves the filter-owned policy in place. All six signed HTTP cases and the actual PostgreSQL creation/export case pass with strict single-value assertions; the original browser assertion is preserved. Native totals are 53 unique cases, with seven focused reruns after this correction rather than additional cases.

The [second feature integration](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/42662601746) reached the finance denial but the test incorrectly expected an owner receipt for a request rejected earlier at Gateway. A minimal real hosted PKCE request reproduced Gateway 403 with no owner receipt, while finance 200 retained and delivered exactly one. The Gateway forbidden assertion is kept and the erroneous owner claim is removed. A real signed HTTP/PostgreSQL assertion now proves the owner 403 separately. An initial native analyst-control wait did not recur in isolated role/API/case inspection; native browser counts consolidate the two successful original journeys and the successful focused card journey, without relaxing its action deadline.

The first local compilation lacked shared-library test dependencies. After adding their test-scoped starter, one method-denial fixture sent a POST without a body length and received the existing early 411 framing rejection. Supplying a valid JSON body exercises the intended 403 authorization denial. The original assertion remains strict. The final complete targeted invocation passes all 53 cases; earlier attempts are not counted as additional tests.

## Remaining acceptance

The finite registry excludes Gateway and early servlet/firewall/framing failures and other routes. A receipt proves pre-transport preparation/decision, not browser delivery or new financial state. Central signed-actor provenance remains the Reporting producer assertion. Runtime receipt rows and statement snapshots are immutable, while trusted database/schema administration, capacity/retention and connection/CPU/network contention remain limits. Broker restart preserves its existing data volume and identities; Reporting receives no Payment/Ledger producer authority.

The [coverage inventory](phase-8-audit-coverage.md) retains other critical actions/security and identity-provider gaps. Collected HTTP/Kafka tracing, six observed dashboards and remaining administration are separate Phase 8 gates; Phases 9–11 remain subsequent milestones.

## Independent main integration

Merged and pushed as [7f0998c](https://github.com/ABDOUUisHERE/FinCore-Bank/commit/7f0998cd5e154ecb3784779cdb64129484ef7aa2). Main independently reran 342 Java cases / 66 classes, 64 frontend cases / 13 files and all 14 hosted PKCE browser journeys, plus the four real owner receipt/replay/storage-recovery probes, producer isolation and Audit non-producer checks, Ledger smoke and Kafka ACL/DLQ/replay checks. All passed. Final native observation still has 16 healthy running services and unchanged post-upgrade configuration and original credential bytes. This documentation proof introduces no runtime change.

- [Frontend quality on main](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/42793120344) — success.
- [Foundation integration on main](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/42793120235) — success.
- [Backend quality on main](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/42793120295) — success.

GitHub REST attributes all 15 commits since the previous main to ABDOUUisHERE on the non-fork default branch. The contribution calendar is observed independently with its timestamp in the evidence, not inferred from commit count. The final documentation proof follows that merge observation. GitHub [documents an indexing delay of up to 24 hours](https://docs.github.com/en/account-and-profile/how-tos/contribution-settings/troubleshooting-missing-contributions); an observed calendar value is not replaced by an expected count.

Commit author dates (UTC): 2026-11-09: 13; 2026-11-10: 2. At the 2026-11-10T09:44:10.418093+00:00 observation, GitHub reported 2 calendar contributions for 2026-11-10. These are dated observations, not a promise that all lot commits count on the same day.
