# Temporary support account access — Phase 8 lot 5

Java 21/Spring Boot Audit/Operations owns the current grant and retained decisions. ADMIN may issue for 5, 15, 30 or 60 minutes, or revoke. The incident must reference an ACCOUNT; resource and recipient are fixed to its immutable reference and creator. Issuing a new version replaces the previous grant. A current SUPPORT_AGENT role remains necessary to use it. Staff references are reported assertions: issuing a grant is the administrator's explicit authorization, not verification of customer ownership.

Both Gateway and Audit check explicit roles. The Account internal route requires `account.support.read` plus signed `azp=fincore-audit`; existing lookup clients and browsers cannot use it. It returns status and observation times, excluding customer identity, Ledger link, balance and postings. Audit validates the returned account UUID and field types before projecting the response. See [ADR 0012](../fincore/adr/0012-online-support-account-access.md) for the broker trust boundary.

Admission and final disclosure each commit a redacted fact under the incident. A short incident row lock serializes decisions with grant changes and resolution. The global fact lock is acquired before the database-clock expiry check, so waiting for another fact cannot authorize an already expired grant. No lock spans HTTP. Revocation, replacement, expiry or resolution while Account responds withholds its result. Every released observation has a retained DISCLOSED fact; that fact records the decision, not proof the browser received the bytes. Missing incident attempts are retained without a resource payload; foreign support cannot list or view that incident history. Authentication, role and malformed-body denials happen before this domain audit path.

V2 adds a grant table and an incident resolution trigger. Grant state, operation fact and command receipt commit together. Resolution invalidates even an expired grant within the incident status transaction; the immutable `incident.status.changed` fact records that resolution and thus its automatic invalidation. Reopening never revives permission. Grant and incident commands share the immutable actor/key receipt table; a lost response must be retried with the exact original body and key. UI receipts are explicitly historical; current permission is refreshed separately.

For a preserved local installation, retain all volumes and credentials. Bootstrap appends only the new `AUDIT_CLIENT_SECRET`; it retains every existing secret and user. The identity job adds the narrow machine identity to fresh and persisted realms without resetting users.

```powershell
python scripts/bootstrap-local.py
docker compose run --rm --no-deps business-identity
docker compose build audit-service account-service api-gateway admin-portal
docker compose run --rm --no-deps audit-migrations
docker compose up -d --no-deps --wait --wait-timeout 600 audit-service account-service api-gateway admin-portal
```

The owner and token calls are individually bounded at five seconds, Gateway permits 25 seconds and the browser 30 seconds for a cold nested lookup. Account/Ledger remain independent observations; a partial Ledger outage is shown explicitly. The browser consults only on a click, clears previous observations on a new attempt or failed request, and keeps account contents out of command persistence and query caches. Leaving, permission refresh or local expiry clears the observation. Previously delivered data cannot be revoked retroactively.

This lot does not grant support name search, customer/payment metadata, financial journal access or financial mutation. Full cross-service read/security audit coverage, tracing and dashboards remain later Phase 8 work. Acceptance is tracked in [verification](../verification/phase-8-support-access.md).
