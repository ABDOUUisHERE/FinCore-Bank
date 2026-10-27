package com.fincore.beneficiary;

import com.fasterxml.jackson.databind.JsonNode;
import com.fincore.support.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BeneficiaryStore {
    private final JdbcTemplate jdbc;
    private static final org.springframework.jdbc.core.RowMapper<JsonNode> ROW = (rs,n) -> Json.read(rs.getString(1));
    public BeneficiaryStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public JsonNode riskContext(String subject,UUID destination){
        var rows=jdbc.query("select jsonb_build_object('saved',true,'createdAt',created_at,'status',status)::text from beneficiary where owner_subject=? and destination_account_id=?",ROW,subject,destination);
        return rows.isEmpty()?Json.read("{\"saved\":false,\"createdAt\":null}"):rows.getFirst();
    }
    public static String label(String value) {
        if (value == null || value.isBlank() || value.strip().length() > 80 || value.chars().anyMatch(Character::isISOControl))
            throw new ApiFailure(400, "INVALID_BENEFICIARY_LABEL");
        return value.strip();
    }
    public JsonNode replay(String actor, String key, UUID destination, String label) {
        Identifiers.key(key);
        var rows = jdbc.query("select to_jsonb(b)::text from beneficiary b where owner_subject=? and key=?", ROW, actor, key);
        if (rows.isEmpty()) return null;
        var row = rows.getFirst();
        if (!row.path("destination_account_id").asText().equals(destination.toString()) || !row.path("initial_label").asText().equals(label))
            throw new ApiFailure(409, "IDEMPOTENCY_CONFLICT");
        return row;
    }
    @Transactional public JsonNode create(String actor, String key, UUID destination, String currency, String label) {
        // A concurrent identical row conflicts on several unique indexes. Handle all of them,
        // then distinguish replay from an already-saved destination using the owned key.
        jdbc.update("insert into beneficiary(id,owner_subject,key,destination_account_id,currency,label,initial_label) values(?,?,?,?,?,?,?) on conflict do nothing",
            Identifiers.stable("beneficiary", actor, key), actor, key, destination, currency, label, label);
        var result = replay(actor, key, destination, label);
        if (result == null) throw new ApiFailure(409, "BENEFICIARY_EXISTS");
        return result;
    }
    public JsonNode own(UUID id, String actor) {
        var rows = jdbc.query("select to_jsonb(b)::text from beneficiary b where id=? and owner_subject=?", ROW, id, actor);
        if (rows.isEmpty()) throw new ApiFailure(404, "BENEFICIARY_NOT_FOUND");
        return rows.getFirst();
    }
    public List<JsonNode> list(String actor, UUID after, String search) {
        if(search==null || search.length()>140 || search.chars().anyMatch(Character::isISOControl))throw new ApiFailure(400,"INVALID_SEARCH");
        return jdbc.query("select to_jsonb(b)::text from beneficiary b where owner_subject=? and (?::uuid is null or id>?::uuid) and (?='' or position(lower(?) in lower(label))>0 or destination_account_id::text=?) order by id limit 50",
            ROW, actor, after, after, search, search, search);
    }
    @Transactional public JsonNode update(UUID id, String actor, String label, String status, long version) {
        own(id, actor);
        if (!Set.of("ACTIVE", "DISABLED").contains(status) || version < 0) throw new ApiFailure(400, "INVALID_BENEFICIARY_UPDATE");
        if (jdbc.update("update beneficiary set label=?,status=?,version=version+1 where id=? and owner_subject=? and version=?", label, status, id, actor, version) != 1)
            throw new ApiFailure(409, "BENEFICIARY_VERSION_CONFLICT");
        return own(id, actor);
    }
    public List<JsonNode> history(UUID id, String actor) {
        own(id, actor);
        return jdbc.query("select jsonb_build_object('version',version,'label',label,'status',status,'occurredAt',occurred_at)::text from beneficiary_history where beneficiary_id=? order by version desc limit 100", ROW, id);
    }
    public static Map<String,Object> view(JsonNode row) {
        var result = new LinkedHashMap<String,Object>();
        for (var field : Map.of("beneficiaryId","id","destinationAccountId","destination_account_id","label","label","currency","currency","status","status","createdAt","created_at").entrySet())
            result.put(field.getKey(), row.path(field.getValue()).asText());
        result.put("version", row.path("version").asLong());
        return result;
    }
}
