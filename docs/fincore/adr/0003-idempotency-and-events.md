# ADR 0003 — Durable intent, idempotent financial identity and outbox delivery

Status: proposed, awaiting user approval.

## Context

A network timeout does not reveal whether PostgreSQL committed. A Payment-to-Ledger-to-Kafka workflow cannot rely on a single distributed transaction or unproven exactly-once delivery.

## Decision

Commit Payment intent and its unique public idempotency mapping before financial commands. Reuse the payment UUID as internal ledger business reference and idempotency key. Store canonical payload fingerprints and atomic outcomes in PostgreSQL. Preserve key identities for the financial dataset lifetime. Unknown results remain pending and recover through status lookup and replay of the same command.

Write outbox rows atomically with each owner's changes. Publish with broker acknowledgment and bounded retries; duplicates are expected. Consumers deduplicate with their effect in one transaction and use aggregate versions for projections. Store outbox records in the early financial milestones even before the Phase 5 publisher exists.

## Alternatives and consequences

Expiring Redis-only keys lose financial identity. Publishing directly after commit can lose an event; publishing before commit can invent one. Kafka transactions alone do not atomically include every service database and browser response. Recovery workers and reconciliation add complexity, while supporting the real failure modes. No end-to-end exactly-once claim is made.
