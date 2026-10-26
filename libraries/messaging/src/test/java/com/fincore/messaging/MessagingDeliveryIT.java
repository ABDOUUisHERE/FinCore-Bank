package com.fincore.messaging;
import com.fincore.support.Json;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import static org.assertj.core.api.Assertions.*;
class MessagingDeliveryIT {
 static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:17.11-alpine");
 static final KafkaContainer KAFKA=new KafkaContainer("apache/kafka:4.2.2");
 static JdbcTemplate jdbc; static TransactionTemplate tx;
 @BeforeAll static void start(){
  PG.start();KAFKA.start();jdbc=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()));tx=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
  jdbc.execute("create table facts(event_id uuid primary key,occurred_at timestamptz default clock_timestamp(),envelope jsonb not null)");
  jdbc.execute("create view fincore_event_source as select * from facts");
  jdbc.execute("create table event_delivery(event_id uuid primary key,sent_at timestamptz,lease_token uuid,lease_until timestamptz,attempts int default 0,next_attempt timestamptz default clock_timestamp(),last_code varchar(40))");
 }
 @AfterAll static void stop(){KAFKA.stop();PG.stop();}
 @BeforeEach void clean(){jdbc.execute("truncate event_delivery,facts");}
 static OutboxPublisher publisher(Runnable afterAck){
  var env=new MockEnvironment().withProperty("KAFKA_BOOTSTRAP_SERVERS",KAFKA.getBootstrapServers()).withProperty("KAFKA_SECURITY_PROTOCOL","PLAINTEXT");
  return new OutboxPublisher(jdbc,tx,new KafkaProducer<>(KafkaSettings.producer(env)),new SimpleMeterRegistry(),afterAck);
 }
 static UUID fact(){var id=UUID.randomUUID();var body=Json.text(Map.of("eventId",id,"eventType","payment.completed","schemaVersion",1,"aggregateId",UUID.randomUUID(),"aggregateVersion",3,"correlationId",id.toString(),"occurredAt",java.time.Instant.now().toString(),"payload",Map.of("journalId",UUID.randomUUID())));jdbc.update("insert into facts(event_id,envelope) values(?,?::jsonb)",id,body);return id;}
 @Test void rolledBackFactsAreNeverPublishedAndMultipleWorkersLeaseDifferentRows()throws Exception{
  assertThatThrownBy(()->tx.execute(s->{fact();throw new IllegalStateException("CRASH_BEFORE_COMMIT");})).hasMessage("CRASH_BEFORE_COMMIT");
  try(var a=publisher(()->{});var b=publisher(()->{})){
   assertThat(a.publishOne()).isFalse();fact();fact();
   var pool=Executors.newFixedThreadPool(2);try{
    var x=pool.submit(a::claim);var y=pool.submit(b::claim);
    assertThat(x.get().id()).isNotEqualTo(y.get().id());
   }finally{pool.shutdownNow();}
  }
 }
 @Test void acknowledgementThenCrashRedeliversSameEventIdentity()throws Exception{
  var id=fact();
  try(var p=publisher(()->{throw new AssertionError("PROCESS_LOST_AFTER_ACK");})){
   assertThatThrownBy(p::publishOne).isInstanceOf(AssertionError.class);
  }
  assertThat(jdbc.queryForObject("select count(*) from event_delivery where sent_at is not null",Long.class)).isZero();
  jdbc.update("update event_delivery set lease_until=clock_timestamp()-interval '1 second'");
  try(var p=publisher(()->{})){assertThat(p.publishOne()).isTrue();}
  assertThat(jdbc.queryForObject("select attempts from event_delivery where event_id=?",Integer.class,id)).isEqualTo(2);
  assertThat(jdbc.queryForObject("select count(*) from facts",Long.class)).isOne();
  var props=new HashMap<String,Object>();props.put("bootstrap.servers",KAFKA.getBootstrapServers());props.put("group.id",UUID.randomUUID().toString());props.put("auto.offset.reset","earliest");props.put("key.deserializer",StringDeserializer.class);props.put("value.deserializer",StringDeserializer.class);
  try(var consumer=new KafkaConsumer<String,String>(props)){
   consumer.subscribe(List.of("fincore.payment.completed.v1"));int matches=0;long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
   while(matches<2 && System.nanoTime()<end)for(var record:consumer.poll(Duration.ofMillis(200)))if(EventEnvelope.parse(record.value()).eventId().equals(id))matches++;
   assertThat(matches).isEqualTo(2);
  }
 }
 @Test void brokerOutageRetainsCommittedFactAndRecoveryPublishes() {
  var id=fact();KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();
  try(var p=publisher(()->{})){p.publishOne();assertThat(p.publishOne()).isFalse();}
  finally{KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec();}
  assertThat(jdbc.queryForObject("select sent_at from event_delivery where event_id=?",java.sql.Timestamp.class,id)).isNull();
  assertThat(jdbc.queryForObject("select last_code from event_delivery where event_id=?",String.class,id)).isEqualTo("PUBLISH_RETRY");
  jdbc.update("update event_delivery set next_attempt=clock_timestamp()");
  try(var p=publisher(()->{})){p.publishOne();}
  assertThat(jdbc.queryForObject("select count(*) from event_delivery where sent_at is not null",Long.class)).isOne();
 }
 @Test void invalidEnvelopeDoesNotGetMarkedSent(){
  jdbc.update("insert into facts(event_id,envelope) values(?,?::jsonb)",UUID.randomUUID(),"{\"schemaVersion\":99}");
  try(var p=publisher(()->{})){p.publishOne();}
  assertThat(jdbc.queryForObject("select count(*) from event_delivery where sent_at is not null",Long.class)).isZero();
 }
}
