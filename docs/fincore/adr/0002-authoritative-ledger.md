# ADR 0002 — One authoritative, immutable double-entry ledger

Status: accepted on 2026-10-22, following approval to start Phase 1.

## Context

Balance mutation in multiple services would permit divergence and overspending. Application checks alone cannot protect invariants against concurrent replicas or SQL bugs.

## Decision

Only Ledger can post money. Use exact BigDecimal values, a restricted initial currency set, immutable balanced journals and compensating reversals. SQL functions, restrictive runtime grants, local constraints and deferred journal checks protect the financial boundary. Ledger-local aggregates and guards are updated under deterministic row locks in the same transaction as postings, outcome and outbox. Account projections are derived. Account restriction workflows acknowledge financial effect only after the ledger guard commits.

## Alternatives and consequences

Redis locks or Java mutexes cannot replace database invariants across independent instances. Optimistic versioning is possible but requires retry behavior under contention; explicit row locks make the initial protocol easier to inspect. Global serializable isolation is unnecessary if all financial mutation paths obey tested local locks, though predicates/counters must be separately protected. A compromised database owner remains outside the runtime immutability guarantee. See the [full protocol](../financial-core.md) before implementation.
