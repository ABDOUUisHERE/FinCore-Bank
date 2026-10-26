# ADR 0008: durable delivery and owned notification projections

Status: implemented for the local simulation; verification is recorded separately.

Customer, Account, Ledger and Payment retain transactional facts. Additive migrations expose narrow event views and separate delivery rows; publishers cannot rewrite ledger facts. Each publisher claims one fenced lease using SKIP LOCKED, awaits Kafka acks=all, then marks delivery. A post-ack crash repeats the original ID/body. Capped exponential backoff/jitter and a process-local open/half-open retry circuit isolate broker failures. Separate scheduler capacity keeps payment recovery runnable.

Kafka 4.2.2 uses persistent single-node KRaft storage, three partitions per versioned topic and no host listener. Generated SASL/PLAIN identities are service-specific. ACLs allow each producer only its own prefix. Notification reads domain topics and writes only its DLQ/replay prefix; it cannot impersonate Payment. Broker/bootstrap credentials are not application credentials. SASL_PLAINTEXT is confined to the local Docker network; TLS and high availability remain deployment work.

Notification owns a separate database and migration/runtime roles. Inbox effects, version watermarks and consumed-event hashes commit together before consumer offset acknowledgment. Watermarks are scoped by producer kind and aggregate ID, so unrelated streams sharing an opaque UUID cannot suppress each other. An additive migration preserves existing watermark rows. Stale snapshots/statuses cannot replace newer versions. Financial views continue reading Account/Ledger APIs. Success messages require a completed Payment fact and journal. A narrow Account lookup client resolves recipient ownership. Semantic keys prevent an unsuccessful reversal's repeated COMPLETED transition from duplicating the original success message.

Preferences govern future channels/categories. A local email outbox and unique synthetic sink use a stable notification ID in one local transaction. No real email provider or SMTP address is involved. The sink is visible only to its signed owner.

Three bounded consumer retries lead to acknowledged DLQ publication. DLQ storage retries database failures without recursively dead-lettering itself. Inspection and versioned replay require an independent technical identity. Replay audits actor/reason and durably queues the unchanged event on a notification-only replay topic. Unsupported schemas stay quarantined; no automatic payload editing or offset deletion hides them. DLQ/replay topics accommodate wrappers around bounded source records.

SSE uses an Authorization header and in-memory bearer, five streams per subject, keepalives and a lifetime bounded by token expiry and 25 seconds. The browser reconnects with a refreshed token. The initial cursor invalidates the inbox to cover reconnect gaps. Events invalidate owner APIs instead of announcing optimistic money. Query tokens and public operator routes are unsupported.

Gateway API quotas allow 300 requests per minute per verified subject after JWT authentication. Anonymous requests share a transport-address bucket; identity and forwarding headers cannot select a bucket. At most 10,000 buckets are kept for the current minute. This replaces the original process-wide shared quota, which let one customer's live refreshes deny another customer's normal request. The limiter remains in-process for the single local gateway; distributed edge protection is later deployment work.

Historical Payment facts without immutable routing metadata are published/consumed without inventing retroactive recipients. Customer/Account events carry minimal immutable identifiers. A new Payment migration captures routing/status/journal fields in the original transaction. Existing migrations remain unchanged. Correlation explicitly falls back to the durable event ID when no original request correlation was stored.

Delivery is at least once. No end-to-end exactly-once, external email guarantee, cluster availability or benchmark claim is made.
