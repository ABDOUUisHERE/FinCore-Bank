CREATE TABLE ledger.instruction (
  id uuid PRIMARY KEY, issuer varchar(120) NOT NULL, actor varchar(200) NOT NULL,
  key varchar(120) NOT NULL CHECK(length(key)>0), kind varchar(30) NOT NULL,
  command jsonb NOT NULL, metadata jsonb NOT NULL, fingerprint varchar(64) NOT NULL,
  authorized_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  UNIQUE(issuer,actor,kind,key)
);
CREATE TRIGGER instruction_immutable BEFORE UPDATE OR DELETE ON ledger.instruction FOR EACH ROW EXECUTE FUNCTION ledger.immutable();
CREATE TRIGGER instruction_no_truncate BEFORE TRUNCATE ON ledger.instruction FOR EACH STATEMENT EXECUTE FUNCTION ledger.immutable();

CREATE FUNCTION ledger.authorize_instruction(p_id uuid,p_issuer text,p_actor text,p_key text,p_kind text,
  p_debit uuid,p_credit uuid,p_amount text,p_currency text,p_metadata jsonb,p_reversal uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ledger,pg_temp AS $$
DECLARE command jsonb; fp text; previous ledger.instruction; original ledger.journal; da ledger.account; ca ledger.account;
BEGIN
  IF p_issuer IS NULL OR length(p_issuer) NOT BETWEEN 1 AND 120 OR p_actor IS NULL OR length(p_actor) NOT BETWEEN 1 AND 200
    OR p_key IS NULL OR length(p_key) NOT BETWEEN 1 AND 120 OR p_id IS NULL OR jsonb_typeof(p_metadata) IS DISTINCT FROM 'object'
    OR p_kind IS NULL OR p_kind NOT IN ('TRANSFER','DEPOSIT','REVERSAL') THEN RAISE EXCEPTION 'INVALID_INSTRUCTION'; END IF;
  IF p_kind='REVERSAL' THEN
    SELECT j.* INTO original FROM ledger.journal j JOIN ledger.operation o ON o.id=j.operation_id WHERE j.id=p_reversal AND o.issuer=p_issuer AND j.reversal_of IS NULL;
    IF NOT FOUND THEN RAISE EXCEPTION 'INVALID_REVERSAL'; END IF;
    SELECT jsonb_build_object('kind','REVERSAL','businessReference',p_id,'currency',original.currency,'reversalOf',p_reversal,
      'entries',jsonb_agg(jsonb_build_object('accountId',account_id,'side',CASE side WHEN 'DEBIT' THEN 'CREDIT' ELSE 'DEBIT' END,'amount',amount::text) ORDER BY account_id)) INTO command
      FROM ledger.posting WHERE journal_id=p_reversal;
  ELSE
    IF p_currency IS NULL OR p_currency NOT IN ('MAD','EUR','USD') OR p_amount IS NULL
      OR p_amount !~ '^(0|[1-9][0-9]{0,17})(\.[0-9]{1,2})?$' OR p_amount::numeric<=0 OR p_debit IS NULL OR p_credit IS NULL OR p_debit=p_credit THEN RAISE EXCEPTION 'INVALID_INSTRUCTION'; END IF;
    SELECT * INTO da FROM ledger.account WHERE id=p_debit;
    SELECT * INTO ca FROM ledger.account WHERE id=p_credit;
    IF da.id IS NULL OR ca.id IS NULL OR da.currency<>p_currency OR ca.currency<>p_currency
      OR ca.type<>'CUSTOMER_LIABILITY' OR (p_kind='TRANSFER' AND (da.type<>'CUSTOMER_LIABILITY' OR da.owner_subject<>p_actor))
      OR (p_kind='DEPOSIT' AND da.type<>'SETTLEMENT_ASSET') THEN RAISE EXCEPTION 'INSTRUCTION_NOT_AUTHORIZED'; END IF;
    command:=jsonb_build_object('kind',p_kind,'businessReference',p_id,'currency',p_currency,
      'entries',(SELECT jsonb_agg(e ORDER BY e->>'accountId') FROM (VALUES
        (jsonb_build_object('accountId',p_debit,'side','DEBIT','amount',(p_amount::numeric(20,2))::text)),
        (jsonb_build_object('accountId',p_credit,'side','CREDIT','amount',(p_amount::numeric(20,2))::text))) v(e)));
  END IF;
  fp:=encode(sha256(convert_to(jsonb_build_array(p_issuer,p_actor,p_key,command,p_metadata)::text,'UTF8')),'hex');
  PERFORM pg_advisory_xact_lock(hashtextextended(jsonb_build_array('instruction',p_id)::text,0));
  SELECT * INTO previous FROM ledger.instruction WHERE id=p_id;
  IF FOUND THEN
    IF previous.fingerprint<>fp THEN RAISE EXCEPTION 'IDEMPOTENCY_CONFLICT'; END IF;
    RETURN to_jsonb(previous);
  END IF;
  INSERT INTO ledger.instruction VALUES(p_id,p_issuer,p_actor,p_key,p_kind,command,p_metadata,fp,clock_timestamp()) RETURNING * INTO previous;
  RETURN to_jsonb(previous);
END $$;

CREATE FUNCTION ledger.assessment_not_expired() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,ledger,pg_temp AS $$
DECLARE expires text:=current_setting('fincore.assessment_expires',true);
BEGIN
  IF expires IS NOT NULL AND expires<>'' AND expires::timestamptz<=clock_timestamp() THEN RAISE EXCEPTION 'ASSESSMENT_EXPIRED'; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER risk_valid_at_post BEFORE INSERT ON ledger.journal FOR EACH ROW EXECUTE FUNCTION ledger.assessment_not_expired();

CREATE FUNCTION ledger.execute_instruction(p_issuer text,p_id uuid,p_assessment_expires timestamptz) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ledger,pg_temp AS $$
DECLARE instruction ledger.instruction; result jsonb;
BEGIN
  SELECT * INTO instruction FROM ledger.instruction WHERE id=p_id AND issuer=p_issuer FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'INSTRUCTION_NOT_FOUND'; END IF;
  SELECT response INTO result FROM ledger.operation WHERE issuer=p_issuer AND business_reference=p_id::text;
  IF FOUND THEN RETURN result; END IF;
  IF p_assessment_expires IS NULL OR p_assessment_expires<=clock_timestamp() OR p_assessment_expires>clock_timestamp()+interval '2 minutes' THEN RAISE EXCEPTION 'ASSESSMENT_EXPIRED'; END IF;
  PERFORM set_config('fincore.assessment_expires',p_assessment_expires::text,true);
  result:=ledger.post(instruction.issuer,instruction.actor,instruction.id::text,instruction.command);
  RETURN result;
END $$;
GRANT SELECT ON ledger.instruction TO ledger_runtime;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA ledger FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ledger.authorize_instruction(uuid,text,text,text,text,uuid,uuid,text,text,jsonb,uuid),ledger.execute_instruction(text,uuid,timestamptz) TO ledger_runtime;
-- Zero-balance assets are infrastructure-owned; Payment receives no provisioning grant.
SELECT ledger.provision('payment-settlement:'||currency,currency,'SETTLEMENT_ASSET','fincore-payment-system') FROM (VALUES('MAD'),('EUR'),('USD')) c(currency);
