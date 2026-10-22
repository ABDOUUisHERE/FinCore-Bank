CREATE SCHEMA ledger AUTHORIZATION ledger_migrator;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA ledger TO ledger_runtime;
ALTER DEFAULT PRIVILEGES IN SCHEMA ledger REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;

CREATE TABLE ledger.account (
  id uuid PRIMARY KEY, external_reference varchar(120) NOT NULL UNIQUE,
  currency varchar(3) NOT NULL CHECK(currency IN ('MAD','EUR','USD')),
  type varchar(30) NOT NULL CHECK(type IN ('CUSTOMER_LIABILITY','SETTLEMENT_ASSET','MERCHANT_LIABILITY')),
  owner_subject varchar(200) NOT NULL CHECK(length(owner_subject)>0),
  status varchar(10) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','FROZEN','CLOSED')),
  guard_revision bigint NOT NULL DEFAULT 0 CHECK(guard_revision>=0),
  UNIQUE(id,currency)
);
CREATE TABLE ledger.balance (
  account_id uuid PRIMARY KEY, currency varchar(3) NOT NULL,
  booked numeric(20,2) NOT NULL DEFAULT 0 CHECK(booked>=0),
  version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
  FOREIGN KEY(account_id,currency) REFERENCES ledger.account(id,currency)
);
CREATE TABLE ledger.operation (
  id uuid PRIMARY KEY, issuer varchar(120) NOT NULL, business_reference varchar(120) NOT NULL,
  actor varchar(200) NOT NULL, kind varchar(30) NOT NULL,
  fingerprint varchar(64) NOT NULL, outcome varchar(10) NOT NULL CHECK(outcome IN ('POSTED','REJECTED')),
  response jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  UNIQUE(issuer,business_reference)
);
CREATE TABLE ledger.idempotency (
  issuer varchar(120) NOT NULL, kind varchar(30) NOT NULL, key varchar(120) NOT NULL,
  operation_id uuid NOT NULL REFERENCES ledger.operation(id),
  PRIMARY KEY(issuer,kind,key)
);
CREATE TABLE ledger.journal (
  id uuid PRIMARY KEY, operation_id uuid NOT NULL UNIQUE REFERENCES ledger.operation(id) DEFERRABLE INITIALLY DEFERRED,
  currency varchar(3) NOT NULL CHECK(currency IN ('MAD','EUR','USD')),
  total numeric(20,2) NOT NULL CHECK(total>0),
  reversal_of uuid UNIQUE REFERENCES ledger.journal(id),
  sealed boolean NOT NULL DEFAULT false,
  created_tx xid8 NOT NULL DEFAULT pg_current_xact_id(),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(), UNIQUE(id,currency)
);
CREATE TABLE ledger.posting (
  id uuid PRIMARY KEY, journal_id uuid NOT NULL, account_id uuid NOT NULL,
  currency varchar(3) NOT NULL, side varchar(6) NOT NULL CHECK(side IN ('DEBIT','CREDIT')),
  amount numeric(20,2) NOT NULL CHECK(amount>0),
  FOREIGN KEY(journal_id,currency) REFERENCES ledger.journal(id,currency),
  FOREIGN KEY(account_id,currency) REFERENCES ledger.account(id,currency)
);
CREATE INDEX posting_account ON ledger.posting(account_id);
CREATE TABLE ledger.outbox_event (
  id uuid PRIMARY KEY, account_id uuid NOT NULL REFERENCES ledger.account(id),
  account_version bigint NOT NULL, operation_id uuid NOT NULL REFERENCES ledger.operation(id) DEFERRABLE INITIALLY DEFERRED,
  payload jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  UNIQUE(account_id,account_version)
);

CREATE FUNCTION ledger.immutable() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,ledger,pg_temp AS $$
BEGIN RAISE EXCEPTION 'IMMUTABLE_FINANCIAL_RECORD'; END $$;
CREATE FUNCTION ledger.seal_only() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,ledger,pg_temp AS $$
BEGIN
  IF TG_OP='UPDATE' AND NOT OLD.sealed AND NEW.sealed AND OLD.created_tx=pg_current_xact_id()
    AND (to_jsonb(OLD)-'sealed')=(to_jsonb(NEW)-'sealed') THEN RETURN NEW; END IF;
  RAISE EXCEPTION 'IMMUTABLE_FINANCIAL_RECORD';
