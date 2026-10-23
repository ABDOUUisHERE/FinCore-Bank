-- Transition facts are produced by the database trigger, not arbitrary runtime inserts.
ALTER FUNCTION payment_transition_fact() SECURITY DEFINER;
ALTER FUNCTION payment_transition_fact() SET search_path=pg_catalog,public,pg_temp;
REVOKE ALL ON FUNCTION payment_transition_fact(),payment_transition_guard() FROM PUBLIC;
REVOKE INSERT ON payment_status_history,payment_outbox FROM payment_runtime;
