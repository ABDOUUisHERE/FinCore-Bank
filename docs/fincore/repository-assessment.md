# Repository assessment

Inspected on 2026-10-22 (UTC), before design or implementation.

## Selected destination

| Item | Observation |
| --- | --- |
| GitHub | https://github.com/ABDOUUisHERE/task-app-management |
| Checkout | `C:/Users/HYPER/OneDrive/Documents/GitHub/task-app-management` |
| Default branch | `main` |
| Starting commit | `201a81e933d299a1bd65d096a2e7a9adaa6fd575` — Initial commit |
| Tracked starting files | `.gitattributes`, `README.md` |
| Starting working tree | Clean |
| Application/dependencies | None; no Java, React, SQL, Compose or CI configuration |
| Repository instructions | No `AGENTS.md` found in this checkout or inspected ancestors |
| Existing architecture | None to migrate |

The initial cwd, `stages-fullstack-challenge`, points to `ABDOUUisHERE/5G-Core-Network-Simulator`. It contains Go 1.25 services, Protobuf/gRPC contracts, PostgreSQL, a JavaScript dashboard and CI. This was examined before the user supplied the FinCore destination. It is unrelated to FinCore. A separate clone prevents accidental replacement, mixed histories or reuse of its fictional telecom authentication. The original checkout was returned to `main` without application changes.

## Host capabilities and limitations

Java 21.0.2, Maven 3.9.9, Node 24.13.0, npm 11.6.2, Python 3.14.2 and Docker CLI 29.1.2 were observed. `gh` was not available. Git HTTPS access could read both remotes. Git commits and push can use Git directly; credentials must remain in Git's credential manager and must not appear in documents or commands.

Docker engine availability, Compose startup, container resources, browser authentication and PostgreSQL execution have not been verified for FinCore. Java 21 is the requested language baseline, but a patched JDK and compatible framework/dependency pins must be selected during Phase 1. The proposed Spring Boot 3.5 family supports Java 21 according to its [system requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html); support lifecycle, Cloud compatibility and security updates must be checked before pinning.

## Consequences

Create a new architecture rather than adapting existing code. Keep the supplied requirements in English and preserve the GitHub repository name until explicitly changed. Do not claim any startup, ledger invariant, security check or performance measurement from the other repository as evidence for FinCore. Local OneDrive paths may affect container mounts; verify the documented workflow on this Windows checkout and on Linux CI.