END $$;
CREATE TRIGGER journal_seal BEFORE UPDATE OR DELETE ON ledger.journal FOR EACH ROW EXECUTE FUNCTION ledger.seal_only();
CREATE FUNCTION ledger.open_journal_only() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,ledger,pg_temp AS $$
BEGIN
  PERFORM 1 FROM ledger.journal WHERE id=NEW.journal_id AND NOT sealed AND created_tx=pg_current_xact_id() FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'JOURNAL_ALREADY_SEALED'; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER posting_open BEFORE INSERT ON ledger.posting FOR EACH ROW EXECUTE FUNCTION ledger.open_journal_only();
CREATE FUNCTION ledger.assert_balanced() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,ledger,pg_temp AS $$
DECLARE j ledger.journal; debits numeric; credits numeric; n bigint;
BEGIN
  IF TG_TABLE_NAME='journal' THEN
    SELECT * INTO j FROM ledger.journal WHERE id=NEW.id;
  ELSE
    SELECT * INTO j FROM ledger.journal WHERE id=NEW.journal_id;
  END IF;
  SELECT count(*), coalesce(sum(amount) FILTER(WHERE side='DEBIT'),0), coalesce(sum(amount) FILTER(WHERE side='CREDIT'),0)
    INTO n,debits,credits FROM ledger.posting WHERE journal_id=j.id;
  IF NOT j.sealed OR n<2 OR debits<>credits OR debits<>j.total THEN RAISE EXCEPTION 'UNBALANCED_JOURNAL'; END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER balanced_journal AFTER INSERT ON ledger.journal DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ledger.assert_balanced();
CREATE CONSTRAINT TRIGGER balanced_postings AFTER INSERT ON ledger.posting DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ledger.assert_balanced();
CREATE FUNCTION ledger.assert_outcome() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,ledger,pg_temp AS $$
DECLARE jid uuid;
BEGIN
  SELECT id INTO jid FROM ledger.journal WHERE operation_id=NEW.id;
  IF (NEW.outcome='POSTED') IS DISTINCT FROM (jid IS NOT NULL)
    OR NEW.response->>'operationId' IS DISTINCT FROM NEW.id::text
    OR NEW.response->>'status' IS DISTINCT FROM NEW.outcome
    OR NEW.response->>'journalId' IS DISTINCT FROM jid::text THEN RAISE EXCEPTION 'INVALID_OPERATION_OUTCOME'; END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER definitive_outcome AFTER INSERT ON ledger.operation DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ledger.assert_outcome();
DO $$ DECLARE t text; BEGIN
  FOREACH t IN ARRAY ARRAY['posting','operation','idempotency','outbox_event'] LOOP
    EXECUTE format('CREATE TRIGGER immutable_rows BEFORE UPDATE OR DELETE ON ledger.%I FOR EACH ROW EXECUTE FUNCTION ledger.immutable()',t);
  END LOOP;
  FOREACH t IN ARRAY ARRAY['journal','posting','operation','idempotency','outbox_event','account','balance'] LOOP
    EXECUTE format('CREATE TRIGGER immutable_truncate BEFORE TRUNCATE ON ledger.%I FOR EACH STATEMENT EXECUTE FUNCTION ledger.immutable()',t);
  END LOOP;
END $$;

CREATE FUNCTION ledger.provision(p_reference text,p_currency text,p_type text,p_owner text) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ledger,pg_temp AS $$
DECLARE a ledger.account;
BEGIN
  IF p_reference IS NULL OR length(p_reference) NOT BETWEEN 1 AND 120 OR p_owner IS NULL OR length(p_owner) NOT BETWEEN 1 AND 200
    OR p_currency IS NULL OR p_currency NOT IN ('MAD','EUR','USD')
    OR p_type IS NULL OR p_type NOT IN ('CUSTOMER_LIABILITY','SETTLEMENT_ASSET','MERCHANT_LIABILITY') THEN RAISE EXCEPTION 'INVALID_ACCOUNT'; END IF;
  PERFORM pg_advisory_xact_lock(hashtextextended(jsonb_build_array('account',p_reference)::text,0));
  SELECT * INTO a FROM ledger.account WHERE external_reference=p_reference;
  IF FOUND THEN
    IF (a.currency,a.type,a.owner_subject) IS DISTINCT FROM (p_currency,p_type,p_owner) THEN RAISE EXCEPTION 'ACCOUNT_REFERENCE_CONFLICT'; END IF;
  ELSE
    INSERT INTO ledger.account(id,external_reference,currency,type,owner_subject)
      VALUES(gen_random_uuid(),p_reference,p_currency,p_type,p_owner) RETURNING * INTO a;
    INSERT INTO ledger.balance(account_id,currency) VALUES(a.id,a.currency);
  END IF;
  RETURN to_jsonb(a);
