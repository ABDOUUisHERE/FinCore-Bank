-- Capture the notification-safe identity and confirmed references in the SAME payment transaction.
CREATE OR REPLACE FUNCTION payment_transition_fact() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE previous text; confirmed uuid;
BEGIN
 IF TG_OP='UPDATE' THEN
  IF NEW.status=OLD.status THEN RETURN NULL; END IF;
  previous:=OLD.status;
 END IF;
 confirmed:=NEW.journal_id;
 IF NEW.status='REVERSED' THEN
  SELECT journal_id INTO confirmed FROM payment WHERE original_payment_id=NEW.id AND status='COMPLETED';
 END IF;
 INSERT INTO payment_status_history VALUES(gen_random_uuid(),NEW.id,NEW.version,previous,NEW.status,NEW.terminal_code,NEW.transition_actor,clock_timestamp());
 INSERT INTO payment_outbox VALUES(gen_random_uuid(),NEW.id,NEW.version,'payment.'||lower(NEW.status),
  jsonb_build_object('paymentId',NEW.id,'status',NEW.status,'version',NEW.version,'journalId',confirmed,'kind',NEW.kind,
   'actorSubject',NEW.actor,'sourceAccountId',NEW.source_account_id,'destinationAccountId',NEW.destination_account_id,
   'amount',NEW.amount::text,'currency',NEW.currency,'code',NEW.terminal_code),clock_timestamp());
 RETURN NULL;
END $$;
-- New delivery metadata; existing financial facts/migrations remain unchanged.
CREATE VIEW fincore_event_source AS WITH facts AS (SELECT id AS event_id,occurred_at,CASE event_type WHEN 'payment.created' THEN 'payment.initiated' ELSE event_type END AS event_type,payment_id AS aggregate_id,aggregate_version,payload FROM payment_outbox)
SELECT event_id,occurred_at,jsonb_build_object('eventId',event_id,'eventType',event_type,'schemaVersion',1,
 'aggregateId',aggregate_id,'aggregateVersion',aggregate_version,'correlationId',event_id::text,'occurredAt',occurred_at,'payload',payload) AS envelope FROM facts;
CREATE TABLE event_delivery (
 event_id uuid PRIMARY KEY, sent_at timestamptz, lease_token uuid, lease_until timestamptz,
 attempts int NOT NULL DEFAULT 0 CHECK(attempts>=0), next_attempt timestamptz NOT NULL DEFAULT clock_timestamp(), last_code varchar(40)
);
CREATE INDEX event_delivery_pending ON event_delivery(next_attempt,event_id) WHERE sent_at IS NULL;
REVOKE ALL ON event_delivery FROM PUBLIC;
GRANT SELECT,INSERT,UPDATE ON event_delivery TO payment_runtime;
GRANT SELECT ON fincore_event_source TO payment_runtime;
