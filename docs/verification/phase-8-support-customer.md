# Phase 8 lot 7 — scoped support customer discovery

Date: 2026-11-09 UTC. Lot 7 is accepted and merged on main. Phase 8 remains in progress. Baseline main: `0892b3131a4ef91d7d0143a6b7a80e1df2c78d4e`. Tested application source: `1ff018b6a4c2dbfb2951d36299ade7b50cdab58d`.

ADMIN may grant CUSTOMER_PROFILE_READ to the creator and immutable resource of an active CUSTOMER incident. SUPPORT_AGENT may explicitly read that customer's limited profile and search a literal name prefix only inside the current set of such grants. The owner requires signed fincore-audit plus customer.support.read. No subject, country, account, financial content or profile mutation is exposed. Account/Payment tables, identities and original receipts remain unchanged.

The [scope decision](../fincore/adr/0013-scoped-support-customer-discovery.md) defines batch semantics. Audit selects at most 100 current owned incident grants, locks all selected incidents in UUID order before the retained-fact cursor, releases locks for HTTP, and rechecks the complete cohort before disclosure. Any expiry, replacement, revocation or resolution withholds all results, including when the changed grant had no displayed match. Customer IDs are deduplicated for the owner request. Newly issued grants enter the next search. Customer treats SQL/LIKE metacharacters literally and returns at most 20 authorized matches, without a global count.

ADMIN reviews recipient, exact customer, duration and string version; uncertain commands retain the same key/body across reloads. Support searches only on submit and consults the six-field profile only on a separate click. Results remain component-local, with no names/prefixes in URLs, retained facts, command persistence or query caches. New attempts, denials, local expiry, clear actions and leaving clear observations. Source schema/resource validation and successful final audit are required before disclosure. See the [contract](../../contracts/openapi/support-customer-access-v1.yaml) and [upgrade guide](../deployment/support-customer-phase-8.md).

## Measured checks

| Check | Observed result |
| --- | --- |
| [Feature backend](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/42605432612) | 287 cases in 57 classes; no failure, error or skip |
| [Feature frontend](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/42605432520) | 64 cases in 13 files; types, lint, both production builds and high-severity audit gate pass |
| [Feature integration](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/42605432549) | Fresh Compose and 14 browser journeys; independent direct authentication, Ledger smoke and Kafka ACL/DLQ/replay pass |
| Focused native Java | 45 unique cases: Gateway 10, Customer support signed routes 2, input validation 1, Customer PostgreSQL 5, broker validation/failure 6, Audit signed routes 2, PostgreSQL grants 19 |
| Native frontend | All 64 pass; ignored one-worker hardware wait configuration, repository defaults retained and passing on GitHub |
| Preserved migration | Audit V3 upgraded to V4; both existing Account grants, both Payment grants and all 39 command receipts unchanged; profile/account/payment/journal/incident counts unchanged before browser fixtures |
| Secrets and data | Entire .env and credential bytes unchanged; no added/rotated secret or deleted volume |
| Real machine identity | Only narrow Account/Payment/Customer business read roles, no staff/customer realm role; exact six-field profile and restricted owner search succeed; ADMIN directory, risk/mutation routes and existing Fraud worker at support routes denied |
| Native hosted PKCE | Three journeys pass: Account 41.6s, Payment 37.9s, Customer 21.5s; exact receipt recovery, discovery/profile, no/foreign scope, revoke masking, resolution/reopen denial and unchanged customer profile |
| Visual/runtime/dependencies | Positive Customer observation inspected at desktop/mobile; layout assertions at 1440/390/320 px; 16 runtimes healthy; production npm audit reports zero vulnerabilities |

Exact workflow/job IDs and preservation measurements are in the [machine-readable evidence](../evidence/phase-8-support-customer-checks.json). Native screenshots and authenticated identifiers remain private. The Java PostgreSQL tests exercise restricted runtime identities, original Account/Payment receipts across additive upgrades, resource isolation, actor-global keys, immutable grants, over-100 cohort refusal, expiry/replacement/revocation/resolution and failed final storage. A separate transaction revokes a grant during the owner callback, proving no incident lock spans HTTP and the complete result is withheld. Customer queries prove literal prefix behavior, bounded authorized IDs, exact bigint versions and unchanged profile/outbox storage.

## Verification corrections

The preserved draft contained a nested-lambda variable name collision; a new fixture also needed its List import. Both compile errors were corrected before successful affected-module and full GitHub builds. An early local migration invocation used the previous runtime image and left V3 unchanged; after the new Audit image was built, V4 applied and the explicit version/hash/count checks passed. Native browser assertions passed unchanged on their first complete run for this lot.

## Limits and remaining Phase 8 work

Audit is trusted for dynamic object authorization; its signed machine role can technically read the redacted owner projection without a grant if compromised. Customer independently enforces machine identity and narrow authority. A DISCLOSED record is an authorization decision, not proof of browser receipt, and previously delivered names cannot be withdrawn retroactively. Authentication, role and malformed-input denials precede this domain decision path.

The new [audit coverage inventory](phase-8-audit-coverage.md) identifies missing security/read/export sources; it does not implement or certify full coverage. Complete critical-action/security/read audit, collected distributed tracing and six observed dashboards remain Phase 8 work. Phases 9–11 are not accepted by this lot. GitHub contribution totals are measured after main integration; empty commits and altered dates are not used.

## Main integration

Merged as `fd312bfa53fa9c620e2f7a260bb8a74fb9b0b6ae` on November 9 UTC. Application, configuration and tests are identical to tested source `1ff018b6a4c2dbfb2951d36299ade7b50cdab58d`. Later acceptance, roadmap and proof changes contain documentation only. All independent main workflows pass:

| Workflow | Observed result |
| --- | --- |
| [Backend quality](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/42608210164) | 287 cases in 57 classes; zero failure/error/skip |
| [Frontend quality](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/42608210160) | 64 cases; types, lint, both builds and dependency gate pass |
| [Foundation integration](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/42608210156) | 14 browser journeys; fresh Compose, independent auth, Ledger and Kafka ACL/DLQ/replay pass |

The positive Customer observation was also inspected on desktop and mobile using hosted PKCE; layout checks pass at 1440/390/320 px and authenticated captures remain private. The verification grant remains revoked after incident reopening. All 16 local runtimes were healthy.

Before this final proof, GitHub associated all 12 new commits with ABDOUUisHERE and reported 24 contributions for November 9 UTC, up from 12 before this lot. This proof is an additional documentation commit. The target of one hundred contributions has not been reached; no dates or empty commits were manufactured. Phase 8 remains incomplete.
