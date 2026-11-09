package com.fincore.customer.configuration;

import com.fincore.messaging.*;
import com.fincore.support.Json;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.util.*;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.MountableFile;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class CustomerAccessAuditIT {
    static final String PASSWORD=UUID.randomUUID().toString();
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17.11-alpine")
        .withDatabaseName("bootstrap").withEnv("CUSTOMER_DB_PASSWORD",PASSWORD).withEnv("CUSTOMER_MIGRATION_PASSWORD",PASSWORD)
        .withEnv("KEYCLOAK_DB_PASSWORD",UUID.randomUUID().toString())
        .withCopyFileToContainer(MountableFile.forHostPath(Path.of("../../infrastructure/docker/postgres/init-databases.sh").toAbsolutePath().normalize().toString(),0744),"/docker-entrypoint-initdb.d/10-databases.sh");
    JdbcTemplate db(String user) { return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl().replace("/bootstrap","/customer"),user,PASSWORD)); }
    JdbcTemplate runtime() { Flyway.configure().dataSource(POSTGRES.getJdbcUrl().replace("/bootstrap","/customer"),"customer_migrator",PASSWORD).load().migrate();return db("customer_runtime"); }
    @Test void runtimeCanAppendButCannotAlterEraseOrTruncateReceipts() {
        var jdbc=runtime();String correlation=UUID.randomUUID().toString();
        new CustomerAccessAudit(jdbc,new DataSourceTransactionManager(jdbc.getDataSource())).retain(new CustomerAccessAudit.Target("CUSTOMER_INSPECT",UUID.randomUUID()),"GET",403,new CustomerAccessAudit.Actor("signed-subject"),correlation);
        assertThat(jdbc.queryForObject("select outcome from customer_access_audit where correlation_id=?",String.class,correlation)).isEqualTo("FORBIDDEN");
        for(String sql:List.of("update customer_access_audit set outcome='SERVER_ERROR'","delete from customer_access_audit","truncate customer_access_audit","alter table customer_access_audit disable trigger all"))
            assertThatThrownBy(()->jdbc.execute(sql)).isInstanceOf(org.springframework.dao.DataAccessException.class).rootCause()
                .isInstanceOfSatisfying(java.sql.SQLException.class,error->assertThat(error.getSQLState()).isEqualTo("42501"));
        assertThatThrownBy(()->db("customer_migrator").update("delete from customer_access_audit where correlation_id=?",correlation)).rootCause().hasMessageContaining("CUSTOMER_ACCESS_AUDIT_IMMUTABLE");
    }
    @Test void databaseRejectsUnboundedMetadataWithoutCreatingAnOutboxSource() {
        var jdbc=runtime();var audit=new CustomerAccessAudit(jdbc,new DataSourceTransactionManager(jdbc.getDataSource()));long before=jdbc.queryForObject("select count(*) from customer_access_audit",Long.class);
        for(var target:List.of(new CustomerAccessAudit.Target("PRIVATE_ACTION",null),new CustomerAccessAudit.Target("CUSTOMER_SEARCH",UUID.randomUUID())))
            assertThatThrownBy(()->audit.retain(target,"POST",200,null,"safe-correlation")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->audit.retain(new CustomerAccessAudit.Target("CUSTOMER_SEARCH",null),"POST",200,new CustomerAccessAudit.Actor("person@example.invalid"),"safe-correlation")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->audit.retain(new CustomerAccessAudit.Target("CUSTOMER_SEARCH",null),"POST",200,null,"PRIVATE QUERY STRING")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("select count(*) from customer_access_audit",Long.class)).isEqualTo(before);
    }
    @Test void typedReceiptProducesOneRedactedEnvelopeWithoutChangingProfiles() {
        var jdbc=runtime();String correlation=UUID.randomUUID().toString();
        long profiles=jdbc.queryForObject("select count(*) from customer_profile",Long.class),domain=jdbc.queryForObject("select count(*) from customer_outbox",Long.class);
        new CustomerAccessAudit(jdbc,new DataSourceTransactionManager(jdbc.getDataSource())).retain(new CustomerAccessAudit.Target("CUSTOMER_SEARCH",null),"POST",401,null,correlation);
        var raw=jdbc.queryForObject("select s.envelope::text from fincore_event_source s join customer_access_audit a on a.id=s.event_id where a.correlation_id=?",String.class,correlation);
        var event=EventEnvelope.parse(raw);
        assertThat(event.eventType()).isEqualTo("customer.access.recorded");assertThat(event.aggregateId()).isEqualTo(event.eventId());assertThat(event.aggregateVersion()).isEqualTo(1);
        assertThat(event.payload()).isEqualTo(Json.read("{\"action\":\"CUSTOMER_SEARCH\",\"outcome\":\"UNAUTHENTICATED\",\"httpMethod\":\"POST\",\"httpStatus\":401,\"actorProvenance\":\"UNAVAILABLE\"}"));
        assertThat(jdbc.queryForObject("select count(*) from customer_profile",Long.class)).isEqualTo(profiles);assertThat(jdbc.queryForObject("select count(*) from customer_outbox",Long.class)).isEqualTo(domain);
    }
    @Test void lostAcknowledgementReplaysTheIdenticalImmutableSource() {
        var jdbc=runtime();var tx=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        var producer=new MockProducer<String,String>(true,new StringSerializer(),new StringSerializer());
        var normal=new OutboxPublisher(jdbc,tx,producer,new SimpleMeterRegistry(),()->{});
        while(normal.publishOne()) { /* Drain any receipts produced by other cases. */ }
        String correlation=UUID.randomUUID().toString();new CustomerAccessAudit(jdbc,new DataSourceTransactionManager(jdbc.getDataSource())).retain(new CustomerAccessAudit.Target("CUSTOMER_SEARCH",null),"POST",200,new CustomerAccessAudit.Actor("signed-subject"),correlation);
        var interrupted=new OutboxPublisher(jdbc,tx,producer,new SimpleMeterRegistry(),()->{throw new IllegalStateException("SIMULATED_AFTER_ACK_CRASH");});
        assertThat(interrupted.publishOne()).isTrue();var first=producer.history().getLast();
        assertThat(first.topic()).isEqualTo("fincore.customer.access.recorded.v1");assertThat(EventEnvelope.parse(first.value()).correlationId()).isEqualTo(correlation);
        assertThat(jdbc.queryForObject("select sent_at is null from event_delivery where event_id=?::uuid",Boolean.class,first.key())).isTrue();
        jdbc.update("update event_delivery set next_attempt=clock_timestamp() where event_id=?::uuid",first.key());
        assertThat(normal.publishOne()).isTrue();var replay=producer.history().getLast();assertThat(replay.key()).isEqualTo(first.key());assertThat(replay.value()).isEqualTo(first.value());
        assertThat(jdbc.queryForObject("select sent_at is not null from event_delivery where event_id=?::uuid",Boolean.class,first.key())).isTrue();
    }
    @Test void oldDomainEnvelopeAndDeliveryReceiptsSurviveAdditiveMigration() {
        // A separate database permits an actual V3 -> V4 upgrade without resetting the test volume.
        try(var admin=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()).getConnection();var statement=admin.createStatement()) {
            statement.execute("create database upgrade_customer owner customer_migrator");
        } catch(java.sql.SQLException failure) { throw new IllegalStateException(failure); }
        String url=POSTGRES.getJdbcUrl().replace("/bootstrap","/upgrade_customer");
        Flyway.configure().dataSource(url,"customer_migrator",PASSWORD).target("3").load().migrate();
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(url,"customer_migrator",PASSWORD));var id=UUID.randomUUID();var event=UUID.randomUUID();
        jdbc.update("insert into customer_profile(id,subject,display_name,country_code) values(?,?,'PRIVATE_NAME','MA')",id,"PRIVATE_SUBJECT");
        jdbc.update("insert into customer_outbox(id,customer_id,aggregate_version,event_type) values(?,?,0,'customer.created')",event,id);
        jdbc.update("insert into event_delivery(event_id,sent_at,attempts) values(?,clock_timestamp(),2)",event);
        String before=jdbc.queryForObject("select envelope::text from fincore_event_source where event_id=?",String.class,event),delivery=jdbc.queryForObject("select to_jsonb(d)::text from event_delivery d where event_id=?",String.class,event);
        Flyway.configure().dataSource(url,"customer_migrator",PASSWORD).load().migrate();
        assertThat(jdbc.queryForObject("select envelope::text from fincore_event_source where event_id=?",String.class,event)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select to_jsonb(d)::text from event_delivery d where event_id=?",String.class,event)).isEqualTo(delivery);
    }
}
