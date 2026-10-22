# ADR 0004 — Keycloak identity with independent service authorization

Status: accepted on 2026-10-22, following approval to start Phase 1.

## Context

The platform has customer and staff roles, sensitive financial objects and browser clients. Gateway-only authorization or client-supplied actor IDs would allow privilege and ownership bypass.

## Decision

Use Keycloak, public browser clients, Authorization Code with PKCE S256, in-memory tokens and exact origins/redirects. Each backend independently validates issuer, audience, signature, expiry and scope and enforces object permissions. Administrative roles do not automatically inherit financial privileges. Sensitive staff changes need additional authorization/MFA and append-only audit.

Internal requests use narrow service credentials and verifiable delegated customer identity where required. Ledger validates entitlements and financial guards under its posting locks. Secrets stay in generated local configuration or external secret management, never version control.

## Alternatives and consequences

A custom password/token implementation duplicates a mature identity provider. Gateway-only checks fail when a backend is reached directly. A cookie/BFF architecture is possible but would add a separate CSRF/session design; it is deferred. Memory-only bearer tokens still require XSS prevention and careful refresh/expiry behavior. Phase 1 must demonstrate the real browser PKCE flow; Phase 3 must prove delegated identity enforcement across the ledger boundary.
