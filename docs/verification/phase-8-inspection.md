# Phase 8 lot 2 — business inspection

Date: 2026-11-04 UTC. Starting main: `93afcc4`. **Implementation and validation in progress; no acceptance claimed yet.**

This lot adds exact-reference customer/payment status for ADMIN and authoritative journal reads for FINANCE_OPERATOR, with independent Gateway/service checks and no financial command. See the [scope and local upgrade guide](../deployment/inspection-phase-8.md) and [three-route contract](../../contracts/openapi/inspection-v1.yaml).

Planned acceptance: signed role/method checks at every HTTP seam; real PostgreSQL projection/redaction, missing records and >50-transition pagination; frontend role/precision/cursor checks; hosted PKCE on Docker with a real posted transfer, exact journal amounts, unchanged balances and desktop/mobile inspection screens; all three GitHub workflows on the final application source and merged main.

The initial workstation checks found Docker manually paused and stale workspace junctions after the repository folder was renamed. The user resumed Docker; all 16 existing FinCore runtimes were healthy before upgrade. The frontend dependency installation is being refreshed. No volumes, credentials, unrelated projects or application language were changed.

Phase 8 remains incomplete. Customer management/search and account status, scoped support business grants, complete critical-action/security/read auditing, tracing and the six observed Grafana dashboards remain outside this lot. Earlier [Audit/Incidents acceptance](phase-8.md) is preserved.
