# FINCORE — MASTER SOFTWARE REQUIREMENTS SPECIFICATION

**Project:** FinCore — Distributed Digital Banking & Payment Processing Platform  
**Version:** 1.0  
**Project type:** Enterprise Full-Stack / Distributed Systems / Fintech  
**Backend:** Java 21, Spring Boot 3.x compatible with Java 21  
**Frontend:** React, TypeScript, Vite, Tailwind CSS  
**Infrastructure:** Docker, Kubernetes, Kafka, PostgreSQL, Redis  
**Target:** Production-style portfolio project, operating exclusively with simulated money

---

# 0. YOUR ROLE AND WORKING INSTRUCTIONS

You are acting as a team of experienced software engineers:

- Principal Java Backend Engineer
- Distributed Systems Architect
- Senior React/TypeScript Engineer
- Banking Domain Architect
- Application Security Engineer
- DevOps and Kubernetes Engineer
- QA Automation Engineer
- Technical Documentation Engineer

Your responsibility is to design and implement FinCore as a complete, maintainable, testable, observable and professionally documented banking simulation platform.

This must not be a simple CRUD demonstration.

The project must demonstrate advanced software engineering concepts, including financial consistency, concurrency control, distributed messaging, security, failure recovery, performance engineering and cloud-native deployment.

## Mandatory working rules

1. Do not start implementing the entire application immediately.
2. First inspect the repository and existing code.
3. Identify the existing architecture, dependencies and technical constraints.
4. If the repository is empty, propose an initial architecture.
5. Create an implementation roadmap.
6. Explain the purpose and responsibility of every service.
7. Identify architectural risks and trade-offs.
8. Present the initial architecture for approval before implementation.
9. Work incrementally, one milestone at a time.
10. Before each major milestone, explain which files and modules will be created or modified.
11. Do not delete or rewrite existing code without explaining why.
12. Never claim functionality is complete without verification.
13. Write automated tests for critical business operations.
14. Maintain documentation as implementation progresses.
15. Never commit secrets, credentials or real financial information.
16. Do not automatically push, merge or deploy without explicit approval.
17. Keep the application runnable at the end of every completed milestone.
18. Prefer proven engineering patterns over unnecessary complexity.

**Important:** All accounts, money, cards, transactions and banking integrations must be simulated. No real banking network or payment provider integration is required.

---

# 1. PROJECT VISION

## 1.1 Overview

FinCore is a distributed digital banking simulation platform inspired by modern neobanking applications.

It allows customers to manage simulated accounts, transfer virtual funds, manage virtual cards, inspect transactions and receive real-time notifications.

Administrators can monitor financial operations, investigate suspicious activity, manage operational incidents and inspect audit trails.

The platform must combine:

- A modern banking user experience
- Reliable financial accounting
- Distributed backend services
- Secure identity and access management
- Event-driven communication
- Transaction monitoring and fraud detection
- Automated testing
- Cloud-native deployment
- Observability and operational tooling

## 1.2 Business objectives

The system must allow:

1. Customer registration and authentication.
2. Customer onboarding.
3. Creation and management of simulated bank accounts.
4. Creation and management of virtual wallets.
5. Simulated deposits and withdrawals.
6. Internal account-to-account transfers.
7. Simulated merchant payments.
8. Virtual card management.
9. Transaction history and downloadable statements.
10. Transaction notifications.
11. Fraud detection and investigation.
12. Administrative monitoring.
13. Financial reconciliation.
14. Operational incident management.
15. Financial and technical reporting.

## 1.3 Non-functional objectives

The application must be:

- Secure by design
- Consistent for financial operations
- Horizontally scalable where appropriate
- Fault-tolerant
- Observable
- Maintainable
- Testable
- Documented
- Reproducible locally
- Deployable on Kubernetes

---

# 2. USER ROLES

Implement role-based access control.

## CUSTOMER

A banking customer can:

- View personal profile
- Manage own accounts
- View available and booked balances
- Initiate transfers
- Manage beneficiaries
- View transaction history
- Manage simulated virtual cards
- View notifications
- Download statements
- Configure account security

## SUPPORT_AGENT

A support agent can:

- Search customers within authorized scope
- Inspect account status
- Review payment incidents
- Assist with account issues
- View limited transaction metadata

Support agents must not have unrestricted access to sensitive information.

## FRAUD_ANALYST

A fraud analyst can:

- Inspect fraud alerts
- Review suspicious transactions
- View risk scores
- Investigate alerts
- Record investigation decisions
- Escalate suspicious activity

