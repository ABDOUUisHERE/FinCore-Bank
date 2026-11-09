# Temporary support payment access — Phase 8 lot 6

The [payment contract](../../contracts/openapi/support-payment-access-v1.yaml) extends the existing [account grant protocol](support-access-phase-8.md). ADMIN authorizes the immutable PAYMENT incident creator and reference for 5, 15, 30 or 60 minutes. Signed SUPPORT_AGENT plus a current grant is required to consult. References are staff assertions, not proof of customer ownership.

Payment returns only paymentId, kind, status, exact string version, createdAt and observedAt. No amount, currency, account, actor, journal, free text, history or risk/worker detail is exposed. A processing status does not certify a Ledger posting. The projection uses one read-only repeatable-read transaction; it cannot initiate, approve, cancel, fund or reverse a payment.

Audit remains the trusted per-object grant broker described in [ADR 0012](../fincore/adr/0012-online-support-account-access.md). The same `fincore-audit` client gains only `payment.support.read` and Payment audience. The Payment owner requires both that role and signed `azp=fincore-audit`. Existing operator lookup clients and browser tokens cannot use the internal route. Audit validates the six fields and discards source extras before disclosure. This machine authority permits redacted status reads; it is not an independent owner-side verification of the incident grant.

Account and Payment share grant decisions, actor-global command receipts and the admission/finalization sequence. Fixed server policies select separate tables and projections. V3 leaves V1/V2 and the Account grant table, identities and original command fingerprints intact. The migration adds `support_payment_grant` and extends the incident resolution trigger to revoke both resource types atomically. Incident state, automatic revocation, status fact and command receipt share the same transaction. Reopening never restores access. Replaying an older ISSUE receipt never reissues permission.

Admission and final disclosure commit separate redacted decisions around HTTP, with no remote call under a database lock. Expiry is checked using the database clock after acquiring the fact cursor lock. Replacement, revocation or resolution during the owner request prevents disclosure. Audit failure also prevents disclosure. DISCLOSED records the authorization decision, not proof that the browser received bytes. Previously delivered information cannot be withdrawn retroactively. Authentication, role and malformed-input denials precede this domain decision path.

The browser renders grant metadata separately from the observation, requests the observation only on a click, clears it on a subsequent attempt, denial, permission refresh, local expiry or leaving the view, and stores no observation in session storage or query caches. Only the original grant command and key are retained for uncertain-response recovery. An ADMIN receipt is historical; current permission is fetched separately.

For a preserved installation, retain volumes, `.env` and `.local/credentials.json`. No secret is added or rotated by this lot. Refresh the existing identity's scopes, apply the additive migration and replace only changed runtimes:

```powershell
docker compose run --rm --no-deps business-identity
docker compose build audit-service payment-service api-gateway admin-portal
docker compose run --rm --no-deps audit-migrations
docker compose up -d --no-deps --wait --wait-timeout 600 audit-service payment-service api-gateway admin-portal
```

Gateway allows 25 seconds and the browser 30 seconds for support observation routes; individual owner/token calls remain bounded at five seconds. Validate the preserved Account receipt before and after V3 and run both Account and Payment browser journeys. Execution evidence belongs in [verification](../verification/phase-8-support-payment.md); this guide is not proof of execution. Customer support scope, full security/read audit coverage, tracing and dashboards remain later Phase 8 work.
