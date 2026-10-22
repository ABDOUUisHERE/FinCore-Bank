# Security model and architectural risks

Status: proposed controls and test obligations. No control is claimed to be implemented yet. All customers, funds, geographic signals and card representations are synthetic.

## Identity and access

Keycloak handles registration, login, verification, MFA, password policy and sessions. Both React portals use separate public OIDC clients with Authorization Code and PKCE S256, exact redirect URIs and explicit web origins. Keep access/refresh tokens in memory; do not persist them in localStorage, sessionStorage or URLs. Hosted login avoids implementing password handling in React. Administrative access requires configured MFA and a recent authentication/step-up check for sensitive actions. The official [Keycloak JavaScript adapter documentation](https://www.keycloak.org/securing-apps/javascript-adapter) describes public-client configuration and in-memory tokens.

Gateway and each backend validate signature, allowed algorithm, issuer, audience, expiration and authorized roles/scopes. Use HTTPS outside loopback, restricted JWKS access/refresh and bounded clock skew. Configure the same externally reachable issuer across browser and containers; do not disable issuer validation to solve local DNS problems. Key rotation and session expiry need explicit tests. Short token lifetimes bound revocation lag; urgent financial restrictions use ledger guards rather than waiting for role claims to expire.

| Role | Permitted scope | Important boundary |
| --- | --- | --- |
| CUSTOMER | Own profile, accounts, beneficiaries, payments and synthetic cards | Account ID alone never grants access |
| SUPPORT_AGENT | Assigned support scope and minimal account/payment metadata | No funds movement, full journal export or unrestricted identity access |
| FRAUD_ANALYST | Risk explanations, assigned cases and audited decisions | No direct ledger writes or automatic payment completion |
| FINANCE_OPERATOR | Journals, reconciliation, authorized reversal workflow | Cannot alter postings or grant roles |
| ADMIN | Service operations, approved staff-role management and configuration | Does not inherit unrestricted finance/customer powers |

Function authorization and object-level authorization are separate server checks. Scope limits apply to search, pagination, detail and exports. Role grants go through a narrowly scoped administrative workflow with additional authorization and audit, not unrestricted Keycloak admin credentials held by Gateway. Unknown/inaccessible account and payment IDs return consistent 404 responses. Support metadata is deliberately redacted.

Internal service calls need service identity and narrow audiences/scopes. Customer operations additionally carry a verifiable delegated actor identity. Ledger independently checks the entitlement and financial guard rather than trusting Payment to name a subject. Simulation funding uses a separate constrained system scope; a customer cannot call it via arbitrary headers. Internal ledger routes are absent from the public Gateway and protected by network policy as well as authentication.

## Transfer threat model

Assets: immutable posting history, available funds, idempotency identity/outcome, user entitlements, audit records and signing/service credentials. Trust boundaries: browser to Gateway, Gateway to business services, Payment/Account to Ledger, each service to its own DB, producers/consumers to Kafka, and staff to administrative APIs.

| Threat / failure | Prevention or containment | Required evidence |
| --- | --- | --- |
| User substitutes another source account | Validated subject plus owner check in Account and entitlement check in Ledger | Another customer's IDs rejected on create/read/replay/export |
| Caller spoofs actor headers | Validated service credential and delegated actor token; reject unverified actor fields | Forged actor, wrong audience and wrong service scope tests |
| Two valid transfers overspend | Ordered row locks and funds checks inside authoritative transaction | Two separate DB connections/processes race, only affordable postings commit |
| Timeout causes fresh duplicate | Durable Payment intent and ledger unique business reference/idempotency | Drop response after commit, retry and restart; only one journal |
| Same key changes amount/destination | Versioned canonical fingerprint and persistent unique scope | Sequential and concurrent conflict tests |
| Freeze races with posting | Shared ledger guard/account lock; acknowledge restriction only after ledger commit | Freeze/transfer race yields documented commit order |
| Reversal removes spent funds | Same funds checks, unique original reversal, compensating journal | Concurrent reversal and post-spend reversal tests |
| Stale risk data allows velocity evasion | Synchronous decision; atomic velocity reservation/counter in Fraud when introduced | Concurrent high-velocity attempts; unavailable/expired assessment fails closed |
| Event loss/duplicate or disorder | Transactional outbox, consumer inbox, versions and replay tooling | Broker outage, post-ack crash, duplicates and stale versions |
| Runtime or SQL bug bypasses accounting | Minimal execution grants, constraints, fixed-path functions and deferred journal checks | Direct DML/append/truncate denied; unbalanced transaction rejected at commit |
| Database administrator rewrites history | Separate migration credentials, change review, backups, audit and reconciliation | Restore/reconcile drills; superuser tamper resistance is not claimed |
| XSS steals browser token | CSP, escaping, no raw HTML, dependency scans, memory-only tokens | UI security checks; CSP and output encoding validation |
| Injection or oversized request | Parameterized SQL, validation, payload bounds, timeouts and rate limits | Invalid inputs, malicious references and throttling tests |
| Sensitive logs/events/exports leak data | Minimize fields, access checks, redaction and restricted retention | Log/event fixture inspection and export permission tests |
| Poison message blocks operations | Bounded retries, DLQ, backlog metrics and audited replay | Poison fixture quarantines without lost identity |

## Operational policy

Never commit secrets. `.env.example` contains placeholder values only; local bootstrap generates synthetic users/secrets in ignored local files. Do not reuse static demo credentials outside loopback. Kubernetes references external or separately provisioned Secrets; do not check in base64 credentials. Runtime database roles cannot migrate or bypass ledger functions. Use non-root containers, read-only filesystem where compatible, resource bounds and minimal network access.

Bearer APIs do not use cookies for application authentication initially. Restrict CORS and reject unexpected browser origins where appropriate. If a BFF/cookie design is introduced, add explicit CSRF tokens and SameSite/secure cookie policy before enabling it. OIDC login requires state/nonce and PKCE regardless. Rate limits use Redis when shared gateway replicas require it; outage behavior and limits on funding/payment routes must be explicit.

Audit relevant authentication failures, role changes, restrictions, beneficiary changes, risk decisions, payment/reversal state transitions and simulation funding. Early services append local audit/outbox records; the dedicated Audit Service later ingests them idempotently. Runtime audit permissions are append-only. Do not log tokens, credentials, full request bodies, synthetic card secrets or unnecessary customer contact data. Set separate configurable retention for operational logs and financial identity records; no legal compliance certification is claimed.

## Architectural trade-offs

Independent services increase deployment and debugging cost but make ownership/failure boundaries demonstrable. One local PostgreSQL server reduces RAM needs while separate databases/grants preserve ownership; it is a shared availability dependency. No distributed transaction spans Account, Payment, Ledger or Kafka. Therefore explicit pending states and recovery are necessary.

The ledger is a critical availability boundary: fail closed if funds cannot be safely verified. Hot accounts serialize writes, and full immutable history grows. Measure contention and storage before choosing partitioning, batching or extra replicas. Do not distribute a single account's financial authority across independent balances. Asynchronous projections improve reads while requiring freshness indicators, version checks and reconciliation. Define measurable performance targets only after a reproducible baseline.