## FINANCE_OPERATOR

A finance operator can:

- View accounting records
- Run reconciliation jobs
- Inspect ledger discrepancies
- Review settlement reports
- Investigate failed financial operations

## ADMIN

An administrator can:

- Manage operational users
- Assign authorized roles
- Configure non-financial platform settings
- Monitor services
- View system-wide operational dashboards
- Inspect audit trails

Sensitive administrative operations must require additional authorization and audit logging.

---

# 3. BACKEND ARCHITECTURE

## 3.1 Architectural approach

Use a domain-driven, distributed microservices architecture.

However, financial correctness is more important than maximizing the number of services.

The ledger must be the authoritative source for financial postings.

Do not implement independent balance mutations across multiple services.

The design must explicitly distinguish:

- Synchronous financial commands
- Asynchronous business events
- Financially authoritative state
- Derived read models
- Operational workflows
- Eventually consistent projections

Each service must own its data.

Do not use a shared mutable database schema across independent services.

## 3.2 Required services

### 3.2.1 API Gateway

Responsibilities:

- Single external API entry point
- Request routing
- Authentication enforcement
- Token validation
- Rate limiting
- Correlation IDs
- Request size restrictions
- Standardized error responses
- CORS configuration

Technology:

- Spring Cloud Gateway

Do not rely exclusively on the gateway for authorization. Backend services must independently enforce permissions.

### 3.2.2 Identity and Access Management

Use Keycloak as the identity provider.

Responsibilities:

- OAuth 2.0 / OpenID Connect
- Authorization Code Flow with PKCE
- Access token validation
- Role and scope management
- Multi-factor authentication support
- Session management
- Account security policies
- Administrative identity management

Do not create a custom password or token system when Keycloak provides the required functionality.

### 3.2.3 Customer Service

Responsibilities:

- Customer profiles
- Customer preferences
- Contact information
- Customer lifecycle
- Simulated KYC status
- Account ownership relationships
- Customer onboarding

Entities:

- Customer
- CustomerProfile
- CustomerPreferences
- KycCase
- CustomerStatusHistory

Expose REST APIs with validation and authorization.

### 3.2.4 Account Service

Responsibilities:

- Account creation
- Account ownership
- Account status
- Account metadata
- Account restrictions
- Account closure workflow
- Balance read projections

Entities:

- Account
- AccountOwner
- AccountRestriction
- AccountStatusHistory
- AccountBalanceProjection

The Account Service must not independently modify authoritative money balances.

### 3.2.5 Ledger Service — Critical Component

The Ledger Service is the financial core of the platform.

Implement an immutable double-entry ledger.

Core requirements:

- Every financial posting must balance.
- Total debits must equal total credits for each currency within a journal entry.
- Posted ledger entries must never be edited or deleted.
- Corrections must use compensating entries.
- Ledger operations must be atomic.
- Financial posting must be idempotent.
- Concurrent operations must not corrupt financial state.
- Financial balances must be derivable from ledger postings.
- Every posting must have a traceable business reference.
- Ledger records must support reconciliation and audit.

Core entities:

- LedgerAccount
- JournalEntry
- LedgerPosting
- PostingBatch
- FinancialOperation
- IdempotencyRecord
- LedgerBalanceProjection

Required fields include:

- Unique identifiers
- Currency
- Monetary amount
- Debit or credit direction
- Business reference
- Transaction timestamp
- Posting status
- Correlation identifier
- Idempotency key

Use BigDecimal or an explicitly documented integer-minor-unit representation.

Never use float or double for monetary accounting.

Define a currency-aware monetary value object and explicit rounding rules.

Use PostgreSQL transactions and database constraints to protect financial invariants.

Design the posting API so a journal entry and all its postings commit atomically.

Define how overdrafts and available funds are prevented under concurrent operations.

Use database row locks, optimistic concurrency control or another justified mechanism.

### 3.2.6 Payment Service

Responsibilities:

- Payment initiation
- Payment validation
- Transfer orchestration
- Payment lifecycle
- Idempotency
- Failure handling
- Payment cancellation when permitted
- Payment reversal workflows
- Payment status tracking

Supported operations:

- Internal transfer
- Simulated deposit
- Simulated withdrawal
- Simulated merchant payment
- Simulated refund

Payment states:

- CREATED
- VALIDATING
- PENDING_RISK_REVIEW
- PROCESSING
- COMPLETED
- FAILED
- REJECTED
- REVERSAL_PENDING
- REVERSED

