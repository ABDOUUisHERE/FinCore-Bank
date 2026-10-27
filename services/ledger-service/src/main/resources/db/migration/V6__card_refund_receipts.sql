-- Keep definitive failed refund outcomes visible on subsequent reads and reloads.
CREATE OR REPLACE FUNCTION ledger.reserved(p_account uuid) RETURNS numeric LANGUAGE sql STABLE SET search_path=pg_catalog,ledger,pg_temp AS $$
 SELECT coalesce(sum(amount),0)::numeric(20,2) FROM ledger.card_hold WHERE account_id=p_account AND status='HELD' AND expires_at>statement_timestamp()
$$;
CREATE OR REPLACE FUNCTION ledger.card_authorization(p_id uuid) RETURNS jsonb LANGUAGE sql STABLE SET search_path=pg_catalog,ledger,pg_temp AS $$
 SELECT jsonb_build_object('authorizationId',i.id,'paymentId',i.id,'cardId',i.card_id,'actor',i.actor,'kind','MERCHANT_PAYMENT','fingerprint',i.fingerprint,'amount',i.amount::text,'currency',i.currency,'destinationAccountId',i.merchant_id,'merchantId',i.merchant_id,'createdAt',i.created_at,'status',CASE WHEN h.id IS NULL THEN 'AUTHORIZING' WHEN h.status='HELD' AND h.expires_at<=statement_timestamp() THEN 'EXPIRED' ELSE h.status END,'code',h.reason,'assessmentId',h.assessment_id,'expiresAt',h.expires_at,'journalId',h.journal_id,'refundJournalId',h.refund_journal_id,'refundOutcome',(SELECT o.response FROM ledger.operation o WHERE o.issuer='fincore-card' AND o.kind='REVERSAL' AND o.business_reference='refund:'||i.id::text)) FROM ledger.card_intent i LEFT JOIN ledger.card_hold h ON h.id=i.id WHERE i.id=p_id
$$;
