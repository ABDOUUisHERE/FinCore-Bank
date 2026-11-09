# Customer administrative read audit — Phase 8 lot 8

Scope: Customer owner's `/api/v1/admin/customers/search` and single-segment `/api/v1/admin/customers/{reference}` routes. All methods on these paths are categorized; only the existing ADMIN POST search and GET inspection remain authorized. This lot does not accept platform-wide security audit, Gateway rejections, identity-provider events, other owner routes, tracing or dashboards.

| Action | Reference retained | Outcomes |
| --- | --- | --- |
| CUSTOMER_SEARCH | None; never a search prefix or cursor | RESPONSE_READY, INVALID_REQUEST, UNAUTHENTICATED, FORBIDDEN, NOT_FOUND, SERVER_ERROR |
| CUSTOMER_INSPECT | Canonical UUID if present, without asserting existence | Same finite outcomes |

The owner buffers these bounded synchronous responses. An append-only Customer database record must commit before any response body is copied to the client. `RESPONSE_READY` means the authorized response was prepared; it does not prove receipt by a browser. A failed local audit write replaces the response with sanitized `503 AUDIT_UNAVAILABLE`, including for a denial. Such an unavailable attempt is not claimed as retained. A broker or central Audit outage does not prevent local retention: the same immutable record is an outbox source and retries asynchronously.

The actor is captured only after successful JWT verification in the owner's security chain. A safe opaque signed subject has provenance SIGNED_JWT; absent, invalid or unretained subjects have provenance UNAVAILABLE and no actor. Forged identity/role headers, unverified token contents, names, query strings, raw paths, request/response bodies and credentials never enter the record. Correlation is a bounded caller-supplied technical assertion, not identity evidence.

Customer migration V4 adds a separate immutable table and unions its envelopes into the existing outbox view without changing prior domain facts or delivery receipts. Events use `customer.access.recorded`, event UUID as aggregate ID and aggregate version 1. Existing producer ACLs remain scoped to `fincore.customer.`. Audit projects only the finite action/outcome/provenance/method/status metadata and optional customer UUID, and retains its existing deduplication, hash-conflict quarantine and broker coordinates. The central record remains a producer assertion; it does not independently verify the source JWT.

Early servlet container/firewall/body-size rejection, asynchronous responses, transport disconnects and requests rejected at Gateway before reaching Customer are outside this finite registry. Existing rate/retention/capacity limits still apply; this lot adds no claim of indefinite Kafka retention or independent immutable backup.

Acceptance requires signed HTTP success/denial/invalid-credential tests, withheld data on audit storage failure, restricted-role PostgreSQL immutability and migration tests, actual outbox delivery plus central replay/redaction, and hosted PKCE regression. Evidence is recorded only after execution.
