REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO notification_runtime;
CREATE TABLE consumed_event(event_id uuid PRIMARY KEY, payload_hash text NOT NULL, processed_at timestamptz NOT NULL DEFAULT clock_timestamp());
CREATE TABLE aggregate_watermark(aggregate_id uuid PRIMARY KEY, version bigint NOT NULL, event_type text NOT NULL);
CREATE TABLE ledger_snapshot(account_id uuid PRIMARY KEY, version bigint NOT NULL, payload jsonb NOT NULL);
CREATE TABLE notification_preferences(subject varchar(200) PRIMARY KEY,in_app boolean NOT NULL DEFAULT true,email boolean NOT NULL DEFAULT false,payments boolean NOT NULL DEFAULT true,security boolean NOT NULL DEFAULT true,version bigint NOT NULL DEFAULT 0);
CREATE TABLE notification (
 id uuid PRIMARY KEY, sequence bigint GENERATED ALWAYS AS IDENTITY UNIQUE, event_id uuid NOT NULL, subject varchar(200) NOT NULL,
 aggregate_id uuid NOT NULL, category varchar(20) NOT NULL CHECK(category IN ('PAYMENTS','SECURITY')),title varchar(100) NOT NULL,
 body varchar(500) NOT NULL,in_app boolean NOT NULL,read_at timestamptz,created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 semantic_key text NOT NULL,UNIQUE(subject,semantic_key)
);
CREATE INDEX notification_owner ON notification(subject,sequence DESC);
CREATE TABLE email_delivery(id uuid PRIMARY KEY REFERENCES notification(id),subject varchar(200) NOT NULL,attempts int NOT NULL DEFAULT 0,
 lease_token uuid,lease_until timestamptz,next_attempt timestamptz NOT NULL DEFAULT clock_timestamp(),delivered_at timestamptz);
CREATE TABLE synthetic_email(delivery_id uuid PRIMARY KEY REFERENCES notification(id),subject varchar(200) NOT NULL,title varchar(100) NOT NULL,body varchar(500) NOT NULL,created_at timestamptz NOT NULL DEFAULT clock_timestamp());
CREATE TABLE dead_letter(id uuid PRIMARY KEY,source_topic varchar(160) NOT NULL,record_key text,body text NOT NULL,
 source_partition int NOT NULL,source_offset bigint NOT NULL,version bigint NOT NULL DEFAULT 0,created_at timestamptz NOT NULL DEFAULT clock_timestamp(),UNIQUE(source_partition,source_offset));
CREATE TABLE replay_audit(id uuid PRIMARY KEY,dead_letter_id uuid NOT NULL REFERENCES dead_letter(id),version bigint NOT NULL,actor varchar(200) NOT NULL,
 reason varchar(100) NOT NULL,created_at timestamptz NOT NULL DEFAULT clock_timestamp(),UNIQUE(dead_letter_id,version));
CREATE TABLE replay_outbox(id uuid PRIMARY KEY REFERENCES replay_audit(id),body jsonb NOT NULL,sent_at timestamptz,lease_token uuid,lease_until timestamptz,attempts int NOT NULL DEFAULT 0,next_attempt timestamptz NOT NULL DEFAULT clock_timestamp());
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM PUBLIC;
GRANT SELECT,INSERT ON consumed_event,notification,synthetic_email,dead_letter,replay_audit TO notification_runtime;
GRANT UPDATE(read_at) ON notification TO notification_runtime;
GRANT SELECT,INSERT,UPDATE ON aggregate_watermark,ledger_snapshot,notification_preferences,email_delivery,replay_outbox TO notification_runtime;
GRANT UPDATE(version) ON dead_letter TO notification_runtime;
GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO notification_runtime;
