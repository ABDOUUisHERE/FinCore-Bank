package com.fincore.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fincore.support.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

class AuditDatabaseIT {
    static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:17.11-alpine");
    static JdbcTemplate db,owner,observer;static AuditStore store;static TransactionTemplate tx;
    @BeforeAll static void setup() {
        PG.start();String password=UUID.randomUUID().toString();
        var admin=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()));
        observer=admin;
        for(String role:List.of("audit_migrator","audit_runtime"))admin.execute("create role "+role+" login password '"+password+"'");
        admin.execute("alter database test owner to audit_migrator");
        Flyway.configure().dataSource(PG.getJdbcUrl(),"audit_migrator",password).load().migrate();
        db=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),"audit_runtime",password));
        owner=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),"audit_migrator",password));
        store=new AuditStore(db);tx=new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource()));
    }
    @AfterAll static void stop() { PG.stop(); }
    static String key() { return UUID.randomUUID().toString(); }
    static UUID id(JsonNode n) { return UUID.fromString(n.path("incidentId").asText()); }
    static AuditStore.Create createRequest() { return new AuditStore.Create("ACCOUNT",UUID.randomUUID(),"HIGH","CUSTOMER_REPORTED"); }
    static JsonNode create(String actor,String key,AuditStore.Create request) { return tx.execute(s->store.create(actor,false,key,"test-correlation",request)); }
    static JsonNode change(UUID id,String actor,boolean admin,String key,long version,String status) {
        return tx.execute(s->store.change(id,actor,admin,key,"test-correlation",new AuditStore.Change(version,status,"INVESTIGATION_UPDATE")));
    }
    static int count(String table) { return db.queryForObject("select count(*) from "+table,Integer.class); }
    static JsonNode json(Object value) { return Json.read(Json.text(value)); }
    static void forbidden(Runnable action,int status) { assertThatThrownBy(action::run).isInstanceOf(ApiFailure.class).extracting("status").isEqualTo(status); }

    @Test void duplicateFactsAreIdempotentButConflictingBodiesNeverReplaceTheOriginal() {
        var aggregate=UUID.randomUUID();var eventId=UUID.randomUUID();
        String body=AuditEventTest.event(eventId,aggregate,Map.of("accountId",aggregate,"status","active","ownerSubject","opaque-owner","displayName","PRIVATE_NAME","token","PRIVATE_TOKEN"));
        for(int i=0;i<2;i++)tx.execute(s->{store.accept("fincore.account.created.v1",aggregate.toString(),body,0,10);return null;});
        assertThat(db.queryForObject("select count(*) from audit_record where id=?",Integer.class,eventId)).isEqualTo(1);
        assertThatThrownBy(()->tx.execute(s->{store.accept("fincore.account.created.v1",aggregate.toString(),body.replace("active","closed"),0,11);return null;})).isInstanceOf(AuditEvent.Poison.class).hasMessage("EVENT_IDENTITY_CONFLICT");
        var fact=json(store.facts(null,aggregate,null)).path("items").get(0);
        assertThat(fact.path("bodyHash").asText()).isEqualTo(AuditEvent.hash(body));
        assertThat(fact.path("actor").asText()).isEqualTo("opaque-owner");
        assertThat(fact.path("sourceOffset").asText()).isEqualTo("10");
        assertThat(fact.toString()).doesNotContain("PRIVATE_NAME","PRIVATE_TOKEN","displayName","ownerSubject");
    }
    @Test void quarantineRetainsOnlyDigestAndCoordinatesAndDeduplicatesDelivery() {
        long offset=Math.abs(UUID.randomUUID().getMostSignificantBits());
        for(int i=0;i<2;i++)tx.execute(s->{store.quarantine("fincore.account.created.v1",1,offset,"PRIVATE_UNTRUSTED_PAYLOAD","INVALID_EVENT_ENVELOPE");return null;});
        assertThat(db.queryForObject("select count(*) from audit_quarantine where source_offset=?",Integer.class,offset)).isEqualTo(1);
        String row=db.queryForObject("select row_to_json(q)::text from audit_quarantine q where source_offset=?",String.class,offset);
        assertThat(row).contains(AuditEvent.hash("PRIVATE_UNTRUSTED_PAYLOAD")).doesNotContain("PRIVATE_UNTRUSTED_PAYLOAD");
    }
    @Test void factsReceiptsAndIncidentIdentityCannotBeRewrittenOrDeletedEvenByTheMigrator() {
        var receipt=create("immutable-owner",key(),createRequest());
        tx.execute(s->{store.quarantine("fincore.account.created.v1",0,123,"invalid","INVALID_EVENT_ENVELOPE");return null;});
        for(JdbcTemplate connection:List.of(db,owner)) {
            for(String table:List.of("audit_record","audit_quarantine","incident_command")) {
                for(String sql:List.of("delete from "+table,"truncate "+table))assertThatThrownBy(()->connection.execute(sql)).isInstanceOf(DataAccessException.class);
            }
            for(String sql:List.of("update audit_record set actor='forged'","update audit_quarantine set code='FORGED'","update incident_command set response='{}'","update incident set created_by='forged'","delete from incident","truncate incident"))
                assertThatThrownBy(()->connection.execute(sql)).isInstanceOf(DataAccessException.class);
        }
        assertThat(store.incident(id(receipt),"immutable-owner",false).path("version").asLong()).isZero();
    }
    @Test void supportScopeAppliesToListsDetailsHistoryMutationsAndReplayAfterRoleLoss() {
        String actor="support-"+key();String command=key();var request=createRequest();var receipt=create(actor,command,request);var incident=id(receipt);
        assertThat(json(store.incidents(null,actor,false)).path("items")).hasSize(1);
        assertThat(json(store.incidents(null,"foreign-support",false)).path("items")).isEmpty();
        forbidden(()->store.incident(incident,"foreign-support",false),404);
        forbidden(()->store.history(incident,null,"foreign-support",false),404);
        forbidden(()->change(incident,"foreign-support",false,key(),0,"INVESTIGATING"),404);
        assertThat(json(store.history(incident,null,actor,false)).path("items")).hasSize(1);
        assertThat(store.incident(incident,"admin",true).path("createdBy").asText()).isEqualTo(actor);
        String adminKey=key();var changed=change(incident,"demoted-admin",true,adminKey,0,"INVESTIGATING");
        forbidden(()->change(incident,"demoted-admin",false,adminKey,0,"INVESTIGATING"),404);
        assertThat(change(incident,"demoted-admin",true,adminKey,0,"INVESTIGATING")).isEqualTo(changed);
    }
    @Test void commandReplayReturnsTheOriginalReceiptAfterLaterChangesAndRejectsKeyReuse() {
        String actor="replay-"+key(),creationKey=key(),updateKey=key();var request=createRequest();var first=create(actor,creationKey,request);var incident=id(first);
        var investigating=change(incident,actor,false,updateKey,0,"INVESTIGATING");
        change(incident,actor,false,key(),1,"RESOLVED");
        assertThat(create(actor,creationKey,request)).isEqualTo(first);
        assertThat(change(incident,actor,false,updateKey,0,"INVESTIGATING")).isEqualTo(investigating);
        assertThat(store.incident(incident,actor,false).path("status").asText()).isEqualTo("RESOLVED");
        forbidden(()->create(actor,creationKey,createRequest()),409);
        forbidden(()->change(incident,actor,false,key(),1,"INVESTIGATING"),409);
        assertThat(json(store.history(incident,null,actor,false)).path("items")).hasSize(3);
    }
    @Test void concurrentCommandsWithTheSameExpectedVersionHaveExactlyOneWinner()throws Exception {
        String actor="race-"+key();var incident=id(create(actor,key(),createRequest()));
        try(var workers=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);List<Future<Boolean>> results=new ArrayList<>();
            for(int i=0;i<2;i++)results.add(workers.submit(()->{start.await();try{change(incident,actor,false,key(),0,"INVESTIGATING");return true;}catch(ApiFailure error){assertThat(error.code).isEqualTo("INCIDENT_VERSION_CONFLICT");return false;}}));
            start.countDown();int winners=0;for(var result:results)if(result.get(20,TimeUnit.SECONDS))winners++;
            assertThat(winners).isEqualTo(1);
        }
        assertThat(store.incident(incident,actor,false).path("version").asLong()).isEqualTo(1);
        assertThat(json(store.history(incident,null,actor,false)).path("items")).hasSize(2);
    }
    @Test void receiptWriteFailureRollsBackStateAndAuditTogether() {
        String actor="rollback-"+key();var incident=id(create(actor,key(),createRequest()));String command=key();
        owner.execute("create function reject_test_receipt() returns trigger language plpgsql as $$ begin if NEW.key='"+command+"' then raise exception 'INJECTED_RECEIPT_FAILURE'; end if; return NEW; end $$");
        owner.execute("create trigger reject_test_receipt before insert on incident_command for each row execute function reject_test_receipt()");
        int facts=count("audit_record"),receipts=count("incident_command");
        try {
            assertThatThrownBy(()->change(incident,actor,false,command,0,"INVESTIGATING")).isInstanceOf(DataAccessException.class);
            assertThat(store.incident(incident,actor,false).path("status").asText()).isEqualTo("OPEN");
            assertThat(count("audit_record")).isEqualTo(facts);assertThat(count("incident_command")).isEqualTo(receipts);
        } finally { owner.execute("drop trigger reject_test_receipt on incident_command");owner.execute("drop function reject_test_receipt()"); }
    }
    @Test void aConcurrentFactCannotCommitAheadOfAnEarlierUncommittedCursor()throws Exception {
        var firstId=UUID.randomUUID();var secondId=UUID.randomUUID();var aggregate=UUID.randomUUID();
        String first=AuditEventTest.event(firstId,aggregate,Map.of()),second=AuditEventTest.event(secondId,aggregate,Map.of());
        var holding=new CountDownLatch(1);var release=new CountDownLatch(1);var secondPid=new CompletableFuture<Integer>();
        try(var workers=Executors.newFixedThreadPool(2)) {
            var a=workers.submit(()->tx.execute(s->{store.accept("fincore.account.created.v1",aggregate.toString(),first,0,20);holding.countDown();try{if(!release.await(15,TimeUnit.SECONDS))throw new IllegalStateException("TEST_TIMEOUT");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}return null;}));
            assertThat(holding.await(10,TimeUnit.SECONDS)).isTrue();
            var b=workers.submit(()->tx.execute(s->{secondPid.complete(db.queryForObject("select pg_backend_pid()",Integer.class));store.accept("fincore.account.created.v1",aggregate.toString(),second,0,21);return null;}));
            int pid=secondPid.get(10,TimeUnit.SECONDS);boolean blocked=false;
            // pg_stat_activity hides other roles' wait details from the non-superuser migrator.
            for(int i=0;i<100;i++){blocked=Boolean.TRUE.equals(observer.queryForObject("select wait_event_type='Lock' and wait_event='advisory' from pg_stat_activity where pid=?",Boolean.class,pid));if(blocked)break;Thread.sleep(20);}
            assertThat(blocked).isTrue();assertThat(json(store.facts(null,aggregate,null)).path("items")).isEmpty();
            release.countDown();a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);
        } finally {release.countDown();}
        var rows=json(store.facts(null,aggregate,null)).path("items");
        assertThat(rows).hasSize(2);assertThat(rows.get(0).path("recordId").asText()).isEqualTo(secondId.toString());
        assertThat(json(store.facts(rows.get(0).path("sequence").asLong(),aggregate,null)).path("items").get(0).path("recordId").asText()).isEqualTo(firstId.toString());
    }
    @Test void boundedCursorsDoNotSkipFactsOrLeakOtherOwnersIntoIncidentPages() {
        var aggregate=UUID.randomUUID();String actor="paged-"+key();
        tx.execute(s->{for(int i=0;i<51;i++)store.accept("fincore.account.created.v1",aggregate.toString(),AuditEventTest.event(UUID.randomUUID(),aggregate,Map.of()),0,1000+i);return null;});
        var first=json(store.facts(null,aggregate,"account.created"));assertThat(first.path("items")).hasSize(50);
        var second=json(store.facts(first.path("nextCursor").asLong(),aggregate,"account.created"));assertThat(second.path("items")).hasSize(1);assertThat(second.path("nextCursor").isNull()).isTrue();
        for(int i=0;i<51;i++)create(actor,key(),createRequest());
        create("foreign-paged-owner",key(),createRequest());
        var incidents=json(store.incidents(null,actor,false));assertThat(incidents.path("items")).hasSize(50);
        for(var row:incidents.path("items"))assertThat(row.path("createdBy").asText()).isEqualTo(actor);
        var tail=json(store.incidents(UUID.fromString(incidents.path("nextCursor").asText()),actor,false));assertThat(tail.path("items")).hasSize(1);
        assertThat(tail.path("items").get(0).path("createdBy").asText()).isEqualTo(actor);
        forbidden(()->store.facts(0L,null,null),400);forbidden(()->store.facts(null,null,"raw%query"),400);
    }
}
