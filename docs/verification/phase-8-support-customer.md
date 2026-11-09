# Phase 8 lot 7 — scoped support customer discovery

Status: implementation in progress. Baseline main: `0892b3131a4ef91d7d0143a6b7a80e1df2c78d4e`.

ADMIN may grant CUSTOMER_PROFILE_READ to the creator and immutable resource of an active CUSTOMER incident. SUPPORT_AGENT may explicitly read that customer's limited profile and search a literal name prefix only inside the current set of such grants. The owner requires signed fincore-audit plus customer.support.read. No subject, country, account, financial content or profile mutation is exposed. Account/Payment tables, identities and original receipts remain unchanged.

Acceptance includes a preserved V3-to-V4 upgrade; real PostgreSQL scope isolation, bounded literal search, global receipt compatibility, ordered batch locking, in-flight invalidation and final audit failure; signed Customer/Audit/Gateway role and machine checks; transient UI results and command recovery; actual hosted PKCE discovery/profile observation, foreign/no-grant denial and revocation; full feature and main CI. The [scope decision](../fincore/adr/0013-scoped-support-customer-discovery.md) defines batch semantics. Only executed results will be marked accepted.
