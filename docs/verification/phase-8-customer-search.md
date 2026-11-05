# Phase 8 lot 4 — customer name search

Implementation and feature CI: 2026-11-04 UTC. Final local/integration verification: 2026-11-05 UTC. Starting main: `08afb392c9576018d8b7cf191b9d62c9a4918fca`. Application/test source: `f41324f1f2790ee354a1f0919b39217f8285fb74`. **Lot 4 is accepted and merged into main; all three main workflows passed.** Phase 8 remains in progress.

## Delivered scope

ADMIN submits a literal name prefix to Java 21/Spring Boot Customer through Gateway. A response contains at most 20 customer UUIDs, display names, onboarding states, exact decimal-string versions and creation timestamps. A UUID keyset continues with the same prefix; there is no total count, empty-prefix directory listing, financial data or profile mutation. Other staff roles and machine scopes receive no new access. Existing exact-reference Customer inspection still excludes names and other personal profile fields.

The staff Inspection workspace has an explicit Find a customer form. Editing or clearing the prefix removes previous matches. Selecting a match fills and focuses the existing redacted status inspection. Search names travel in POST JSON, not URLs. Successful responses use no-store; search query pages are held in memory with no persistent cache and are removed when unmounted. Empty, error and retry states remain distinct.

See the [contract](../../contracts/openapi/customer-search-v1.yaml) and [operational guide](../deployment/customer-search-phase-8.md). Prior [account-status acceptance](phase-8-account-status.md) remains historical evidence for lot 3.

## Executed checks

| Check | Observed result |
| --- | --- |
| Native focused Java | **29 distinct cases passed**: Gateway 12, Customer 17, including four actual PostgreSQL cases. Two focused commands succeeded; the initial paused-Docker invocation failed and is not counted as a pass |
| Signed permissions | ADMIN-only POST at Gateway and Customer; other roles, machine token, wrong audience, actor headers and GET/PUT denial tested |
| PostgreSQL | 23 real profiles paginate 20/3 in UUID order with no duplicates; end cursor is empty; case-insensitive prefix and literal apostrophe/percent/underscore/exclamation matching; unchanged profile rows and outbox |
| Precision/redaction | Versions above JavaScript's safe integer range remain strings. Search exposes no subject/country/account/financial fields; storage errors and malformed JSON do not echo private input |
| Native frontend | **44 passed in 9 files** before the final singular-label correction; all **six search cases**, types and lint passed again after it |
| Full backend CI | **239 passed in 47 classes**, zero failure/error/skip; actual PostgreSQL/Kafka suites |
| Full frontend CI | **44 passed in 9 files**; types, lint, both production builds and high-severity audit gate passed |
| Full integration CI | **11 browser journeys passed**; fresh Compose, direct auth, Ledger smoke, Kafka ACL denial, durable DLQ and audited replay |
| Native stack/browser | Three images rebuilt/replaced; two hosted-PKCE journeys passed, including real profile search and existing account Ledger outage/restore; **16 healthy runtimes** |
| Native production/visual | Both production builds; 1440/390/320 px layouts asserted; desktop/mobile search captures inspected privately |
| Dependencies/configuration | Production-only audit: **0 advisories**. Feature CI reports 11 moderate development advisories on the unchanged lockfile. Local configuration/credential hashes unchanged; no migration or identity upgrade |
| Contract/evidence | One path, one resolved schema reference; relative links, whitespace and staged generated-secret scans passed |

Native commands select the installed Java 21 compiler with a process-only JAVA_HOME override. Gateway: `mvnw.cmd -B -ntp -pl services/api-gateway -am test`. Customer: `mvnw.cmd -B -ntp -pl services/customer-service -am verify '-Dit.test=DatabaseIsolationIT' '-Dfailsafe.failIfNoSpecifiedTests=false'`. These are focused checks, not a full native-reactor claim. Frontend suite: `node node_modules/vitest/vitest.mjs run --maxWorkers=1`; its unchanged bounds/assertions passed in 153.82 seconds.

## Findings and limits

The initial native PostgreSQL invocation stopped before database startup because Docker Desktop was manually paused. The Desktop window was inaccessible to automation; the user resumed Docker. The original four-case database suite then passed. No Desktop/WSL reset, volume deletion or global Java change was required.

Visual QA found the one-result label used the plural “customers”. A focused assertion reproduced it, then passed after the singular wording correction. All six search cases passed again; the already successful 44-case native run is not relabeled as a run containing that later assertion. Final-source CI and Docker/browser verification cover the corrected label.

The follow-up native image build was interrupted before completion on November 4. It was not counted as a successful build. Final local acceptance completed on November 5; earlier successful results and the interrupted attempt remain distinct.

Prefix search uses PostgreSQL lower-case rules and literal matching. It is not fuzzy, accent-insensitive, middle-name/infix search or a full customer-management workflow. Prefixes require 3–80 UTF-16 code units with no controls; names shorter than three characters can still be inspected by exact UUID. A three-second query timeout bounds database execution; no large-corpus performance baseline is claimed. Each page is a later observation; edits and onboarding can change subsequent matches. No distributed or cross-page snapshot is claimed.

Search does not emit a new audit fact or authorize support business access. Complete critical-action/security/read audit, scoped support grants, further customer/payment administration, aggregate monitoring, tracing and six Grafana dashboards remain Phase 8 work. Kubernetes and subsequent roadmap milestones remain later work. No complete-project or production-readiness acceptance is claimed.

## Feature and main evidence

All three feature runs use `f41324f1f2790ee354a1f0919b39217f8285fb74`:

| Workflow | Feature result |
| --- | --- |
| [Backend quality](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/41882121793) | 239 cases, zero failure/error/skip |
| [Frontend quality](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/41882121921) | 44 cases, types/lint/builds/audit gate |
| [Foundation integration](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/41882121968) | 11 journeys, fresh Compose, Ledger and ACL/DLQ/replay |

The [machine-readable evidence](../evidence/phase-8-customer-search-checks.json) records measured results. The latest jobs initially waited in the GitHub queue; superseded source runs were automatically cancelled. Queue status alone is not reported as a product failure or a confirmed external incident.

Generated credentials, names/identifiers from authenticated sessions, tokens, raw logs and authenticated screenshots stay outside public evidence. The seven source commits each contain scope, implementation or tests; acceptance documentation is a further cohesive commit. GitHub associates them with ABDOUUisHERE.

## Main integration

Merged as `4d367045d7669eb75aedc19666dceb8392cf12a6` on 2026-11-05 UTC. Application and tests are identical to feature-tested source `f41324f1f2790ee354a1f0919b39217f8285fb74`; intervening feature acceptance and this final proof contain documentation only. Independent main checks passed:

| Workflow | Main result |
| --- | --- |
| [Backend quality](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/42004867240) | 239 passed in 47 classes; zero failure/error/skip |
| [Frontend quality](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/42004867214) | 44 passed; types, lint, both production builds and high-severity audit gate passed |
| [Foundation integration](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/42004867273) | 11 journeys; fresh Compose, direct authentication, Ledger smoke, ACL/DLQ/replay passed |

Seven implementation/scope/test commits, one feature acceptance commit and the merge retain their history on main. This final documentation proof records the observed main results. No force push, history rewrite or artificial contribution commit was used.

Main dependency audit reports **7 moderate development advisories** on the unchanged lockfile; the high-severity gate passes. The separate production-only audit on November 5 reports **0 advisories**. Feature CI's earlier 11-advisory observation remains historical.