Define permitted state transitions explicitly.

The Payment Service must coordinate financial operations through the Ledger Service.

It must never declare a payment completed before authoritative posting is confirmed.

### 3.2.7 Beneficiary Service

Responsibilities:

- Add beneficiaries
- Update beneficiary metadata
- Verify account references
- List beneficiaries
- Disable beneficiaries
- Maintain beneficiary audit history

Implement safeguards against unauthorized beneficiary changes.

### 3.2.8 Card Service

Responsibilities:

- Issue simulated virtual cards
- Freeze and unfreeze cards
- Simulate card authorizations
- Configure spending limits
- Display masked card information
- Maintain card activity history

Card states:

- ACTIVE
- FROZEN
- EXPIRED
- TERMINATED

Do not generate or process real payment-card credentials.

Use synthetic identifiers and non-functional card representations.

### 3.2.9 Fraud Detection Service

Implement an extensible rule-based fraud detection engine.

Initial detection rules:

- Excessive transaction frequency
- Unusually large transfers
- Rapid repeated transfers
- New beneficiary with high-value transfer
- Unusual simulated geographic activity
- Multiple failed payment attempts
- Sudden transaction velocity changes

Fraud decisions:

- ALLOW
- REVIEW
- BLOCK

Entities:

- FraudRule
- FraudAlert
- RiskAssessment
- FraudCase
- InvestigationDecision

Provide configurable thresholds.

Use a clear, explainable risk scoring mechanism.

Fraud evaluation must be integrated into the payment authorization workflow.

Do not rely solely on delayed Kafka events for decisions that must prevent a payment.

### 3.2.10 Notification Service

Responsibilities:

- In-app notifications
- Simulated email notifications
- Payment notifications
- Security alerts
- Fraud-related notifications
- Notification preferences

Communication:

- Kafka event consumers
- WebSocket or Server-Sent Events for live updates

Implement retry policies and dead-letter handling.

### 3.2.11 Reporting Service

Responsibilities:

- Transaction summaries
- Customer activity reports
- Administrative financial reports
- Ledger reconciliation reports
- Operational statistics
- CSV and PDF export

Reports must distinguish authoritative financial records from eventually consistent projections.

### 3.2.12 Audit Service

Responsibilities:

- Security event audit
- Administrative activity logging
- Sensitive operation tracking
- Fraud investigation history
- Financial workflow audit metadata

Audit records must be append-only at the application level.

Apply retention and access-control policies.

Never log passwords, raw access tokens or sensitive secrets.

---

# 4. FINANCIAL DOMAIN RULES

## 4.1 Money representation

Implement a Money value object containing:

- Amount
- Currency

Rules:

- Use precise decimal arithmetic.
- Enforce currency-specific precision.
- Reject invalid monetary values.
- Reject unsupported currencies.
- Reject cross-currency arithmetic without explicit conversion.
- Never silently round financial postings.

## 4.2 Ledger invariants

For every journal entry:

SUM(DEBITS) = SUM(CREDITS)

The equality must hold independently for each currency.

All postings belonging to a journal entry must be committed in the same database transaction.

A transaction must never leave partially committed ledger entries.

## 4.3 Internal transfer example

Customer A transfers 100.00 MAD to Customer B.

Required accounting result:

- Debit Customer A liability account: 100.00 MAD
- Credit Customer B liability account: 100.00 MAD

The exact debit/credit conventions must be documented according to the chosen chart of accounts.

Before posting:

- Validate both accounts.
- Validate transfer permissions.
- Validate available funds.
- Validate transaction limits.
- Evaluate fraud rules.
- Validate the idempotency key.

After successful posting:

- Update transaction status.
- Publish a durable payment completion event.
- Update read models.
- Notify the participants.

## 4.4 Simulated deposits

A simulated deposit must have a balancing counter-entry against a designated simulated funding or settlement account.

Never create money by directly incrementing a customer balance.

## 4.5 Idempotency

All money-moving APIs must support idempotency keys.

Requirements:

- Identical request and key must not produce duplicate postings.
- Reusing a key with a different payload must be rejected.
- Concurrent requests with the same key must be handled safely.
- Retried requests must return a consistent recorded outcome.
- Idempotency record retention must be documented.

## 4.6 Concurrency

Explicitly test:

- Two simultaneous transfers from the same account.
- Multiple requests using the same idempotency key.
- Concurrent transfers between the same accounts.
- Transfer processing during a service restart.
- Payment retries after network timeouts.

