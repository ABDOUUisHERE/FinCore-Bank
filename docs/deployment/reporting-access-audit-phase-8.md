# Reporting access audit — Phase 8 lot 10

Scope: CUSTOMER statement request/list/detail/CSV/PDF and separate FINANCE_OPERATOR reconciliation/journal inspection. Existing ownership and role checks remain authoritative. Every registered bounded synchronous response must have an immutable owner receipt committed before disclosure. Signed role denials and absent/invalid credentials reaching the registry are included; Gateway, early servlet/firewall rejection and other routes remain outside it.

| Route category | Action | Optional reference |
| --- | --- | --- |
| POST `/api/v1/statements` | STATEMENT_CREATE_REQUEST | None; never request body/account/month |
| Other methods `/api/v1/statements` | STATEMENT_LIST | None; never cursor |
| `/api/v1/statements/{id}` | STATEMENT_INSPECT | Canonical statement UUID |
| `/api/v1/statements/{id}/csv` | STATEMENT_EXPORT_CSV | Canonical statement UUID |
| `/api/v1/statements/{id}/pdf` | STATEMENT_EXPORT_PDF | Canonical statement UUID |
| `/api/v1/admin/reports/reconciliation` | RECONCILIATION_INSPECT | None |
| `/api/v1/admin/ledger/journals/{id}` | JOURNAL_INSPECT | Canonical journal UUID |

Categories record attempts for all methods; they add no authorization. The signed current caller is distinct from a statement owner or Ledger technical client. Actor provenance, finite outcomes and independent three-second receipt transaction follow the [owner read contract](owner-read-audit-phase-8.md). RESPONSE_READY describes a prepared response, not delivery, financial success or a newly created statement. Creation can reuse an existing immutable snapshot. A committed snapshot may survive subsequent receipt failure; the prepared response must be withheld, and retry must preserve its original content/hash and identity.

Reporting responses have a four MiB buffering ceiling. An oversized prepared response is discarded with sanitized 503 RESPONSE_TOO_LARGE and a server-error receipt, rather than returning partial content. Receipt INSERT/transaction failure returns sanitized 503 AUDIT_UNAVAILABLE, without a private attachment filename or prepared body. Broker/Audit outages leave the local immutable receipt for retry. No raw URLs, body/month/filter text, statement content/hash, amounts, credentials or tokens enter the event.

Additive Reporting V2 preserves V1 snapshots and adds a separate immutable receipt/outbox source plus mutable delivery metadata. A dedicated Reporting Kafka producer can write only its own prefix; central Audit admits only the typed reporting.access.recorded event for this new prefix. It does not accept arbitrary Reporting domain facts. Existing owner event shapes and producer authority remain unchanged. Migration/database administration stays trusted, and existing Kafka capacity/retention and transport limits still apply.

Acceptance requires real signed HTTP and PostgreSQL tests, committed-snapshot/failed-receipt retry, append-only and lost-ack replay checks, preserved-data upgrade, actual producer isolation and central redaction/quarantine, and hosted PKCE CSV/PDF/finance regression. Results will be recorded after execution; this document does not accept the lot or complete Phase 8.
