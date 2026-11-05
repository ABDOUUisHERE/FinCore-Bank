# Phase 8 lot 5 — temporary support account access

Status: implementation in progress. Previous accepted lots remain unchanged.

Scope: ADMIN grants or revokes ACCOUNT_STATUS_READ for an active ACCOUNT incident, fixed to its creator and account UUID. SUPPORT_AGENT may explicitly request a redacted status observation through that incident. Expiry, foreign ownership, resolution and revocation deny access. No support customer search, payment inspection, journal or financial mutation is included.

Acceptance requires signed HTTP role and machine-client checks; PostgreSQL receipt atomicity, concurrency, expiry and resolution/reopening checks; no disclosure after an in-flight revocation or failed audit write; browser confirmation and lost-response replay; a hosted PKCE grant/inspect/revoke journey; preserved-volume native upgrade and passing feature/main CI. Results will be recorded only after execution.
