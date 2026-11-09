# Scoped support customer discovery — Phase 8 lot 7

An ADMIN-issued CUSTOMER_PROFILE_READ grant authorizes the immutable creator/customer of an active CUSTOMER incident for 5, 15, 30 or 60 minutes. The recipient also needs a current signed SUPPORT_AGENT role. A staff-supplied reference is an assertion; granting it is the administrator's explicit authorization, not proof of customer ownership.

The support Incidents screen includes a literal, case-insensitive name-prefix search over the requesting agent's current customer grants. Audit selects at most 100 incident grants; Customer searches only their distinct customer IDs and returns at most 20 matches. An oversized cohort is refused. There is no global count or browser-provided scope. Newly issued grants enter the next search. Refine the prefix when hasMore is true.

All cohort incidents are locked in UUID order before the audit fact cursor. HTTP occurs between two short transactions. Finalization rechecks every admitted grant, including those without a displayed match, and the cohort's earliest expiry. Any changed, revoked, resolved or expired permission withholds the whole response. Per-incident SEARCH_CHECKED facts are checks; a separate aggregate DISCLOSED fact records the actual search authorization decision. Prefixes, names and profile contents never enter these retained facts. An audit-write failure withholds the result. See [ADR 0013](../fincore/adr/0013-scoped-support-customer-discovery.md).

The same fincore-audit machine identity gains only customer.support.read and Customer audience; it retains the existing narrow Account/Payment roles. Customer requires both the signed client and the new authority on its internal profile/search routes. Audit is trusted for per-object scope, as described in ADR 0012. Neither this grant nor its machine role permits the ADMIN directory, risk context, country/subject lookup, accounts, balances or customer mutation.

The explicit profile observation includes customerId, displayName, status, profileVersion as a decimal string, createdAt and observedAt. Search exposes customerId, displayName, status and its authorized incidentId, plus bounded availability/expiry metadata. No subject, country, account, contact or financial contents are included. Names and prefixes remain in component-local memory; requests use POST, no-store, no query-string names and no automatic retry. A new attempt, denial, local expiry, clear action or leaving clears results. Already delivered information cannot be withdrawn retroactively.

V4 adds support_customer_grant and extends atomic resolution invalidation to the third resource. V1–V3, Account/Payment identities, fingerprints and existing receipts remain unchanged. Incident/grant commands retain the actor-global key namespace. Replaying an original ISSUE receipt reports its original outcome and never restores permission.

For a preserved installation, retain all volumes, .env and .local/credentials.json. No secret is added or rotated. Capture the existing Account/Payment grants and receipts before applying the additive upgrade:

```powershell
docker compose run --rm --no-deps business-identity
docker compose build customer-service audit-service api-gateway admin-portal
docker compose run --rm --no-deps audit-migrations
docker compose up -d --no-deps --wait --wait-timeout 600 customer-service audit-service api-gateway admin-portal
```

Gateway permits 25 seconds and the browser 30 seconds for scoped reads/search; owner/token calls remain bounded independently. Check unchanged credentials, legacy grants/receipts, domain counts before fixtures, real machine scopes and the hosted Customer/Account/Payment journeys. This guide is not execution evidence. [Measured acceptance](../verification/phase-8-support-customer.md) records actual outcomes. Full platform security/read audit coverage, collected distributed tracing and six observed dashboards remain subsequent Phase 8 work.
