# Phase 8 lot 6 — temporary support payment access

Date: 2026-11-09 UTC. Lot 6 is accepted on the feature branch; main integration is recorded separately below. Phase 8 remains in progress. Baseline main: `2055889dab72817f0fa7b889579b822c13d29e3e`. Tested application source: `fac25b9eb641fca9d629fd893b82d27b3219701d`.

This lot adds ADMIN-issued PAYMENT_STATUS_READ for the creator and immutable resource of an active PAYMENT incident. The support observation includes only payment reference, kind, status, exact string version and observation/creation times. It excludes amounts, actors, account and journal links, free-text reference, risk assessment and worker internals. SUPPORT_AGENT cannot search payments or initiate, approve, cancel, fund or reverse them.

Account and payment grants share one authorization/receipt implementation with fixed resource policies. The existing account table, command fingerprints, response fields and receipts remain compatible. An additive payment table uses the same version/expiry/immutability rules; incident resolution revokes both supported resource kinds atomically. Each resource has a separate machine authority, protected owner route and consumer projection. Admission and final disclosure remain separate transactions around HTTP; type mismatch, foreign ownership, expiry, grant replacement, revocation or resolution deny disclosure.

Payment independently requires signed `azp=fincore-audit` and `payment.support.read`. Audit remains the trusted dynamic per-object broker; its machine identity can technically read redacted status outside a grant if compromised. It gains no financial command or Ledger authority. See the [contract](../../contracts/openapi/support-payment-access-v1.yaml), [upgrade guide](../deployment/support-payment-phase-8.md) and [ADR 0012](../fincore/adr/0012-online-support-account-access.md).

ADMIN reviews the fixed recipient/payment, duration and exact string version. Unknown-response commands retain the same body and actor-global key across reloads. Receipts remain historical; current permission refreshes separately. The common grant frame checks the returned incident, recipient, resource and permission before rendering actions. Observations remain component-local, are requested only on a click and clear on a subsequent attempt, failure, permission refresh, local expiry or leaving the view.

## Measured checks

| Check | Observed result |
| --- | --- |
| [Feature backend](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/42596916605) | 270 cases in 53 classes; no failures, errors or skips |
| [Feature frontend](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/42596916413) | 55 cases in 11 files; types, lint, both production builds and high-severity audit gate pass |
| [Feature integration](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/42596916411) | Fresh Compose and 13 browser journeys; independent direct authentication, Ledger smoke and Kafka ACL/DLQ/replay pass |
| Native Java | 49 unique focused cases; Gateway class deduplicated; includes 14 real PostgreSQL grant cases and 11 Payment recovery cases |
| Native frontend | All 55 pass with ignored one-worker hardware wait configuration; GitHub uses repository defaults |
| Native migration | Audit V2 upgraded to V3; existing Account grant and all 19 command receipts unchanged; profile/account/payment/journal/incident counts unchanged before browser fixtures |
| Secrets | Entire .env and user credential bytes unchanged; no new secret or volume deletion |
| Actual machine identity | Only narrow Account/Payment business read roles; six-field Payment read succeeds; broad Payment lookup/list/ADMIN, existing operator at the support route and Ledger audience access denied; Account read preserved |
| Native hosted PKCE | Account and Payment journeys pass; four identities, real resources, exact original receipt replay, foreign-scope denial, UI revoke/masking, resolution/reopen denial and 1440/390/320 px checks |
| Financial effects | PostgreSQL projection leaves Payment row/history/outbox and booked balances unchanged; hosted terminal Payment and source balance remain unchanged after consultations |
| Runtime and dependencies | 16 local runtimes healthy; production npm audit reports zero vulnerabilities |

The [machine-readable evidence](../evidence/phase-8-support-payment-checks.json) contains exact workflow/job IDs, measured counts and preservation results. GitHub attributes the eight implementation/design/test commits made on November 9 and the earlier scope commit to ABDOUUisHERE. Calendar totals are checked after main integration; branch-only counts are not presented as visible contributions.

PostgreSQL verifies the original Account fingerprint and receipt across V2-to-V3, cross-kind denial, actor-global key conflicts, immutable identities, concurrent versions, in-flight expiry/replacement/revocation/resolution and atomic rollback of a failed resolution receipt. Existing Account tests exercise the shared module with concurrent revocation during HTTP and an injected final audit failure. Payment tests prove strict resource/schema validation and withholding when admission or final storage fails. Signed Gateway/Audit/Payment tests cover roles, methods, machine identity, audience, forged headers and unknown JSON fields.

## Verification failures resolved

An ambiguous generic AssertJ assertion initially prevented migration-test compilation. Explicitly assigning the replayed JsonNode resolved it; the affected modules subsequently passed locally and the complete backend passed on GitHub.

The local stack was initially stopped. Dependencies were started without deleting volumes; identity setup first encountered unavailable Keycloak and then a cold authentication timeout. After readiness, persisted identity setup completed with unchanged credentials. The first native browser pair encountered an incident HTTP 504 and an unconfirmed revocation response during cold startup. The UI retained the uncertain request. An unchanged rerun after stabilization passed both complete journeys in 26.8 and 32.8 seconds. No application timeout or assertion was weakened to obtain those passes.

## Scope limits

Payment processing status does not certify a Ledger posting. DISCLOSED records an authorization decision, not proof of browser receipt. Delivered information cannot be withdrawn retroactively. Authentication, role and malformed-body denials precede the domain audit decision path. Customer support scope, complete critical/read/security audit coverage, tracing and six observed dashboards remain later Phase 8 work. Phases 9–11 remain unaccepted. Authenticated identifiers, screenshots, credentials and raw logs remain private.
