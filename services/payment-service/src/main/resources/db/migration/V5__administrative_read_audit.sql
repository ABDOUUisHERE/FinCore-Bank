CREATE TABLE payment_access_audit (
 id uuid PRIMARY KEY,
 occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 action varchar(30) NOT NULL CHECK(action IN ('PAYMENT_INSPECT')),
 payment_id uuid,
 http_method varchar(5) NOT NULL CHECK(http_method IN ('GET','POST','OTHER')),
 http_status integer NOT NULL CHECK(http_status BETWEEN 100 AND 599),
 outcome varchar(24) NOT NULL CHECK(outcome = CASE
   WHEN http_status BETWEEN 200 AND 299 THEN 'RESPONSE_READY'
   WHEN http_status IN (400,405,409,415,422) THEN 'INVALID_REQUEST'
   WHEN http_status=401 THEN 'UNAUTHENTICATED' WHEN http_status=403 THEN 'FORBIDDEN'
   WHEN http_status=404 THEN 'NOT_FOUND' ELSE 'SERVER_ERROR' END),
 actor_subject varchar(200),
 actor_provenance varchar(16) NOT NULL CHECK(actor_provenance IN ('SIGNED_JWT','UNAVAILABLE')),
 correlation_id varchar(64) NOT NULL CHECK(correlation_id ~ '^[A-Za-z0-9._-]{1,64}$'),
 CHECK((actor_provenance='UNAVAILABLE' AND actor_subject IS NULL)
    OR (actor_provenance='SIGNED_JWT' AND actor_subject IS NOT NULL AND actor_subject ~ '^[A-Za-z0-9:_-]{1,200}$')),
 CHECK(action='PAYMENT_INSPECT' OR payment_id IS NULL)
);
CREATE FUNCTION reject_payment_access_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'PAYMENT_ACCESS_AUDIT_IMMUTABLE'; END $$;
CREATE TRIGGER payment_access_no_mutation BEFORE UPDATE OR DELETE ON payment_access_audit
 FOR EACH ROW EXECUTE FUNCTION reject_payment_access_mutation();
CREATE TRIGGER payment_access_no_truncate BEFORE TRUNCATE ON payment_access_audit
 FOR EACH STATEMENT EXECUTE FUNCTION reject_payment_access_mutation();
REVOKE ALL ON payment_access_audit FROM PUBLIC;
GRANT SELECT,INSERT ON payment_access_audit TO payment_runtime;

CREATE OR REPLACE VIEW fincore_event_source AS WITH facts AS (
 SELECT id AS event_id,occurred_at,CASE event_type WHEN 'payment.created' THEN 'payment.initiated' ELSE event_type END AS event_type,
   payment_id AS aggregate_id,aggregate_version,id::text AS correlation_id,payload FROM payment_outbox
 UNION ALL
 SELECT id,occurred_at,'payment.access.recorded',id,1,correlation_id,
   jsonb_strip_nulls(jsonb_build_object('paymentId',payment_id,'action',action,'outcome',outcome,
     'httpMethod',http_method,'httpStatus',http_status,'actorSubject',actor_subject,'actorProvenance',actor_provenance))
 FROM payment_access_audit
) SELECT event_id,occurred_at,jsonb_build_object(
 'eventId',event_id,'eventType',event_type,'schemaVersion',1,'aggregateId',aggregate_id,
 'aggregateVersion',aggregate_version,'correlationId',correlation_id,'occurredAt',occurred_at,'payload',payload
) AS envelope FROM facts;
