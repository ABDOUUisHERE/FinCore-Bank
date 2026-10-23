REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO beneficiary_runtime;

CREATE TABLE beneficiary (
  id uuid PRIMARY KEY, owner_subject varchar(200) NOT NULL,
  key varchar(120) NOT NULL, destination_account_id uuid NOT NULL,
  currency varchar(3) NOT NULL CHECK(currency IN ('MAD','EUR','USD')),
  label varchar(80) NOT NULL CHECK(length(trim(label))>0 AND label !~ '[[:cntrl:]]'),
  initial_label varchar(80) NOT NULL,
  status varchar(10) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','DISABLED')),
  version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  UNIQUE(owner_subject,key), UNIQUE(owner_subject,destination_account_id)
);
CREATE INDEX beneficiary_owner ON beneficiary(owner_subject,id);
CREATE TABLE beneficiary_history (
  beneficiary_id uuid NOT NULL REFERENCES beneficiary(id) DEFERRABLE INITIALLY DEFERRED,
  version bigint NOT NULL, label varchar(80) NOT NULL, status varchar(10) NOT NULL,
  occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY(beneficiary_id,version)
);
CREATE FUNCTION validate_beneficiary() RETURNS trigger LANGUAGE plpgsql
SET search_path=public,pg_temp AS $$
BEGIN
  IF TG_OP='UPDATE' THEN
    IF ROW(NEW.id,NEW.owner_subject,NEW.key,NEW.destination_account_id,NEW.currency,NEW.initial_label,NEW.created_at)
       IS DISTINCT FROM ROW(OLD.id,OLD.owner_subject,OLD.key,OLD.destination_account_id,OLD.currency,OLD.initial_label,OLD.created_at)
       OR NEW.version<>OLD.version+1 THEN
      RAISE EXCEPTION 'Immutable beneficiary identity or invalid version';
    END IF;
  ELSIF NEW.version<>0 OR NEW.status<>'ACTIVE' OR NEW.initial_label<>NEW.label THEN
    RAISE EXCEPTION 'Invalid initial beneficiary';
  END IF;
  NEW.updated_at=clock_timestamp();
  RETURN NEW;
END $$;
CREATE TRIGGER beneficiary_validation BEFORE INSERT OR UPDATE ON beneficiary FOR EACH ROW EXECUTE FUNCTION validate_beneficiary();
CREATE FUNCTION record_beneficiary() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
SET search_path=public,pg_temp AS $$
BEGIN
  INSERT INTO beneficiary_history(beneficiary_id,version,label,status)
    VALUES(NEW.id,NEW.version,NEW.label,NEW.status);
  RETURN NEW;
END $$;
CREATE TRIGGER beneficiary_fact AFTER INSERT OR UPDATE ON beneficiary FOR EACH ROW EXECUTE FUNCTION record_beneficiary();
REVOKE ALL ON FUNCTION record_beneficiary(),validate_beneficiary() FROM PUBLIC;
GRANT SELECT ON beneficiary,beneficiary_history TO beneficiary_runtime;
GRANT INSERT(id,owner_subject,key,destination_account_id,currency,label,initial_label) ON beneficiary TO beneficiary_runtime;
GRANT UPDATE(label,status,version) ON beneficiary TO beneficiary_runtime;