Financial invariants must remain valid under concurrency.

## 4.7 Reversals

Do not delete or modify posted financial records.

A reversal must create a new compensating journal entry linked to the original transaction.

Reversal operations must also be idempotent.

---

# 5. DISTRIBUTED COMMUNICATION

## 5.1 Synchronous communication

Use REST or gRPC for operations requiring immediate responses.

Examples:

- Customer validation
- Account validation
- Fraud pre-authorization
- Financial posting command
- Payment status query

Choose REST or gRPC per service boundary and justify the choice.

## 5.2 Asynchronous communication

Use Apache Kafka for domain events.

Initial event types:

- customer.created
- account.created
- payment.initiated
- payment.completed
- payment.failed
- payment.reversed
- fraud.alert.created
- card.frozen
- notification.requested

Events must contain:

- Event ID
- Event type
- Schema version
- Aggregate ID
- Correlation ID
- Timestamp
- Payload

## 5.3 Transactional Outbox

Implement the Transactional Outbox Pattern for critical event publication.

Business state changes and outbox records must commit atomically.

A background publisher must deliver outbox events to Kafka.

Consumers must be idempotent.

Document the actual delivery guarantees and avoid claiming exactly-once end-to-end processing without proof.

## 5.4 Failure handling

Implement:

- Bounded retries
- Exponential backoff
- Timeouts
- Circuit breakers
- Dead-letter topics
- Consumer idempotency
- Correlation IDs
- Replay-safe event processing

Avoid automatic retries for non-idempotent operations.

---

# 6. FRONTEND — REACT APPLICATION

## 6.1 Technology stack

Use:

- React
- TypeScript
- Vite
- Tailwind CSS
- React Router
- TanStack Query
- React Hook Form
- Zod
- Recharts
- Accessible UI components

Use a consistent design system.

The application must support responsive desktop, tablet and mobile layouts.

## 6.2 Design direction

Create a modern premium fintech interface.

Design characteristics:

- Clean layouts
- Professional typography
- Light and dark themes
- Clear financial hierarchy
- Responsive navigation
- Accessible form controls
- Loading skeletons
- Empty states
- Error states
- Confirmation dialogs
- Toast notifications
- Transaction status indicators

Do not directly copy another banking application's proprietary design, logo or branding.

Use original FinCore branding.

## 6.3 Authentication pages

Create:

- Login
- Registration
- Email verification state
- MFA challenge
- Session expired
- Access denied
- Logout confirmation

Integrate with Keycloak using secure OIDC flows.

## 6.4 Customer dashboard

Display:

- Customer greeting
- Total account balances by currency
- Individual account balances
- Recent transactions
- Monthly income and expenses
- Spending chart
- Quick transfer action
- Virtual card overview
- Recent notifications

Never aggregate different currencies into a single total without an explicit exchange-rate conversion.

## 6.5 Accounts page

Features:

- List accounts
- Account details
- Account status
- Available balance
- Booked balance
- Transaction history
- Account restrictions
- Account statement export

## 6.6 Transfers page

Build a multi-step transfer workflow.

Step 1: Select source account.

Step 2: Select beneficiary.

Step 3: Enter amount and reference.

Step 4: Validate limits and funds.

Step 5: Display confirmation summary.

Step 6: Submit transfer using an idempotency key.

Step 7: Display transaction result and reference.

Include clear handling of pending, rejected and failed operations.

Never display success solely because the HTTP request was accepted.

## 6.7 Transaction history

Features:

- Search
- Date filters
- Amount filters
- Status filters
- Transaction type filters
- Pagination
- Transaction details
- Export
- Transaction reference
- Linked reversal information

## 6.8 Virtual cards

Features:

- List virtual cards
- Create simulated card
- Freeze card
- Unfreeze card
- Configure spending limits
- View card activity

Never expose real payment-card credentials.

## 6.9 Beneficiaries

Features:

- List beneficiaries
- Add beneficiary
- Edit beneficiary
- Disable beneficiary
- Search beneficiaries

Apply additional confirmation for sensitive changes.

## 6.10 Notifications

Features:

- Notification center
- Unread count
- Real-time updates
- Mark as read
- Notification categories
- Notification preferences

## 6.11 Profile and security

Features:

- Profile details
- Security preferences
- MFA management through the identity provider
- Active session overview where supported
- Logout
- Personal notification settings

---

# 7. ADMINISTRATION APPLICATION

Implement a separate administration area with strictly enforced backend permissions.

