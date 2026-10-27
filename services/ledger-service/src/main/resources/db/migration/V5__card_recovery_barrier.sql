-- After the last attempted grant expires, serialize with every admission before declaring no hold.
CREATE FUNCTION ledger.card_recover(p_id uuid,p_last_grant_expires timestamptz) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ledger,pg_temp AS $$
DECLARE v_account_id uuid;BEGIN
 SELECT c.account_id INTO v_account_id FROM ledger.card_intent i JOIN ledger.card_control c ON c.id=i.card_id WHERE i.id=p_id;
 IF NOT FOUND THEN RAISE EXCEPTION 'CARD_NOT_FOUND';END IF;
 PERFORM 1 FROM ledger.account WHERE id=v_account_id FOR UPDATE;
 IF p_last_grant_expires IS NULL OR p_last_grant_expires>=clock_timestamp() THEN RAISE EXCEPTION 'ASSESSMENT_EXPIRED';END IF;
 RETURN ledger.card_authorization(p_id);
END $$;
REVOKE ALL ON FUNCTION ledger.card_recover(uuid,timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ledger.card_recover(uuid,timestamptz) TO ledger_runtime;
