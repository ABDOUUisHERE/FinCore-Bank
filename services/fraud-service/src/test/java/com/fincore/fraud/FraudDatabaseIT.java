package com.fincore.fraud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fincore.support.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

class FraudDatabaseIT {
 static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:17.11-alpine").withDatabaseName("fraud");
 static JdbcTemplate owner,jdbc;static FraudStore store;static TransactionTemplate tx;static String initialRules;
 @BeforeAll static void start(){
  PG.start();String password=UUID.randomUUID().toString();var admin=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()));
  for(var role:List.of("fraud_runtime","fraud_migrator"))admin.execute("create role "+role+" login password '"+password+"'");
  admin.execute("alter database fraud owner to fraud_migrator");admin.execute("revoke all on database fraud from public");admin.execute("grant connect on database fraud to fraud_runtime");
  Flyway.configure().dataSource(PG.getJdbcUrl(),"fraud_migrator",password).load().migrate();
  owner=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),"fraud_migrator",password));
  jdbc=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),"fraud_runtime",password));store=new FraudStore(jdbc);tx=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
  initialRules=owner.queryForObject("select rules::text from fraud_rule where version=1",String.class);
 }
 @AfterAll static void stop(){PG.stop();}
 @BeforeEach void reset(){
  owner.execute("truncate current_rule,fraud_rule,risk_payment,velocity_admission,risk_assessment,fraud_case,investigation_decision,fraud_outbox,event_delivery restart identity cascade");
  owner.update("insert into fraud_rule(version,rules,actor,reason) values(1,?::jsonb,'migration','INITIAL_SIMULATION_POLICY')",initialRules);owner.update("insert into current_rule values(true,1)");
 }
 static ObjectNode context(UUID payment,String actor,String amount,UUID destination){
  return (ObjectNode)Json.read(Json.text(Map.of("paymentId",payment,"fingerprint",FraudStore.hash(payment.toString()),"actor",actor,"kind","TRANSFER","amount",amount,"currency","MAD","destinationAccountId",destination,"countryCode","MA","recentFailures",0,"beneficiary",Map.of("saved",true,"createdAt",Instant.now().minus(Duration.ofDays(2)).toString()))));
 }
 static JsonNode assess(JsonNode context){return tx.execute(s->store.assess(context));}
 static void policy(java.util.function.Consumer<ObjectNode> edit){var rule=(ObjectNode)Json.read(initialRules);edit.accept(rule);tx.execute(s->store.rules(1,FraudRules.read(rule),"analyst","VERIFY_RULE_CHANGE"));}
 static UUID caseId(UUID payment){return store.caseForPayment(payment);}
 @Test void rollbackCannotLeaveAdmissionAssessmentAlertOrCaseFacts(){
  var input=context(UUID.randomUUID(),"actor","20000.00",UUID.randomUUID());
  assertThatThrownBy(()->tx.execute(s->{store.assess(input);throw new IllegalStateException("CRASH");})).hasMessage("CRASH");
  for(var table:List.of("risk_payment","velocity_admission","risk_assessment","fraud_case","fraud_outbox"))assertThat(jdbc.queryForObject("select count(*) from "+table,Long.class)).isZero();
 }
 @Test void concurrentIdenticalRequestsReserveOneSlotAndHaveOneAssessment()throws Exception{
  var input=context(UUID.randomUUID(),"same-actor","100.00",UUID.randomUUID());
  try(var workers=Executors.newFixedThreadPool(8)){
   var jobs=new ArrayList<Future<JsonNode>>();for(int n=0;n<8;n++)jobs.add(workers.submit(()->assess(input)));
   var ids=new HashSet<String>();for(var job:jobs)ids.add(job.get().path("assessmentId").asText());assertThat(ids).hasSize(1);
  }
  assertThat(jdbc.queryForObject("select count(*) from velocity_admission",Long.class)).isOne();
  assertThat(jdbc.queryForObject("select count(*) from risk_assessment",Long.class)).isOne();
  var changed=input.deepCopy().put("amount","101.00");assertThatThrownBy(()->assess(changed)).hasMessage("RISK_IDENTITY_CONFLICT");
 }
 @Test void simultaneousDistinctPaymentsCannotEvadeFrequencyAdmission()throws Exception{
  policy(r->{r.put("frequencyReview",2);r.put("frequencyBlock",3);});var destination=UUID.randomUUID();
  try(var workers=Executors.newFixedThreadPool(8)){
   var jobs=new ArrayList<Future<JsonNode>>();for(int n=0;n<8;n++){var c=context(UUID.randomUUID(),"parallel-actor","100.00",destination);jobs.add(workers.submit(()->assess(c)));}
   var verdicts=new ArrayList<String>();for(var job:jobs)verdicts.add(job.get().path("verdict").asText());
   assertThat(verdicts.stream().filter("ALLOW"::equals).count()).isOne();assertThat(verdicts.stream().filter("REVIEW"::equals).count()).isOne();assertThat(verdicts.stream().filter("BLOCK"::equals).count()).isEqualTo(6);
  }
 }
 @Test void expiredAssessmentMustRenewItsSlotBeforeAnotherAllow(){
  policy(r->{r.put("frequencyReview",2);r.put("frequencyBlock",3);});var dest=UUID.randomUUID();var first=context(UUID.randomUUID(),"expiry-actor","100.00",dest);
  assertThat(assess(first).path("verdict").asText()).isEqualTo("ALLOW");
  owner.update("update velocity_admission set expires_at=clock_timestamp()-interval '1 second',last_attempt=clock_timestamp()-interval '2 minutes'");
  owner.update("update risk_assessment set expires_at=clock_timestamp()-interval '1 second'");
  assess(context(UUID.randomUUID(),"expiry-actor","100.00",dest));assess(context(UUID.randomUUID(),"expiry-actor","100.00",dest));
  assertThat(assess(first).path("verdict").asText()).isEqualTo("BLOCK");
  assertThat(jdbc.queryForObject("select count(*) from velocity_admission",Long.class)).isEqualTo(3);
 }
 @Test void approvedReviewRechecksNewSignalsAndNeverOverridesHardRules(){
  var payment=UUID.randomUUID();var input=context(payment,"review-actor","20000.00",UUID.randomUUID());
  assertThat(assess(input).path("verdict").asText()).isEqualTo("REVIEW");var id=caseId(payment);
  var approved=tx.execute(s->store.decide(id,1,true,"REVIEW_CONFIRMED","analyst","decision-1"));
  JsonNode repeated=tx.execute(s->store.decide(id,1,true,"REVIEW_CONFIRMED","analyst","decision-1"));assertThat(repeated).isEqualTo(approved);
  assertThat(assess(input).path("verdict").asText()).isEqualTo("ALLOW");
  var changed=input.deepCopy().put("recentFailures",5);
  assertThat(assess(changed).path("verdict").asText()).isEqualTo("REVIEW");
  assertThat(store.caseDetail(id).path("status").asText()).isEqualTo("OPEN");
  var country=changed.deepCopy().put("countryCode","ZZ");assertThat(assess(country).path("verdict").asText()).isEqualTo("BLOCK");
  assertThatThrownBy(()->tx.execute(s->store.decide(id,store.caseDetail(id).path("version").asLong(),true,"OVERRIDE_ATTEMPT","analyst","decision-2"))).hasMessage("CASE_REASSESSMENT_REQUIRED");
  assertThat(jdbc.queryForObject("select count(*) from investigation_decision",Long.class)).isOne();
 }
 @Test void ruleVersionChangeInvalidatesCachedApprovalAndRelaxationIsExplicitlyCleared(){
  var payment=UUID.randomUUID();var input=context(payment,"rules-actor","20000.00",UUID.randomUUID());assess(input);var id=caseId(payment);
  tx.execute(s->store.decide(id,1,true,"REVIEW_CONFIRMED","analyst","initial-decision"));assertThat(assess(input).path("verdict").asText()).isEqualTo("ALLOW");
  policy(r->{((ObjectNode)r.path("blockAmounts")).put("MAD","15000.00");});
  assertThat(assess(input).path("verdict").asText()).isEqualTo("BLOCK");
  var relaxed=(ObjectNode)Json.read(initialRules);((ObjectNode)relaxed.path("reviewAmounts")).put("MAD","50000.00");
  tx.execute(s->store.rules(2,FraudRules.read(relaxed),"analyst","POLICY_RELAXATION"));
  assertThat(assess(input).path("verdict").asText()).isEqualTo("ALLOW");assertThat(store.caseDetail(id).path("status").asText()).isEqualTo("CLEARED");
 }
 @Test void concurrentInvestigationDecisionsPreserveOneAppendOnlyOutcome()throws Exception{
  var payment=UUID.randomUUID();assess(context(payment,"decision-actor","20000.00",UUID.randomUUID()));var id=caseId(payment);
  try(var workers=Executors.newFixedThreadPool(2)){
   var a=workers.submit(()->{try{return tx.execute(s->store.decide(id,1,true,"APPROVE_REVIEW","analyst-a","key-a"));}catch(ApiFailure f){return f.code;}});
   var b=workers.submit(()->{try{return tx.execute(s->store.decide(id,1,false,"REJECT_REVIEW","analyst-b","key-b"));}catch(ApiFailure f){return f.code;}});
   assertThat(List.of(a.get(),b.get()).stream().filter("CASE_VERSION_CONFLICT"::equals).count()).isOne();
  }
  assertThat(jdbc.queryForObject("select count(*) from investigation_decision",Long.class)).isOne();
  for(var sql:List.of("delete from risk_assessment","update risk_payment set actor='forged'","update investigation_decision set reason='FORGED'","truncate fraud_outbox","update fraud_rule set reason='FORGED'","create table forbidden(id int)"))assertThatThrownBy(()->jdbc.execute(sql)).isInstanceOf(org.springframework.dao.DataAccessException.class);
 }
 @Test void deniedInvestigationHasAnExplicitReasonEvenIfBaseRulesWouldAllow(){
  var payment=UUID.randomUUID();var c=context(payment,"denied-actor","20000.00",UUID.randomUUID());assess(c);
  tx.execute(s->store.decide(caseId(payment),1,false,"REJECT_REVIEW","analyst","deny-key"));
  var result=assess(c);assertThat(result.path("verdict").asText()).isEqualTo("BLOCK");
  assertThat(result.path("reasons")).anySatisfy(r->assertThat(r.path("code").asText()).isEqualTo("ANALYST_REJECTED"));
 }
}
