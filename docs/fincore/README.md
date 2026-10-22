# Phase 0 — technical architecture review

Date: 2026-10-22 (UTC). Status: **proposed; not approved**.

## A. Current state

The user-selected GitHub destination is `ABDOUUisHERE/task-app-management`. At inspection its `main` branch contained only `.gitattributes` and a one-line README, with initial commit `201a81e933d299a1bd65d096a2e7a9adaa6fd575`. There is no FinCore code, build, database or CI. The original workspace contains an unrelated Go simulator; its history and runtime are not imported. See the [assessment](repository-assessment.md).

## B. Proposed implementation

Use a domain-driven microservice architecture with a small initial deployment: Keycloak, gateway, customer service and two thin React shells. Add the ledger next, followed by account and payment services. Choose independent services over a modular monolith because the specification explicitly requires service ownership and distributed failure behavior. Accept additional local infrastructure cost, and control it by introducing modules only when needed.

The [architecture](architecture.md) defines every required service, communication boundary and diagram. The [financial core](financial-core.md) defines exact decimal accounting, immutable double entry, a single authoritative ledger, database locks, durable idempotency and timeout recovery. [Contracts](contracts.md) separate immediate financial commands from asynchronous projections. [Security](security.md) covers roles, ownership, restriction races and threat controls.

## C. Files to create or modify

Phase 0 changes `README.md` and creates these documents only:

- `docs/fincore/specification.md`: original supplied specification.
- `docs/fincore/README.md`: this A–H review and approval gate.
- `docs/fincore/repository-assessment.md`: repository and environment evidence.
- `docs/fincore/architecture.md`: boundaries, service matrix and diagrams.
- `docs/fincore/financial-core.md`: schema, invariants and recovery protocol.
- `docs/fincore/contracts.md`: proposed public/internal APIs and versioned events.
- `docs/fincore/security.md`: security model and architectural risks.
- `docs/fincore/roadmap.md`: milestones, tests and exact first implementation scope.
- `docs/fincore/verification.md`: executed documentation checks and delivery evidence.
- `docs/fincore/adr/0001-incremental-microservices.md`: deployment choice.
- `docs/fincore/adr/0002-authoritative-ledger.md`: accounting and concurrency.
- `docs/fincore/adr/0003-idempotency-and-events.md`: recovery and delivery guarantees.
- `docs/fincore/adr/0004-identity-and-authorization.md`: identity and permissions.

No application code, migrations, infrastructure manifests or empty service directories are generated. Replacing the original one-line README makes the proposal discoverable; no existing application is removed.

## D. Risks and trade-offs

The highest risks are duplicate postings after a timeout, concurrent overspending, authorization changes racing a posting, silent event loss, and migration privileges defeating immutability. The proposal addresses each with an explicit owner, transaction boundary and required test in the [risk register](security.md). Independent services cost more operationally than a monolith. No distributed ACID transaction, global Kafka ordering or end-to-end exactly-once delivery is claimed.

## E. Test strategy

For Phase 0: check relative documentation links, parse authored Mermaid diagrams, check whitespace and confirm that only documentation is staged. Specification diagrams are preserved as received. No application test or runtime result can exist yet. Phases 1–3 require real authentication, PostgreSQL-backed invariant tests, concurrent writers and crash/retry tests; see the [acceptance gates](roadmap.md).

## F. Implementation

Only the architecture deliverable is in scope. All ADRs and API/schema details are proposals. The first code milestone is defined precisely in the roadmap and requires architecture approval under master specification sections 0, 19 and 22.

## G. Verification

Repository inspection: `git status --short --branch`, `git log -5 --oneline`, `git remote -v`, `git ls-files`, `rg --files`, and reads of `.gitattributes` and `README.md`. Remote inspection: `git ls-remote --symref <destination> HEAD`, followed by a separate clone. Tool checks found Java 21.0.2, Maven 3.9.9, Node 24.13.0, npm 11.6.2, Python 3.14.2 and Docker CLI 29.1.2 in the host environment; GitHub CLI is absent. These observations do not certify dependency compatibility or Docker engine readiness.

Final documentation validation and Git delivery results are recorded in [verification.md](verification.md). Application tests, build, migrations and runtime: **not applicable / not executed**, because application implementation is explicitly deferred.

## H. Next milestone and approval

Approve the proposed architecture before Phase 1. The proposed baseline is independent services, MAD/EUR/USD with two decimal places and no FX, zero overdraft, full reversals only, simulated funding, and persistent financial idempotency identities. These are deliberately restricted initial business choices, not claims that all master-specification features already exist.

Approval should cover this baseline, the ledger restriction/ownership guard protocol and the [exact Phase 1 file list](roadmap.md). Future milestones receive their own scope and verification review. No merge, deployment or GitHub repository rename is part of this delivery.
