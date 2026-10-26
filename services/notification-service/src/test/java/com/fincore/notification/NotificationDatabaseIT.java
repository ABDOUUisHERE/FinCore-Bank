package com.fincore.notification;
import com.fincore.messaging.EventEnvelope;
import com.fincore.support.*;
import java.time.Instant;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class NotificationDatabaseIT {
 static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:17.11-alpine").withDatabaseName("notification");
 static final String PASSWORD=UUID.randomUUID().toString();static JdbcTemplate jdbc;static NotificationStore store;static TransactionTemplate tx;
 @BeforeAll static void start(){
  PG.start();var admin=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()));
  for(var role:List.of("notification_runtime","notification_migrator"))admin.execute("create role "+role+" login password '"+PASSWORD+"'");
  admin.execute("alter database notification owner to notification_migrator");admin.execute("revoke all on database notification from public");admin.execute("grant connect on database notification to notification_runtime");
  Flyway.configure().dataSource(PG.getJdbcUrl(),"notification_migrator",PASSWORD).load().migrate();
  jdbc=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),"notification_runtime",PASSWORD));store=new NotificationStore(jdbc);tx=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
 }
 @AfterAll static void stop(){PG.stop();}
 static EventEnvelope event(UUID aggregate,long version,String type){return new EventEnvelope(UUID.randomUUID(),type,1,aggregate,version,UUID.randomUUID().toString(),Instant.now(),Json.read("{\"exact\":\"99999999999999999.99\"}"));}
 static String raw(EventEnvelope e){return Json.text(Map.of("eventId",e.eventId(),"eventType",e.eventType(),"schemaVersion",1,"aggregateId",e.aggregateId(),"aggregateVersion",e.aggregateVersion(),"correlationId",e.correlationId(),"occurredAt",e.occurredAt().toString(),"payload",e.payload()));}
 static List<NotificationStore.Notice> notice(String owner,EventEnvelope e){return List.of(new NotificationStore.Notice(owner,"PAYMENTS","Transfer completed","100.00 MAD",e.aggregateId()+":completed"));}
 static boolean accept(EventEnvelope e,String owner){return tx.execute(s->store.accept(e,raw(e),notice(owner,e)));}
 @Test void crashRollbackAndRedeliveryHaveOneAtomicEffect(){
  var owner=UUID.randomUUID().toString();var e=event(UUID.randomUUID(),3,"payment.completed");
  assertThatThrownBy(()->tx.execute(s->{store.accept(e,raw(e),notice(owner,e));throw new IllegalStateException("CRASH_BEFORE_COMMIT");})).hasMessage("CRASH_BEFORE_COMMIT");
  assertThat(store.consumed(e.eventId())).isFalse();assertThat(store.list(owner,null,false).get("items")).asList().isEmpty();
  assertThat(accept(e,owner)).isTrue();assertThat(accept(e,owner)).isFalse();
  assertThat(store.list(owner,null,false).get("items")).asList().hasSize(1);
  assertThatThrownBy(()->tx.execute(s->store.accept(e,raw(e)+" ",notice(owner,e)))).hasMessage("EVENT_ID_CONFLICT");
 }
 @Test void concurrentRedeliveryAndSemanticDuplicatesCannotDuplicateInboxOrEmail()throws Exception{
  var owner=UUID.randomUUID().toString();tx.execute(s->store.preferences(owner,true,true,true,true,0));
  var e=event(UUID.randomUUID(),3,"payment.completed");
  try(var workers=java.util.concurrent.Executors.newFixedThreadPool(4)){
   var jobs=new ArrayList<java.util.concurrent.Future<?>>();for(int i=0;i<8;i++)jobs.add(workers.submit(()->accept(e,owner)));for(var job:jobs)job.get();
  }
  accept(event(e.aggregateId(),5,"payment.completed"),owner);
  assertThat(store.list(owner,null,false).get("items")).asList().hasSize(1);
  var worker=new DeliveryWorker(jdbc,tx,mock(org.springframework.kafka.core.KafkaTemplate.class));worker.emailOne();worker.emailOne();
  assertThat(store.emails(owner)).hasSize(1);
 }
 @Test void staleSnapshotsAndStatusesNeverReplaceNewerProjections(){
  var aggregate=UUID.randomUUID();var latest=event(aggregate,7,"ledger.journal.posted");accept(latest,"unused");accept(event(aggregate,2,"ledger.journal.posted"),"unused");
  assertThat(jdbc.queryForObject("select version from ledger_snapshot where account_id=?",Long.class,aggregate)).isEqualTo(7);
  assertThat(jdbc.queryForObject("select payload->>'exact' from ledger_snapshot where account_id=?",String.class,aggregate)).isEqualTo("99999999999999999.99");
  var owner=UUID.randomUUID().toString();var payment=UUID.randomUUID();accept(event(payment,5,"payment.reversed"),owner);
  assertThat(accept(event(payment,3,"payment.completed"),owner)).isFalse();
 }
 @Test void preferencesOwnershipReadIdempotencyAndPermissionsAreEnforced(){
  var owner=UUID.randomUUID().toString();var e=event(UUID.randomUUID(),3,"payment.completed");accept(e,owner);
  var rows=jdbc.queryForList("select id from notification where subject=?",UUID.class,owner);var id=rows.getFirst();
  assertThatThrownBy(()->store.read("other",id)).isInstanceOf(ApiFailure.class).hasMessage("NOTIFICATION_NOT_FOUND");
  store.read(owner,id);store.read(owner,id);assertThat(store.list(owner,null,true).get("unreadCount")).isEqualTo(0L);
  tx.execute(s->store.preferences(owner,false,false,false,true,0));
  assertThatThrownBy(()->tx.execute(s->store.preferences(owner,true,true,true,true,0))).hasMessage("PREFERENCE_VERSION_CONFLICT");
  accept(event(UUID.randomUUID(),3,"payment.completed"),owner);assertThat(store.list(owner,null,false).get("items")).asList().hasSize(1);
  for(var sql:List.of("delete from consumed_event","truncate notification","update notification set body='forged'","delete from synthetic_email","create table forbidden(id int)"))assertThatThrownBy(()->jdbc.execute(sql)).isInstanceOf(DataAccessException.class);
 }
 @Test void deadLetterCaptureAndReplayAuditAreIdempotentAndVersioned(){
  String body=raw(event(UUID.randomUUID(),3,"payment.completed"));long offset=Math.abs(UUID.randomUUID().getMostSignificantBits());
  store.deadLetter("fincore.payment.completed.v1","key",body,0,offset);store.deadLetter("fincore.payment.completed.v1","key",body,0,offset);
  var id=jdbc.queryForObject("select id from dead_letter where source_offset=?",UUID.class,offset);
  var replay=tx.execute(s->store.replay(id,0,"operator","RECOVERED_DEPENDENCY"));
  assertThatThrownBy(()->tx.execute(s->store.replay(id,0,"operator","RECOVERED_DEPENDENCY"))).hasMessage("REPLAY_VERSION_CONFLICT");
  assertThat(jdbc.queryForObject("select count(*) from replay_audit where id=?",Long.class,replay)).isOne();
  assertThat(jdbc.queryForObject("select body->>'value' from replay_outbox where id=?",String.class,replay)).isEqualTo(body);
 }
}
