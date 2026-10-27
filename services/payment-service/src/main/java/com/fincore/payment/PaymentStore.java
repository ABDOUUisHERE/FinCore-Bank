package com.fincore.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fincore.support.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentStore {
    private final JdbcTemplate jdbc;
    private static final RowMapper<JsonNode> ROW=(rs,n)->Json.read(rs.getString(1));
    public PaymentStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public JsonNode get(UUID id){var values=jdbc.query("select to_jsonb(p)::text from payment p where id=?",ROW,id);if(values.isEmpty())throw new ApiFailure(404,"PAYMENT_NOT_FOUND");return values.getFirst();}
    public JsonNode own(UUID id,String actor){var payment=get(id);if(!payment.path("actor").asText().equals(actor))throw new ApiFailure(404,"PAYMENT_NOT_FOUND");return payment;}
    public boolean exists(UUID id){return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select from payment where id=?)",Boolean.class,id));}
    public List<JsonNode> list(String actor,UUID after){return list(actor,after,"");}
    public List<JsonNode> list(String actor,UUID after,String status){
        if(!status.isEmpty() && Arrays.stream(PaymentState.values()).noneMatch(s->s.name().equals(status)))throw new ApiFailure(400,"INVALID_PAYMENT_FILTER");
        return jdbc.query("select to_jsonb(p)::text from payment p where actor=? and kind='TRANSFER' and (?='' or status=?) and (?::uuid is null or (created_at,id)<(select c.created_at,c.id from payment c where c.id=? and c.actor=? and c.kind='TRANSFER')) order by created_at desc,id desc limit 50",ROW,actor,status,status,after,after,actor);
    }
    @Transactional public JsonNode importInstruction(JsonNode instruction){
        var id=uuid(instruction,"id");if(exists(id))return get(id);
        var metadata=instruction.path("metadata");var kind=instruction.path("kind").asText();var actor=instruction.path("actor").asText();
        UUID source=optionalUuid(metadata,"sourceAccountId"),destination=optionalUuid(metadata,"destinationAccountId"),original=optionalUuid(metadata,"originalPaymentId");
        String amount=metadata.path("amount").asText(),currency=metadata.path("currency").asText(),reference=metadata.path("reference").asText("");
        var initial=new LinkedHashMap<String,Object>();initial.put("paymentId",id);initial.put("status","CREATED");initial.put("version",0);initial.put("amount",amount);initial.put("currency",currency);
        int inserted=jdbc.update("insert into payment(id,actor,kind,key,source_account_id,destination_account_id,amount,currency,reference,instruction,initial_response,original_payment_id,transition_actor) values(?,?,?,?,?,?,?::numeric,?,?,?::jsonb,?::jsonb,?,?) on conflict(id) do nothing",
            id,actor,kind,instruction.path("key").asText(),source,destination,amount,currency,reference,instruction.toString(),Json.text(initial),original,actor);
        if(inserted==1 && original!=null){
            var previous=get(original);
            if(!previous.path("status").asText().equals("COMPLETED"))throw new ApiFailure(409,"REVERSAL_STATE_CONFLICT");
            if(jdbc.update("update payment set status='REVERSAL_PENDING',version=version+1,transition_actor=? where id=? and status='COMPLETED' and version=?",actor,original,previous.path("version").asLong())!=1)throw new ApiFailure(409,"REVERSAL_STATE_CONFLICT");
        }
        return get(id);
    }
    @Transactional public JsonNode claim(){
        var values=jdbc.query("with candidate as (select id from payment where status in ('CREATED','VALIDATING','PENDING_RISK_REVIEW','PROCESSING') and next_attempt<=clock_timestamp() and (lease_until is null or lease_until<clock_timestamp()) order by next_attempt,id for update skip locked limit 1) update payment p set lease_token=gen_random_uuid(),lease_until=clock_timestamp()+interval '30 seconds',attempts=attempts+1 from candidate c where p.id=c.id returning to_jsonb(p)::text",ROW);
        return values.isEmpty()?null:values.getFirst();
    }
    @Transactional public JsonNode move(JsonNode before,PaymentState target,String code,RiskPolicy.Assessment assessment){
        var current=PaymentState.valueOf(before.path("status").asText());if(!current.canMoveTo(target))throw new IllegalArgumentException("Invalid payment transition");
        int changed=jdbc.update("update payment set status=?,version=version+1,terminal_code=?,assessment_reason=?,assessment_expires=?::timestamptz,risk_assessment_id=?,risk_ruleset=?,transition_actor='fincore-payment' where id=? and status=? and version=? and lease_token=? and lease_until>clock_timestamp()",
            target.name(),code,assessment==null?null:assessment.reason(),assessment==null?null:assessment.expiresAt().toString(),assessment==null?null:assessment.reference(),assessment==null?null:assessment.ruleset(),uuid(before,"id"),current.name(),before.path("version").asLong(),uuid(before,"lease_token"));
        if(changed!=1)throw new ApiFailure(409,"STALE_PAYMENT_LEASE");return get(uuid(before,"id"));
    }
    @Transactional public void completed(JsonNode before,JsonNode outcome){
        boolean posted=outcome.path("status").asText().equals("POSTED");
        int changed=jdbc.update("update payment set status=?,version=version+1,terminal_code=?,journal_id=?,lease_token=null,lease_until=null,transition_actor='fincore-payment' where id=? and status='PROCESSING' and version=? and lease_token=? and lease_until>clock_timestamp()",
            posted?"COMPLETED":"FAILED",outcome.path("code").asText(),posted?uuid(outcome,"journalId"):null,uuid(before,"id"),before.path("version").asLong(),uuid(before,"lease_token"));
        if(changed!=1)throw new ApiFailure(409,"STALE_PAYMENT_LEASE");
        var original=optionalUuid(before,"original_payment_id");
        if(original!=null){int restored=jdbc.update("update payment set status=?,version=version+1,transition_actor=? where id=? and status='REVERSAL_PENDING'",posted?"REVERSED":"COMPLETED",before.path("actor").asText(),original);if(restored!=1)throw new ApiFailure(409,"REVERSAL_STATE_CONFLICT");}
    }
    @Transactional public void retry(JsonNode payment,String code){
        long delay=Math.min(60,1L<<Math.min(6,payment.path("attempts").asInt()));
        jdbc.update("update payment set lease_token=null,lease_until=null,next_attempt=clock_timestamp()+(? * interval '1 second'),terminal_code=? where id=? and lease_token=?",delay,code,uuid(payment,"id"),uuid(payment,"lease_token"));
    }
    @Transactional public void release(JsonNode payment){jdbc.update("update payment set lease_token=null,lease_until=null where id=? and lease_token=?",uuid(payment,"id"),uuid(payment,"lease_token"));}
    @Transactional public void deferReview(JsonNode payment){jdbc.update("update payment set lease_token=null,lease_until=null,next_attempt=clock_timestamp()+interval '10 seconds' where id=? and lease_token=?",uuid(payment,"id"),uuid(payment,"lease_token"));}
    @Transactional public void assessed(JsonNode payment,RiskPolicy.Assessment assessment){
        if(jdbc.update("update payment set assessment_expires=?::timestamptz,assessment_reason=?,risk_assessment_id=?,risk_ruleset=?,terminal_code=null where id=? and version=? and lease_token=? and lease_until>clock_timestamp()",assessment.expiresAt().toString(),assessment.reason(),assessment.reference(),assessment.ruleset(),uuid(payment,"id"),payment.path("version").asLong(),uuid(payment,"lease_token"))!=1)throw new ApiFailure(409,"STALE_PAYMENT_LEASE");
    }
    @Transactional public JsonNode cancel(UUID id,String actor,long version){
        var payment=own(id,actor);var state=PaymentState.valueOf(payment.path("status").asText());
        if(!state.cancellable() || payment.path("version").asLong()!=version)throw new ApiFailure(409,"PAYMENT_STATE_CONFLICT");
        if(jdbc.update("update payment set status='REJECTED',version=version+1,terminal_code='CANCELLED',lease_token=null,lease_until=null,transition_actor=? where id=? and version=? and status in ('CREATED','VALIDATING','PENDING_RISK_REVIEW')",actor,id,version)!=1)throw new ApiFailure(409,"PAYMENT_STATE_CONFLICT");return get(id);
    }
    @Transactional public JsonNode review(UUID id,String actor,long version,boolean approve,String reason){
        if(reason==null || !reason.matches("[A-Z_]{3,80}"))throw new ApiFailure(400,"INVALID_REVIEW_REASON");
        if(jdbc.update("update payment set status=?,version=version+1,approved=?,terminal_code=?,lease_token=null,lease_until=null,next_attempt=clock_timestamp(),transition_actor=? where id=? and version=? and status='PENDING_RISK_REVIEW'",approve?"VALIDATING":"REJECTED",approve,reason,actor,id,version)!=1)throw new ApiFailure(409,"PAYMENT_STATE_CONFLICT");return get(id);
    }
    public List<JsonNode> history(UUID id){return jdbc.query("select jsonb_build_object('version',version,'from',old_status,'to',new_status,'reason',reason,'occurredAt',occurred_at)::text from payment_status_history where payment_id=? order by version",ROW,id);}
    public Map<String,Object> riskContext(UUID id){
        var p=get(id);var result=new LinkedHashMap<String,Object>();result.put("paymentId",id);result.put("actor",p.path("actor").asText());result.put("kind",p.path("kind").asText());
        result.put("amount",new BigDecimal(p.path("amount").asText()).setScale(2).toPlainString());result.put("currency",p.path("currency").asText());result.put("destinationAccountId",p.path("destination_account_id").asText(null));
        result.put("fingerprint",p.path("instruction").path("fingerprint").asText());result.put("paymentStatus",p.path("status").asText());result.put("paymentVersion",p.path("version").asLong());
        result.put("journalId",p.path("journal_id").asText(null));result.put("riskAssessmentId",p.path("risk_assessment_id").asText(null));result.put("riskRuleset",p.path("risk_ruleset").asText(null));
        result.put("recentFailures",jdbc.queryForObject("select count(*) from payment p where actor=? and kind='TRANSFER' and status='FAILED' and exists(select from payment_status_history h where h.payment_id=p.id and h.new_status='FAILED' and h.occurred_at>=clock_timestamp()-interval '10 minutes')",Integer.class,p.path("actor").asText()));return result;
    }
    public static UUID uuid(JsonNode node,String key){return UUID.fromString(node.path(key).asText());}
    public static UUID optionalUuid(JsonNode node,String key){return node.path(key).isMissingNode() || node.path(key).isNull()?null:uuid(node,key);}
    public static Map<String,Object> view(JsonNode payment){
        var result=new LinkedHashMap<String,Object>();result.put("paymentId",payment.path("id").asText());result.put("kind",payment.path("kind").asText());result.put("status",payment.path("status").asText());result.put("version",payment.path("version").asLong());
        result.put("amount",new BigDecimal(payment.path("amount").asText()).setScale(2).toPlainString());result.put("currency",payment.path("currency").asText());result.put("reference",payment.path("reference").asText());
        result.put("sourceAccountId",payment.path("source_account_id").asText(null));result.put("destinationAccountId",payment.path("destination_account_id").asText(null));result.put("createdAt",payment.path("created_at").asText());
        result.put("journalId",payment.path("journal_id").isNull()?null:payment.path("journal_id").asText());result.put("code",payment.path("terminal_code").isNull()?null:payment.path("terminal_code").asText());
        result.put("riskAssessmentId",payment.path("risk_assessment_id").asText(null));result.put("riskRuleset",payment.path("risk_ruleset").asText(null));return result;
    }
}
