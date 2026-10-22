# FinCore

Distributed digital banking and payment processing **simulation**, using synthetic customers and simulated money exclusively.

**Status: Phase 0 — architecture proposed, awaiting approval.** No banking application, financial API, UI, deployment or benchmark has been implemented. The repository retains its existing GitHub name, `task-app-management`; renaming it is a separate decision.

## Architecture review

Start with the [Phase 0 review](docs/fincore/README.md), which covers the requested A–H milestone report and approval decisions.

| Document | Contents |
| --- | --- |
| [Master specification](docs/fincore/specification.md) | Original supplied requirements, preserved verbatim |
| [Repository assessment](docs/fincore/repository-assessment.md) | Actual starting point, tooling and constraints |
| [Architecture proposal](docs/fincore/architecture.md) | Service boundaries, deployment model and Mermaid diagrams |
| [Financial core](docs/fincore/financial-core.md) | Accounting, schema, concurrency, idempotency and recovery |
| [API and event contracts](docs/fincore/contracts.md) | Proposed requests, errors and event delivery |
| [Security and risks](docs/fincore/security.md) | Authorization, transfer threat model and trade-offs |
| [Roadmap](docs/fincore/roadmap.md) | Prioritized milestones, exact Phase 1 scope and validation gates |
| [Architecture decisions](docs/fincore/adr/) | Proposed ADRs, all pending approval |

## Proposed stack

Java 21, Spring Boot 3.x, Spring Cloud Gateway, Keycloak, PostgreSQL, React, TypeScript, Vite and Tailwind CSS. Kafka, Redis, observability and Kubernetes are introduced when their milestones need them. Exact dependencies and compatible release trains will be pinned and verified in Phase 1.

The ledger owns all financial postings. Customer and account balance screens are read models; payment success requires authoritative ledger confirmation. Services are independently deployable and added incrementally, rather than creating eleven empty implementations.

## Running and testing

There is no application to run yet. Phase 1 will provide documented Docker Compose startup, health checks, authenticated browser-to-API communication and CI. Phase 2 will introduce and test the financial core before any transfer feature is presented as functional.

Do not use the Core5G simulator from the previously selected workspace as FinCore runtime or evidence: it is a separate project and repository.

## GitHub workflow

Use a dedicated branch for each milestone and cohesive Conventional Commits. Push reviewed changes to this repository, open a pull request when requested, and retain verification evidence with the milestone. Architecture approval is required before application implementation. Push authorization does not imply permission to merge or deploy.

