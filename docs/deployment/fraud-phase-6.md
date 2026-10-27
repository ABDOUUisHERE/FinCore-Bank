# Phase 6 local upgrade and analyst demo

This is a synthetic-money local environment. Preserve `.env`, `.local/credentials.json`, generated realm/Kafka files and all named volumes. Existing financial migrations remain unchanged; Payment adds V4 and Fraud creates its own database/schema. Never use `down -v` for an upgrade.

Run `python scripts/bootstrap-local.py` to append missing Fraud/verification credentials without rotating existing secrets. For an existing stack, quiesce only `payment-service` before installing the new authorization boundary. Build the changed images. Run the idempotent Fraud bootstrap, Fraud migrations, Payment migrations and business-identity setup with `docker compose run --rm --no-deps <job>` while PostgreSQL/Keycloak are healthy. Run `kafka-config`, restart only FinCore's `kafka` so it reloads the generated Fraud SASL identity, then run `kafka-init` to add the Fraud topic/ACL. Preserve its data/config volumes.

Start updated Customer, Beneficiary and Ledger, then Fraud, Payment, Notification, Gateway and both portals. Wait for readiness. Normal fresh setup remains `docker compose up --build -d --wait --wait-timeout 600`. There are thirteen long-running services after this milestone. Bootstrap/migration/identity jobs exit successfully; they are not additional running services.

Open `http://localhost:5174`, using exactly `localhost`, and sign in as `analyst.demo` with the generated `analyst` password in ignored `.local/credentials.json`. FRAUD_ANALYST has investigation access; `admin.demo` alone does not. The analyst has no funding or money-execution privilege.

Create a fresh synthetic customer, an active MAD account with private simulation funding and a saved beneficiary to another synthetic account. A 6000.00 MAD transfer to that new beneficiary reaches pending review under the initial rules. No funds are reserved or posted. Open Fraud cases, investigate the exact payment, inspect the original alert and recorded explanations, enter an uppercase reason code and confirm the decision. The UI records the decision first. Payment separately rechecks rules and velocity, and only Ledger confirmation produces COMPLETED and a journal reference. A strict blocking rule cannot be approved.

Fraud rules shows the current immutable version and separate MAD/EUR/USD amount thresholds. Review and confirm a change with a reason. New assessments use the new version; already issued grants remain bounded by their thirty-second expiry. Unknown HTTP outcomes retain the exact original intent for retry. Refresh recorded rules/cases before resolving a known version conflict.

For an outage exercise, stop only `fraud-service`, submit a fresh small transfer, verify that it remains validating without a ledger journal, then start Fraud again. The same durable Payment should recover one posting. An existing Ledger outcome remains recoverable even if Fraud is down. Do not stop Docker Desktop, WSL or unrelated projects for this check.

The real hosted-PKCE journey is `npx playwright test tests/e2e/fraud.spec.ts`. Authenticated screenshots are opt-in with `FINCORE_VISUAL_QA=1` and stay in ignored `.local/visual`; they must not be published as CI artifacts. See the [Phase 6 report](../verification/phase-6.md), [Fraud API](../../contracts/openapi/fraud-v1.json) and [ADR 0009](../fincore/adr/0009-synchronous-fraud-and-investigation.md).
