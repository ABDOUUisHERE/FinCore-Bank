package com.fincore.fraud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fincore.support.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FraudStore {
 private final JdbcTemplate jdbc;
 private static final RowMapper<JsonNode> ROW=(rs,n)->Json.read(rs.getString(1));
 public FraudStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
 static UUID id(JsonNode row,String key){return UUID.fromString(row.path(key).asText());}
 static String hash(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
 private void lock(String actor){jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,"fraud:"+actor);}
 public JsonNode rules(){return jdbc.queryForObject("select jsonb_build_object('version',r.version,'rules',r.rules,'actor',r.actor,'reason',r.reason,'createdAt',r.created_at)::text from fraud_rule r join current_rule c on c.version=r.version",ROW);}
 @Transactional public JsonNode rules(long expected,FraudRules rules,String actor,String reason){
  reason(reason);if(expected<1)throw new ApiFailure(400,"INVALID_RULE_VERSION");var validated=Json.read(Json.text(rules.validated()));
  jdbc.queryForObject("select version from current_rule where singleton for update",Long.class);
  var previous=jdbc.query("select to_jsonb(r)::text from fraud_rule r where version=?",ROW,expected+1);
  if(!previous.isEmpty()){
   var p=previous.getFirst();if(p.path("rules").equals(validated) && p.path("actor").asText().equals(actor) && p.path("reason").asText().equals(reason))return ruleVersion(expected+1);
   throw new ApiFailure(409,"RULE_VERSION_CONFLICT");
  }
  if(jdbc.queryForObject("select version from current_rule",Long.class)!=expected)throw new ApiFailure(409,"RULE_VERSION_CONFLICT");
  jdbc.update("insert into fraud_rule(version,rules,actor,reason) values(?,?::jsonb,?,?)",expected+1,validated.toString(),actor,reason);
  jdbc.update("update current_rule set version=? where singleton",expected+1);return ruleVersion(expected+1);
 }
 private JsonNode ruleVersion(long version){return jdbc.queryForObject("select jsonb_build_object('version',version,'rules',rules,'actor',actor,'reason',reason,'createdAt',created_at)::text from fraud_rule where version=?",ROW,version);}
 private static void reason(String value){if(value==null || !value.matches("[A-Z_]{3,100}"))throw new ApiFailure(400,"INVALID_INVESTIGATION_REASON");}

 @Transactional public JsonNode assess(JsonNode context){
  UUID payment=id(context,"paymentId");String actor=context.path("actor").asText();lock(actor);
  var now=jdbc.queryForObject("select clock_timestamp()",java.time.OffsetDateTime.class).toInstant();
  var current=rules();long version=current.path("version").asLong();var config=FraudRules.read(current.path("rules"));
  jdbc.update("insert into risk_payment(payment_id,fingerprint,actor,kind,amount,currency,destination_id) values(?,?,?,?,?::numeric,?,?) on conflict do nothing",payment,context.path("fingerprint").asText(),actor,context.path("kind").asText(),context.path("amount").asText(),context.path("currency").asText(),optionalId(context,"destinationAccountId"));
  var saved=jdbc.queryForObject("select to_jsonb(p)::text from risk_payment p where payment_id=?",ROW,payment);
  if(!saved.path("fingerprint").asText().equals(context.path("fingerprint").asText()) || !saved.path("actor").asText().equals(actor)
    || !saved.path("kind").asText().equals(context.path("kind").asText()) || !saved.path("currency").asText().equals(context.path("currency").asText())
    || new BigDecimal(saved.path("amount").asText()).compareTo(new BigDecimal(context.path("amount").asText()))!=0
    || !Objects.equals(optionalId(saved,"destination_id"),optionalId(context,"destinationAccountId")))throw new ApiFailure(409,"RISK_IDENTITY_CONFLICT");
  var cases=jdbc.query("select to_jsonb(c)::text from fraud_case c where payment_id=? for update",ROW,payment);
  JsonNode existing=cases.isEmpty()?null:cases.getFirst();long caseVersion=existing==null?0:existing.path("version").asLong();
  // Workflow status/version can advance without changing the risk facts or the authorized command.
  var canonical=context.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)canonical).remove(List.of("paymentVersion","paymentStatus"));String contextHash=hash(canonical.toString());
  var cache=jdbc.query("select to_jsonb(a)::text from risk_assessment a where payment_id=? and rules_version=? and case_version=? and context_hash=? and expires_at>clock_timestamp() order by sequence desc limit 1",ROW,payment,version,caseVersion,contextHash);
  if(!cache.isEmpty())return assessmentView(cache.getFirst());
  if(context.path("kind").asText().equals("TRANSFER"))jdbc.update("insert into velocity_admission(payment_id,actor,destination_id,last_attempt,expires_at) values(?,?,?,?::timestamptz,?::timestamptz) on conflict(payment_id) do update set last_attempt=excluded.last_attempt,expires_at=excluded.expires_at",payment,actor,optionalId(context,"destinationAccountId"),now.toString(),now.plusSeconds(60).toString());
  int count=jdbc.queryForObject("select count(*) from velocity_admission where actor=? and expires_at>clock_timestamp()",Integer.class,actor);
  int prior=jdbc.queryForObject("select count(distinct payment_id) from risk_assessment where context->>'actor'=? and created_at>=clock_timestamp()-interval '5 minutes' and created_at<clock_timestamp()-interval '1 minute'",Integer.class,actor);
  int rapid=jdbc.queryForObject("select count(*) from velocity_admission where actor=? and destination_id=? and last_attempt>=clock_timestamp()-interval '10 seconds'",Integer.class,actor,optionalId(context,"destinationAccountId"));
  var country=context.path("countryCode").asText();
  boolean changed=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select from risk_assessment where context->>'actor'=? and context->>'kind'='TRANSFER' and context->>'countryCode'<>? and created_at>=clock_timestamp()-interval '10 minutes')",Boolean.class,actor,country));
  var beneficiary=context.path("beneficiary").path("createdAt");Instant created=beneficiary.isMissingNode() || beneficiary.isNull()?null:Instant.parse(beneficiary.asText());
  var result=FraudEngine.evaluate(new FraudEngine.Context(context.path("kind").asText(),new BigDecimal(context.path("amount").asText()),context.path("currency").asText(),country,created,context.path("recentFailures").asInt()),new FraudEngine.Velocity(count,prior,rapid,changed),config,now);
  String verdict=result.verdict();var codes=result.reasons().stream().map(FraudEngine.Reason::code).sorted().toList();
  var explanations=new ArrayList<>(result.reasons());int score=result.score();
  if(existing!=null && existing.path("status").asText().equals("REJECTED")){verdict="BLOCK";score=100;explanations.add(new FraudEngine.Reason("ANALYST_REJECTED",100,"recorded investigation decision","same payment cannot override rejection"));}
  else if(existing!=null && existing.path("status").asText().equals("APPROVED")){
   var approved=new HashSet<String>();existing.path("reason_codes").forEach(n->approved.add(n.asText()));
   if(!verdict.equals("BLOCK") && existing.path("rules_version").asLong()==version && approved.containsAll(codes)){verdict="ALLOW";explanations.add(new FraudEngine.Reason("ANALYST_APPROVED_REVIEW",0,"recorded investigation decision","same rules and no new review reasons"));}
   else {caseVersion++;jdbc.update("update fraud_case set status=?,version=?,rules_version=?,reason_codes=?::jsonb,updated_at=clock_timestamp() where id=?",caseStatus(verdict),caseVersion,version,Json.text(codes),id(existing,"id"));}
  }
  UUID assessment=UUID.randomUUID();UUID caseId=existing==null?Identifiers.stable("fraud-case",payment.toString()):id(existing,"id");
  if(existing==null && !verdict.equals("ALLOW")){
   caseVersion=1;
   jdbc.update("insert into fraud_case(id,payment_id,status,rules_version,reason_codes) values(?,?,?,?,?::jsonb)",caseId,payment,verdict.equals("BLOCK")?"BLOCKED":"OPEN",version,Json.text(codes));
   var event=UUID.randomUUID();jdbc.update("insert into fraud_outbox(id,aggregate_id,aggregate_version,event_type,payload) values(?,?,1,'fraud.alert.created',?::jsonb)",event,payment,Json.text(Map.of("paymentId",payment,"caseId",caseId,"actorSubject",actor,"verdict",verdict,"rulesVersion",version)));
  }else if(existing!=null && !existing.path("status").asText().equals("APPROVED") && !existing.path("status").asText().equals("REJECTED")
    && (existing.path("rules_version").asLong()!=version || !existing.path("reason_codes").equals(Json.read(Json.text(codes))) || !existing.path("status").asText().equals(caseStatus(verdict)))){
   caseVersion++;jdbc.update("update fraud_case set status=?,version=?,rules_version=?,reason_codes=?::jsonb,updated_at=clock_timestamp() where id=?",caseStatus(verdict),caseVersion,version,Json.text(codes),caseId);
  }
  jdbc.update("insert into risk_assessment(id,payment_id,rules_version,case_version,context_hash,context,verdict,score,reasons,expires_at) values(?,?,?,?,?,?::jsonb,?,?,?::jsonb,?::timestamptz)",assessment,payment,version,caseVersion,contextHash,canonical.toString(),verdict,score,Json.text(explanations),now.plusSeconds(30).toString());
  if(existing!=null || caseVersion>0)jdbc.update("update fraud_case set last_assessment=? where id=?",assessment,caseId);
  return assessmentView(jdbc.queryForObject("select to_jsonb(a)::text from risk_assessment a where id=?",ROW,assessment));
 }
 private static UUID optionalId(JsonNode node,String key){return node.path(key).isNull() || node.path(key).isMissingNode()?null:id(node,key);}
 private static String caseStatus(String verdict){return verdict.equals("ALLOW")?"CLEARED":verdict.equals("BLOCK")?"BLOCKED":"OPEN";}
 private static Map<String,Object> assessmentFields(JsonNode row){return Map.of("assessmentId",row.path("id").asText(),"paymentId",row.path("payment_id").asText(),"verdict",row.path("verdict").asText(),"score",row.path("score").asInt(),"rulesVersion",row.path("rules_version").asLong(),"expiresAt",row.path("expires_at").asText(),"reasons",row.path("reasons"));}
 private static JsonNode assessmentView(JsonNode row){return Json.read(Json.text(assessmentFields(row)));}

 public List<JsonNode> cases(Long before,String status){
  if(before!=null && before<1 || !Set.of("","OPEN","APPROVED","REJECTED","BLOCKED","CLEARED").contains(status))throw new ApiFailure(400,"INVALID_CASE_FILTER");
  return jdbc.query("select jsonb_build_object('caseId',c.id,'paymentId',c.payment_id,'version',c.version,'status',c.status,'createdAt',c.created_at,'sequence',a.sequence::text,'assessment',jsonb_build_object('verdict',a.verdict,'score',a.score,'rulesVersion',a.rules_version,'reasons',a.reasons),'context',a.context)::text from fraud_case c join risk_assessment a on a.id=c.last_assessment where (?='' or c.status=?) and (?::bigint is null or a.sequence<?) order by a.sequence desc limit 50",ROW,status,status,before,before);
 }
 public JsonNode caseDetail(UUID id){
  var rows=jdbc.query("select jsonb_build_object('caseId',c.id,'paymentId',c.payment_id,'version',c.version,'status',c.status,'createdAt',c.created_at,'rulesVersion',c.rules_version,'assessment',to_jsonb(a),'context',a.context)::text from fraud_case c join risk_assessment a on a.id=c.last_assessment where c.id=?",ROW,id);
  if(rows.isEmpty())throw new ApiFailure(404,"FRAUD_CASE_NOT_FOUND");
  var result=(com.fasterxml.jackson.databind.node.ObjectNode)rows.getFirst();
  result.set("decisions",Json.read(Json.text(jdbc.query("select jsonb_build_object('decisionId',id,'caseVersion',case_version,'actor',actor,'approve',approve,'reason',reason,'createdAt',created_at)::text from investigation_decision where case_id=? order by case_version",ROW,id))));
  return result;
 }
 public UUID caseForPayment(UUID payment){var rows=jdbc.query("select id from fraud_case where payment_id=?",(rs,n)->rs.getObject(1,UUID.class),payment);if(rows.isEmpty())throw new ApiFailure(404,"FRAUD_CASE_NOT_FOUND");return rows.getFirst();}
 public JsonNode decisionReplay(UUID caseId,String key,Long expected,boolean approve,String reason,String actor){
  Identifiers.key(key);var rows=jdbc.query("select to_jsonb(d)::text from investigation_decision d where case_id=? and request_key=?",ROW,caseId,key);
  if(rows.isEmpty())return null;var d=rows.getFirst();
  if(!d.path("actor").asText().equals(actor) || d.path("approve").asBoolean()!=approve || !d.path("reason").asText().equals(reason)
    || expected!=null && d.path("case_version").asLong()!=expected+1)throw new ApiFailure(409,"IDEMPOTENCY_CONFLICT");
  return d.path("response");
 }
 @Transactional public JsonNode decide(UUID caseId,long expected,boolean approve,String reason,String actor,String key){
  reason(reason);Identifiers.key(key);if(expected<1)throw new ApiFailure(400,"INVALID_CASE_VERSION");
  var subject=jdbc.query("select p.actor from fraud_case c join risk_payment p on p.payment_id=c.payment_id where c.id=?",(rs,n)->rs.getString(1),caseId);
  if(subject.isEmpty())throw new ApiFailure(404,"FRAUD_CASE_NOT_FOUND");lock(subject.getFirst());
  String fingerprint=hash(Json.text(List.of(expected,approve,reason,actor)));
  var replay=jdbc.query("select to_jsonb(d)::text from investigation_decision d where case_id=? and request_key=?",ROW,caseId,key);
  if(!replay.isEmpty()){
   if(!replay.getFirst().path("fingerprint").asText().equals(fingerprint))throw new ApiFailure(409,"IDEMPOTENCY_CONFLICT");return replay.getFirst().path("response");
  }
  var c=jdbc.queryForObject("select to_jsonb(c)::text from fraud_case c where id=? for update",ROW,caseId);
  if(c.path("version").asLong()!=expected || !Set.of("OPEN","BLOCKED").contains(c.path("status").asText()))throw new ApiFailure(409,"CASE_VERSION_CONFLICT");
  if(approve && (!c.path("status").asText().equals("OPEN") || c.path("rules_version").asLong()!=rules().path("version").asLong()))throw new ApiFailure(409,"CASE_REASSESSMENT_REQUIRED");
  UUID decision=UUID.randomUUID();var response=Json.read(Json.text(Map.of("decisionId",decision,"caseId",caseId,"version",expected+1,"status",approve?"APPROVED":"REJECTED")));
  jdbc.update("update fraud_case set status=?,version=version+1,updated_at=clock_timestamp() where id=?",approve?"APPROVED":"REJECTED",caseId);
  jdbc.update("insert into investigation_decision(id,case_id,request_key,fingerprint,case_version,actor,approve,reason,response) values(?,?,?,?,?,?,?,?,?::jsonb)",decision,caseId,key,fingerprint,expected+1,actor,approve,reason,response.toString());
  return response;
 }
}
