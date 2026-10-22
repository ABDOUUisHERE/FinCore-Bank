# Phase 0 verification

Date: 2026-10-22 (UTC). Scope: architecture documentation only.

## Checks

| Executed check | Result |
| --- | --- |
| Python inline documentation validator (`python -` via PowerShell here-string) | PASS: 14 Markdown files, 20 relative links resolve, balanced code fences and 2 JSON examples parse |
| Python byte comparison of supplied attachment and copied specification | PASS: source copy is identical before Git newline normalization |
| Node inline Mermaid validator (`node --input-type=module`, `mermaid.parse`) | PASS: all 7 authored diagrams parse (4 architecture, 1 ER, 2 sequence) |
| `git diff --check` | PASS for the initial tracked README change |
| `git diff --cached --check -- . ':(exclude)docs/fincore/specification.md'` | PASS: authored changes have no whitespace errors |

The first Mermaid attempt failed because the Node environment lacked the browser DOM needed by its sanitizer (`purify.addHook is not a function`). Installing jsdom into an external temporary QA directory and initializing its DOM before Mermaid resolved the environment issue; the final parser run passed all seven diagrams. This was a tooling failure, not an application test. QA dependencies were installed outside the repository and do not add application dependencies or lockfiles.

The default staged whitespace check flagged six original Markdown hard-break lines in the supplied specification (lines 3–8). Their two trailing spaces are retained to preserve the source. Check authored files separately with the exclusion above; a whole-index check allowing end-of-line spaces uses `git -c core.whitespace=-blank-at-eol,blank-at-eof,space-before-tab diff --cached --check` without changing repository configuration. The initial attempt without the explicit `-blank-at-eol` flag still reported those same source lines; the corrected check disables only that category.

Original attachment SHA256 at copy time: `444b612acb150e20244e89f38cb521832ff04c5539654a048e7127e84c8754b4`. Git's existing text attributes may normalize line endings; the text is preserved without editorial changes.

No Java/React application, financial migration or deployment exists in this milestone; application builds, unit/integration/E2E tests and runtime startup are not executed and no pass is claimed. Mermaid parsing verifies syntax, not rendered visual layout or architectural correctness. No GitHub Actions pipeline exists yet, so a push is not evidence of CI passing.

## Git delivery

Target: `https://github.com/ABDOUUisHERE/task-app-management.git`. Branch: `docs/fincore-architecture`, based on `201a81e933d299a1bd65d096a2e7a9adaa6fd575`. Only `README.md` and `docs/fincore/` belong to this milestone. Before commit, run `git diff --cached --check` and inspect the staged file list. Commit with a Conventional Commit message, push with `git push -u origin docs/fincore-architecture`, then compare `git rev-parse HEAD` with `git ls-remote --heads origin docs/fincore-architecture`. The final handoff reports the actual commit and push outcome after those operations. No merge or deployment is authorized or performed by this milestone.

## External references checked

- [PostgreSQL row locking](https://www.postgresql.org/docs/current/explicit-locking.html): basis for the proposed lock behavior and consistent acquisition order.
- [PostgreSQL constraint triggers](https://www.postgresql.org/docs/current/sql-createtrigger.html): basis for the proposed deferred cross-row financial checks.
- [Keycloak JavaScript adapter](https://www.keycloak.org/securing-apps/javascript-adapter): public-client setup and token handling.
- [Spring Boot 3.5 requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html): Java compatibility; exact Phase 1 dependency/support choices remain unpinned.

The accounting model, service boundaries and recovery protocols are FinCore design proposals. Reading these references does not prove their implementation or tests.
