-- New delivery metadata; existing financial facts/migrations remain unchanged.
CREATE VIEW fincore_event_source AS WITH facts AS (SELECT o.id AS event_id,o.created_at AS occurred_at,o.event_type,o.account_id AS aggregate_id,o.aggregate_version,
 jsonb_build_object('accountId',o.account_id,'ownerSubject',a.owner_subject,'currency',a.currency) AS payload FROM account_outbox o JOIN account a ON a.id=o.account_id)
SELECT event_id,occurred_at,jsonb_build_object('eventId',event_id,'eventType',event_type,'schemaVersion',1,
 'aggregateId',aggregate_id,'aggregateVersion',aggregate_version,'correlationId',event_id::text,'occurredAt',occurred_at,'payload',payload) AS envelope FROM facts;
CREATE TABLE event_delivery (
 event_id uuid PRIMARY KEY, sent_at timestamptz, lease_token uuid, lease_until timestamptz,
 attempts int NOT NULL DEFAULT 0 CHECK(attempts>=0), next_attempt timestamptz NOT NULL DEFAULT clock_timestamp(), last_code varchar(40)
);
CREATE INDEX event_delivery_pending ON event_delivery(next_attempt,event_id) WHERE sent_at IS NULL;
REVOKE ALL ON event_delivery FROM PUBLIC;
GRANT SELECT,INSERT,UPDATE ON event_delivery TO account_runtime;
GRANT SELECT ON fincore_event_source TO account_runtime;