## 7.1 Admin dashboard

Display:

- Total customers
- Active accounts
- Payment volume
- Successful transactions
- Failed transactions
- Fraud alerts
- Pending investigations
- Operational incidents

Use explicit time ranges and currency labels.

## 7.2 Customer management

Features:

- Customer search
- Customer details
- Account status
- Onboarding status
- Authorized restrictions
- Customer activity

## 7.3 Transaction monitoring

Features:

- Transaction search
- Payment status
- Failure reasons
- Correlation IDs
- Timeline of processing events
- Linked ledger references
- Reversal workflow

## 7.4 Fraud dashboard

Features:

- Fraud alerts
- Risk score
- Triggered rules
- Alert severity
- Investigation status
- Analyst notes
- Case assignment
- Investigation decisions

## 7.5 Ledger dashboard

Features:

- Journal entry search
- Posting details
- Debit and credit inspection
- Reconciliation status
- Financial operation references
- Discrepancy alerts

Do not allow administrators to edit posted ledger entries.

## 7.6 System health dashboard

Display:

- Service health
- Request rates
- Error rates
- Response latency
- Kafka consumer lag
- Database connectivity
- Recent incidents

Use real telemetry from monitoring tools rather than fabricated production metrics.

---

# 8. DATABASE DESIGN

Use PostgreSQL.

Create independently owned schemas or databases for each service.

Required design principles:

- UUID identifiers where appropriate
- Foreign keys within service-owned databases
- Unique constraints
- Check constraints
- Explicit indexes
- Optimistic locking where appropriate
- Database migration scripts
- Audit timestamps
- Controlled transaction boundaries

Use Flyway for migrations.

Never rely on automatic schema creation in production-style environments.

## Critical ledger tables

Design at least:

- ledger_accounts
- journal_entries
- ledger_postings
- financial_operations
- idempotency_records
- outbox_events
- account_balance_projections

Define:

- Primary keys
- Foreign keys
- Unique constraints
- Indexes
- Currency validation
- Monetary precision
- Transaction boundaries
- Posting invariants

Document how the system guarantees that each committed journal entry is balanced.

---

# 9. API DESIGN

Use RESTful APIs where appropriate.

Version public APIs using `/api/v1`.

Examples:

### Customer APIs

- GET /api/v1/customers/me
- PATCH /api/v1/customers/me

### Account APIs

- GET /api/v1/accounts
- GET /api/v1/accounts/{accountId}
- GET /api/v1/accounts/{accountId}/balance
- GET /api/v1/accounts/{accountId}/transactions

### Payment APIs

- POST /api/v1/transfers
- GET /api/v1/transfers/{transferId}
- POST /api/v1/transfers/{transferId}/reversal

### Beneficiary APIs

- GET /api/v1/beneficiaries
- POST /api/v1/beneficiaries
- PATCH /api/v1/beneficiaries/{beneficiaryId}

### Card APIs

- GET /api/v1/cards
- POST /api/v1/cards
- POST /api/v1/cards/{cardId}/freeze
- POST /api/v1/cards/{cardId}/unfreeze

### Administration APIs

- GET /api/v1/admin/transactions
- GET /api/v1/admin/fraud-alerts
- GET /api/v1/admin/ledger/journals
- GET /api/v1/admin/operations/health

The exact API contracts must be finalized during design.

Requirements:

- OpenAPI documentation
- Request validation
- Pagination
- Consistent response schemas
- Standardized error responses
- Authorization
- Idempotency headers for financial commands
- Correlation IDs
- Clear HTTP status semantics

Use RFC 9457 Problem Details where appropriate.

---

# 10. SECURITY REQUIREMENTS

Implement defense in depth.

## Authentication

- OAuth 2.0 / OpenID Connect
- Keycloak
- PKCE
- Short-lived access tokens
- Secure token handling
- Session expiration
- MFA support

## Authorization

- RBAC
- Object-level authorization
- Account ownership validation
- Administrative scope restrictions
- Least privilege
- Server-side permission checks

## Application security

Protect against:

- SQL injection
- Cross-site scripting
- Cross-site request forgery where applicable
- Broken object-level authorization
- Privilege escalation
- Excessive request rates
- Sensitive information disclosure
- Unsafe deserialization
- Replay of financial commands

Implement:

- Input validation
- Secure headers
- Secret management
- Audit logging
- Dependency vulnerability scanning
- Container image scanning
- Safe error handling

Never hardcode secrets.

Provide `.env.example` with placeholder values.

