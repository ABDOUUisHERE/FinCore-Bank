package com.fincore.reporting;

import com.fincore.support.*;
import com.fincore.messaging.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.*;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"REPORTING_CLIENT_SECRET=synthetic-test-secret","fincore.messaging.enabled=false","fincore.tracing.outbox-enabled=true"})
@AutoConfigureMockMvc
@Import(ReportingSecurityTest.Decoders.class)
class ReportingAccessAuditIT {
    static final String PASSWORD=UUID.randomUUID().toString();
    static final PostgreSQLContainer<?> PG=database();
    static PostgreSQLContainer<?> database(){
        var pg=new PostgreSQLContainer<>("postgres:17.11-alpine");pg.start();
        var admin=new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword()));
        for(String role:List.of("reporting_runtime","reporting_migrator"))admin.execute("create role "+role+" login password '"+PASSWORD+"'");
        admin.execute("alter database test owner to reporting_migrator");Flyway.configure().dataSource(pg.getJdbcUrl(),"reporting_migrator",PASSWORD).load().migrate();return pg;
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",PG::getJdbcUrl);r.add("spring.datasource.username",()->"reporting_runtime");r.add("spring.datasource.password",()->PASSWORD);}
    @AfterAll static void stop(){PG.stop();}
    @Autowired MockMvc http;@Autowired JdbcTemplate db;@Autowired AdministrativeReadAudit audit;@Autowired PlatformTransactionManager transactions;
    @MockitoBean Remote remote;
    static JdbcTemplate migrator(){return new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),"reporting_migrator",PASSWORD));}
    String token(String actor,String role){return SignedTokens.token("fincore-api",actor,"browser",List.of(role),Map.of());}
    long count(String table){return db.queryForObject("select count(*) from "+table,Long.class);}
    String snapshot(UUID id){return db.queryForObject("select to_jsonb(s)::text from statement_snapshot s where id=?",String.class,id);}
    @Test void committedCreationSurvivesWithheldReceiptAndRetryReusesOriginalSnapshotAndExports()throws Exception{
        String actor="owner-"+UUID.randomUUID();UUID account=UUID.randomUUID(),ledger=UUID.randomUUID();
        var source=(ObjectNode)StatementExportsTest.statement(3).path("snapshot");source.put("accountId",account.toString());source.put("ledgerAccountId",ledger.toString());source.put("accountReference","account:"+account);source.put("reconciled",true);
        when(remote.serviceToken()).thenReturn("technical");when(remote.exchange(anyString())).thenReturn("delegated");
        when(remote.call(eq("GET"),contains("/internal/v1/accounts/"),eq("technical"),isNull())).thenReturn(Json.read(Json.text(Map.of("ownerSubject",actor,"ledgerId",ledger))));
        when(remote.call(eq("GET"),contains("/statement?month="),eq("delegated"),isNull())).thenReturn(source);
        String denial=UUID.randomUUID().toString();
        http.perform(get("/api/v1/admin/reports/reconciliation").header("Authorization","Bearer "+token("signed-administrator","ADMIN")).header("X-Correlation-ID",denial)).andExpect(status().isForbidden());
        assertThat(db.queryForObject("select count(*) from reporting_access_audit where correlation_id=? and action='RECONCILIATION_INSPECT' and http_status=403 and outcome='FORBIDDEN' and actor_subject='signed-administrator' and actor_provenance='SIGNED_JWT' and resource_id is null",Long.class,denial)).isEqualTo(1);
        String bearer=token(actor,"CUSTOMER"),body=Json.text(Map.of("accountId",account,"month","2026-01"));long before=count("statement_snapshot"),receipts=count("reporting_access_audit");
        try{
            migrator().execute("revoke insert on reporting_access_audit from reporting_runtime");
            http.perform(post("/api/v1/statements").header("Authorization","Bearer "+bearer).contentType("application/json").content(body)).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AUDIT_UNAVAILABLE")).andExpect(jsonPath("$.statementId").doesNotExist());
        }finally{migrator().execute("grant insert on reporting_access_audit to reporting_runtime");}
        assertThat(count("statement_snapshot")).isEqualTo(before+1);assertThat(count("reporting_access_audit")).isEqualTo(receipts);
        UUID id=db.queryForObject("select id from statement_snapshot where actor=?",UUID.class,actor);String original=snapshot(id);
        for(int n=0;n<2;n++)http.perform(post("/api/v1/statements").header("Authorization","Bearer "+bearer).contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.statementId").value(id.toString()));
        assertThat(count("statement_snapshot")).isEqualTo(before+1);assertThat(snapshot(id)).isEqualTo(original);
        assertThat(db.queryForObject("select count(*) from reporting_access_audit where actor_subject=? and action='STATEMENT_CREATE_REQUEST' and resource_id is null",Long.class,actor)).isEqualTo(2);
        for(String format:List.of("csv","pdf")){
            long retained=count("reporting_access_audit");
            try{
                migrator().execute("revoke insert on reporting_access_audit from reporting_runtime");
                var unavailable=http.perform(get("/api/v1/statements/"+id+"/"+format).header("Authorization","Bearer "+bearer)).andExpect(status().isServiceUnavailable()).andExpect(header().doesNotExist("Content-Disposition")).andReturn();
                assertThat(unavailable.getResponse().getContentAsString()).contains("AUDIT_UNAVAILABLE").doesNotContain(id.toString(),"%PDF-","100.00");
            }finally{migrator().execute("grant insert on reporting_access_audit to reporting_runtime");}
            assertThat(count("reporting_access_audit")).isEqualTo(retained);
            String correlation=UUID.randomUUID().toString();var exported=http.perform(get("/api/v1/statements/"+id+"/"+format).header("Authorization","Bearer "+bearer).header("X-User-ID","FORGED_OWNER").header("X-Correlation-ID",correlation)).andExpect(status().isOk()).andExpect(header().stringValues("Cache-Control","no-store")).andExpect(header().string("Content-Disposition","attachment; filename=\"fincore-"+id+"."+format+"\"")).andReturn();
            assertThat(exported.getResponse().getContentAsByteArray()).hasSizeGreaterThan(500);
            var fact=Json.read(db.queryForObject("select envelope::text from fincore_event_source where envelope->>'correlationId'=?",String.class,correlation));
            assertThat(fact.path("payload")).isEqualTo(Json.read(Json.text(Map.of("statementId",id,"action",format.equals("csv")?"STATEMENT_EXPORT_CSV":"STATEMENT_EXPORT_PDF","httpMethod","GET","httpStatus",200,"outcome","RESPONSE_READY","actorSubject",actor,"actorProvenance","SIGNED_JWT"))));
            http.perform(get("/api/v1/statements/"+id+"/"+format).header("Authorization","Bearer "+token("foreign-owner","CUSTOMER"))).andExpect(status().isNotFound());
        }
        assertThat(snapshot(id)).isEqualTo(original);
    }
    @Test void receiptIsImmutableAndCommitsOutsideAnEnforcedReadOnlyRollback(){
        var manager=(DataSourceTransactionManager)transactions;manager.setEnforceReadOnly(true);var outer=new TransactionTemplate(manager);outer.setReadOnly(true);String correlation=UUID.randomUUID().toString();UUID journal=UUID.randomUUID();
        outer.executeWithoutResult(s->{assertThat(db.queryForObject("show transaction_read_only",String.class)).isEqualTo("on");audit.retain(new AdministrativeReadAudit.Target("JOURNAL_INSPECT",journal),"GET",200,new AdministrativeReadAudit.Actor("signed-finance"),correlation);assertThat(db.queryForObject("show transaction_read_only",String.class)).isEqualTo("on");s.setRollbackOnly();});
        var fact=Json.read(db.queryForObject("select envelope::text from fincore_event_source where envelope->>'correlationId'=?",String.class,correlation));assertThat(fact.path("payload").path("journalId").asText()).isEqualTo(journal.toString());assertThat(fact.path("payload").has("statementId")).isFalse();
        for(String sql:List.of("delete from reporting_access_audit","update reporting_access_audit set http_status=500","truncate reporting_access_audit","alter table reporting_access_audit disable trigger all"))assertThatThrownBy(()->db.execute(sql)).rootCause().isInstanceOfSatisfying(java.sql.SQLException.class,e->assertThat(e.getSQLState()).isEqualTo("42501"));
        for(String sql:List.of("delete from reporting_access_audit","truncate reporting_access_audit"))assertThatThrownBy(()->migrator().execute(sql)).rootCause().hasMessageContaining("REPORTING_ACCESS_AUDIT_IMMUTABLE");
    }
    @Test void lostBrokerAcknowledgementReplaysIdenticalReceiptBytes(){
        var tx=new TransactionTemplate(transactions);var producer=new MockProducer<String,String>(true,new StringSerializer(),new StringSerializer());var normal=new OutboxPublisher(db,tx,producer,new SimpleMeterRegistry(),()->{});while(normal.publishOne()){}
        audit.retain(new AdministrativeReadAudit.Target("STATEMENT_LIST",null),"GET",401,null,UUID.randomUUID().toString());
        var lost=new OutboxPublisher(db,tx,producer,new SimpleMeterRegistry(),()->{throw new IllegalStateException("AFTER_ACK_FAULT");});assertThat(lost.publishOne()).isTrue();var original=producer.history().getLast();assertThat(original.topic()).isEqualTo("fincore.reporting.access.recorded.v1");
        db.update("update event_delivery set next_attempt=clock_timestamp() where event_id=?::uuid",original.key());assertThat(normal.publishOne()).isTrue();assertThat(producer.history().getLast().value()).isEqualTo(original.value());assertThat(db.queryForObject("select sent_at is not null from event_delivery where event_id=?::uuid",Boolean.class,original.key())).isTrue();
    }
    @Test void additiveV1UpgradePreservesOriginalSnapshotBytesAndHashes(){
        var admin=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()));admin.execute("create database reporting_upgrade owner reporting_migrator");String url=PG.getJdbcUrl().replace("/test","/reporting_upgrade");
        Flyway.configure().dataSource(url,"reporting_migrator",PASSWORD).target("1").load().migrate();var jdbc=new JdbcTemplate(new DriverManagerDataSource(url,"reporting_runtime",PASSWORD));var tx=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));var store=new StatementStore(jdbc);var source=StatementExportsTest.statement(3).path("snapshot");
        var original=tx.execute(s->store.save("original-owner",UUID.fromString(source.path("accountId").asText()),source));var id=UUID.fromString(original.path("statementId").asText());String bytes=jdbc.queryForObject("select to_jsonb(s)::text from statement_snapshot s where id=?",String.class,id);
        Flyway.configure().dataSource(url,"reporting_migrator",PASSWORD).load().migrate();assertThat(jdbc.queryForObject("select to_jsonb(s)::text from statement_snapshot s where id=?",String.class,id)).isEqualTo(bytes);assertThat(store.own(id,"original-owner")).isEqualTo(original);assertThat(jdbc.queryForObject("select count(*) from reporting_access_audit",Long.class)).isZero();assertThat(jdbc.queryForObject("select count(*) from fincore_event_source",Long.class)).isZero();
    }
    @Test void invalidActionReferencesAndActorMetadataCannotLeavePartialSources(){
        long before=count("reporting_access_audit");
        for(var target:List.of(new AdministrativeReadAudit.Target("ACCOUNT_INSPECT",UUID.randomUUID()),new AdministrativeReadAudit.Target("STATEMENT_CREATE_REQUEST",UUID.randomUUID()),new AdministrativeReadAudit.Target("RECONCILIATION_INSPECT",UUID.randomUUID())))assertThatThrownBy(()->audit.retain(target,"GET",200,null,"invalid-metadata")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->audit.retain(new AdministrativeReadAudit.Target("STATEMENT_LIST",null),"GET",200,new AdministrativeReadAudit.Actor("email@example.invalid"),"invalid-metadata")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);assertThat(count("reporting_access_audit")).isEqualTo(before);
    }
    @Test void endedRequestOriginSurvivesLostAckRetriesWithoutChangingTheImmutableEnvelope() {
        var exporter=io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter.create();
        try(var provider=io.opentelemetry.sdk.trace.SdkTracerProvider.builder().addSpanProcessor(io.opentelemetry.sdk.trace.export.SimpleSpanProcessor.create(exporter)).build()) {
            var backlogProducer=new MockProducer<String,String>(true,new StringSerializer(),new StringSerializer());
            var backlog=new OutboxPublisher(db,new TransactionTemplate(transactions),backlogProducer,new SimpleMeterRegistry(),()->{});while(backlog.publishOne()){}
            var tracing=new com.fincore.observability.Tracing(provider.get("reporting-origin-test"));String origin,trace;String correlation=UUID.randomUUID().toString();
            try(var request=tracing.server(null,"GET");var scope=request.makeCurrent()) {
                origin=request.parent();trace=request.traceId();audit.retain(new AdministrativeReadAudit.Target("RECONCILIATION_INSPECT",null),"GET",200,new AdministrativeReadAudit.Actor("finance"),correlation);
            }
            UUID id=db.queryForObject("select id from reporting_access_audit where correlation_id=?",UUID.class,correlation);
            assertThat(db.queryForObject("select traceparent from event_delivery where event_id=?",String.class,id)).isEqualTo(origin);
            String body=db.queryForObject("select envelope::text from fincore_event_source where event_id=?",String.class,id);
            assertThat(body).doesNotContain("traceparent",trace);
            assertThatThrownBy(()->db.update("update event_delivery set traceparent=null where event_id=?",id)).hasMessageContaining("TRACE_ORIGIN_IMMUTABLE");
            assertThatThrownBy(()->db.update("update event_delivery set event_id=? where event_id=?",UUID.randomUUID(),id)).hasMessageContaining("TRACE_ORIGIN_IMMUTABLE");
            var producer=new MockProducer<String,String>(true,new StringSerializer(),new StringSerializer());
            var tx=new TransactionTemplate(transactions);
            var lostAck=new OutboxPublisher(db,tx,producer,new SimpleMeterRegistry(),()->{throw new IllegalStateException("PRIVATE_ACK_FAILURE");},tracing,true);
            assertThat(lostAck.publishOne()).isTrue();assertThat(producer.history()).hasSize(1);
            db.update("update event_delivery set next_attempt=clock_timestamp() where event_id=?",id);
            var retry=new OutboxPublisher(db,tx,producer,new SimpleMeterRegistry(),()->{},tracing,true);assertThat(retry.publishOne()).isTrue();
            var records=producer.history();assertThat(records).hasSize(2).allSatisfy(r->assertThat(r.value()).isEqualTo(body));
            String first=new String(records.getFirst().headers().lastHeader("traceparent").value(),java.nio.charset.StandardCharsets.US_ASCII);
            String second=new String(records.getLast().headers().lastHeader("traceparent").value(),java.nio.charset.StandardCharsets.US_ASCII);
            assertThat(first).isNotEqualTo(second);assertThat(first).startsWith("00-"+trace);assertThat(second).startsWith("00-"+trace);
            assertThat(db.queryForObject("select traceparent from event_delivery where event_id=?",String.class,id)).isEqualTo(origin);
            assertThat(db.queryForObject("select sent_at is not null from event_delivery where event_id=?",Boolean.class,id)).isTrue();
            assertThat(exporter.getFinishedSpanItems().stream().filter(s->s.getKind()==io.opentelemetry.api.trace.SpanKind.PRODUCER).toList()).hasSize(2).allSatisfy(s->assertThat(s.getParentSpanId()).isEqualTo(origin.substring(36,52)));
        }
    }
    @Test void originMetadataFailureRollsBackTheReceiptRatherThanLeavingPartialCausality() {
        try(var provider=io.opentelemetry.sdk.trace.SdkTracerProvider.builder().build()) {
            var tracing=new com.fincore.observability.Tracing(provider.get("rollback-test"));long before=count("reporting_access_audit");
            try(var request=tracing.server(null,"GET");var scope=request.makeCurrent()) {
                try {
                    migrator().execute("revoke insert on event_delivery from reporting_runtime");
                    assertThatThrownBy(()->audit.retain(new AdministrativeReadAudit.Target("STATEMENT_LIST",null),"GET",200,null,"origin-rollback")).isInstanceOf(org.springframework.dao.DataAccessException.class);
                } finally { migrator().execute("grant insert on event_delivery to reporting_runtime"); }
            }
            assertThat(count("reporting_access_audit")).isEqualTo(before);assertThat(db.queryForObject("select count(*) from reporting_access_audit where correlation_id='origin-rollback'",Long.class)).isZero();
        }
    }
    @Test void v2ToV3PreservesOriginalSourceAndDeliveryRowsWithoutInventingHistoricalOrigins() {
        var admin=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()));admin.execute("create database reporting_trace_upgrade owner reporting_migrator");String url=PG.getJdbcUrl().replace("/test","/reporting_trace_upgrade");
        Flyway.configure().dataSource(url,"reporting_migrator",PASSWORD).target("2").load().migrate();var jdbc=new JdbcTemplate(new DriverManagerDataSource(url,"reporting_runtime",PASSWORD));
        var oldAudit=new AdministrativeReadAudit(jdbc,new DataSourceTransactionManager(jdbc.getDataSource()),AdministrativeReadAudit.Owner.REPORTING);
        oldAudit.retain(new AdministrativeReadAudit.Target("STATEMENT_LIST",null),"GET",200,null,"old-source");
        UUID id=jdbc.queryForObject("select event_id from fincore_event_source",UUID.class);jdbc.update("insert into event_delivery(event_id,attempts,last_code) values(?,2,'PUBLISH_RETRY')",id);
        String original=jdbc.queryForObject("select envelope::text from fincore_event_source",String.class),delivery=jdbc.queryForObject("select to_jsonb(d)::text from event_delivery d",String.class);
        Flyway.configure().dataSource(url,"reporting_migrator",PASSWORD).load().migrate();
        assertThat(jdbc.queryForObject("select envelope::text from fincore_event_source",String.class)).isEqualTo(original);
        assertThat(jdbc.queryForObject("select (to_jsonb(d)-'traceparent')::text from event_delivery d",String.class)).isEqualTo(delivery);
        assertThat(jdbc.queryForObject("select traceparent from event_delivery",String.class)).isNull();
        assertThatThrownBy(()->jdbc.update("insert into event_delivery(event_id,traceparent) values(?,?)",UUID.randomUUID(),"00-"+"0".repeat(32)+"-"+"b".repeat(16)+"-01")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
