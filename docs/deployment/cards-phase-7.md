# Phase 7 cards and statements

Milestone validation is still in progress; final evidence belongs in [Phase 7 verification](../verification/phase-7.md).

For a fresh checkout, run the normal bootstrap and Compose startup from the repository root. Bootstrap generates all new Card/Reporting database and client secrets, a Card Kafka identity and the finance demo password. It retains existing secrets on subsequent runs. Business identity configuration supports both fresh and persisted realms.

The runtime adds private `card-service` and `reporting-service` containers. Their databases, migrations and runtime roles are separate. No additional host port is exposed: Customer remains `http://localhost:5173`, Operations `http://localhost:5174`, Gateway `http://localhost:8280` and Keycloak `http://localhost:8180`.

For persisted Phase 6 data, retain the matching `.env`, `.local/credentials.json`, realm/Kafka configuration and named volumes. Re-run bootstrap, rebuild changed service images and run the Card/Reporting bootstrap and migration jobs. Ledger V4-V6 and Fraud V2 are additive upgrades; no earlier migration or journal is rewritten. Re-run business identity setup. The generated Kafka configuration must be copied through `kafka-config`, then the existing FinCore broker restarted and its topics/ACL job rerun so the new Card producer identity is accepted. Recreate only the changed FinCore application containers after their migrations. Do not reset WSL, prune Docker or remove volumes for this upgrade. The final verification report records the exact executed upgrade sequence.

Sign in again after identity configuration so new delegated scopes are present. The generated `finance.demo` user has FINANCE_OPERATOR; ordinary `admin.demo` does not acquire this role. Passwords remain in ignored local configuration.

Customer demo:

1. Open an active funded synthetic account and choose **Cards**. Review account, payment limit and UTC daily limit before confirming creation.
2. Simulate a Demo Market authorization. AUTHORIZING and under-review states do not claim funds have been reserved. HELD shows actual reserved funds and expiry; the account panel distinguishes booked, reserved and available.
3. Capture the full payment, release its reservation or refund one captured payment in full. Recorded journals identify booked money; a failed refund explicitly remains unposted. Refunds retain daily usage.
4. Freeze/unfreeze with confirmation. Freeze blocks new holds; existing admitted holds remain capturable. Release holds before terminating the card.
5. Open **Statements**, choose an account and UTC month, and generate the latest immutable snapshot. Opening plus signed booked movements equals closing. Current availability is labeled at the snapshot observation time. Download CSV/PDF through authenticated buttons.
6. Use **Reconciliation** in Operations with `finance.demo`. ADMIN alone cannot access it.

New merchant spending above 5,000.00 triggers the default new-merchant review signal; values above the configured hard limit block. An analyst reviews canonical Card context in the existing Fraud workspace. Current rules remain visible and versioned. Card limits and funds are rechecked after an approval, so an approval alone does not guarantee a hold.

A lost mutation response keeps the original owner-scoped request reference and body in browser session storage. Retry that exact request. The service's durable recovery does not store browser tokens and checks existing Ledger outcomes before attempting more work. Fraud outages leave admission pending with no permissive fallback. Established holds can still be captured or released independently of a fresh Fraud assessment, subject to their expiry and account guards.

Exports are capped at 1,000 entries per month. An oversized month fails explicitly; a truncated report is never saved. This phase does not provide real card credentials, networks, partial settlement, performance guarantees or Phase 8 operational administration.
