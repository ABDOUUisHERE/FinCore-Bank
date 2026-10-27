package com.fincore.ledger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LedgerStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public LedgerStore(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }
    private JsonNode decode(String value) {
        try { return json.readTree(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Invalid stored ledger response", exception); }
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalArgumentException("Invalid command", exception); }
    }
    private JsonNode query(String sql, Object... args) {
        var values = jdbc.query(sql, (rs, row) -> decode(rs.getString(1)), args);
        if (values.isEmpty()) throw new MissingRecord();
        return values.getFirst();
    }
    @Transactional public JsonNode provision(String reference, String currency, String type, String owner) {
        return query("select ledger.provision(?,?,?,?)", reference, currency, type, owner);
    }
    @Transactional public JsonNode guard(UUID id, String status, long revision) {
        return query("select ledger.set_guard(?,?,?)", id, status, revision);
    }
    @Transactional public JsonNode executeInstruction(String issuer,UUID id,java.time.Instant verifiedExpiry){
        jdbc.execute("set local lock_timeout='3s'");jdbc.execute("set local statement_timeout='4s'");
        return query("select ledger.execute_instruction(?,?,?::timestamptz)",issuer,id,verifiedExpiry.toString());
    }
    @Transactional public JsonNode post(String issuer, String actor, String key, Object command) {
        jdbc.execute("set local lock_timeout = '5s'");
        jdbc.execute("set local statement_timeout = '10s'");
        return query("select ledger.post(?,?,?,?::jsonb)", issuer, actor, key, encode(command));
    }
    @Transactional public JsonNode reverse(String issuer, String actor, String key, UUID journal, String reference) {
        // The stored function rechecks exact inversion and original issuer under lock.
        var original = query("select jsonb_build_object('currency',j.currency,'entries',jsonb_agg(jsonb_build_object('accountId',p.account_id,'side',case p.side when 'DEBIT' then 'CREDIT' else 'DEBIT' end,'amount',p.amount::text))) from ledger.journal j join ledger.operation o on o.id=j.operation_id join ledger.posting p on p.journal_id=j.id where j.id=? and o.issuer=? group by j.id", journal, issuer);
        return post(issuer, actor, key, Map.of("kind", "REVERSAL", "businessReference", reference, "currency", original.get("currency").asText(), "entries", original.get("entries"), "reversalOf", journal));
    }
    public JsonNode balance(UUID id) {
        return query("select jsonb_build_object('accountId',account_id,'currency',currency,'booked',booked::text,'reserved',ledger.reserved(account_id)::text,'available',(booked-ledger.reserved(account_id))::text,'version',version) from ledger.balance where account_id=?", id);
    }
    public JsonNode operation(String issuer, String actor, String reference) {
        return query("select response from ledger.operation where issuer=? and actor=? and business_reference=?", issuer, actor, reference);
    }
    public JsonNode journal(UUID id) {
        return query("select jsonb_build_object('journalId',j.id,'operationId',j.operation_id,'currency',j.currency,'total',j.total::text,'reversalOf',j.reversal_of,'createdAt',j.created_at,'entries',jsonb_agg(jsonb_build_object('accountId',p.account_id,'side',p.side,'amount',p.amount::text) order by p.account_id,p.side)) from ledger.journal j join ledger.posting p on p.journal_id=j.id where j.id=? group by j.id", id);
    }
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public JsonNode reconcile() {
        return query("select jsonb_build_object('balanceDiscrepancies',(select coalesce(jsonb_agg(to_jsonb(r)), '[]'::jsonb) from ledger.reconciliation r),'invalidJournals',(select coalesce(jsonb_agg(id),'[]'::jsonb) from (select j.id from ledger.journal j left join ledger.posting p on p.journal_id=j.id group by j.id having not j.sealed or count(p.id)<2 or coalesce(sum(p.amount) filter(where p.side='DEBIT'),0)<>j.total or coalesce(sum(p.amount) filter(where p.side='CREDIT'),0)<>j.total) invalid))");
    }
    public static class MissingRecord extends RuntimeException { private static final long serialVersionUID = 1L; }
}
