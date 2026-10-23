# Local FinCore setup

The foundation provides authentication, protected service communication and two portal shells. Phase 3 adds owned account and payment APIs backed by the private ledger; browser banking forms are Phase 4. All identities and money are synthetic.

## Prerequisites

Git, Python 3.10+ and a running Docker Engine with Docker Compose v2. Allow several GB of RAM and disk space. Node 24 and Java 21 are required only for native development and tests; container builds include their build tools. Windows users must start Docker Desktop in Linux-container mode. An installed Docker CLI does not prove that its engine is ready: `docker version` must show a Server section.

## Start

```sh
git clone https://github.com/ABDOUUisHERE/FinCore.git
cd FinCore
git switch main
python scripts/bootstrap-local.py
docker compose up --build -d --wait --wait-timeout 300
docker compose run --rm ledger-check
```

On Linux/macOS, use `python3` if `python` is not available. Equivalent bootstrap entry points are `sh scripts/bootstrap-local.sh` and `powershell -ExecutionPolicy Bypass -File scripts/bootstrap-local.ps1`.

| Interface | URL | Access |
| --- | --- | --- |
| Customer workspace | http://localhost:5173 | Sign in as generated `customer.demo` |
| Operations workspace | http://localhost:5174 | Sign in as generated `admin.demo`; customer identities are denied |
| Gateway readiness | http://localhost:8280/actuator/health/readiness | Redacted process readiness |
| Identity provider | http://localhost:8180 | Hosted login; `platform-admin` is local infrastructure administration only |

Use **localhost**, not `127.0.0.1`, in browser URLs: OIDC redirect URIs, origins and issuer are exact. Container JWKS requests use the internal Keycloak DNS name while validating the same public issuer. No issuer verification is disabled. PostgreSQL and Customer Service are not published to the host.

The bootstrap writes random secrets to ignored `.env`, synthetic user credentials to ignored `.local/credentials.json`, and an ignored realm import containing those synthetic users. View the credential file locally to sign in. The infrastructure-admin password is the generated `KEYCLOAK_ADMIN_PASSWORD` in `.env`. Never commit, share, paste into issues or retain these files in CI artifacts. The bootstrap is idempotent: subsequent calls preserve matching files. It refuses an incomplete configuration instead of rotating credentials against an existing volume.

On Unix, generated files are mode 0600. A network-isolated one-shot `realm-config` container copies the realm into a dedicated named volume, assigning it to Keycloak's non-root UID with mode 0640. This keeps the host credential file private while allowing the container to read it. The init container briefly runs as root for that ownership change; Keycloak and application runtimes remain non-root. A successful exited init container is expected.

Keycloak imports the realm only when it does not already exist. The Phase 2 `ledger-identity` one-shot deliberately configures the private technical client on both fresh and existing realms. It neither rewrites public clients nor reimports users. The local bootstrap appends only missing ledger secrets to `.env`; existing foundation secrets remain stable. `ledger-bootstrap` creates the separate database/roles if absent, and Flyway migrates it. No volume reset is needed to upgrade Phase 1.

## Service ownership

One local PostgreSQL container hosts separate `customer` and `keycloak` databases. PUBLIC cannot connect to either. Customer runtime can connect only to its own database and SELECT foundation metadata; it cannot create tables, modify metadata or access Keycloak. A one-shot `customer-migrations` container runs Flyway with `customer_migrator`; its successful exit is required before Customer starts. The runtime container has no migration password. An exited migration container with status 0 is expected.

The same container now also hosts an isolated `ledger` database. `ledger_runtime` has SELECT and controlled function execution; `ledger_migrator` owns schema changes. The ledger runtime has no bootstrap or migration password. `ledger-bootstrap`, `ledger-migrations` and `ledger-identity` must exit 0 before it becomes ready. Neither PostgreSQL nor Ledger publishes a host port in Compose. The Gateway has no Ledger route. Run the synthetic HTTP journey from the private network with `docker compose run --rm ledger-check`; it obtains a token in memory and never prints credentials.

The privileged local `fincore-ledger-operator` client is for this isolated technical journey. Do not distribute its secret to portals. Its tokens require the `ledger-service` audience and explicit client roles. The `KEYCLOAK_ADMIN_PASSWORD` is confined to identity configuration; it is never passed to the ledger runtime/check. See [ADR 0005](../fincore/adr/0005-ledger-write-boundary.md) for limitations and future narrower Payment/Account identities.

All published ports bind to loopback. Keycloak uses development HTTP mode for this local environment. This Compose configuration is not an internet-facing production deployment. Gateway's 300-request/minute budget is shared by all requests in that one process and resets on restart; distributed Redis-based limits belong to a later milestone. Declared API bodies above 16 KiB and chunked API requests are rejected. Only the documented GET API is authorized.

## Development

```sh
npm ci
npm run dev:customer
# In another terminal
npm run dev:admin
```

Stop the corresponding portal containers first to free ports 5173/5174; leave Gateway, Customer, Keycloak and PostgreSQL running. Vite uses the same exact public client origins. For Java changes, rebuild the corresponding service container. Native Customer startup needs a migrated PostgreSQL database and `CUSTOMER_DATABASE_URL`/`CUSTOMER_DB_PASSWORD`; use the separate `--migrate-only` command with migration credentials beforehand. Do not give migration credentials to the runtime process.

## Stop and troubleshoot

```sh
docker compose ps -a
docker compose logs --tail 80 customer-migrations customer-service api-gateway keycloak
docker compose down
```

Normal shutdown keeps the `postgres-data` volume. Removing volumes deliberately destroys all synthetic identities and service data; do not do that just to rotate one password. Back up the matching ignored configuration if you need the dataset again. Never publish unreviewed logs/configuration dumps: `docker compose config` can expand secrets; prefer `docker compose config --quiet` for validation.

If Docker calls hang, resolve Docker Desktop/engine readiness before starting the stack; Java/frontend unit checks can still run independently. If a sign-in fails, verify the exact localhost URL, generated user credentials, Keycloak health and persisted realm configuration. If Customer readiness fails, inspect the migration job and database grants rather than disabling its DB health check. Browser code never receives database or client secrets.

## Phase 3 services and upgrades

`business-bootstrap` creates isolated Account/Payment databases and separate runtime/migration roles without changing existing secrets or resetting volumes. `business-identity` creates narrow service clients and configures standard token exchange on fresh and persisted Keycloak realms. A generated second synthetic identity, `recipient.demo`, is recorded in the private credentials file; existing user passwords remain unchanged. Applications start only after their migrations, identity setup and dependencies are ready.

The Account runtime receives only its own DB password and Account client secret. Payment receives only its own DB password and Payment client secret; it does not receive the simulation operator secret or ledger migration credentials. Ledger remains internal with no Gateway route. Account/Payment also have no published host ports. Public `/api/v1/accounts` and `/api/v1/transfers` routes are independently authorized by their owners. The private funding/review/reversal routes are not routed by Gateway.

To verify the financial journey, install Chromium and run `npm run test:e2e`. The new browser test signs in two customers with real Authorization Code + PKCE, passes their short-lived access tokens to `business-check` through stdin only and exercises the private network. No token is written to disk or command-line arguments. Running `business-check` by itself without those genuine tokens is not a browser-login substitute. The isolated native verification option is documented in the test guide.

Accepted payment responses remain 202/CREATED on exact replay. Read the Location resource for its current state. Pending risk review reserves no funds. Cancellation is possible only before PROCESSING; a timeout after sending money keeps the payment PROCESSING until the ledger outcome is known. Account provisioning/guard intent can similarly remain pending through an outage and reconcile on recovery. All domain money is synthetic.
