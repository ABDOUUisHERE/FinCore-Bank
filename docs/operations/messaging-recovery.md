# Messaging recovery for the local simulation

Run these commands from the FinCore repository with its existing ignored `.env` and `.local` configuration. This procedure concerns Kafka publication and owned notifications. Financial outcomes remain authoritative in Payment, Account and Ledger.

## Establish the current state

```sh
docker compose ps kafka notification-service keycloak postgres
python scripts/diagnostics.py
```

Diagnostics redacts generated local credentials. Review output before sharing it. A missing notification alone does not establish that a transfer failed: inspect the owned transfer result and confirmed journal in the customer workspace before considering another financial request. An ambiguous transfer retains its original request and idempotency key.

## Resume an existing stopped broker

```sh
docker compose start kafka
docker compose ps kafka
```

Wait for the broker to report healthy. Keep the existing Kafka data/config volumes and generated identities together. The four producers retain unsent rows, use bounded backoff and resume after successful acknowledgments. A post-ack crash can repeat an event; the consumer deduplicates its local effects. Delivery can take another retry interval after the broker recovers.

Do not resubmit a successful payment to force a notification. Do not delete delivery rows or consumer offsets. If Kafka is healthy but Notification is stopped, resume only that existing service:

```sh
docker compose start notification-service
docker compose ps notification-service
```

An image/schema upgrade instead follows the migration procedure in [local setup](../deployment/local-setup.md#phase-5-messaging-upgrade). A restart does not apply a new migration.

## Inspect quarantined events

With dependencies healthy, use the separate private operator identity:

```sh
docker compose run --rm --no-deps notification-operator list
```

The response contains up to 100 newest entries: `deadLetterId`, `sourceTopic`, `version` and `createdAt`. It omits event bodies. This is a recent inspection window, not a complete archival export. The operator has no money scopes and is unavailable through the public Gateway.

Check whether the consumer's dependency or schema compatibility has actually been repaired before requesting a replay. The public customer session is not an operator credential.

## Request one versioned replay

Replace both placeholders with values from the current inspection response:

```sh
docker compose run --rm --no-deps notification-operator replay DEAD_LETTER_UUID CURRENT_VERSION DEPENDENCY_RECOVERED
```

The reason must contain 3 to 100 uppercase ASCII letters or underscores. Acceptance returns a `replayId`, appends the actor/reason audit, increments the dead-letter version and queues the original topic/key/value for publication. Acceptance is not confirmation that the consumer has processed it. The CLI emits a generic failure message on an HTTP error; inspect the metadata again before deciding whether another request is appropriate.

Two operators using the same old version cannot both schedule a replay: the API returns `409 REPLAY_VERSION_CONFLICT` for the stale request. Refresh the inspection result and establish whether a replay was already accepted. Do not blindly repeat the command with a newer version, because that would request an additional replay.

Replay preserves the event identity and body. An unsupported schema remains invalid and returns to quarantine after bounded processing attempts. A new quarantine entry does not mean the original was repaired or removed. Deploy a compatible consumer before attempting recovery of that record; do not edit the original fact to bypass validation.

## Confirm the result and scope

Inspect the signed owner's inbox and local simulated email sink. The live connection refreshes authoritative APIs and reconnects using a bearer header. A simulated email is a database record, not an external email delivery.

Publisher pending/oldest-age/circuit/retry/acknowledged metrics are registered in process. Current HTTP management exposure includes health only; this milestone does not provide a public metrics endpoint or an operational dashboard. Persistent topics retain seven days, and single-node Kafka does not provide high availability.

The synthetic fault-injection acceptance command is documented in [testing](../testing.md#phase-5-messaging-acceptance). It deliberately creates invalid records and replay audit entries, so it is an acceptance exercise rather than a routine health probe.
