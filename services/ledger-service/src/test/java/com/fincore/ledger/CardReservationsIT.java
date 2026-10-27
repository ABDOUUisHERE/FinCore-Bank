package com.fincore.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

class CardReservationsIT {
 static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:17.11-alpine").withDatabaseName("ledger");
 static JdbcTemplate db,owner;static LedgerStore ledger;static TransactionTemplate tx;
 static final String ACTOR="signed-customer";
 @BeforeAll static void setup(){
  PG.start();String password=UUID.randomUUID().toString();var admin=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()));
  for(String name:List.of("ledger_migrator","ledger_runtime"))admin.execute("create role "+name+" login password '"+password+"'");
  admin.execute("alter database ledger owner to ledger_migrator");admin.execute("grant connect on database ledger to ledger_runtime");
  Flyway.configure().dataSource(PG.getJdbcUrl(),"ledger_migrator",password).load().migrate();
  db=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),"ledger_runtime",password));owner=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),"ledger_migrator",password));
  ledger=new LedgerStore(db,new ObjectMapper());tx=new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource()));
 }
 @AfterAll static void stop(){PG.stop();}
 static String key(){return UUID.randomUUID().toString();}
 static JsonNode sql(String query,Object...args){return db.queryForObject(query,(rs,n)->com.fincore.support.Json.read(rs.getString(1)),args);}
 static UUID account(String amount){
  UUID source=UUID.fromString(ledger.provision(key(),"MAD","CUSTOMER_LIABILITY",ACTOR).path("id").asText());
  var settlement=UUID.fromString(ledger.provision(key(),"MAD","SETTLEMENT_ASSET",ACTOR).path("id").asText());
  tx.execute(s->ledger.post("lab",ACTOR,key(),command("DEPOSIT",key(),settlement,source,amount)));return source;
 }
 static Object command(String kind,String reference,UUID source,UUID dest,String amount){return Map.of("kind",kind,"businessReference",reference,"currency","MAD","entries",List.of(Map.of("accountId",source,"side","DEBIT","amount",amount),Map.of("accountId",dest,"side","CREDIT","amount",amount)));}
 static UUID card(UUID account,String per,String daily){var id=UUID.randomUUID();tx.execute(s->sql("select ledger.card_create(?,?,?,?,?::numeric,?::numeric)",id,ACTOR,key(),account,per,daily));return id;}
 static UUID prepare(UUID card,String amount){var id=UUID.randomUUID();var merchant=db.queryForObject("select m.id from ledger.card_merchant m join ledger.account a on a.id=m.id where currency='MAD'",UUID.class);tx.execute(s->sql("select ledger.card_prepare(?,?,?,?,?,?::numeric,'MAD')",id,card,ACTOR,key(),merchant,amount));return id;}
 static JsonNode reserve(UUID id){return tx.execute(s->sql("select ledger.card_reserve(?,?,?::timestamptz)",id,UUID.randomUUID(),Instant.now().plusSeconds(30).toString()));}
 static JsonNode settle(UUID id,String key,String action){return tx.execute(s->sql("select ledger.card_settle(?,?,?,?)",id,ACTOR,key,action));}
 @Test void reservesAvailableFundsWithoutPostingAndEveryDebitHonorsThem(){
  var account=account("100.00");var card=card(account,"100.00","100.00");var hold=prepare(card,"70.00");assertThat(reserve(hold).path("status").asText()).isEqualTo("HELD");
  assertThat(ledger.balance(account).path("booked").asText()).isEqualTo("100.00");assertThat(ledger.balance(account).path("available").asText()).isEqualTo("30.00");
  var dest=UUID.fromString(ledger.provision(key(),"MAD","CUSTOMER_LIABILITY",ACTOR).path("id").asText());
  var denied=tx.execute(s->ledger.post("lab",ACTOR,key(),command("TRANSFER",key(),account,dest,"40.00")));assertThat(denied.path("code").asText()).isEqualTo("INSUFFICIENT_FUNDS");
  assertThat(settle(hold,key(),"RELEASE").path("status").asText()).isEqualTo("RELEASED");assertThat(ledger.balance(account).path("available").asText()).isEqualTo("100.00");
 }
 @Test void concurrentReservationsCannotOverspend()throws Exception{
  var account=account("100.00");var card=card(account,"100.00","1000.00");var a=prepare(card,"70.00");var b=prepare(card,"70.00");
  try(var pool=Executors.newFixedThreadPool(2)){var x=pool.submit(()->reserve(a));var y=pool.submit(()->reserve(b));assertThat(List.of(x.get().path("status").asText(),y.get().path("status").asText())).containsExactlyInAnyOrder("HELD","DECLINED");}
  assertThat(ledger.balance(account).path("available").asText()).isEqualTo("30.00");
 }
 @Test void ordinaryTransferRacingHoldCannotSpendTheSameMoney()throws Exception{
  var account=account("100.00");var card=card(account,"100.00","1000.00");var id=prepare(card,"70.00");
  var dest=UUID.fromString(ledger.provision(key(),"MAD","CUSTOMER_LIABILITY",ACTOR).path("id").asText());var start=new CountDownLatch(1);
  try(var pool=Executors.newFixedThreadPool(2)){
   var a=pool.submit(()->{start.await();return reserve(id).path("status").asText();});
   var b=pool.submit(()->{start.await();return tx.execute(s->ledger.post("lab",ACTOR,key(),command("TRANSFER",key(),account,dest,"70.00"))).path("status").asText();});start.countDown();
   assertThat(List.of(a.get(),b.get())).isIn(List.of("HELD","REJECTED"),List.of("DECLINED","POSTED"));
  }
  assertThat(ledger.balance(account).path("available").asText()).isEqualTo("30.00");
 }
 @Test void canonicalCommandsReplayAndChangedBodiesOrStaleControlsCannotWin(){
  var account=account("100.00");var card=card(account,"100.00","1000.00");String key=key();
  var changed=tx.execute(s->sql("select ledger.card_configure(?,?,?,0,'FROZEN',50,100)",card,ACTOR,key));
  JsonNode replay=tx.execute(s->sql("select ledger.card_configure(?,?,?,0,'FROZEN',50,100)",card,ACTOR,key));assertThat(replay).isEqualTo(changed);
  assertThatThrownBy(()->tx.execute(s->sql("select ledger.card_configure(?,?,?,0,'ACTIVE',50,100)",card,ACTOR,key))).hasMessageContaining("IDEMPOTENCY_CONFLICT");
  assertThatThrownBy(()->tx.execute(s->sql("select ledger.card_configure(?,?,?,0,'ACTIVE',50,100)",card,ACTOR,key()))).hasMessageContaining("CARD_VERSION_CONFLICT");
  var id=prepare(card,"10.00");assertThatThrownBy(()->tx.execute(s->sql("select ledger.card_reserve(?,?,?::timestamptz)",id,UUID.randomUUID(),Instant.now().minusSeconds(1).toString()))).hasMessageContaining("ASSESSMENT_EXPIRED");
  assertThat(ledger.balance(account).path("available").asText()).isEqualTo("100.00");
 }
 @Test void liveHoldPreventsTerminationAndFreezePreservesEstablishedMerchantAuthorization(){
  var account=account("100.00");var card=card(account,"100.00","1000.00");var id=prepare(card,"60.00");reserve(id);
  assertThatThrownBy(()->tx.execute(s->sql("select ledger.card_configure(?,?,?,0,'TERMINATED',100,1000)",card,ACTOR,key()))).hasMessageContaining("CARD_HAS_RESERVATIONS");
  tx.execute(s->sql("select ledger.card_configure(?,?,?,0,'FROZEN',100,1000)",card,ACTOR,key()));
  assertThat(settle(id,key(),"CAPTURE").path("status").asText()).isEqualTo("CAPTURED");
  assertThat(reserve(prepare(card,"10.00")).path("code").asText()).isEqualTo("CARD_RESTRICTED");
 }
 @Test void expiryAtPostingRollsBackCaptureAndPreservesTheHoldFact(){
  var account=account("100.00");var card=card(account,"100.00","1000.00");var id=prepare(card,"60.00");reserve(id);
  // Inject a pause immediately before the existing journal deadline trigger.
  owner.execute("create function ledger.test_slow_journal() returns trigger language plpgsql as $$ begin perform pg_sleep(0.5);return NEW;end $$");
  owner.execute("create trigger aaa_test_delay before insert on ledger.journal for each row execute function ledger.test_slow_journal()");
  try{
   owner.update("update ledger.card_hold set expires_at=clock_timestamp()+interval '250 milliseconds' where id=?",id);
   assertThatThrownBy(()->settle(id,key(),"CAPTURE")).hasMessageContaining("ASSESSMENT_EXPIRED");
   assertThat(ledger.balance(account).path("booked").asText()).isEqualTo("100.00");
   assertThat(db.queryForObject("select status from ledger.card_hold where id=?",String.class,id)).isEqualTo("HELD");
  }finally{owner.execute("drop trigger aaa_test_delay on ledger.journal");owner.execute("drop function ledger.test_slow_journal()");}
 }
 @Test void captureAndFullRefundReplayOneBalancedJournalEachAndNeverResetDailySpend(){
  var account=account("100.00");var card=card(account,"80.00","80.00");var id=prepare(card,"60.00");reserve(id);
  var captured=settle(id,key(),"CAPTURE");assertThat(captured.path("status").asText()).isEqualTo("CAPTURED");assertThat(settle(id,key(),"CAPTURE").path("journalId")).isEqualTo(captured.path("journalId"));
  assertThat(ledger.balance(account).path("booked").asText()).isEqualTo("40.00");assertThat(ledger.balance(account).path("available").asText()).isEqualTo("40.00");
  var refunded=settle(id,key(),"REFUND");assertThat(refunded.path("status").asText()).isEqualTo("REFUNDED");assertThat(settle(id,key(),"REFUND").path("refundJournalId")).isEqualTo(refunded.path("refundJournalId"));
  assertThat(ledger.balance(account).path("booked").asText()).isEqualTo("100.00");assertThat(reserve(prepare(card,"30.00")).path("code").asText()).isEqualTo("CARD_LIMIT_EXCEEDED");
  assertThat(ledger.reconcile().path("balanceDiscrepancies")).isEmpty();assertThat(ledger.reconcile().path("invalidJournals")).isEmpty();
 }
 @Test void expiryReleaseAndCardFreezePreventNewSpendingWithoutFabricatedPostings(){
  var account=account("100.00");var card=card(account,"100.00","100.00");var id=prepare(card,"60.00");reserve(id);
  owner.update("update ledger.card_hold set expires_at=clock_timestamp()-interval '1 second' where id=?",id);
  assertThat(ledger.balance(account).path("available").asText()).isEqualTo("100.00");assertThatThrownBy(()->settle(id,key(),"CAPTURE")).hasMessageContaining("CARD_STATE_CONFLICT");
  tx.execute(s->sql("select ledger.card_configure(?,?,?,0,'FROZEN',100,100)",card,ACTOR,key()));
  assertThat(reserve(prepare(card,"10.00")).path("code").asText()).isEqualTo("CARD_RESTRICTED");
 }
 @Test void captureRacingReleaseConvergesOnExactlyOneState()throws Exception{
  var account=account("100.00");var card=card(account,"100.00","100.00");var id=prepare(card,"60.00");reserve(id);
  try(var pool=Executors.newFixedThreadPool(2)){
   var a=pool.submit(()->{try{return settle(id,key(),"CAPTURE").path("status").asText();}catch(org.springframework.dao.DataAccessException e){return "CONFLICT";}});
   var b=pool.submit(()->{try{return settle(id,key(),"RELEASE").path("status").asText();}catch(org.springframework.dao.DataAccessException e){return "CONFLICT";}});
   assertThat(List.of(a.get(),b.get())).contains("CONFLICT");
  }
  assertThat(ledger.balance(account).path("available").asText()).isIn("40.00","100.00");assertThat(ledger.reconcile().path("balanceDiscrepancies")).isEmpty();
 }
 @Test void callerCannotChangeCanonicalIdentityOrRewriteFactsAndRollbackRemovesEveryEffect(){
  var account=account("100.00");var card=card(account,"100.00","100.00");var id=prepare(card,"60.00");
  assertThatThrownBy(()->tx.execute(s->{sql("select ledger.card_reserve(?,?,?::timestamptz)",id,UUID.randomUUID(),Instant.now().plusSeconds(30).toString());throw new IllegalStateException("CRASH");})).hasMessage("CRASH");
  assertThat(ledger.balance(account).path("available").asText()).isEqualTo("100.00");
  for(String statement:List.of("update ledger.card_hold set amount=1","delete from ledger.card_intent","truncate ledger.card_action","insert into ledger.card_hold(id) values(gen_random_uuid())"))assertThatThrownBy(()->db.execute(statement)).isInstanceOf(org.springframework.dao.DataAccessException.class);
  assertThatThrownBy(()->tx.execute(s->sql("select ledger.card_configure(?,?,?,0,'ACTIVE',100,100)",card,"forged-actor",key()))).hasMessageContaining("CARD_NOT_FOUND");
 }
 @Test void statementsUseRealPostingsAndSeparateBookedHistoryFromAvailableFunds(){
  var account=account("100.00");var card=card(account,"100.00","1000.00");var id=prepare(card,"60.00");reserve(id);
  var statements=new StatementStore(db);var month=YearMonth.now(ZoneOffset.UTC).toString();
  var before=tx.execute(s->statements.statement(account,ACTOR,month));assertThat(before.path("opening").asText()).isEqualTo("0.00");assertThat(before.path("closing").asText()).isEqualTo("100.00");assertThat(before.path("currentAvailable").asText()).isEqualTo("40.00");assertThat(before.path("entries")).hasSize(1);
  settle(id,key(),"CAPTURE");settle(id,key(),"REFUND");
  var after=tx.execute(s->statements.statement(account,ACTOR,month));assertThat(after.path("entries")).hasSize(3);assertThat(after.path("closing").asText()).isEqualTo("100.00");assertThat(after.path("movement").asText()).isEqualTo("100.00");assertThat(after.path("reconciled").asBoolean()).isTrue();
  assertThatThrownBy(()->tx.execute(s->statements.statement(account,"other-owner",month))).isInstanceOf(com.fincore.support.ApiFailure.class).extracting("status").isEqualTo(404);
  var previous=tx.execute(s->statements.statement(account,ACTOR,YearMonth.now(ZoneOffset.UTC).minusMonths(1).toString()));assertThat(previous.path("entries")).isEmpty();assertThat(previous.path("closing").asText()).isEqualTo("0.00");assertThat(previous.path("currentBooked").asText()).isEqualTo("100.00");
 }
}