Document a threat model for the financial transfer workflow.

---

# 11. OBSERVABILITY

Use:

- Spring Boot Actuator
- Micrometer
- OpenTelemetry
- Prometheus
- Grafana
- Loki or an equivalent log backend
- Tempo or Jaeger for distributed tracing

Implement:

- Structured JSON logging
- Distributed tracing
- Correlation IDs
- Business metrics
- JVM metrics
- HTTP latency metrics
- Kafka metrics
- Database connection metrics
- Payment failure metrics
- Ledger posting metrics

Required dashboards:

1. System overview
2. Service performance
3. Payment processing
4. Kafka monitoring
5. Ledger operations
6. Fraud detection

Do not expose sensitive customer information through logs or metrics.

---

# 12. DOCKER AND LOCAL ENVIRONMENT

The entire platform must be runnable locally.

Provide Docker Compose configurations for:

- PostgreSQL instances
- Redis
- Kafka
- Keycloak
- Backend services
- React frontend
- Prometheus
- Grafana
- Supporting observability services

Requirements:

- Multi-stage Dockerfiles
- Non-root containers where possible
- Health checks
- Environment-specific configuration
- Persistent volumes where required
- Service dependency management
- Development and production-style profiles

Provide clear commands for starting and stopping the platform.

A developer should be able to clone the repository, configure local environment variables and launch the documented MVP without manually installing every dependency.

---

# 13. KUBERNETES

Create Kubernetes deployment manifests or Helm charts.

Include:

- Deployments
- Services
- ConfigMaps
- Secrets references
- Ingress
- Readiness probes
- Liveness probes
- Resource requests
- Resource limits
- Horizontal Pod Autoscaling where justified
- Network policies
- Pod security settings

Use appropriate strategies for stateful components.

Do not assume PostgreSQL and Kafka should be deployed identically to stateless application services.

Document local Kubernetes deployment using kind or minikube.

Cloud deployment is optional and must not be required to run the project locally.

---

# 14. CI/CD

Use GitHub Actions.

Required pipelines:

## Backend CI

- Compile
- Unit tests
- Integration tests
- Static analysis
- Dependency checks
- Build artifacts

## Frontend CI

- TypeScript type checking
- Linting
- Unit tests
- Component tests
- Production build

## Container CI

- Docker image build
- Container scanning
- Image tagging

## Integration pipeline

- Start required dependencies
- Execute integration tests
- Validate API contracts
- Run database migrations
- Verify critical financial invariants

Do not deploy automatically to production.

---

# 15. TESTING STRATEGY

Use:

- JUnit 5
- Mockito
- AssertJ
- Testcontainers
- Spring Boot Test
- REST Assured
- React Testing Library
- Vitest
- Playwright
- k6 or Gatling

## Mandatory ledger tests

- Balanced journal entry succeeds.
- Unbalanced journal entry fails.
- Negative or invalid amount fails.
- Duplicate idempotency key does not duplicate postings.
- Same key with different payload fails.
- Concurrent insufficient-funds transfers cannot overdraw an account.
- Partial database failure rolls back all postings.
- Posted journal entries cannot be modified.
- Reversal creates compensating postings.
- Reconciliation detects deliberate inconsistencies in test fixtures.

## Mandatory payment tests

- Valid transfer succeeds.
- Insufficient funds fails.
- Unauthorized transfer fails.
- Fraud-blocked transfer fails.
- Retried request does not create duplicate transfers.
- Network timeout does not cause duplicate postings.
- Kafka consumer redelivery is safe.
- Service restart preserves financial correctness.

## Frontend tests

- Login flow
- Protected routes
- Account dashboard
- Transfer validation
- Transfer confirmation
- Error states
- Admin access restrictions

## End-to-end tests

Implement realistic user journeys from customer login through transfer confirmation and ledger verification.

---

# 16. PERFORMANCE ENGINEERING

Create reproducible performance test scenarios.

Test:

- Concurrent balance reads
- Concurrent transfers
- Payment initiation
- Transaction history queries
- Kafka event consumption
- Database contention
- Service restart during processing

Measure:

- Requests per second
- Transactions per second
- p50 latency
- p95 latency
- p99 latency
- Error rate
- CPU usage
- Memory usage
- Database lock contention

Do not invent benchmark results.

All performance claims must include:

- Hardware environment
- Dataset size
- Concurrency level
- Test duration
- Measurement method
- Actual observed results

Set performance targets only after establishing a measured baseline.

---

