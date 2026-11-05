# Phase 8 lot 4: customer name search

Delivered scope: ADMIN searches Customer's authoritative profile storage by a case-insensitive **name prefix**, then opens the existing exact-reference inspection. Backend remains Java 21/Spring Boot. The [contract](../../contracts/openapi/customer-search-v1.yaml) is additive; prior inspection responses remain unchanged.

`POST /api/v1/admin/customers/search` accepts `namePrefix` and optional `afterId`. The stripped prefix must contain 3–80 UTF-16 code units and no control character. Percent, underscore, exclamation mark and apostrophe are literal characters, not search operators. Matching uses PostgreSQL lower-case semantics; it is not accent-insensitive or fuzzy search. A valid request returns at most 20 matches in ascending UUID order, with `nextAfterId` only when another match was observed. Repeat the same prefix with that UUID for the next page. There is no total count or empty-prefix directory listing. Names shorter than three characters remain accessible through an exact customer UUID.

Results contain only customer UUID, display name, onboarding status, decimal-string profile version and creation time. `observedAt` is the application response observation time. Each page is a separate database observation: concurrent onboarding or name edits can change later results. No cross-page snapshot is claimed. The database query has a three-second statement timeout; no large-dataset latency SLA or index performance acceptance is claimed in this lot.

Both Gateway and Customer independently require signed ADMIN authority. Other staff roles and technical identities receive no implicit permission. Other methods on the search path are denied. Search terms travel in a bounded JSON body rather than URLs. The response is `Cache-Control: no-store`; the browser uses explicit submissions, an in-memory page state and no persistent search cache. No profile subject, country, contact details, balances, account IDs or raw database error reaches the response.

The staff Inspection workspace adds a separate **Find a customer** form for ADMIN. Editing the prefix clears prior results; next-page navigation retains the submitted prefix. Selecting a match opens the existing redacted customer status. Empty, loading, failed and retry states must work at 1440/390/320 px. Search is a read: it changes no profile, outbox, account or financial record. Complete sensitive-read/security audit and scoped support business grants remain later Phase 8 work.

Existing installations will rebuild and replace only `customer-service`, `api-gateway` and `admin-portal`, retaining configuration, credentials and persistent volumes. No schema migration, role provisioning or secret rotation is required.

```powershell
docker compose build customer-service api-gateway admin-portal
docker compose up -d --no-deps --wait --wait-timeout 600 customer-service api-gateway admin-portal
```

Measured permission, PostgreSQL, frontend, stack/browser and CI outcomes are recorded in the [verification report](../verification/phase-8-customer-search.md). Main acceptance is recorded separately from feature validation.
