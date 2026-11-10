-- Diagnostic context belongs to delivery metadata, never the immutable source envelope.
ALTER TABLE event_delivery ADD COLUMN traceparent varchar(55)
 CHECK(traceparent IS NULL OR (traceparent ~ '^00-[0-9a-f]{32}-[0-9a-f]{16}-(00|01)$'
 AND substring(traceparent from 4 for 32) <> repeat('0',32)
 AND substring(traceparent from 37 for 16) <> repeat('0',16)));
CREATE FUNCTION preserve_reporting_trace_origin() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.traceparent IS DISTINCT FROM OLD.traceparent OR NEW.event_id IS DISTINCT FROM OLD.event_id THEN RAISE EXCEPTION 'TRACE_ORIGIN_IMMUTABLE'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER reporting_trace_origin_immutable BEFORE UPDATE OF traceparent,event_id ON event_delivery
 FOR EACH ROW EXECUTE FUNCTION preserve_reporting_trace_origin();
