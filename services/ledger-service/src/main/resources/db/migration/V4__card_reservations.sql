-- Additive card spending guards and reservations. Existing financial facts are untouched.
CREATE TABLE ledger.card_control (
 id uuid PRIMARY KEY,account_id uuid NOT NULL REFERENCES ledger.account(id),actor varchar(200) NOT NULL,
 status varchar(12) NOT NULL CHECK(status IN ('ACTIVE','FROZEN','TERMINATED')),
 transaction_limit numeric(20,2) NOT NULL CHECK(transaction_limit>0),daily_limit numeric(20,2) NOT NULL CHECK(daily_limit>=transaction_limit),
 version bigint NOT NULL DEFAULT 0,expires_at timestamptz NOT NULL,created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE ledger.card_intent (
 id uuid PRIMARY KEY,card_id uuid NOT NULL REFERENCES ledger.card_control(id),actor varchar(200) NOT NULL,
 request_key varchar(120) NOT NULL,merchant_id uuid NOT NULL REFERENCES ledger.account(id),amount numeric(20,2) NOT NULL CHECK(amount>0),
 currency varchar(3) NOT NULL,fingerprint varchar(64) NOT NULL,created_at timestamptz NOT NULL DEFAULT clock_timestamp(),UNIQUE(actor,request_key)
);
CREATE TABLE ledger.card_hold (
 id uuid PRIMARY KEY REFERENCES ledger.card_intent(id),account_id uuid NOT NULL REFERENCES ledger.account(id),card_id uuid NOT NULL REFERENCES ledger.card_control(id),
 amount numeric(20,2) NOT NULL CHECK(amount>0),status varchar(12) NOT NULL CHECK(status IN ('HELD','DECLINED','CAPTURED','RELEASED','REFUNDED')),
 assessment_id uuid NOT NULL,expires_at timestamptz NOT NULL,admitted_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 reason varchar(80) NOT NULL,journal_id uuid REFERENCES ledger.journal(id),refund_journal_id uuid REFERENCES ledger.journal(id)
);
CREATE INDEX card_hold_live ON ledger.card_hold(account_id,expires_at) WHERE status='HELD';
CREATE INDEX card_hold_daily ON ledger.card_hold(card_id,admitted_at);
CREATE TABLE ledger.card_action (
 actor varchar(200) NOT NULL,kind varchar(20) NOT NULL,request_key varchar(120) NOT NULL,fingerprint varchar(64) NOT NULL,
 response jsonb NOT NULL,created_at timestamptz NOT NULL DEFAULT clock_timestamp(),PRIMARY KEY(actor,kind,request_key)
);
CREATE TABLE ledger.card_merchant(id uuid PRIMARY KEY REFERENCES ledger.account(id),label text NOT NULL);
INSERT INTO ledger.card_merchant SELECT (ledger.provision('card-merchant:demo-market:'||currency,currency,'MERCHANT_LIABILITY','synthetic-demo-market')->>'id')::uuid,'Demo Market' FROM (VALUES('MAD'),('EUR'),('USD')) c(currency);
DO $$ DECLARE t text;BEGIN
 FOREACH t IN ARRAY ARRAY['card_intent','card_action'] LOOP
  EXECUTE format('CREATE TRIGGER immutable_rows BEFORE UPDATE OR DELETE ON ledger.%I FOR EACH ROW EXECUTE FUNCTION ledger.immutable()',t);
 END LOOP;
 FOREACH t IN ARRAY ARRAY['card_control','card_intent','card_hold','card_action','card_merchant'] LOOP
  EXECUTE format('CREATE TRIGGER immutable_truncate BEFORE TRUNCATE ON ledger.%I FOR EACH STATEMENT EXECUTE FUNCTION ledger.immutable()',t);
 END LOOP;
END $$;
CREATE FUNCTION ledger.reserved(p_account uuid) RETURNS numeric LANGUAGE sql STABLE SET search_path=pg_catalog,ledger,pg_temp AS $$
 SELECT coalesce(sum(amount),0) FROM ledger.card_hold WHERE account_id=p_account AND status='HELD' AND expires_at>statement_timestamp()
$$;
CREATE OR REPLACE FUNCTION ledger.post(p_issuer text,p_actor text,p_key text,p_command jsonb) RETURNS jsonb
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
    ELSIF db.booked+delta_d<ledger.reserved(debit_id) OR cb.booked+delta_c<ledger.reserved(credit_id) THEN reason:=coalesce(reason,'INSUFFICIENT_FUNDS');
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
      SELECT gen_random_uuid(),account_id,version,oid,jsonb_build_object('type','LedgerBalanceChanged.v1','accountId',account_id,'currency',b.currency,'booked',booked::text,'available',(booked-ledger.reserved(account_id))::text,'version',version,'journalId',jid,'operationId',oid)
      FROM ledger.balance b WHERE account_id IN (debit_id,credit_id);
  END IF;
  response:=jsonb_build_object('operationId',oid,'journalId',jid,'status',CASE WHEN reason IS NULL THEN 'POSTED' ELSE 'REJECTED' END,'code',coalesce(reason,'POSTED'));
  INSERT INTO ledger.operation(id,issuer,business_reference,actor,kind,fingerprint,outcome,response)
    VALUES(oid,p_issuer,ref,p_actor,kind,fingerprint,response->>'status',response);
  INSERT INTO ledger.idempotency VALUES(p_issuer,kind,p_key,oid);
  RETURN response;
END $$;

CREATE OR REPLACE FUNCTION ledger.set_guard(p_id uuid,p_status text,p_revision bigint) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ledger,pg_temp AS $$
DECLARE a ledger.account;
BEGIN
  SELECT * INTO a FROM ledger.account WHERE id=p_id FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'ACCOUNT_NOT_FOUND'; END IF;
  IF p_status IS NULL OR p_status NOT IN ('ACTIVE','FROZEN','CLOSED') THEN RAISE EXCEPTION 'INVALID_GUARD'; END IF;
  IF p_revision IS NULL OR p_revision<>a.guard_revision OR a.status='CLOSED' THEN RAISE EXCEPTION 'GUARD_CONFLICT'; END IF;
  IF p_status='CLOSED' AND ledger.reserved(p_id)>0 THEN RAISE EXCEPTION 'ACCOUNT_NOT_EMPTY'; END IF;
  IF p_status='CLOSED' AND (SELECT booked FROM ledger.balance WHERE account_id=p_id)<>0 THEN RAISE EXCEPTION 'ACCOUNT_NOT_EMPTY'; END IF;
  UPDATE ledger.account SET status=p_status,guard_revision=guard_revision+1 WHERE id=p_id RETURNING * INTO a;
  RETURN to_jsonb(a);
END $$;


CREATE FUNCTION ledger.card_view(p_id uuid) RETURNS jsonb LANGUAGE sql STABLE SET search_path=pg_catalog,ledger,pg_temp AS $$
 SELECT jsonb_build_object('cardId',c.id,'ledgerAccountId',c.account_id,'accountReference',a.external_reference,'actor',c.actor,'currency',a.currency,'status',CASE WHEN c.expires_at<=statement_timestamp() AND c.status<>'TERMINATED' THEN 'EXPIRED' ELSE c.status END,'transactionLimit',c.transaction_limit::text,'dailyLimit',c.daily_limit::text,'version',c.version,'expiresAt',c.expires_at,'createdAt',c.created_at) FROM ledger.card_control c JOIN ledger.account a ON a.id=c.account_id WHERE c.id=p_id
$$;
CREATE FUNCTION ledger.card_authorization(p_id uuid) RETURNS jsonb LANGUAGE sql STABLE SET search_path=pg_catalog,ledger,pg_temp AS $$
 SELECT jsonb_build_object('authorizationId',i.id,'paymentId',i.id,'cardId',i.card_id,'actor',i.actor,'kind','MERCHANT_PAYMENT','fingerprint',i.fingerprint,'amount',i.amount::text,'currency',i.currency,'destinationAccountId',i.merchant_id,'merchantId',i.merchant_id,'createdAt',i.created_at,'status',CASE WHEN h.id IS NULL THEN 'AUTHORIZING' WHEN h.status='HELD' AND h.expires_at<=statement_timestamp() THEN 'EXPIRED' ELSE h.status END,'code',h.reason,'assessmentId',h.assessment_id,'expiresAt',h.expires_at,'journalId',h.journal_id,'refundJournalId',h.refund_journal_id) FROM ledger.card_intent i LEFT JOIN ledger.card_hold h ON h.id=i.id WHERE i.id=p_id
$$;
CREATE FUNCTION ledger.card_record(p_actor text,p_kind text,p_key text,p_fingerprint text,p_response jsonb) RETURNS jsonb LANGUAGE plpgsql SET search_path=pg_catalog,ledger,pg_temp AS $$
BEGIN INSERT INTO ledger.card_action VALUES(p_actor,p_kind,p_key,p_fingerprint,p_response,clock_timestamp());RETURN p_response;END $$;
CREATE FUNCTION ledger.card_replay(p_actor text,p_kind text,p_key text,p_fingerprint text) RETURNS jsonb LANGUAGE plpgsql SET search_path=pg_catalog,ledger,pg_temp AS $$
DECLARE saved ledger.card_action;BEGIN
 IF p_actor IS NULL OR length(p_actor) NOT BETWEEN 1 AND 200 OR p_key IS NULL OR p_key !~ '^[A-Za-z0-9._:-]{1,120}$' THEN RAISE EXCEPTION 'INVALID_COMMAND';END IF;
 PERFORM pg_advisory_xact_lock(hashtextextended(jsonb_build_array('card-action',p_actor,p_kind,p_key)::text,0));
 SELECT * INTO saved FROM ledger.card_action WHERE actor=p_actor AND kind=p_kind AND request_key=p_key;
 IF FOUND THEN IF saved.fingerprint<>p_fingerprint THEN RAISE EXCEPTION 'IDEMPOTENCY_CONFLICT'; END IF;RETURN saved.response;END IF;RETURN NULL;
END $$;
CREATE FUNCTION ledger.card_create(p_id uuid,p_actor text,p_key text,p_account uuid,p_transaction numeric,p_daily numeric) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ledger,pg_temp AS $$
DECLARE fp text;result jsonb; a ledger.account;BEGIN
 fp:=encode(sha256(convert_to(jsonb_build_array(p_id,p_account,p_transaction,p_daily)::text,'UTF8')),'hex');
 result:=ledger.card_replay(p_actor,'CREATE',p_key,fp);IF result IS NOT NULL THEN RETURN result;END IF;
 IF p_id IS NULL OR p_transaction IS NULL OR p_daily IS NULL OR p_transaction<=0 OR p_daily<p_transaction OR p_daily>1000000 OR scale(p_transaction)>2 OR scale(p_daily)>2 THEN RAISE EXCEPTION 'INVALID_CARD_LIMIT';END IF;
 SELECT * INTO a FROM ledger.account WHERE id=p_account FOR UPDATE;
 IF NOT FOUND OR a.owner_subject<>p_actor OR a.type<>'CUSTOMER_LIABILITY' THEN RAISE EXCEPTION 'CARD_NOT_FOUND';END IF;
 IF a.status<>'ACTIVE' THEN RAISE EXCEPTION 'CARD_RESTRICTED';END IF;
 INSERT INTO ledger.card_control VALUES(p_id,p_account,p_actor,'ACTIVE',p_transaction,p_daily,0,date_trunc('day',clock_timestamp() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'+interval '3 years',clock_timestamp());
 RETURN ledger.card_record(p_actor,'CREATE',p_key,fp,ledger.card_view(p_id));
END $$;
CREATE FUNCTION ledger.card_configure(p_id uuid,p_actor text,p_key text,p_version bigint,p_status text,p_transaction numeric,p_daily numeric) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ledger,pg_temp AS $$
DECLARE fp text;result jsonb;c ledger.card_control;BEGIN
 fp:=encode(sha256(convert_to(jsonb_build_array(p_id,p_version,p_status,p_transaction,p_daily)::text,'UTF8')),'hex');
 result:=ledger.card_replay(p_actor,'CONFIGURE',p_key,fp);IF result IS NOT NULL THEN RETURN result;END IF;
 SELECT * INTO c FROM ledger.card_control WHERE id=p_id AND actor=p_actor;
 IF NOT FOUND THEN RAISE EXCEPTION 'CARD_NOT_FOUND';END IF;
 PERFORM 1 FROM ledger.account WHERE id=c.account_id FOR UPDATE;
 SELECT * INTO c FROM ledger.card_control WHERE id=p_id FOR UPDATE;
 IF c.version<>p_version OR c.status='TERMINATED' OR c.expires_at<=clock_timestamp() THEN RAISE EXCEPTION 'CARD_VERSION_CONFLICT';END IF;
 IF p_status IS NULL OR p_status NOT IN ('ACTIVE','FROZEN','TERMINATED') OR p_transaction IS NULL OR p_daily IS NULL OR p_transaction<=0 OR p_daily<p_transaction OR p_daily>1000000 OR scale(p_transaction)>2 OR scale(p_daily)>2 THEN RAISE EXCEPTION 'INVALID_CARD_LIMIT';END IF;
 IF p_status='TERMINATED' AND EXISTS(SELECT FROM ledger.card_hold WHERE card_id=p_id AND status='HELD' AND expires_at>clock_timestamp()) THEN RAISE EXCEPTION 'CARD_HAS_RESERVATIONS';END IF;
 UPDATE ledger.card_control SET status=p_status,transaction_limit=p_transaction,daily_limit=p_daily,version=version+1 WHERE id=p_id;
 RETURN ledger.card_record(p_actor,'CONFIGURE',p_key,fp,ledger.card_view(p_id));
END $$;
CREATE FUNCTION ledger.card_prepare(p_id uuid,p_card uuid,p_actor text,p_key text,p_merchant uuid,p_amount numeric,p_currency text) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ledger,pg_temp AS $$
DECLARE fp text;result jsonb;c ledger.card_control;a ledger.account;m ledger.account;BEGIN
 fp:=encode(sha256(convert_to(jsonb_build_array('MERCHANT_PAYMENT',p_id,p_card,p_actor,p_merchant,p_amount,p_currency)::text,'UTF8')),'hex');
 result:=ledger.card_replay(p_actor,'AUTHORIZE',p_key,fp);IF result IS NOT NULL THEN RETURN result;END IF;
 SELECT * INTO c FROM ledger.card_control WHERE id=p_card AND actor=p_actor;
 IF NOT FOUND THEN RAISE EXCEPTION 'CARD_NOT_FOUND';END IF;
 SELECT * INTO a FROM ledger.account WHERE id=c.account_id;
 SELECT ma.* INTO m FROM ledger.account ma JOIN ledger.card_merchant cm ON cm.id=ma.id WHERE ma.id=p_merchant;
 IF m.id IS NULL OR m.currency<>p_currency OR a.currency<>p_currency OR p_amount IS NULL OR p_amount<=0 OR p_amount>999999999999999999.99 OR scale(p_amount)>2 THEN RAISE EXCEPTION 'INVALID_CARD_PAYMENT';END IF;
 INSERT INTO ledger.card_intent VALUES(p_id,p_card,p_actor,p_key,p_merchant,p_amount,p_currency,fp,clock_timestamp());
 RETURN ledger.card_record(p_actor,'AUTHORIZE',p_key,fp,ledger.card_authorization(p_id));
END $$;
CREATE FUNCTION ledger.card_reserve(p_id uuid,p_assessment uuid,p_assessment_expires timestamptz) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ledger,pg_temp AS $$
DECLARE i ledger.card_intent;c ledger.card_control;a ledger.account;h ledger.card_hold;used numeric;reason text;expires timestamptz;BEGIN
 SELECT * INTO i FROM ledger.card_intent WHERE id=p_id;
 IF NOT FOUND THEN RAISE EXCEPTION 'CARD_NOT_FOUND';END IF;
 SELECT * INTO c FROM ledger.card_control WHERE id=i.card_id;
 PERFORM 1 FROM ledger.account WHERE id=c.account_id FOR UPDATE;
 SELECT * INTO c FROM ledger.card_control WHERE id=i.card_id FOR UPDATE;
 SELECT * INTO h FROM ledger.card_hold WHERE id=p_id;
 IF FOUND THEN RETURN ledger.card_authorization(p_id);END IF;
 IF p_assessment IS NULL OR p_assessment_expires IS NULL OR p_assessment_expires<=clock_timestamp() OR p_assessment_expires>clock_timestamp()+interval '1 minute' THEN RAISE EXCEPTION 'ASSESSMENT_EXPIRED';END IF;
 SELECT * INTO a FROM ledger.account WHERE id=c.account_id;
 SELECT coalesce(sum(amount),0) INTO used FROM ledger.card_hold WHERE card_id=i.card_id AND admitted_at>=(date_trunc('day',clock_timestamp() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC') AND (status IN ('CAPTURED','REFUNDED') OR status='HELD' AND expires_at>clock_timestamp());
 IF c.status<>'ACTIVE' OR c.expires_at<=clock_timestamp() OR a.status<>'ACTIVE' THEN reason:='CARD_RESTRICTED';
 ELSIF i.amount>c.transaction_limit OR i.amount+used>c.daily_limit THEN reason:='CARD_LIMIT_EXCEEDED';
 ELSIF (SELECT booked FROM ledger.balance WHERE account_id=c.account_id)-ledger.reserved(c.account_id)<i.amount THEN reason:='INSUFFICIENT_FUNDS';END IF;
 expires:=least(clock_timestamp()+interval '30 minutes',c.expires_at);
 -- Reservation admission linearizes here under the same account lock as every debit.
 IF p_assessment_expires<=clock_timestamp() THEN RAISE EXCEPTION 'ASSESSMENT_EXPIRED';END IF;
 INSERT INTO ledger.card_hold VALUES(p_id,c.account_id,c.id,i.amount,CASE WHEN reason IS NULL THEN 'HELD' ELSE 'DECLINED' END,p_assessment,expires,clock_timestamp(),coalesce(reason,'RESERVED'),NULL,NULL);
 IF reason IS NULL THEN UPDATE ledger.balance SET version=version+1 WHERE account_id=c.account_id;END IF;
 RETURN ledger.card_authorization(p_id);
END $$;
CREATE FUNCTION ledger.card_settle(p_id uuid,p_actor text,p_key text,p_action text) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ledger,pg_temp AS $$
DECLARE fp text;result jsonb;i ledger.card_intent;c ledger.card_control;h ledger.card_hold;outcome jsonb;command jsonb;BEGIN
 IF p_action IS NULL OR p_action NOT IN ('CAPTURE','RELEASE','REFUND') THEN RAISE EXCEPTION 'INVALID_CARD_PAYMENT';END IF;
 fp:=encode(sha256(convert_to(jsonb_build_array(p_id,p_action)::text,'UTF8')),'hex');
 result:=ledger.card_replay(p_actor,p_action,p_key,fp);IF result IS NOT NULL THEN RETURN result;END IF;
 SELECT * INTO i FROM ledger.card_intent WHERE id=p_id AND actor=p_actor;
 IF NOT FOUND THEN RAISE EXCEPTION 'CARD_NOT_FOUND';END IF;
 SELECT * INTO h FROM ledger.card_hold WHERE id=p_id;
 IF p_action='REFUND' AND h.journal_id IS NOT NULL THEN PERFORM 1 FROM ledger.journal WHERE id=h.journal_id FOR UPDATE;END IF;
 SELECT * INTO c FROM ledger.card_control WHERE id=i.card_id;
 -- Every financial path locks accounts in immutable order before controls and balances.
 PERFORM 1 FROM ledger.account WHERE id IN (c.account_id,i.merchant_id) ORDER BY id FOR UPDATE;
 SELECT * INTO h FROM ledger.card_hold WHERE id=p_id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'CARD_STATE_CONFLICT';END IF;
 IF p_action='RELEASE' AND h.status IN ('HELD','RELEASED') THEN
  IF h.status='HELD' THEN UPDATE ledger.card_hold SET status='RELEASED',reason='RELEASED' WHERE id=p_id;UPDATE ledger.balance SET version=version+1 WHERE account_id=c.account_id;END IF;
 ELSIF (p_action='CAPTURE' AND h.status='CAPTURED') OR (p_action='REFUND' AND h.status='REFUNDED') THEN NULL;
 ELSIF p_action='CAPTURE' AND h.status='HELD' AND h.expires_at>clock_timestamp() THEN
  UPDATE ledger.card_hold SET status='CAPTURED' WHERE id=p_id;
  -- The existing journal trigger checks this deadline at the actual posting point.
  PERFORM set_config('fincore.assessment_expires',h.expires_at::text,true);
  command:=jsonb_build_object('kind','MERCHANT_PAYMENT','businessReference',p_id,'currency',i.currency,'entries',jsonb_build_array(jsonb_build_object('accountId',c.account_id,'side','DEBIT','amount',i.amount::text),jsonb_build_object('accountId',i.merchant_id,'side','CREDIT','amount',i.amount::text)));
  outcome:=ledger.post('fincore-card',p_actor,p_id::text,command);
  UPDATE ledger.card_hold SET status=CASE WHEN outcome->>'status'='POSTED' THEN 'CAPTURED' ELSE 'DECLINED' END,reason=outcome->>'code',journal_id=(outcome->>'journalId')::uuid WHERE id=p_id;
  IF outcome->>'status'<>'POSTED' THEN UPDATE ledger.balance SET version=version+1 WHERE account_id=c.account_id;END IF;
 ELSIF p_action='REFUND' AND h.status='CAPTURED' THEN
  SELECT jsonb_build_object('kind','REVERSAL','businessReference','refund:'||p_id,'currency',i.currency,'reversalOf',h.journal_id,'entries',jsonb_agg(jsonb_build_object('accountId',account_id,'side',CASE side WHEN 'DEBIT' THEN 'CREDIT' ELSE 'DEBIT' END,'amount',amount::text))) INTO command FROM ledger.posting WHERE journal_id=h.journal_id;
  outcome:=ledger.post('fincore-card',p_actor,'refund:'||p_id,command);
  IF outcome->>'status'='POSTED' THEN UPDATE ledger.card_hold SET status='REFUNDED',reason='REFUNDED',refund_journal_id=(outcome->>'journalId')::uuid WHERE id=p_id;END IF;
 ELSE RAISE EXCEPTION 'CARD_STATE_CONFLICT';END IF;
 result:=ledger.card_authorization(p_id);
 IF outcome IS NOT NULL THEN result:=result||jsonb_build_object('operationOutcome',outcome);END IF;
 RETURN ledger.card_record(p_actor,p_action,p_key,fp,result);
END $$;
REVOKE ALL ON ledger.card_control,ledger.card_intent,ledger.card_hold,ledger.card_action,ledger.card_merchant FROM PUBLIC;
GRANT SELECT ON ledger.card_control,ledger.card_intent,ledger.card_hold,ledger.card_action,ledger.card_merchant TO ledger_runtime;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA ledger FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ledger.reserved(uuid),ledger.card_view(uuid),ledger.card_authorization(uuid),ledger.card_create(uuid,text,text,uuid,numeric,numeric),ledger.card_configure(uuid,text,text,bigint,text,numeric,numeric),ledger.card_prepare(uuid,uuid,text,text,uuid,numeric,text),ledger.card_reserve(uuid,uuid,timestamptz),ledger.card_settle(uuid,text,text,text) TO ledger_runtime;
-- Preserve immutable historic event amounts; new posting events carry available-at-post snapshots.
CREATE OR REPLACE VIEW fincore_event_source AS WITH facts AS (SELECT id AS event_id,created_at AS occurred_at,'ledger.journal.posted'::text AS event_type,account_id AS aggregate_id,account_version AS aggregate_version,
 payload || jsonb_build_object('accountId',account_id,'ledgerVersion',account_version,'bookedBalance',payload->>'booked','availableBalance',coalesce(payload->>'available',payload->>'booked')) AS payload FROM ledger.outbox_event)
SELECT event_id,occurred_at,jsonb_build_object('eventId',event_id,'eventType',event_type,'schemaVersion',1,'aggregateId',aggregate_id,'aggregateVersion',aggregate_version,'correlationId',event_id::text,'occurredAt',occurred_at,'payload',payload) AS envelope FROM facts;
