package com.fincore.account;

import com.fincore.support.*;
import com.fincore.messaging.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.*;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

/** Both real owner schemas, restricted runtime roles and their actual immutable publisher views. */
class OwnerReadAuditIT {
    static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:17.11-alpine").withDatabaseName("bootstrap");
    static final String PASSWORD=UUID.randomUUID().toString();
    static final List<String> OWNERS=List.of("account","payment");
    @BeforeAll static void setup() {
        PG.start();var admin=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()));
        for(String domain:OWNERS) {
            for(String role:List.of("runtime","migrator"))admin.execute("create role "+domain+"_"+role+" login password '"+PASSWORD+"'");
            createDatabase(domain,admin,domain);migrate(domain,domain,null);
        }
    }
    @AfterAll static void stop() { PG.stop(); }
    static String url(String database) { return PG.getJdbcUrl().replace("/bootstrap","/"+database); }
    static JdbcTemplate db(String database,String user) { return new JdbcTemplate(new DriverManagerDataSource(url(database),user,PASSWORD)); }
    static JdbcTemplate runtime(String domain) { return db(domain,domain+"_runtime"); }
    static JdbcTemplate owner(String domain) { return db(domain,domain+"_migrator"); }
    static void createDatabase(String database,JdbcTemplate admin,String domain) {
        admin.execute("create database "+database+" owner "+domain+"_migrator");admin.execute("revoke all on database "+database+" from public");admin.execute("grant connect on database "+database+" to "+domain+"_runtime");
    }
    static void migrate(String database,String domain,String target) {
        var config=Flyway.configure().dataSource(url(database),domain+"_migrator",PASSWORD).locations("filesystem:../"+domain+"-service/src/main/resources/db/migration");
        if(target!=null)config.target(target);config.load().migrate();
    }
    static AdministrativeReadAudit audit(String domain,JdbcTemplate jdbc,DataSourceTransactionManager tx) {
        return new AdministrativeReadAudit(jdbc,tx,AdministrativeReadAudit.Owner.valueOf(domain.toUpperCase(Locale.ROOT)));
    }
    static AdministrativeReadAudit audit(String domain,JdbcTemplate jdbc) { return audit(domain,jdbc,new DataSourceTransactionManager(jdbc.getDataSource())); }
    static AdministrativeReadAudit.Target target(String domain,UUID id) { return new AdministrativeReadAudit.Target(domain.toUpperCase(Locale.ROOT)+"_INSPECT",id); }
    static long count(JdbcTemplate jdbc,String table) { return jdbc.queryForObject("select count(*) from "+table,Long.class); }

    @Test void runtimeReceiptsAreImmutableAndPublishOnlyTypedStaffMetadata() {
        for(String domain:OWNERS) {
            var jdbc=runtime(domain);var id=UUID.randomUUID();String correlation=UUID.randomUUID().toString();
            audit(domain,jdbc).retain(target(domain,id),"GET",200,new AdministrativeReadAudit.Actor("signed-administrator"),correlation);
            var body=jdbc.queryForObject("select envelope::text from fincore_event_source s join "+domain+"_access_audit a on a.id=s.event_id where a.correlation_id=?",String.class,correlation);
            var event=EventEnvelope.parse(body);assertThat(event.aggregateId()).isEqualTo(event.eventId());assertThat(event.aggregateVersion()).isEqualTo(1);
            assertThat(event.eventType()).isEqualTo(domain+".access.recorded");
            assertThat(event.payload()).isEqualTo(Json.read(Json.text(Map.of(domain+"Id",id,"action",domain.toUpperCase(Locale.ROOT)+"_INSPECT","httpMethod","GET","httpStatus",200,"outcome","RESPONSE_READY","actorSubject","signed-administrator","actorProvenance","SIGNED_JWT"))));
            for(String sql:List.of("update "+domain+"_access_audit set http_status=500","delete from "+domain+"_access_audit","truncate "+domain+"_access_audit","alter table "+domain+"_access_audit disable trigger all"))
                assertThatThrownBy(()->jdbc.execute(sql)).rootCause().isInstanceOfSatisfying(java.sql.SQLException.class,e->assertThat(e.getSQLState()).isEqualTo("42501"));
            for(String sql:List.of("delete from "+domain+"_access_audit","truncate "+domain+"_access_audit"))
                assertThatThrownBy(()->owner(domain).execute(sql)).rootCause().hasMessageContaining(domain.toUpperCase(Locale.ROOT)+"_ACCESS_AUDIT_IMMUTABLE");
        }
    }
    @Test void aReceiptCommitsIndependentlyOfAnEnforcedReadOnlySnapshotAndOuterRollback() {
        for(String domain:OWNERS) {
            var jdbc=runtime(domain);var manager=new DataSourceTransactionManager(jdbc.getDataSource());manager.setEnforceReadOnly(true);
            var outer=new TransactionTemplate(manager);outer.setReadOnly(true);String correlation=UUID.randomUUID().toString();
            outer.executeWithoutResult(status->{
                assertThat(jdbc.queryForObject("show transaction_read_only",String.class)).isEqualTo("on");
                audit(domain,jdbc,manager).retain(target(domain,UUID.randomUUID()),"GET",200,new AdministrativeReadAudit.Actor("signed-reader"),correlation);
                assertThat(jdbc.queryForObject("show transaction_read_only",String.class)).isEqualTo("on");status.setRollbackOnly();
            });
            assertThat(jdbc.queryForObject("select count(*) from "+domain+"_access_audit where correlation_id=?",Long.class,correlation)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from fincore_event_source s join "+domain+"_access_audit a on a.id=s.event_id where a.correlation_id=?",Long.class,correlation)).isEqualTo(1);
        }
    }
    @Test void invalidMetadataAndUnavailableInsertNeverLeaveAHalfReceiptOrSource() {
        for(String domain:OWNERS) {
            var jdbc=runtime(domain);var store=audit(domain,jdbc);long before=count(jdbc,domain+"_access_audit");
            assertThatThrownBy(()->store.retain(new AdministrativeReadAudit.Target("FOREIGN_INSPECT",UUID.randomUUID()),"GET",200,null,"valid-correlation")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThatThrownBy(()->store.retain(target(domain,null),"GET",200,new AdministrativeReadAudit.Actor("email@example.invalid"),"valid-correlation")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            String correlation=UUID.randomUUID().toString();
            try {
                owner(domain).execute("revoke insert on "+domain+"_access_audit from "+domain+"_runtime");
                assertThatThrownBy(()->store.retain(target(domain,null),"GET",401,null,correlation)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            } finally { owner(domain).execute("grant insert on "+domain+"_access_audit to "+domain+"_runtime"); }
            assertThat(count(jdbc,domain+"_access_audit")).isEqualTo(before);
            assertThat(jdbc.queryForObject("select count(*) from fincore_event_source where envelope->>'correlationId'=?",Long.class,correlation)).isZero();
        }
    }
    @Test void lostAcknowledgementReplaysIdenticalOwnerReceiptBytes() {
        for(String domain:OWNERS) {
            var jdbc=runtime(domain);var tx=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
            var producer=new MockProducer<String,String>(true,new StringSerializer(),new StringSerializer());
            var normal=new OutboxPublisher(jdbc,tx,producer,new SimpleMeterRegistry(),()->{});while(normal.publishOne()) { /* Isolated owner fixture drain. */ }
            String correlation=UUID.randomUUID().toString();audit(domain,jdbc).retain(target(domain,UUID.randomUUID()),"GET",403,new AdministrativeReadAudit.Actor("signed-staff"),correlation);
            var lost=new OutboxPublisher(jdbc,tx,producer,new SimpleMeterRegistry(),()->{throw new IllegalStateException("AFTER_ACK_FAULT");});
            assertThat(lost.publishOne()).isTrue();var original=producer.history().getLast();assertThat(original.topic()).isEqualTo("fincore."+domain+".access.recorded.v1");
            assertThat(EventEnvelope.parse(original.value()).correlationId()).isEqualTo(correlation);
            assertThat(jdbc.queryForObject("select sent_at is null from event_delivery where event_id=?::uuid",Boolean.class,original.key())).isTrue();
            jdbc.update("update event_delivery set next_attempt=clock_timestamp() where event_id=?::uuid",original.key());
            assertThat(normal.publishOne()).isTrue();assertThat(producer.history().getLast().value()).isEqualTo(original.value());
            assertThat(jdbc.queryForObject("select sent_at is not null from event_delivery where event_id=?::uuid",Boolean.class,original.key())).isTrue();
        }
    }
    @Test void additiveMigrationsPreserveOldFinancialRowsEnvelopesAndAcknowledgedDeliveries() {
        var admin=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()));
        for(String domain:OWNERS) {
            String database=domain+"_upgrade";createDatabase(database,admin,domain);migrate(database,domain,domain.equals("account")?"2":"4");
            var jdbc=db(database,domain+"_migrator");UUID id=UUID.randomUUID();
            if(domain.equals("account")) {
                jdbc.update("insert into account(id,customer_id,owner_subject,key,currency,status) values(?,?,'private-customer',?,'MAD','PROVISIONING')",id,UUID.randomUUID(),UUID.randomUUID().toString());
                jdbc.update("insert into account_outbox(id,account_id,aggregate_version,event_type) values(?,?,0,'account.created')",UUID.randomUUID(),id);
            } else jdbc.update("insert into payment(id,actor,kind,key,amount,currency,reference,instruction,initial_response,transition_actor) values(?,'private-payer','TRANSFER',?,'10.01','MAD','PRIVATE_REFERENCE','{}','{}','private-payer')",id,UUID.randomUUID().toString());
            jdbc.execute("insert into event_delivery(event_id,sent_at,attempts) select event_id,clock_timestamp(),2 from fincore_event_source");
            String row=jdbc.queryForObject("select to_jsonb(p)::text from "+domain+" p where id=?",String.class,id);
            String sources=jdbc.queryForObject("select jsonb_agg(to_jsonb(s) order by event_id)::text from fincore_event_source s",String.class);
            String deliveries=jdbc.queryForObject("select jsonb_agg(to_jsonb(d) order by event_id)::text from event_delivery d",String.class);
            migrate(database,domain,null);
            assertThat(jdbc.queryForObject("select to_jsonb(p)::text from "+domain+" p where id=?",String.class,id)).isEqualTo(row);
            assertThat(jdbc.queryForObject("select jsonb_agg(to_jsonb(s) order by event_id)::text from fincore_event_source s",String.class)).isEqualTo(sources);
            assertThat(jdbc.queryForObject("select jsonb_agg(to_jsonb(d) order by event_id)::text from event_delivery d",String.class)).isEqualTo(deliveries);
            assertThat(count(jdbc,domain+"_access_audit")).isZero();
        }
    }
}
