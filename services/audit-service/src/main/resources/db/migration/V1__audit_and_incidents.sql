REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO audit_runtime;

CREATE TABLE audit_record (
 id uuid PRIMARY KEY,
 sequence bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
 source varchar(12) NOT NULL CHECK(source IN ('DOMAIN','OPERATIONS')),
 event_type varchar(100) NOT NULL, aggregate_id uuid NOT NULL,
 aggregate_version bigint NOT NULL CHECK(aggregate_version>=0),
 actor varchar(200), correlation_id varchar(120) NOT NULL,
 occurred_at timestamptz NOT NULL, observed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 details jsonb NOT NULL CHECK(jsonb_typeof(details)='object'),
 body_hash varchar(64) NOT NULL CHECK(body_hash ~ '^[a-f0-9]{64}$'),
 source_topic varchar(160), source_partition int, source_offset bigint,
 CHECK((source='OPERATIONS' AND source_topic IS NULL AND source_partition IS NULL AND source_offset IS NULL)
    OR (source='DOMAIN' AND source_topic IS NOT NULL AND source_partition IS NOT NULL AND source_offset IS NOT NULL AND source_partition>=0 AND source_offset>=0))
);
CREATE INDEX audit_aggregate ON audit_record(aggregate_id,sequence DESC);
CREATE INDEX audit_type ON audit_record(event_type,sequence DESC);

CREATE TABLE audit_quarantine (
 sequence bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 source_topic varchar(160) NOT NULL,source_partition int NOT NULL CHECK(source_partition>=0),
 source_offset bigint NOT NULL CHECK(source_offset>=0),body_hash varchar(64) NOT NULL,
 code varchar(50) NOT NULL,observed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(source_topic,source_partition,source_offset)
);

CREATE TABLE incident (
 id uuid PRIMARY KEY,created_by varchar(200) NOT NULL,
 resource_kind varchar(12) NOT NULL CHECK(resource_kind IN ('CUSTOMER','ACCOUNT','PAYMENT','JOURNAL','CARD','SERVICE')),
 resource_id uuid NOT NULL,severity varchar(8) NOT NULL CHECK(severity IN ('LOW','MEDIUM','HIGH')),
 status varchar(15) NOT NULL CHECK(status IN ('OPEN','INVESTIGATING','RESOLVED')),
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),reason varchar(60) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX incident_owner ON incident(created_by,id);
CREATE TABLE incident_command (
 actor varchar(200) NOT NULL,key varchar(120) NOT NULL,fingerprint varchar(64) NOT NULL,
 incident_id uuid NOT NULL REFERENCES incident(id),response jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),PRIMARY KEY(actor,key)
);

CREATE FUNCTION immutable_audit_fact() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'IMMUTABLE_AUDIT_FACT';END $$;
DO $$ DECLARE t text;BEGIN
 FOREACH t IN ARRAY ARRAY['audit_record','audit_quarantine','incident_command'] LOOP
  EXECUTE format('CREATE TRIGGER immutable_fact BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION immutable_audit_fact()',t);
  EXECUTE format('CREATE TRIGGER immutable_truncate BEFORE TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION immutable_audit_fact()',t);
 END LOOP;
END $$;
CREATE FUNCTION incident_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP IN ('DELETE','TRUNCATE') THEN RAISE EXCEPTION 'IMMUTABLE_INCIDENT_IDENTITY';END IF;
 IF (to_jsonb(NEW)-ARRAY['status','version','reason','updated_at'])<>(to_jsonb(OLD)-ARRAY['status','version','reason','updated_at'])
  OR NEW.version<>OLD.version+1
  OR NOT ((OLD.status IN ('OPEN','RESOLVED') AND NEW.status='INVESTIGATING') OR (OLD.status='INVESTIGATING' AND NEW.status='RESOLVED'))
 THEN RAISE EXCEPTION 'INVALID_INCIDENT_TRANSITION';END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER incident_update BEFORE UPDATE OR DELETE ON incident FOR EACH ROW EXECUTE FUNCTION incident_guard();
CREATE TRIGGER incident_truncate BEFORE TRUNCATE ON incident FOR EACH STATEMENT EXECUTE FUNCTION incident_guard();

REVOKE ALL ON ALL TABLES IN SCHEMA public FROM PUBLIC;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA public FROM PUBLIC;
GRANT SELECT,INSERT ON audit_record,audit_quarantine,incident,incident_command TO audit_runtime;
GRANT UPDATE(status,version,reason,updated_at) ON incident TO audit_runtime;
GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO audit_runtime;
