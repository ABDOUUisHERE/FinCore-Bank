# ADR 0006 — Durable authorization before asynchronous payment execution

Status: accepted for Phase 3 implementation, 2026-10-23 UTC.

Payment must not recover a customer debit by trusting a caller-supplied actor header or by retaining the customer's bearer/refresh token. A short-lived signed actor token alone cannot authorize a worker after a process restart.

Use Keycloak standard token exchange V2. The browser token includes the Payment requester audience; the confidential Payment client exchanges it for the Ledger audience. Ledger independently verifies issuer, signature, expiry, subject, authorized party and narrow prepare roles. Customer tokens may authorize transfers only; separately scoped technical simulation operators authorize funding/reversal. No naked impersonation, password grant or preview exchange feature is introduced for browser journeys.

Ledger first stores an immutable authorized instruction containing the canonical command, signed actor/issuer identity and safe payment metadata. This records customer authorization without moving money or reserving funds. Payment then persists its own workflow and runs validation/risk before invoking the instruction with its separate technical execute credential. Ledger derives the debit actor from its stored receipt, never from worker JSON, and checks ownership, guards and funds at execution. The worker has no generic posting privilege.

A process crash after authorization but before Payment persistence can leave an instruction without a local workflow. Payment scans its issuer-scoped instruction pages in repeated bounded cycles and imports missing workflows before execution. It never advances an irreversible high-water mark past potentially uncommitted records. A crash before authorization creates no financial action; retrying the original customer request is safe. A crash after ledger commit is resolved using the same instruction/business identity. No token is stored in either database.

Payment uses short local transactions, append-only status/outbox facts, lease tokens, compare-and-set state changes and bounded retries. No HTTP call runs inside its database transaction. Cancellation is permitted only before PROCESSING; ambiguous money outcomes remain PROCESSING. A stale lease holder cannot publish a newer local outcome. A ledger write trigger refuses an expired risk authorization at the journal insertion point; expiry leaves the identity reusable for reassessment. Definitive financial rejection remains immutable.

Account similarly records provisioning/restriction intent before calling Ledger, then reconciles retries against its durable external reference and ledger guard revision. Customer and Account own their respective databases; public object queries require the validated owner's subject. Ledger remains the balance authority.

A small `libraries/service-support` module shares only HTTP, token and error/security infrastructure between the new services, with no shared mutable domain or financial entities. Risk evaluation is a local policy port with explicit ALLOW/REVIEW/BLOCK outcomes and an expiry; extraction and richer fraud/velocity rules remain Phase 6. Review, simulation funding and full reversal are private technical operator functions; staff browser mutations/MFA remain Phase 8.

Reference: [Keycloak standard token exchange](https://www.keycloak.org/securing-apps/token-exchange) documents confidential requester authentication, requester audience membership and target audience filtering. Durable instruction receipts and recovery scanning are FinCore design choices.
