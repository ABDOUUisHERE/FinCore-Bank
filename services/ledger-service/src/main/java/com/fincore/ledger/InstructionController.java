package com.fincore.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1/ledger")
public class InstructionController {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public InstructionController(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    public record Instruction(UUID id,String key,String kind,UUID debitAccountId,UUID creditAccountId,String amount,String currency,JsonNode metadata,UUID reversalOf) {}
    public record Execute(Instant assessmentExpiresAt) {}
    @PostMapping("/instructions") @Transactional public JsonNode authorize(@RequestBody Instruction body,@AuthenticationPrincipal Jwt jwt){
        payment(jwt);
        var required=switch(body.kind()==null?"":body.kind()){case "TRANSFER"->"ledger.prepare";case "DEPOSIT"->"ledger.prepare.fund";case "REVERSAL"->"ledger.prepare.reverse";default->throw new IllegalArgumentException("Unsupported instruction");};
        var roles=com.fincore.ledger.configuration.SecurityConfiguration.roles(jwt);
        if(roles.stream().noneMatch(r->r.getAuthority().equals(required)))throw new AccessDeniedException("Wrong instruction scope");
        if(body.kind().equals("TRANSFER")){
            var realm=jwt.getClaimAsMap("realm_access");
            if(realm==null || !(realm.get("roles") instanceof List<?> values) || !values.contains("CUSTOMER"))throw new AccessDeniedException("Customer subject required");
        }
        if(body.metadata()==null)throw new IllegalArgumentException("Metadata required");
        return one("select ledger.authorize_instruction(?,?,?,?,?,?,?,?,?,?::jsonb,?)",body.id(),jwt.getClaimAsString("azp"),jwt.getSubject(),body.key(),body.kind(),body.debitAccountId(),body.creditAccountId(),body.amount(),body.currency(),body.metadata().toString(),body.reversalOf());
    }
    @PostMapping("/instructions/{id}/execute") @Transactional public JsonNode execute(@PathVariable UUID id,@RequestBody Execute body,@AuthenticationPrincipal Jwt jwt){
        payment(jwt);jdbc.execute("set local lock_timeout='3s'");jdbc.execute("set local statement_timeout='4s'");
        return one("select ledger.execute_instruction(?,?,?::timestamptz)",jwt.getClaimAsString("azp"),id,body.assessmentExpiresAt()==null?null:body.assessmentExpiresAt().toString());
    }
    @GetMapping("/instructions/{id}") public JsonNode instruction(@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt){payment(jwt);return one("select to_jsonb(i)||jsonb_build_object('outcome',o.response) from ledger.instruction i left join ledger.operation o on o.issuer=i.issuer and o.business_reference=i.id::text where i.id=? and i.issuer=?",id,jwt.getClaimAsString("azp"));}
    @GetMapping("/instructions") public Map<String,Object> instructions(@RequestParam(required=false) UUID after,@AuthenticationPrincipal Jwt jwt){
        payment(jwt);
        var rows=jdbc.query("select to_jsonb(i)::text from ledger.instruction i where issuer=? and (?::uuid is null or id>?::uuid) order by id limit 100",(rs,n)->decode(rs.getString(1)),jwt.getClaimAsString("azp"),after,after);
        var result=new LinkedHashMap<String,Object>();result.put("items",rows);result.put("nextCursor",rows.size()==100?rows.getLast().path("id").asText():null);return result;
    }
    @GetMapping("/accounts/{id}") public JsonNode account(@PathVariable UUID id){return one("select to_jsonb(a) from ledger.account a where id=?",id);}
    @GetMapping("/settlement/{currency}") public JsonNode settlement(@PathVariable String currency,@AuthenticationPrincipal Jwt jwt){payment(jwt);return one("select to_jsonb(a) from ledger.account a where external_reference=? and type='SETTLEMENT_ASSET'","payment-settlement:"+currency);}
    private void payment(Jwt jwt){if(!"fincore-payment".equals(jwt.getClaimAsString("azp")))throw new AccessDeniedException("Payment issuer required");}
    private JsonNode one(String sql,Object...args){var rows=jdbc.query(sql,(rs,n)->decode(rs.getString(1)),args);if(rows.isEmpty())throw new LedgerStore.MissingRecord();return rows.getFirst();}
    private JsonNode decode(String value){try{return json.readTree(value);}catch(java.io.IOException error){throw new IllegalStateException("Invalid instruction data",error);}}
}