# 17. REPOSITORY STRUCTURE

Propose a maintainable monorepo.

Initial target:

```text
fincore/
├── apps/
│   ├── customer-portal/
│   └── admin-portal/
│
├── services/
│   ├── api-gateway/
│   ├── customer-service/
│   ├── account-service/
│   ├── ledger-service/
│   ├── payment-service/
│   ├── beneficiary-service/
│   ├── card-service/
│   ├── fraud-service/
│   ├── notification-service/
│   ├── reporting-service/
│   └── audit-service/
│
├── contracts/
│   ├── openapi/
│   └── events/
│
├── infrastructure/
│   ├── docker/
│   ├── kubernetes/
│   ├── helm/
│   ├── keycloak/
│   └── monitoring/
│
├── tests/
│   ├── e2e/
│   ├── performance/
│   ├── contract/
│   └── resilience/
│
├── docs/
│   ├── architecture/
│   ├── adr/
│   ├── api/
│   ├── database/
│   ├── security/
│   ├── diagrams/
│   └── deployment/
│
├── scripts/
├── .github/
│   └── workflows/
│
├── compose.yaml
├── .env.example
├── README.md
├── CONTRIBUTING.md
└── LICENSE
```

This is a target structure, not a requirement to create empty services immediately.

Only create modules when they are needed for the current milestone.

---

# 18. DOCUMENTATION

Create professional English documentation.

Required documents:

- README.md
- Architecture overview
- System context diagram
- Container diagram
- Component diagrams
- Deployment diagram
- Database ER diagrams
- Payment sequence diagram
- Ledger posting sequence diagram
- Event flow diagram
- Security architecture
- Threat model
- API documentation
- Local setup guide
- Kubernetes deployment guide
- Testing guide
- Performance report
- Troubleshooting guide
- Architectural Decision Records

Use Mermaid or PlantUML for diagrams.

The README must include:

- Project description
- Feature overview
- Technology stack
- Architecture diagram
- Screenshots
- Quick start
- Demo credentials for synthetic local users only
- API documentation
- Test instructions
- Known limitations
- Roadmap
- Engineering highlights

---

# 19. DEVELOPMENT ROADMAP

Develop incrementally.

## PHASE 0 — Architecture and planning

Deliver:

- Repository analysis
- Requirements clarification
- Architecture proposal
- Service responsibility matrix
- Financial domain model
- Initial ER diagrams
- Security model
- Event contracts
- Implementation roadmap
- Architectural Decision Records

Do not write production code before architecture approval.

## PHASE 1 — Foundation

Implement:

- Repository structure
- Java project configuration
- Spring Boot foundation
- Docker Compose
- PostgreSQL
- Keycloak
- API Gateway
- React application skeletons
- Basic CI pipeline
- Health endpoints

Acceptance criteria:

- All foundational services start.
- Authentication works.
- Protected APIs reject unauthorized requests.
- Frontend can communicate with backend.

## PHASE 2 — Ledger core

Implement:

- Chart of accounts
- Journal entries
- Ledger postings
- Double-entry validation
- Idempotency
- Atomic posting
- Concurrency protection
- Ledger unit tests
- Ledger integration tests

Acceptance criteria:

- Financial invariants are enforced.
- Concurrent postings are safe.
- Invalid journal entries are rejected.
- No partial postings are possible.

## PHASE 3 — Accounts and transfers

Implement:

- Customer accounts
- Account ownership
- Internal transfers
- Available funds validation
- Payment state machine
- Transaction history
- Reversals
- Outbox event publication

Acceptance criteria:

- Customers can transfer simulated money.
- Transfers produce correct ledger postings.
- Retries do not duplicate financial operations.
- Insufficient funds are rejected.

## PHASE 4 — Customer frontend

Implement:

- Authentication
- Customer dashboard
- Accounts page
- Transfer workflow
- Transaction history
- Beneficiaries
- Profile
- Responsive design

Acceptance criteria:

- Complete end-to-end customer transfer flow works.
- UI reflects actual backend transaction state.
- Unauthorized account access is prevented.

## PHASE 5 — Kafka and notifications

Implement:

- Kafka infrastructure
- Transactional outbox
- Event consumers
- Consumer idempotency
- Notifications
- Real-time frontend updates
- Dead-letter handling

Acceptance criteria:

- Events are reliably published.
- Duplicate events are handled safely.
- Notifications appear after successful transactions.

## PHASE 6 — Fraud detection

Implement:

