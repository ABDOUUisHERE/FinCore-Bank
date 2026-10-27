-- Each table has a different NEW/OLD record shape. Do not rely on SQL AND
-- short-circuiting when the same pooled connection has cached another shape.
CREATE OR REPLACE FUNCTION card_identity_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='TRUNCATE' OR TG_OP='DELETE' THEN
  RAISE EXCEPTION 'IMMUTABLE_CARD_FACT';
 END IF;
 IF TG_TABLE_NAME='card_projection' THEN
  IF (to_jsonb(NEW)-ARRAY['observed_version','observed_state'])<>(to_jsonb(OLD)-ARRAY['observed_version','observed_state']) THEN
   RAISE EXCEPTION 'IMMUTABLE_CARD_IDENTITY';
  END IF;
 ELSIF TG_TABLE_NAME='card_authorization' THEN
  IF (NEW.id,NEW.actor,NEW.card_id,NEW.receipt,NEW.created_at) IS DISTINCT FROM (OLD.id,OLD.actor,OLD.card_id,OLD.receipt,OLD.created_at) THEN
   RAISE EXCEPTION 'IMMUTABLE_CARD_IDENTITY';
  END IF;
 ELSIF TG_TABLE_NAME='card_outbox' THEN
  RAISE EXCEPTION 'IMMUTABLE_CARD_EVENT';
 ELSE
  RAISE EXCEPTION 'UNKNOWN_CARD_FACT';
 END IF;
 RETURN NEW;
END $$;
