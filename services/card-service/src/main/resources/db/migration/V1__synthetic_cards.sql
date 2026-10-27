REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO card_runtime;
CREATE TABLE card_projection (
 id uuid PRIMARY KEY,actor varchar(200) NOT NULL,account_id uuid NOT NULL,
 first_observation jsonb NOT NULL,observed_version bigint NOT NULL,observed_state varchar(12) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX cards_owned ON card_projection(actor,id);
CREATE TABLE card_authorization (
 id uuid PRIMARY KEY,actor varchar(200) NOT NULL,card_id uuid NOT NULL REFERENCES card_projection(id),receipt jsonb NOT NULL,
 status varchar(25) NOT NULL DEFAULT 'AUTHORIZING' CHECK(status IN ('AUTHORIZING','PENDING_RISK_REVIEW','REJECTED','RESOLVED')),
 version bigint NOT NULL DEFAULT 0,code varchar(80),assessment_id uuid,ledger_outcome jsonb,last_grant_expires timestamptz,
 attempts int NOT NULL DEFAULT 0,lease_token uuid,lease_until timestamptz,next_attempt timestamptz NOT NULL DEFAULT clock_timestamp(),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX authorizations_owned ON card_authorization(actor,id);
CREATE INDEX authorizations_pending ON card_authorization(next_attempt,id) WHERE status IN ('AUTHORIZING','PENDING_RISK_REVIEW');
CREATE TABLE card_outbox (
 id uuid PRIMARY KEY,aggregate_id uuid NOT NULL,aggregate_version bigint NOT NULL,event_type varchar(100) NOT NULL,payload jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),UNIQUE(aggregate_id,aggregate_version)
);
CREATE VIEW fincore_event_source AS SELECT id AS event_id,created_at AS occurred_at,jsonb_build_object('eventId',id,'eventType',event_type,'schemaVersion',1,'aggregateId',aggregate_id,'aggregateVersion',aggregate_version,'correlationId',id::text,'occurredAt',created_at,'payload',payload) AS envelope FROM card_outbox;
CREATE TABLE event_delivery(event_id uuid PRIMARY KEY REFERENCES card_outbox(id),sent_at timestamptz,lease_token uuid,lease_until timestamptz,attempts int NOT NULL DEFAULT 0 CHECK(attempts>=0),next_attempt timestamptz NOT NULL DEFAULT clock_timestamp(),last_code varchar(40));
CREATE INDEX event_delivery_pending ON event_delivery(next_attempt,event_id) WHERE sent_at IS NULL;
CREATE FUNCTION card_identity_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='TRUNCATE' OR TG_OP='DELETE' THEN RAISE EXCEPTION 'IMMUTABLE_CARD_FACT';END IF;
 IF TG_TABLE_NAME='card_projection' AND (to_jsonb(NEW)-ARRAY['observed_version','observed_state'])<>(to_jsonb(OLD)-ARRAY['observed_version','observed_state']) THEN RAISE EXCEPTION 'IMMUTABLE_CARD_IDENTITY';END IF;
 IF TG_TABLE_NAME='card_authorization' AND (NEW.id,NEW.actor,NEW.card_id,NEW.receipt,NEW.created_at) IS DISTINCT FROM (OLD.id,OLD.actor,OLD.card_id,OLD.receipt,OLD.created_at) THEN RAISE EXCEPTION 'IMMUTABLE_CARD_IDENTITY';END IF;
 IF TG_TABLE_NAME='card_outbox' THEN RAISE EXCEPTION 'IMMUTABLE_CARD_EVENT';END IF;
 RETURN NEW;
END $$;
DO $$ DECLARE t text;BEGIN FOREACH t IN ARRAY ARRAY['card_projection','card_authorization','card_outbox'] LOOP
 EXECUTE format('CREATE TRIGGER immutable_identity BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION card_identity_immutable()',t);
 EXECUTE format('CREATE TRIGGER immutable_truncate BEFORE TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION card_identity_immutable()',t);
END LOOP;END $$;
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM PUBLIC;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA public FROM PUBLIC;
GRANT SELECT,INSERT ON card_projection,card_authorization,card_outbox TO card_runtime;
GRANT UPDATE(observed_version,observed_state) ON card_projection TO card_runtime;
GRANT UPDATE(status,version,code,assessment_id,ledger_outcome,last_grant_expires,attempts,lease_token,lease_until,next_attempt) ON card_authorization TO card_runtime;
GRANT SELECT ON fincore_event_source TO card_runtime;
GRANT SELECT,INSERT,UPDATE ON event_delivery TO card_runtime;