END $$;
CREATE FUNCTION ledger.set_guard(p_id uuid,p_status text,p_revision bigint) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ledger,pg_temp AS $$
DECLARE a ledger.account;
BEGIN
  SELECT * INTO a FROM ledger.account WHERE id=p_id FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'ACCOUNT_NOT_FOUND'; END IF;
  IF p_status IS NULL OR p_status NOT IN ('ACTIVE','FROZEN','CLOSED') THEN RAISE EXCEPTION 'INVALID_GUARD'; END IF;
  IF p_revision IS NULL OR p_revision<>a.guard_revision OR a.status='CLOSED' THEN RAISE EXCEPTION 'GUARD_CONFLICT'; END IF;
  IF p_status='CLOSED' AND (SELECT booked FROM ledger.balance WHERE account_id=p_id)<>0 THEN RAISE EXCEPTION 'ACCOUNT_NOT_EMPTY'; END IF;
  UPDATE ledger.account SET status=p_status,guard_revision=guard_revision+1 WHERE id=p_id RETURNING * INTO a;
  RETURN to_jsonb(a);
END $$;

-- This function is the only runtime path that changes money. Advisory locks serialize
-- request identities; ordered database row locks serialize balances and account guards.
CREATE FUNCTION ledger.post(p_issuer text,p_actor text,p_key text,p_command jsonb) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ledger,pg_temp AS $$
#variable_conflict use_variable
DECLARE
  kind text:=p_command->>'kind'; ref text:=p_command->>'businessReference'; currency text:=p_command->>'currency';
  entries jsonb:=p_command->'entries'; canonical jsonb; fingerprint text; old ledger.operation;
  original ledger.journal; inverse jsonb; reversal uuid; debit_id uuid; credit_id uuid; amount numeric;
  da ledger.account; ca ledger.account; db ledger.balance; cb ledger.balance;
  delta_d numeric; delta_c numeric; reason text; jid uuid; oid uuid:=gen_random_uuid(); response jsonb; lock_id bigint;
