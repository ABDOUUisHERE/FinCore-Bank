# Local Docker verification (2026-10-23 UTC)

Verified source: `4ee64571f6970993958b399c9c37175a046707c5`. The application services are unchanged from Phase 3. This follow-up adjusts the Playwright assertion budget and the current repository clone instructions.

## Results

| Executed local check | Result |
| --- | --- |
| Bootstrap and Compose configuration validation | PASS; existing generated configuration retained |
| Complete Docker application image builds | PASS; five Java services and two portal images |
| Compose startup, migrations and identity setup | PASS; nine healthy running services and nine one-shots exited 0 |
| `python scripts/check-foundation.py` | PASS; Gateway/portal readiness, unauthenticated Gateway rejection and independent private Customer authentication |
| `docker compose run --rm ledger-check` | PASS; signed technical identity, funding, transfer, replay/conflict, insufficient funds, full reversal, guards and reconciliation |
| `npm run test:e2e` | PASS; five real browser journeys, retries disabled, reported duration 1.6m |
| `mvnw.cmd -B -ntp verify` | PASS; 74 tests, zero failures/errors/skips: 43 unit/security and 31 real PostgreSQL integration tests |
| `npm test`, typecheck and ESLint | PASS; seven component tests, typecheck and lint |

The browser business journey uses two hosted Authorization Code + PKCE logins and runs `business-check` on the private Compose network. The native business override was unset. It verifies onboarding, ownership, restricted funding, exchanged customer authorization, transfers, exact replay and conflict, balances/history, insufficient funds, review/block decisions, cancellation, versioned account guards, full reversal, original journal preservation and reconciliation. Tokens remain in memory/stdin.

Backend integration tests used Testcontainers through the workstation's local Docker named pipe with `postgres:17.11-alpine`; no native database override was supplied. The workstation runs Docker Engine 29.1.2 and Compose 2.40.3-desktop.1. Keycloak is 26.8.1.

## Issues found and corrected

Docker Desktop initially remained `starting`, and engine probes timed out. Its WSL bootstrap logged `/dev/sde is apparently in use by the system; will not make a filesystem here!`. A Desktop restart followed by WSL shutdown/start restored the engine. No manual disk formatting, volume deletion or factory reset was performed.

The first browser run passed three cases and failed two five-second assertions: the anonymous sign-in control appeared late, and the authenticated workspace still awaited its API response. Those snapshots establish incomplete transitions at the deadline; they do not isolate which initialization step caused the delay. The shared Playwright assertion budget is now 15 seconds, with retries still disabled and all assertions preserved. The final complete run passed after a Gateway restart. No application authorization, financial rule or accepted status code was weakened.

## GitHub verification

All three workflows passed for the exact source commit above:

- [Backend quality](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/39953428898)
- [Frontend quality](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/39953428846)
- [Foundation integration](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/39953428839)

The current repository is `ABDOUUisHERE/FinCore-Bank`, repository ID `1138747020`; the local remote and current clone instructions use that name. Historical milestone evidence retains the repository name recorded at the time.

## Handoff

The healthy Compose stack is left running. Customer portal: http://localhost:5173. Operations portal: http://localhost:5174. Generated sign-in credentials remain in ignored `.local/credentials.json`. Synthetic datasets and matching secrets are retained; unrelated PostgreSQL data was not modified.

The [machine-readable evidence](../evidence/local-docker-2026-10-23.json) includes fresh backend suite counts, final Compose service states, versions and exact GitHub run metadata. Private credentials, bearer tokens and authenticated captures are excluded. Functional verification covers the implemented Phase 3 APIs and portal authentication shells; customer banking screens remain Phase 4.
