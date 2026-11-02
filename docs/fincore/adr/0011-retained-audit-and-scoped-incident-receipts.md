# ADR 0011: retained audit facts and scoped incident receipts

Status: accepted for Phase 8 lot 1. Date: 2026-11-02 UTC.

Audit is a Java/Spring Boot resource server with its own database and consumer-only Kafka identity. It observes published facts; it does not become a financial authority. Read/describe privileges cover only existing customer/account/ledger/payment/fraud/card topic prefixes and its consumer group. No producer, Ledger scope or other service database credential is issued.

Original source bodies are hashed and allowlisted before persistence. Opaque IDs/statuses and provenance are retained; customer names/contact details, arbitrary free text and unknown payload fields are omitted. Event IDs deduplicate only identical body hashes. Invalid schema/metadata/source or identity conflicts are durably quarantined by topic/partition/offset with a fixed reason and digest. Database/recovery failures prevent acknowledgment. Source-reported event time/actor and local observation time remain distinct. Retained Kafka events cannot reconstruct every historic/security action, and no exactly-once transport claim is made.

ADMIN reads global facts and manages all incidents. SUPPORT_AGENT operates only incidents created by the verified JWT subject. Every list, detail, history, status mutation and receipt replay enforces the same database owner predicate. Resource UUIDs are staff-reported references, not ownership assertions. Existing financial and analyst roles gain no implicit operations access; ADMIN gains no finance/analyst permission.

Incident state, append-only audit fact and exact original command receipt share one database transaction. Actor-global idempotency keys are locked before lookup; body fingerprints reject reuse. Expected versions and row locks admit only one concurrent transition. Replay returns the original receipt after later state changes, with access scope rechecked under the current role. The browser confirms structured changes, retains unknown-response commands under the service-verified subject and distinguishes recorded receipts from freshly fetched state.

Fact/quarantine writers take a shared transaction advisory lock before identity sequence allocation and retain it until commit. This prevents a delayed earlier append from becoming invisible behind an already-issued older-page cursor. It serializes local audit writes and sacrifices throughput; this low-volume demonstration makes no performance claim. Mutable incident worklists remain UUID-ordered live lists, not snapshots.

Database grants and immutable triggers reject ordinary rewriting/deletion even through the migration role. A privileged database administrator can change schema/triggers, so this is not tamper-proof evidence against database administration. Complete critical-action sources, scoped business inspection, distributed tracing and six observed dashboards remain subsequent Phase 8 lots.
