GRANT USAGE ON SCHEMA public TO ledger_runtime;
-- New delivery metadata; existing financial facts/migrations remain unchanged.
CREATE VIEW fincore_event_source AS WITH facts AS (SELECT id AS event_id,created_at AS occurred_at,'ledger.journal.posted'::text AS event_type,account_id AS aggregate_id,account_version AS aggregate_version,
 payload || jsonb_build_object('accountId',account_id,'ledgerVersion',account_version,'bookedBalance',payload->>'booked','availableBalance',payload->>'booked') AS payload FROM ledger.outbox_event)
SELECT event_id,occurred_at,jsonb_build_object('eventId',event_id,'eventType',event_type,'schemaVersion',1,
 'aggregateId',aggregate_id,'aggregateVersion',aggregate_version,'correlationId',event_id::text,'occurredAt',occurred_at,'payload',payload) AS envelope FROM facts;
CREATE TABLE event_delivery (
 event_id uuid PRIMARY KEY, sent_at timestamptz, lease_token uuid, lease_until timestamptz,
 attempts int NOT NULL DEFAULT 0 CHECK(attempts>=0), next_attempt timestamptz NOT NULL DEFAULT clock_timestamp(), last_code varchar(40)
);
CREATE INDEX event_delivery_pending ON event_delivery(next_attempt,event_id) WHERE sent_at IS NULL;
REVOKE ALL ON event_delivery FROM PUBLIC;
GRANT SELECT,INSERT,UPDATE ON event_delivery TO ledger_runtime;
GRANT SELECT ON fincore_event_source TO ledger_runtime;
