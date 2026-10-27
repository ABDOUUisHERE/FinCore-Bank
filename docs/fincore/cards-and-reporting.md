# Cards and reporting contract

Phase 7 remains under validation. All money is synthetic. Amounts are decimal strings; opaque references are UUIDs. Customer routes require a signed CUSTOMER JWT and independently enforce ownership. `Idempotency-Key` is required for every card mutation. Unknown responses retain the same key and body.

| Method and public route | Request / result |
| --- | --- |
| POST `/api/v1/cards` | `{accountId, transactionLimit, dailyLimit}`. Stable card identity, synthetic label and Ledger-confirmed controls. |
| GET `/api/v1/cards?after=UUID` | Owned cards, 50 per page with `nextCursor`. Each returned card reads current Ledger controls. |
| GET `/api/v1/cards/{id}` | Owned current card. Cross-owner access is 404. |
| PUT `/api/v1/cards/{id}` | `{expectedVersion, status, transactionLimit, dailyLimit}`; ACTIVE/FROZEN/TERMINATED. Stale version is 409. |
| GET `/api/v1/card-merchants` | Seeded Demo Market identities, one per MAD/EUR/USD. No arbitrary real merchant accounts. |
| POST `/api/v1/cards/{id}/authorizations` | `{merchantId, amount, currency}`. 202 immutable AUTHORIZING receipt. Read the current authorization separately. |
| GET `/api/v1/cards/{id}/authorizations?after=UUID` | Owned authorizations, 50 per page. |
| GET `/api/v1/card-authorizations/{id}` | Current canonical financial outcome, or pending/rejected Fraud workflow before any hold. |
| POST `/api/v1/card-authorizations/{id}/settle` | `{action: CAPTURE\|RELEASE\|REFUND}`. Full amounts only. Inspect `operationOutcome`, `refundOutcome` and journal references; HTTP 200 can contain a recorded financial rejection. |
| POST `/api/v1/statements` | `{accountId, month: YYYY-MM}`. Owner-authorized immutable monthly snapshot. Natural content replay; no money movement. |
| GET `/api/v1/statements?after=UUID` | Owned saved snapshot summaries, 50 per page. |
| GET `/api/v1/statements/{id}` | Snapshot and source hash. |
| GET `/api/v1/statements/{id}/csv` or `/pdf` | Authenticated attachment from the same snapshot. No bearer tokens in URLs. |
| GET `/api/v1/admin/reports/reconciliation` | Explicit FINANCE_OPERATOR; independent Ledger repeatable snapshot with discrepancy arrays. ADMIN alone is forbidden. |

Authorization states before a hold are AUTHORIZING, PENDING_RISK_REVIEW or REJECTED. Ledger outcomes are HELD, DECLINED, CAPTURED, RELEASED, REFUNDED or EXPIRED. A non-null journal is required to claim a booked capture/refund. A live hold lasts at most 30 minutes, capped by card expiry. Daily limits use the hold admission's UTC day. Automatic expiry stops reducing available funds by database time; it does not create a compensating journal.

The browser retains an outstanding card command in owner-scoped session storage before sending it. After an ambiguous response it locks the body and permits retry with the same identity, including after a page reload. A later retry refusal alone does not prove the original request failed. Statements are read-only financial snapshots, so generating a new latest snapshot is safe and does not replace older ones.

| Private boundary | Accepted identity / scope |
| --- | --- |
| Ledger card create/configure/prepare/settle | `azp=fincore-card`, CUSTOMER subject, `ledger.card.manage` from token exchange. |
| Ledger card/control/authorization inspection | `azp=fincore-card`, `ledger.card.inspect`. Bounded scans recover immutable receipts. |
| Ledger reservation/recovery barrier | `azp=fincore-card`, `ledger.card.reserve`; independent Fraud grant validation for admission. |
| Card risk context | `card.risk.read`, assigned only to fincore-fraud. No caller-supplied risk facts. |
| Fraud merchant assessment | `fraud.card.assess`, assigned only to fincore-card. Existing Payment assessment path is separate. |
| Ledger customer statement | `azp=fincore-reporting`, CUSTOMER subject, `ledger.statement.read`; actual Ledger account owner checked again. |
| Ledger reconciliation | `azp=fincore-reporting`, `ledger.reconcile.read`; no statement ownership bypass. |

Card events are `card.created`, `card.frozen`, `card.state.changed` on `fincore.<event>.v1`. Producer ACLs only allow the `fincore.card.` topic prefix. A freeze is an owned security notice, subject to notification preferences, and never a claim that money was posted. Capture/refund financial events remain Ledger journal facts. Available funds in new posting events are frozen at posting time; historical event facts remain unchanged.

Local bootstrap appends missing secrets without rotating existing data credentials. The new demo identity is `finance.demo`; its generated password is retained only in ignored local configuration. Card and Reporting databases use independent runtime and migrator roles. No new business service port is published.
