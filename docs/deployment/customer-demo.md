# Customer banking demo

All users, accounts and money are synthetic. Start from main using the [local setup](local-setup.md). Keep the generated configuration and volumes when upgrading.

## Sign in and open accounts

1. Open http://localhost:5173 and use the generated customer.demo credentials from ignored .local/credentials.json.
2. Complete the display name and two-letter country profile if requested, then confirm the summary.
3. Open Accounts, choose MAD, EUR or USD and confirm creation. An opening account becomes active only after ledger provisioning is confirmed.
4. Account details show its full internal UUID. Copy it for private funding or share it with another simulated customer. It is not an IBAN.
5. Use a separate private browser window for recipient.demo. Complete its profile, open a MAD account and share that reference with the sender.

## Add simulated funds privately

From the repository directory, replace ACCOUNT_REFERENCE with the sender's UUID:

    docker compose run --rm simulation-fund ACCOUNT_REFERENCE 1000.00 MAD

The command uses the private technical funding scope. No operator secret is entered into the portal. It prints a request key before sending and a payment reference afterward. Only a confirmed journal and COMPLETED state establish completed funding. If the response is unconfirmed, retain the key and retry unchanged details:

    docker compose run --rm simulation-fund ACCOUNT_REFERENCE 1000.00 MAD --key ORIGINAL_KEY

Refresh portal data afterward. A repeated key does not add money twice; a new key deliberately creates another funding request. Currency, amount and effective restrictions remain backend checks.

## Send and inspect

1. Under Beneficiaries, enter the recipient label and full account reference, review and confirm. Verification checks an active internal account, not a real payee identity.
2. Under Transfers, choose the active source, a saved recipient in the same currency, an exact positive amount and an optional reference.
3. Review the complete source/destination references and amount. Confirm and send once.
4. Follow the recorded status. Only Transfer completed with a journal confirms posting. Pending review reserves no funds. A later decision rechecks funds and restrictions. FAILED and REJECTED show the recorded reason.
5. After an unconfirmed response, keep this tab and use Retry same transfer. Reloading retains its key/body. A later retry refusal cannot unlock an earlier unknown outcome. After closing an unconfirmed tab, inspect Your transfers before making a new request.
6. Cancellation is offered only before execution, uses the current version and reports the server outcome.
7. History shows confirmed ledger entries separately from pending requests. Filter by UTC dates, exact amount, type, direction or reference. Compensation links preserve original entries. CSV includes the loaded filtered rows; load more for additional rows.
8. Accounts support confirmed versioned freeze/unfreeze and permanent zero-balance closure. Profile and beneficiary edits require review/confirmation.

Dashboard totals stay separate by currency and are withheld if data is incomplete. Monthly flows cover the whole selected UTC month, including funding and compensation. Cards, statements and operational review retain later phases. Self-registration is not claimed: generated demo users and test-created synthetic users use real hosted sign-in.

## Notifications and live updates

Open Notifications to view your own inbox, filter unread messages and mark entries read. Preferences apply to future channels and categories. Review and confirm changes; stale versions require refresh. The simulated email inbox contains local stored deliveries, without external email.

The live connection uses a bearer header and refreshes owner APIs when a recorded notification arrives. Reconnecting does not determine a transfer outcome. Inspect the transfer resource and confirmed journal while notification delivery is delayed. Broker recovery can replay events without posting money again.
