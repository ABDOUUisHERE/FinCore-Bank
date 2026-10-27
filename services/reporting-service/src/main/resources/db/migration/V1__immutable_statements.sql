REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO reporting_runtime;
CREATE TABLE statement_snapshot (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),actor varchar(200) NOT NULL,account_id uuid NOT NULL,
 month varchar(7) NOT NULL,content_hash varchar(64) NOT NULL,payload jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),UNIQUE(actor,content_hash)
);
CREATE INDEX statements_owned ON statement_snapshot(actor,id);
CREATE FUNCTION immutable_statement() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'IMMUTABLE_STATEMENT';END $$;
CREATE TRIGGER immutable_rows BEFORE UPDATE OR DELETE ON statement_snapshot FOR EACH ROW EXECUTE FUNCTION immutable_statement();
CREATE TRIGGER immutable_truncate BEFORE TRUNCATE ON statement_snapshot FOR EACH STATEMENT EXECUTE FUNCTION immutable_statement();
REVOKE ALL ON statement_snapshot FROM PUBLIC;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA public FROM PUBLIC;
GRANT SELECT,INSERT ON statement_snapshot TO reporting_runtime;