- Fraud rules
- Risk scoring
- Pre-payment risk checks
- Fraud alerts
- Investigation workflows
- Fraud analyst dashboard

Acceptance criteria:

- High-risk transfers can be blocked or reviewed.
- Fraud decisions are auditable.
- Analysts can investigate generated alerts.

## PHASE 7 — Cards and advanced features

Implement:

- Simulated virtual cards
- Card lifecycle
- Simulated card payments
- Card spending limits
- Transaction exports
- Statements
- Reporting

Acceptance criteria:

- Card actions enforce permissions.
- Simulated card payments integrate with the ledger.
- Statements reconcile with authoritative records.

## PHASE 8 — Administration and monitoring

Implement:

- Admin dashboard
- Customer management
- Payment monitoring
- Ledger inspection
- Fraud monitoring
- Audit history
- Grafana dashboards
- Distributed tracing

Acceptance criteria:

- Administrators can inspect operations.
- Service traces can be correlated.
- Critical actions are auditable.

## PHASE 9 — Kubernetes and CI/CD

Implement:

- Container hardening
- Kubernetes manifests
- Helm charts
- Deployment configuration
- CI/CD improvements
- Security scans
- Environment documentation

Acceptance criteria:

- The application deploys to a local Kubernetes cluster.
- Health checks work.
- Configuration is externalized.
- Secrets are not committed.

## PHASE 10 — Performance and resilience

Implement:

- Load tests
- Concurrency tests
- Failure injection
- Service restart tests
- Kafka outage tests
- Database contention analysis
- Performance optimization
- Benchmark reports

Acceptance criteria:

- Financial invariants survive failure scenarios.
- Measured performance results are documented.
- Known bottlenecks are identified.
- Recovery procedures are documented.

## PHASE 11 — Portfolio release

Deliver:

- Polished README
- Architecture diagrams
- Screenshots
- Demo video plan
- Reproducible demo environment
- API collection
- Final test reports
- Performance report
- Release notes

The final project must be demonstrable to a technical recruiter without requiring access to real financial infrastructure.

---

# 20. DEFINITION OF DONE

A feature is complete only when:

1. Its business rules are implemented.
2. Input validation is present.
3. Authorization is enforced.
4. Error handling is implemented.
5. Relevant tests pass.
6. Database migrations are included.
7. APIs are documented.
8. Logs and metrics are appropriate.
9. Security implications are reviewed.
10. The feature works in the documented local environment.

A milestone is complete only when:

- The application builds.
- Relevant automated tests pass.
- Integration scenarios are verified.
- Documentation is updated.
- Known limitations are reported.
- No critical financial invariant is violated.

---

# 21. REQUIRED CODEX RESPONSE FORMAT

For every milestone, respond with:

## A. Current state

Explain what already exists and what is missing.

## B. Proposed implementation

Describe the architecture and design choices.

## C. Files to create or modify

List the exact paths and responsibilities.

## D. Risks and trade-offs

Identify security, concurrency, consistency and maintainability risks.

## E. Test strategy

Explain how the milestone will be validated.

## F. Implementation

Implement only the approved milestone.

## G. Verification

Report:

- Commands executed
- Tests passed
- Tests failed
- Build status
- Runtime status
- Remaining limitations

## H. Next milestone

Explain the next recommended step.

Never claim tests passed if they were not executed.

Never claim a service is production-ready merely because it starts successfully.

---

# 22. FIRST TASK — START HERE

Do not generate the entire platform yet.

Your first assignment is to produce a detailed technical architecture proposal.

Specifically:

1. Inspect the current repository.
2. Identify whether it is empty or contains an existing implementation.
3. Recommend the most appropriate initial architecture.
4. Explain whether the first milestone should use a modular monolith or independent microservices.
5. Define the initial bounded contexts.
6. Explain the financial ledger design in detail.
7. Define the authoritative source of balances.
8. Explain how concurrent transfers will be protected.
9. Define payment idempotency and recovery behavior.
10. Propose the database schema for the initial financial core.
11. Create Mermaid diagrams for the system architecture, ledger and transfer sequence.
12. Identify critical architectural risks.
13. Provide a prioritized development roadmap.
14. Specify the exact scope of the first implementation milestone.

**Stop after presenting the architecture and wait for my approval before writing application code.**

The goal is not to generate the largest amount of code.

The goal is to build a credible, technically rigorous, enterprise-grade financial software system that demonstrates senior-level engineering practices through working, tested and documented implementations.

**END OF MASTER SPECIFICATION**
