# Docker Desktop and Ubuntu integration on Windows

Use this guide for the local FinCore environment after initial setup. Keep the generated `.env`, `.local/credentials.json`, realm configuration and Kafka configuration together with the existing named volumes. Restarting an engine is different from resetting its data.

## Identify the failure before restarting

| Observation | First action |
| --- | --- |
| Docker returns HTTP 503 with `Docker Desktop is manually paused` | Resume Docker Desktop through its menu/dashboard, then retry an engine check. This response does not establish a WSL failure. |
| Docker cannot connect and Desktop is stopped | Start Desktop and wait for the Linux engine. |
| Desktop reports `DockerDesktop/Wsl/ExecError` for the Ubuntu distro proxy | Inspect distro/Desktop state, then use a controlled restart if the integration remains blocked. |
| Engine responds but one FinCore service is unhealthy | Inspect that service's dependency/migration diagnostics. A healthy engine does not establish application readiness. |

Read-only checks in PowerShell:

```powershell
docker desktop status
wsl --list --verbose
docker info --format '{{.ServerVersion}}'
```

If a command hangs, interrupt it rather than launching more concurrent restarts. The Compose and Maven integration commands require an answering engine; a 'could not find a valid Docker environment' failure is not evidence that their integration assertions ran.

When the engine responds, inspect running projects and FinCore's state:

```powershell
docker ps --format '{{.Names}}'
docker compose ps -a
```

Run the Compose command from the FinCore repository. Other project names in the Docker inventory identify workloads outside FinCore; leave their containers, networks and volumes intact.

## Start a stopped Desktop

On the installed Desktop CLI, startup supports a bounded wait:

```powershell
docker desktop start --timeout 45
```

A timeout means readiness was not confirmed within that interval. Inspect Desktop and check engine responsiveness again before running application commands. It does not justify deleting application data or regenerating credentials.

## Recover a blocked Ubuntu proxy

A Desktop restart interrupts Docker workloads. `wsl --shutdown` also stops all WSL distributions, including unrelated active Linux work. Save their work and arrange that interruption before using this recovery sequence. Merely seeing an Ubuntu proxy dialog is not a reason to shut down healthy unrelated workloads immediately.

First stop Desktop normally, through its UI or its bounded CLI:

```powershell
docker desktop stop --timeout 45
```

Confirm Desktop has stopped. If the command times out, inspect the UI/process state and complete shutdown before continuing. A forced Desktop stop is a last resort for a blocked engine, not the default recovery step.

Inspect WSL again:

```powershell
wsl --list --verbose
```

Only after Desktop is stopped and interruption of any remaining active distro is acceptable, release the WSL instances:

```powershell
wsl --shutdown
```

Then start Desktop and verify the engine:

```powershell
docker desktop start --timeout 45
docker info --format '{{.ServerVersion}}'
```

This procedure does not unregister Ubuntu or Docker's distributions. Do not substitute a factory reset, `wsl --unregister`, volume deletion or broad prune for a routine restart. Those actions can destroy unrelated data and are not required by the observed Phase 5 recovery.

## Confirm application recovery

With the existing images/configuration and an answering engine, restore FinCore from its repository:

```powershell
docker compose up -d --wait --wait-timeout 600
docker compose ps
python scripts/check-foundation.py
```

The cold-start allowance covers migrations, broker topic/ACL setup and Java readiness. Inspect a failed one-shot job or unhealthy service before retrying; retain the actual health checks. Apply an image/schema upgrade through [local setup](../deployment/local-setup.md), including its Notification V1-to-V2 sequencing, rather than treating it as an engine restart.

For logs, use the bounded generated-secret-redacting helper and review its output before sharing:

```powershell
python scripts/diagnostics.py
```

Use exactly `http://localhost:5173` for the customer portal and `http://localhost:5174` for administration so the configured OIDC redirect origin matches. For delayed notifications after broker recovery, continue with the [messaging recovery guide](../operations/messaging-recovery.md).

The [Phase 5 verification record](../verification/phase-5.md) distinguishes the observed WSL integration recovery from a later explicit Docker pause response. Successful recovery checks do not promise that a future Desktop/WSL failure cannot recur.
