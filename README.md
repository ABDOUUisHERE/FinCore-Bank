# FinCore

Distributed digital banking and payment processing **simulation**, using synthetic customers and simulated money exclusively.

**Status: Phase 7 - cards, reservations and reconciled statements.** The connected customer workspace now supports synthetic cards, merchant authorization with explainable Fraud review, Ledger-owned holds, full capture/release/refund and immutable monthly CSV/PDF statements. Finance reconciliation requires an explicit role. Existing account, transfer, notification and analyst journeys remain covered. See the [Phase 7 verification report](docs/verification/phase-7.md), [Card/Reporting demo and upgrade](docs/deployment/cards-phase-7.md) and [customer demo](docs/deployment/customer-demo.md).

GitHub's canonical repository is `ABDOUUisHERE/FinCore-Bank`. Local Docker validation is recorded in the [workstation verification report](docs/verification/local-docker-2026-10-23.md).

## Quick start

Requires Git, Python 3.10+ and a running Docker Engine with Compose v2.

```sh
git clone https://github.com/ABDOUUisHERE/FinCore-Bank.git
cd FinCore-Bank
git switch main
python scripts/bootstrap-local.py
docker compose up --build -d --wait --wait-timeout 600
docker compose run --rm --no-deps ledger-check
```

Open **http://localhost:5173** for the customer workspace or **http://localhost:5174** for operations. Sign in as `customer.demo`, `admin.demo`, `analyst.demo` or `finance.demo` using their generated passwords in ignored `.local/credentials.json`. Only the analyst role opens Fraud cases/rules; explicit FINANCE_OPERATOR opens reconciliation. No shared passwords are committed. Use `localhost` exactly for OIDC redirect matching. [Setup and troubleshooting](docs/deployment/local-setup.md) explains startup, migration credentials, native development and persistent data. Persisted-volume upgrades for cards/statements use the [Phase 7 guide](docs/deployment/cards-phase-7.md).

Stop with `docker compose down`. The named PostgreSQL volume survives normal shutdown. This loopback-only environment uses simulated identities and development HTTP; external deployment is outside this milestone.

## Incremental architecture

![FinCore public workspace captured during the real native foundation verification](docs/evidence/foundation-desktop.png)

[Mobile capture](docs/evidence/foundation-mobile.png). These show the anonymous foundation UI; banking features are explicitly marked as upcoming. See the [verification report](docs/verification/phase-1.md) for capture environment and container test evidence.

```mermaid
flowchart LR
  Customer[Customer portal] --> Gateway[Spring Cloud Gateway]
  Admin[Operations portal] --> Keycloak
  Customer --> Keycloak
  Gateway --> Service[Customer resource server]
  Service --> CDB[(Private customer database)]
  Keycloak --> KDB[(Private identity database)]
  Migration[One-shot Flyway migration] --> CDB
  Gateway --> Account[Account service]
  Gateway --> Payment[Payment service]
  Gateway --> Beneficiary[Beneficiary service]
  Beneficiary --> BDB[(Private beneficiary database)]
  Beneficiary --> Account
  Account --> ADB[(Private account database)]
  Payment --> PDB[(Private payment database)]
  Account --> Ledger[Authoritative ledger]
  Payment --> Ledger
  Payment --> Fraud[Fraud service]
  Ledger -->|Verify risk grant| Fraud
  Gateway --> Fraud
  Fraud --> FDB[(Private fraud database)]
  Fraud --> Service
  Fraud --> Beneficiary
  Gateway --> Card[Card service]
  Card --> CardDB[(Private card database)]
  Card -->|Signed owner commands and reservations| Ledger
  Card --> Fraud
  Fraud -->|Canonical merchant context| Card
  Gateway --> Reporting[Reporting service]
  Reporting --> RDB[(Immutable statement snapshots)]
  Reporting -->|Owner snapshots and finance reconciliation| Ledger
  Ledger --> LDB[(Private ledger database)]
  Gateway --> Notification[Notification service]
  Notification --> NDB[(Private notification database)]
  Service --> Kafka[Private Kafka and durable delivery]
  Account --> Kafka
  Payment --> Kafka
  Ledger --> Kafka
  Fraud --> Kafka
  Card --> Kafka
  Kafka --> Notification
  Notification --> Account
  Operator[Private simulation operator] --> Payment
  Operator --> Keycloak
```

