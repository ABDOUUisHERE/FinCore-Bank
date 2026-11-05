# Phase 8 lot 6 — temporary support payment access

Status: implementation in progress. Baseline main: `2055889dab72817f0fa7b889579b822c13d29e3e`.

This lot adds ADMIN-issued PAYMENT_STATUS_READ for the creator and immutable resource of an active PAYMENT incident. The support observation includes only payment reference, kind, status, exact string version and observation/creation times. It excludes amounts, actors, account and journal links, free-text reference, risk assessment and worker internals. SUPPORT_AGENT cannot search payments or initiate, approve, cancel, fund or reverse them.

Account and payment grants share one authorization/receipt implementation with fixed resource policies. The existing account table, command fingerprints, response fields and receipts remain compatible. An additive payment table uses the same version/expiry/immutability rules; incident resolution revokes both supported resource kinds atomically. Each resource has a separate machine authority, protected owner route and consumer projection. Admission and final disclosure remain separate transactions around HTTP; type mismatch, foreign ownership, expiry, grant replacement, revocation or resolution deny disclosure.

Acceptance requires a preserved V2-to-V3 upgrade including an existing account grant/receipt; real PostgreSQL cross-kind/global-key/closure checks; signed Gateway/Audit/Payment permission tests; strict redacted consumer validation; browser review/replay/denial handling; actual hosted PKCE payment observation and account regression; passing feature and main CI. Only executed results will be reported as accepted.
