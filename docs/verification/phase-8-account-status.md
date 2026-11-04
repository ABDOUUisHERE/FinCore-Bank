# Phase 8 lot 3 — account status inspection

Date: 2026-11-04 UTC. Base: `29cb2b8`. **Implementation and validation in progress; no acceptance claimed yet.**

The [operational guide](../deployment/account-status-phase-8.md) defines ADMIN-only exact-reference account inspection with recorded intent, separately verified Ledger status, explicit availability states and no mutation. The additive [contract](../../contracts/openapi/inspection-v1.yaml) leaves prior customer/payment/journal permissions unchanged. Support business grants and full customer management remain subsequent work.

Acceptance requires signed Gateway/Account/Ledger checks, actual PostgreSQL projection and guard reads, no inspection-triggered recovery, frontend precision/stale-state checks, hosted PKCE with a real restriction and Ledger outage/restore, responsive QA and successful feature/main workflows. Executed results will replace this pending status.

The first native Maven invocation selected JAVA_HOME's installed Java 11 and stopped before application compilation (`release version 21 not supported`). Subsequent checks select the already-installed Java 21 compiler for the task process only. No global Java setting is changed.
