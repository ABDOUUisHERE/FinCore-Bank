# Phase 7 cards and statements

Milestone acceptance passed; measured evidence belongs in [Phase 7 verification](../verification/phase-7.md).

For a fresh checkout, run the normal bootstrap and Compose startup from the repository root. Bootstrap generates all new Card/Reporting database and client secrets, a Card Kafka identity and the finance demo password. It retains existing secrets on subsequent runs. Business identity configuration supports both fresh and persisted realms.

The runtime adds private `card-service` and `reporting-service` containers. Their databases, migrations and runtime roles are separate. No additional host port is exposed: Customer remains `http://localhost:5173`, Operations `http://localhost:5174`, Gateway `http://localhost:8280` and Keycloak `http://localhost:8180`.

For persisted Phase 6 data, retain the matching `.env`, `.local/credentials.json`, realm/Kafka configuration and named volumes. Re-run bootstrap, rebuild changed service images and run the Card/Reporting bootstrap and migration jobs. Ledger V4-V6 and Fraud V2 are additive upgrades; no earlier migration or journal is rewritten. Re-run business identity setup. The generated Kafka configuration must be copied through `kafka-config`, then the existing FinCore broker restarted and its topics/ACL job rerun so the new Card producer identity is accepted. Recreate only the changed FinCore application containers after their migrations. Do not reset WSL, prune Docker or remove volumes for this upgrade. The final verification report records the exact executed upgrade sequence.

After bootstrap and successful builds, the tested upgrade order is:

```sh
docker compose run --rm --no-deps -T card-bootstrap
docker compose run --rm --no-deps -T reporting-bootstrap
docker compose run --rm --no-deps -T ledger-migrations
docker compose run --rm --no-deps -T fraud-migrations
docker compose run --rm --no-deps -T card-migrations
docker compose run --rm --no-deps -T reporting-migrations
docker compose run --rm --no-deps -T business-identity
docker compose run --rm --no-deps -T kafka-config
docker compose restart -t 20 kafka
```

Wait for the existing FinCore broker to become healthy before the remaining commands:

```sh
docker compose run --rm --no-deps -T kafka-init
docker compose up --no-build --no-deps -d --wait --wait-timeout 360 ledger-service fraud-service notification-service card-service reporting-service api-gateway customer-portal admin-portal
```

This sequence upgraded Ledger V3 to V6 and Fraud V1 to V2 on the retained workstation volume, created the two new schemas at V1 and ended with fifteen healthy runtime services. Hashes of `.env` and `.local/credentials.json` matched before/after this cutover; unrelated Commerce container identities also matched. Normal health is a startup check; the separate acceptance report records financial/browser results.

Card V2 subsequently replaces the shared identity trigger with explicit table-specific branches. This avoids cached PostgreSQL trigger record shapes reading another table's columns on a reused connection. It changes no identity, journal or saved receipt. Rebuild the Card image and rerun `card-migrations` to apply it on a Card V1 volume before acceptance.

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

Card/Reporting Gateway routes use a bounded 20-second response budget because account lookup, delegated exchange and Ledger reads each have their own five-second bound. Their browser calls allow 25 seconds. These per-route budgets avoid the earlier five-second Gateway cutoff on valid multi-call work; they are not a measured latency guarantee. Ambiguous financial responses still require exact-identity recovery. [Spring's timeout documentation](https://docs.spring.io/spring-cloud-gateway/reference/4.3/spring-cloud-gateway-server-webflux/http-timeouts-configuration.html) defines route metadata in milliseconds.
