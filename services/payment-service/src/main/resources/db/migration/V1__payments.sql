CREATE TABLE payment (
  id uuid PRIMARY KEY, actor varchar(200) NOT NULL, kind varchar(20) NOT NULL CHECK(kind IN ('TRANSFER','DEPOSIT','REVERSAL')),
  key varchar(120) NOT NULL, source_account_id uuid, destination_account_id uuid, amount numeric(20,2) NOT NULL CHECK(amount>0),
  currency varchar(3) NOT NULL CHECK(currency IN ('MAD','EUR','USD')), reference varchar(140) NOT NULL,
  instruction jsonb NOT NULL, initial_response jsonb NOT NULL, original_payment_id uuid REFERENCES payment(id),
  status varchar(30) NOT NULL DEFAULT 'CREATED' CHECK(status IN ('CREATED','VALIDATING','PENDING_RISK_REVIEW','PROCESSING','COMPLETED','FAILED','REJECTED','REVERSAL_PENDING','REVERSED')),
  version bigint NOT NULL DEFAULT 0, approved boolean NOT NULL DEFAULT false,
  assessment_expires timestamptz, assessment_reason varchar(80), terminal_code varchar(80), journal_id uuid,
  lease_token uuid, lease_until timestamptz, attempts int NOT NULL DEFAULT 0, next_attempt timestamptz NOT NULL DEFAULT clock_timestamp(),
  transition_actor varchar(200) NOT NULL, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  UNIQUE(actor,kind,key), CHECK((kind='REVERSAL')=(original_payment_id IS NOT NULL)),
  CHECK(status NOT IN ('COMPLETED','REVERSED') OR journal_id IS NOT NULL)
);
CREATE UNIQUE INDEX one_active_reversal ON payment(original_payment_id) WHERE kind='REVERSAL' AND status NOT IN ('FAILED','REJECTED');
CREATE INDEX payment_recovery ON payment(next_attempt) WHERE status IN ('CREATED','VALIDATING','PROCESSING');
CREATE TABLE payment_status_history (
  id uuid PRIMARY KEY, payment_id uuid NOT NULL REFERENCES payment(id), version bigint NOT NULL,
  old_status varchar(30), new_status varchar(30) NOT NULL, reason varchar(80), actor varchar(200) NOT NULL,
  occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(), UNIQUE(payment_id,version)
);
CREATE TABLE payment_outbox (
  id uuid PRIMARY KEY, payment_id uuid NOT NULL REFERENCES payment(id), aggregate_version bigint NOT NULL,
  event_type varchar(60) NOT NULL, payload jsonb NOT NULL, occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  UNIQUE(payment_id,aggregate_version)
);
CREATE FUNCTION payment_transition_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF (NEW.id,NEW.actor,NEW.kind,NEW.key,NEW.source_account_id,NEW.destination_account_id,NEW.amount,NEW.currency,NEW.reference,NEW.instruction,NEW.initial_response,NEW.original_payment_id,NEW.created_at)
    IS DISTINCT FROM (OLD.id,OLD.actor,OLD.kind,OLD.key,OLD.source_account_id,OLD.destination_account_id,OLD.amount,OLD.currency,OLD.reference,OLD.instruction,OLD.initial_response,OLD.original_payment_id,OLD.created_at) THEN RAISE EXCEPTION 'IMMUTABLE_PAYMENT_INTENT'; END IF;
  IF NEW.status=OLD.status THEN
    IF NEW.version<>OLD.version THEN RAISE EXCEPTION 'INVALID_PAYMENT_VERSION'; END IF;
  ELSE
    IF NEW.version<>OLD.version+1 OR NOT (
      (OLD.status='CREATED' AND NEW.status IN ('VALIDATING','REJECTED')) OR
      (OLD.status='VALIDATING' AND NEW.status IN ('PENDING_RISK_REVIEW','PROCESSING','REJECTED','FAILED')) OR
      (OLD.status='PENDING_RISK_REVIEW' AND NEW.status IN ('VALIDATING','REJECTED')) OR
      (OLD.status='PROCESSING' AND NEW.status IN ('COMPLETED','FAILED')) OR
      (OLD.status='COMPLETED' AND NEW.status='REVERSAL_PENDING') OR
      (OLD.status='REVERSAL_PENDING' AND NEW.status IN ('REVERSED','COMPLETED'))
    ) THEN RAISE EXCEPTION 'INVALID_PAYMENT_TRANSITION'; END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER payment_guard BEFORE UPDATE ON payment FOR EACH ROW EXECUTE FUNCTION payment_transition_guard();
CREATE FUNCTION payment_transition_fact() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE old_status text;
BEGIN
  IF TG_OP='UPDATE' THEN
    IF NEW.status=OLD.status THEN RETURN NULL; END IF;
    old_status:=OLD.status;
  END IF;
  INSERT INTO payment_status_history VALUES(gen_random_uuid(),NEW.id,NEW.version,old_status,NEW.status,NEW.terminal_code,NEW.transition_actor,clock_timestamp());
  INSERT INTO payment_outbox VALUES(gen_random_uuid(),NEW.id,NEW.version,'payment.'||lower(NEW.status),jsonb_build_object('paymentId',NEW.id,'status',NEW.status,'version',NEW.version,'journalId',NEW.journal_id),clock_timestamp());
  RETURN NULL;
END $$;
CREATE TRIGGER payment_facts AFTER INSERT OR UPDATE ON payment FOR EACH ROW EXECUTE FUNCTION payment_transition_fact();
REVOKE ALL ON payment,payment_status_history,payment_outbox FROM PUBLIC;
GRANT SELECT,INSERT,UPDATE ON payment TO payment_runtime;
GRANT SELECT,INSERT ON payment_status_history,payment_outbox TO payment_runtime;
