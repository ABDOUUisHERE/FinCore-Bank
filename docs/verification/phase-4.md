# Phase 4: customer banking experience

## A. Outcome and scope

The customer workspace connects profile onboarding/editing, owned accounts, exact separate-currency balances, effective restrictions, whole-month account flows, beneficiaries, transfer review/submission/recovery/cancellation and filtered confirmed history to real services. Completed means the owned Payment resource reports COMPLETED with a journal reference.

Beneficiary Service owns a private database, migration/runtime roles, address-book identity and append-only audit. It verifies new internal destinations using a separate narrow Account lookup client. Public ownership comes only from the validated JWT subject.

## B. Decisions

Money stays decimal text at HTTP boundaries and BigInt minor units in the client. Missing balances, incomplete account pages and provisioning withhold totals. Monthly flows query all postings for one UTC month independently of history paging. The UI identifies funding/compensation as flows instead of inventing spending categories or FX.

A transfer attempt is saved under the verified subject in browser session storage before submission. Response loss and reload retain its original key/body. A later retry refusal cannot establish rejection of an earlier ambiguous request. Only a definitive first preacceptance rejection permits an explicit new draft. Tokens remain in memory. Cancellation and restrictions use current versions; financial views refresh from recorded results.

See [ADR 0007](../fincore/adr/0007-customer-confirmation-and-beneficiaries.md).

## C. Files and modules

- packages/portal-ui/src/banking/: typed API adapter, exact money, scoped recovery, shared UI, profile/accounts/dashboard/activity, beneficiaries, transfer status and history/export.
- Portal.tsx: connected navigation, confirmation on logout, current repository link and accurate anonymous feature descriptions.
- services/beneficiary-service/: independent JWT checks, destination verification, scoped CRUD/search, identity/version validation, append-only audit and PostgreSQL/security tests.
- Ledger/Account read endpoints: server-side filters, chronological owner-scoped cursors, whole-month aggregation and compensation links.
- Payment list/read model: chronological owner-scoped status filtering and public source/destination/time fields. Ledger posting algorithms and prior financial migrations are unchanged.
- Compose, bootstrap, identity and diagnostics: repeatable beneficiary upgrade with no volume reset or existing-secret rotation.
- Dockerfiles: shared locked dependency caches, manifest-first frontend dependency layers and unchanged non-root runtimes.
- Browser/private-fixture tests, simulation funding helper, OpenAPI contracts and deployment/test guides.

## D. Limits

All identities and money are synthetic. Onboarding is not real KYC and beneficiary presence is not payee certification. Address-book disablement prevents new UI selection and does not financially freeze an account or cancel an existing payment.

One unresolved draft is supported per subject/tab. Session storage is not cross-device recovery; after closing an unknown tab, inspect owned transfer resources. CSV includes loaded filtered rows and is not a bank statement. Review reserves no funds. Cards, statements, notification delivery, self-registration and operational review/MFA workflows retain later requirements/milestones. No external deployment or performance claim is made.

## E. Test strategy

Preserve the complete financial kernel/security/recovery suite. Add actual PostgreSQL and signed-JWT tests for beneficiary ownership, mutable labels versus immutable identity, all-index insert conflict handling, audit permissions/atomicity and version races. Add ledger read precision/filter/pagination/monthly tests. Exercise browser state and ambiguous submission recovery separately with stubs and then with genuine hosted PKCE, real APIs and actual ledger verification.

## F. Status

Local acceptance and all three GitHub workflows passed for source commit `59a50908d405cca31a8917c0017a705c26abf641`. Integration into main uses a fast-forward of this validated source and the verification documentation.

- [Backend quality](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/39982701238): SUCCESS.
- [Foundation integration](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/39982701270): SUCCESS.
- [Frontend quality](https://github.com/ABDOUUisHERE/FinCore-Bank/actions/runs/39982701440): SUCCESS.

Remote job logs independently confirm 89 backend tests, 17 frontend cases, zero dependency vulnerabilities and six browser journeys (1.1m).

Machine-readable results: [phase-4-checks.json](../evidence/phase-4-checks.json). Exact changed-file list: [phase-4-files.txt](phase-4-files.txt).

## G. Observed local verification

| Executed check | Result |
| --- | --- |
| Complete default Maven verify, Docker Testcontainers | PASS: 89 tests; 49 unit/security and 40 PostgreSQL 17.11 cases; zero failures/errors/skips |
| Frontend components | PASS: 17 cases |
| TypeScript, ESLint and npm audit | PASS; audit reports zero vulnerabilities |
| Docker application builds and existing-volume startup | PASS; six Java and two portal images; beneficiary migration/identity setup succeeds |
| Foundation HTTP/private Customer check | PASS |
| Complete browser suite | PASS: six real journeys, retries disabled, reported 4.4m |
| Focused final customer UI journey | PASS after recovery guard and beneficiary editing/search/disable checks |
| Private funding helper | PASS: 1.00 MAD; repeating the same key reports the same payment and confirmed journal |
| Manual visual inspection | PASS: actual synthetic dashboard at desktop/mobile widths and mobile dark mode; ignored opt-in captures |

The first beneficiary test run exposed audit insertion in a BEFORE INSERT trigger, which fires before conflict handling. Moving fact creation to AFTER fixed sequential replay. The complete suite then exposed an intermittent concurrent collision on another unique index. Insert conflict handling now covers all unique indexes before resolving the owned original key or duplicate destination. The final full reactor and eight concurrent pairs pass.

The first customer browser attempt timed out on an overly strict label selector despite the visible account form. The test now uses the select's accessible combobox name and bounded 15-second actions; final real flows pass. An editing-time text encoding regression was corrected and verified. No retry, assertion, ownership check or financial invariant was removed to pass.

The stack remains running with synthetic datasets and matching configuration retained. No volumes were deleted. Credentials, bearer values and authenticated captures are excluded from committed evidence.

## H. Next milestone

Phase 5: Kafka delivery, outbox publishers, idempotent consumers, notifications/SSE, retry/DLQ and broker-outage/redelivery verification. Durable outbox rows are not yet a claim of delivered notifications.