Gateway and each business resource server independently validate signed JWTs, issuer, audience, expiry and roles. Customer APIs derive identity from the token; administrator privileges do not imply customer or fraud analyst privileges. Portal tokens stay in memory. The analyst workspace uses independently authorized [Fraud APIs](contracts/openapi/fraud-v1.json). [Customer API contract](contracts/openapi/customer-v1.yaml): `GET /api/v1/customers/me` returns the authorized subject and recognized roles only.

Ledger also owns card controls and live reservations. All existing debits subtract those holds; capture removes its hold and books one balanced journal atomically. Full refunds compensate exactly once and retain daily usage. Card labels cannot function as payment credentials. Reporting stores immutable owner-authorized monthly snapshots with reconciled CSV/PDF exports; historical closing balances remain separate from currently available funds. See the [Card API](contracts/openapi/cards-v1.json), [Reporting API](contracts/openapi/reporting-v1.json) and [boundary ADR](docs/fincore/adr/0010-card-reservations-and-statement-snapshots.md).

## Architecture review

The architecture was approved before implementation. The [Phase 0 review](docs/fincore/README.md) is the historical proposal. [Phase 1 verification](docs/verification/phase-1.md) preserves foundation evidence; [Phase 2 verification](docs/verification/phase-2.md) records the ledger milestone, actual results and limits. [Account/Payment API](contracts/openapi/business-v1.yaml), [Ledger API](contracts/openapi/ledger-v1.yaml) and [write-boundary ADR](docs/fincore/adr/0005-ledger-write-boundary.md) describe its contract and guarantees.

| Document | Contents |
| --- | --- |
| [Master specification](docs/fincore/specification.md) | Original supplied requirements, preserved verbatim |
| [Repository assessment](docs/fincore/repository-assessment.md) | Actual starting point, tooling and constraints |
| [Architecture proposal](docs/fincore/architecture.md) | Service boundaries, deployment model and Mermaid diagrams |
| [Financial core](docs/fincore/financial-core.md) | Accounting, schema, concurrency, idempotency and recovery |
| [API and event contracts](docs/fincore/contracts.md) | Proposed requests, errors and event delivery |
| [Security and risks](docs/fincore/security.md) | Authorization, transfer threat model and trade-offs |
| [Roadmap](docs/fincore/roadmap.md) | Prioritized milestones, exact Phase 1 scope and validation gates |
| [Architecture decisions](docs/fincore/adr/) | Accepted decisions and implementation refinements |

## Proposed stack

Java 21, Spring Boot 3.5.16, Spring Cloud 2025.0.3, Keycloak 26.8.1, PostgreSQL 17.11, Kafka 4.2.2, React 19.3.1, TypeScript, Vite and Tailwind CSS. Dependencies are locked. Redis, full observability and Kubernetes remain later milestones.

The ledger owns all financial postings. Customer and account balance screens are read models; payment success requires authoritative ledger confirmation. Services are independently deployable and added incrementally, rather than creating eleven empty implementations.

## Running and testing

```sh
./mvnw -B -ntp verify
npm ci
npm run typecheck
npm run lint
npm test
npm run build
npm audit --audit-level=high
```

Use `mvnw.cmd` on Windows. Maven integration tests use real PostgreSQL through Docker; `-DskipITs` explicitly omits them. Once the stack is running, `docker compose run --rm --no-deps ledger-check` tests the private financial API with a real technical token. `python scripts/check-foundation.py` and `npm run test:e2e` check private Customer rejection and browser PKCE login (install Chromium with `npx playwright install chromium`). The browser suite also completes a two-customer financial API journey using real PKCE tokens and the private check runner. See the [test guide](docs/testing.md) and [current verification](docs/verification/phase-7.md) for actual outcomes. GitHub Actions runs backend, frontend and Compose/browser/ledger checks; no automatic deployment is configured.

Do not use the Core5G simulator from the previously selected workspace as FinCore runtime or evidence: it is a separate project and repository.

## GitHub workflow

Use a dedicated branch for each milestone and cohesive Conventional Commits. Push meaningful changes and retain measured verification evidence. Merge validated milestones when authorized by the repository owner; production deployment requires its own authorization. Documentation-only evidence commits identify the tested application source and do not invent extra implementation contributions.