BEGIN
  IF p_issuer IS NULL OR length(p_issuer) NOT BETWEEN 1 AND 120 OR p_actor IS NULL OR length(p_actor) NOT BETWEEN 1 AND 200
    OR p_key IS NULL OR length(p_key) NOT BETWEEN 1 AND 120 OR ref IS NULL OR length(ref) NOT BETWEEN 1 AND 120
    OR kind IS NULL OR kind NOT IN ('DEPOSIT','TRANSFER','WITHDRAWAL','MERCHANT_PAYMENT','REVERSAL')
    OR currency IS NULL OR currency NOT IN ('MAD','EUR','USD') OR jsonb_typeof(entries) IS DISTINCT FROM 'array' THEN RAISE EXCEPTION 'INVALID_COMMAND'; END IF;
  IF jsonb_array_length(entries)<>2 THEN RAISE EXCEPTION 'INVALID_POSTINGS'; END IF;
  IF EXISTS(SELECT 1 FROM jsonb_array_elements(entries) e WHERE jsonb_typeof(e->'amount') IS DISTINCT FROM 'string'
      OR coalesce(e->>'amount','') !~ '^(0|[1-9][0-9]{0,17})(\.[0-9]{1,2})?$'
      OR coalesce(e->>'side','') NOT IN ('DEBIT','CREDIT') OR e->>'accountId' IS NULL)
    THEN RAISE EXCEPTION 'INVALID_POSTINGS'; END IF;
  SELECT jsonb_agg(jsonb_build_object('accountId',(e->>'accountId')::uuid,'side',e->>'side','amount',((e->>'amount')::numeric(20,2))::text)
    ORDER BY (e->>'accountId')::uuid,e->>'side') INTO canonical FROM jsonb_array_elements(entries) e;
  SELECT (e->>'accountId')::uuid,(e->>'amount')::numeric INTO debit_id,amount FROM jsonb_array_elements(canonical) e WHERE e->>'side'='DEBIT';
  SELECT (e->>'accountId')::uuid INTO credit_id FROM jsonb_array_elements(canonical) e WHERE e->>'side'='CREDIT';
  IF debit_id IS NULL OR credit_id IS NULL OR debit_id=credit_id OR amount<=0
    OR EXISTS(SELECT 1 FROM jsonb_array_elements(canonical) e WHERE (e->>'amount')::numeric<>amount) THEN RAISE EXCEPTION 'UNBALANCED_COMMAND'; END IF;
  reversal:=(p_command->>'reversalOf')::uuid;
  IF (kind='REVERSAL') IS DISTINCT FROM (reversal IS NOT NULL) THEN RAISE EXCEPTION 'INVALID_REVERSAL'; END IF;
  fingerprint:=encode(sha256(convert_to(jsonb_build_object('actor',p_actor,'kind',kind,'ref',ref,'currency',currency,'entries',canonical,'reversal',reversal)::text,'UTF8')),'hex');
  FOR lock_id IN SELECT DISTINCT h FROM unnest(ARRAY[
    hashtextextended(jsonb_build_array('key',p_issuer,kind,p_key)::text,0),
    hashtextextended(jsonb_build_array('reference',p_issuer,ref)::text,0)]) h ORDER BY h LOOP
    PERFORM pg_advisory_xact_lock(lock_id);
  END LOOP;
  SELECT o.* INTO old FROM ledger.idempotency i JOIN ledger.operation o ON o.id=i.operation_id WHERE i.issuer=p_issuer AND i.kind=kind AND i.key=p_key;
  IF FOUND THEN
    IF old.fingerprint<>fingerprint THEN RAISE EXCEPTION 'IDEMPOTENCY_CONFLICT'; END IF;
    RETURN old.response;
  END IF;
  SELECT * INTO old FROM ledger.operation WHERE issuer=p_issuer AND business_reference=ref;
  IF FOUND THEN
    IF old.fingerprint<>fingerprint THEN RAISE EXCEPTION 'BUSINESS_REFERENCE_CONFLICT'; END IF;
    INSERT INTO ledger.idempotency VALUES(p_issuer,kind,p_key,old.id);
    RETURN old.response;
  END IF;
  IF reversal IS NOT NULL THEN
    SELECT j.* INTO original FROM ledger.journal j JOIN ledger.operation o ON o.id=j.operation_id WHERE j.id=reversal AND o.issuer=p_issuer FOR UPDATE OF j;
    IF NOT FOUND OR original.reversal_of IS NOT NULL OR original.currency<>currency THEN RAISE EXCEPTION 'INVALID_REVERSAL'; END IF;
    SELECT jsonb_agg(jsonb_build_object('accountId',account_id,'side',CASE side WHEN 'DEBIT' THEN 'CREDIT' ELSE 'DEBIT' END,'amount',p.amount::text)
      ORDER BY account_id,CASE side WHEN 'DEBIT' THEN 'CREDIT' ELSE 'DEBIT' END) INTO inverse FROM ledger.posting p WHERE journal_id=reversal;
    IF inverse<>canonical THEN RAISE EXCEPTION 'INVALID_REVERSAL'; END IF;
    IF EXISTS(SELECT 1 FROM ledger.journal WHERE reversal_of=reversal) THEN reason:='ALREADY_REVERSED'; END IF;
  END IF;
  PERFORM 1 FROM ledger.account WHERE id IN (debit_id,credit_id) ORDER BY id FOR UPDATE;
  PERFORM 1 FROM ledger.balance WHERE account_id IN (debit_id,credit_id) ORDER BY account_id FOR UPDATE;
  SELECT * INTO da FROM ledger.account WHERE id=debit_id;
  SELECT * INTO ca FROM ledger.account WHERE id=credit_id;
  SELECT * INTO db FROM ledger.balance WHERE account_id=debit_id;
  SELECT * INTO cb FROM ledger.balance WHERE account_id=credit_id;
  IF da.id IS NULL OR ca.id IS NULL THEN reason:=coalesce(reason,'ACCOUNT_NOT_FOUND');
  ELSIF da.currency<>currency OR ca.currency<>currency THEN RAISE EXCEPTION 'CURRENCY_MISMATCH';
  ELSE
    IF kind<>'REVERSAL' AND NOT ((kind='DEPOSIT' AND da.type='SETTLEMENT_ASSET' AND ca.type='CUSTOMER_LIABILITY')
      OR (kind='TRANSFER' AND da.type='CUSTOMER_LIABILITY' AND ca.type='CUSTOMER_LIABILITY')
      OR (kind='WITHDRAWAL' AND da.type='CUSTOMER_LIABILITY' AND ca.type='SETTLEMENT_ASSET')
      OR (kind='MERCHANT_PAYMENT' AND da.type='CUSTOMER_LIABILITY' AND ca.type='MERCHANT_LIABILITY')) THEN RAISE EXCEPTION 'INVALID_CHART'; END IF;
    IF kind='DEPOSIT' AND amount>1000000.00 THEN reason:=coalesce(reason,'FUNDING_LIMIT_EXCEEDED'); END IF;
    delta_d:=CASE WHEN da.type='SETTLEMENT_ASSET' THEN amount ELSE -amount END;
    delta_c:=CASE WHEN ca.type='SETTLEMENT_ASSET' THEN -amount ELSE amount END;
    IF da.status<>'ACTIVE' OR ca.status<>'ACTIVE' THEN reason:=coalesce(reason,'ACCOUNT_RESTRICTED');
    ELSIF kind<>'REVERSAL' AND da.type<>'SETTLEMENT_ASSET' AND da.owner_subject<>p_actor THEN reason:=coalesce(reason,'DEBIT_NOT_AUTHORIZED');
    ELSIF db.booked+delta_d<0 OR cb.booked+delta_c<0 THEN reason:=coalesce(reason,'INSUFFICIENT_FUNDS');
    ELSIF db.booked+delta_d>999999999999999999.99 OR cb.booked+delta_c>999999999999999999.99 THEN reason:=coalesce(reason,'BALANCE_LIMIT_EXCEEDED'); END IF;
  END IF;
  IF reason IS NULL THEN
    jid:=gen_random_uuid();
    INSERT INTO ledger.journal(id,operation_id,currency,total,reversal_of) VALUES(jid,oid,currency,amount,reversal);
    INSERT INTO ledger.posting(id,journal_id,account_id,currency,side,amount)
      SELECT gen_random_uuid(),jid,(e->>'accountId')::uuid,currency,e->>'side',(e->>'amount')::numeric FROM jsonb_array_elements(canonical) e;
    UPDATE ledger.journal SET sealed=true WHERE id=jid;
    UPDATE ledger.balance SET booked=booked+CASE account_id WHEN debit_id THEN delta_d ELSE delta_c END,version=version+1 WHERE account_id IN (debit_id,credit_id);
    INSERT INTO ledger.outbox_event(id,account_id,account_version,operation_id,payload)
      SELECT gen_random_uuid(),account_id,version,oid,jsonb_build_object('type','LedgerBalanceChanged.v1','accountId',account_id,'currency',b.currency,'booked',booked::text,'version',version,'journalId',jid,'operationId',oid)
      FROM ledger.balance b WHERE account_id IN (debit_id,credit_id);
  END IF;
  response:=jsonb_build_object('operationId',oid,'journalId',jid,'status',CASE WHEN reason IS NULL THEN 'POSTED' ELSE 'REJECTED' END,'code',coalesce(reason,'POSTED'));
  INSERT INTO ledger.operation(id,issuer,business_reference,actor,kind,fingerprint,outcome,response)
    VALUES(oid,p_issuer,ref,p_actor,kind,fingerprint,response->>'status',response);
  INSERT INTO ledger.idempotency VALUES(p_issuer,kind,p_key,oid);
  RETURN response;
END $$;

CREATE VIEW ledger.reconciliation AS
  WITH derived AS (SELECT a.id,coalesce(sum(CASE WHEN (a.type='SETTLEMENT_ASSET')=(p.side='DEBIT') THEN p.amount ELSE -p.amount END),0) AS booked
    FROM ledger.account a LEFT JOIN ledger.posting p ON p.account_id=a.id GROUP BY a.id)
  SELECT d.id AS account_id,b.booked AS stored,d.booked AS derived FROM derived d JOIN ledger.balance b ON b.account_id=d.id WHERE b.booked<>d.booked;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA ledger FROM PUBLIC;
GRANT SELECT ON ALL TABLES IN SCHEMA ledger TO ledger_runtime;
GRANT EXECUTE ON FUNCTION ledger.provision(text,text,text,text),ledger.set_guard(uuid,text,bigint),ledger.post(text,text,text,jsonb) TO ledger_runtime;
