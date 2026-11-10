CREATE TABLE reporting_access_audit (
 id uuid PRIMARY KEY, occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 action varchar(30) NOT NULL CHECK(action IN ('STATEMENT_CREATE_REQUEST','STATEMENT_LIST','STATEMENT_INSPECT','STATEMENT_EXPORT_CSV','STATEMENT_EXPORT_PDF','RECONCILIATION_INSPECT','JOURNAL_INSPECT')),
 resource_id uuid,
 http_method varchar(5) NOT NULL CHECK(http_method IN ('GET','POST','OTHER')),
 http_status integer NOT NULL CHECK(http_status BETWEEN 100 AND 599),
 outcome varchar(24) NOT NULL CHECK(outcome=CASE
  WHEN http_status BETWEEN 200 AND 299 THEN 'RESPONSE_READY'
  WHEN http_status IN (400,405,409,415,422) THEN 'INVALID_REQUEST'
  WHEN http_status=401 THEN 'UNAUTHENTICATED' WHEN http_status=403 THEN 'FORBIDDEN'
  WHEN http_status=404 THEN 'NOT_FOUND' ELSE 'SERVER_ERROR' END),
 actor_subject varchar(200),
 actor_provenance varchar(16) NOT NULL CHECK(actor_provenance IN ('SIGNED_JWT','UNAVAILABLE')),
 correlation_id varchar(64) NOT NULL CHECK(correlation_id ~ '^[A-Za-z0-9._-]{1,64}$'),
 CHECK((actor_provenance='UNAVAILABLE' AND actor_subject IS NULL)
  OR (actor_provenance='SIGNED_JWT' AND actor_subject IS NOT NULL AND actor_subject ~ '^[A-Za-z0-9:_-]{1,200}$')),
 CHECK(action IN ('STATEMENT_INSPECT','STATEMENT_EXPORT_CSV','STATEMENT_EXPORT_PDF','JOURNAL_INSPECT') OR resource_id IS NULL)
);
CREATE FUNCTION reject_reporting_access_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'REPORTING_ACCESS_AUDIT_IMMUTABLE'; END $$;
CREATE TRIGGER reporting_access_no_mutation BEFORE UPDATE OR DELETE ON reporting_access_audit
 FOR EACH ROW EXECUTE FUNCTION reject_reporting_access_mutation();
CREATE TRIGGER reporting_access_no_truncate BEFORE TRUNCATE ON reporting_access_audit
 FOR EACH STATEMENT EXECUTE FUNCTION reject_reporting_access_mutation();
REVOKE ALL ON reporting_access_audit FROM PUBLIC;
GRANT SELECT,INSERT ON reporting_access_audit TO reporting_runtime;
CREATE VIEW fincore_event_source AS
SELECT id AS event_id,occurred_at,jsonb_build_object(
 'eventId',id,'eventType','reporting.access.recorded','schemaVersion',1,'aggregateId',id,
 'aggregateVersion',1,'correlationId',correlation_id,'occurredAt',occurred_at,
 'payload',jsonb_strip_nulls(jsonb_build_object('action',action,'httpMethod',http_method,'httpStatus',http_status,
  'outcome',outcome,'actorSubject',actor_subject,'actorProvenance',actor_provenance,
  'statementId',CASE WHEN action IN ('STATEMENT_INSPECT','STATEMENT_EXPORT_CSV','STATEMENT_EXPORT_PDF') THEN resource_id END,
  'journalId',CASE WHEN action='JOURNAL_INSPECT' THEN resource_id END))) AS envelope
FROM reporting_access_audit;
CREATE TABLE event_delivery (
 event_id uuid PRIMARY KEY, sent_at timestamptz, lease_token uuid, lease_until timestamptz,
 attempts int NOT NULL DEFAULT 0 CHECK(attempts>=0), next_attempt timestamptz NOT NULL DEFAULT clock_timestamp(), last_code varchar(40)
);
CREATE INDEX event_delivery_pending ON event_delivery(next_attempt,event_id) WHERE sent_at IS NULL;
REVOKE ALL ON event_delivery FROM PUBLIC;
GRANT SELECT,INSERT,UPDATE ON event_delivery TO reporting_runtime;
GRANT SELECT ON fincore_event_source TO reporting_runtime;
