# ADR 0007: Customer confirmation and owned beneficiaries

Status: accepted for Phase 4 implementation. Synthetic identities and money only.

## Decision

Customer screens query the existing owner APIs. Account and ledger reads supply balances and confirmed activity; Payment supplies workflow status. A successful submission response establishes acceptance only. The UI displays completion after the owned payment resource reports COMPLETED with a journal reference.

Money remains exact decimal text at HTTP boundaries and uses integer minor units (BigInt) for client validation, aggregation and formatting. Different currencies never share a total. Missing balances, pending provisioning and incomplete account pages withhold aggregate totals. Monthly activity queries all confirmed postings for the chosen account and UTC month, independently of history pagination. It is labeled money in/out, including funding and compensation, rather than a fabricated spending classification.

An immutable transfer attempt contains its original key and payload, scoped to the verified subject in browser session storage. It is written before submission. Reloads and network-response loss reuse that identity; the UI cannot edit an ambiguous request into a different payload. Tokens remain in memory. A storage-write failure prevents submission. A definitive preacceptance 400/403/404/422 response on the first submission allows an explicit new draft; a refusal on a later retry cannot establish the outcome of an earlier ambiguous submission, so that attempt stays locked; ambiguous network/503 and identity-conflict responses retain the original attempt.

Recovery storage lasts for the current browser tab. Accepted operations remain on the server after sign-out or tab closure; customers should inspect their transfer list before creating another operation after losing an unconfirmed tab. This is not cross-device draft synchronization. One unresolved draft is supported per subject and tab.

The new Beneficiary Service owns a separate database and runtime/migrator credentials. Its narrow confidential client can read private Account metadata to verify a destination at creation, without ledger posting or payment operator permissions. Public calls derive address-book ownership from validated JWT claims; injected owner fields are rejected. Label/status edits use optimistic versions; the destination is immutable. Disablement prevents selection for new UI transfers and does not cancel existing payments or financially freeze the destination account.

The beneficiary runtime cannot alter identities, delete rows, fabricate history, truncate or create schema objects. A validation trigger protects identity and versions. A separate AFTER trigger appends audit facts as the schema owner. AFTER is necessary: PostgreSQL invokes BEFORE INSERT triggers before conflict handling, so audit writes in a BEFORE trigger would break idempotent insert replays. Failed statements and transactions roll back both entity and audit changes. Insert conflict handling covers all unique indexes before the store distinguishes original-key replay from an already-saved destination. Restricting the conflict target to one index can still fail on a different unique index during simultaneous identical inserts.

## Consequences and limits

- History is chronological with an account-scoped cursor, server-side filters, linked compensation references and a CSV export explicitly limited to loaded entries.
- Transfer lists include pending/failed/rejected states and a server-side status filter. Cancellation uses the latest version and never claims success on a conflict.
- Customer query caches are subject-scoped and cleared on loss of authenticated state. Polling refreshes known workflow resources and effective account restrictions.
- Stored beneficiary labels and references are simulation data. No real payee certification, KYC, bank integration, FX conversion or card credentials are represented.
- Cards, statement generation and notification delivery retain their later roadmap milestones. No Kafka publisher or operational staff mutation UI is added here.
