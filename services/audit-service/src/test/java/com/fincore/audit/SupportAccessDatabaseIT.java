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
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SupportAccessDatabaseIT {
    static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:17.11-alpine");
    static JdbcTemplate db,owner;static AuditStore audit;static SupportAccessStore grants;static TransactionTemplate tx;
    @BeforeAll static void setup() {
        PG.start();String password=UUID.randomUUID().toString();
        var admin=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()));
        for(String role:List.of("audit_migrator","audit_runtime"))admin.execute("create role "+role+" login password '"+password+"'");
        admin.execute("alter database test owner to audit_migrator");
        // A real additive upgrade with an incident written under V1.
        Flyway.configure().dataSource(PG.getJdbcUrl(),"audit_migrator",password).target("1").load().migrate();
        owner=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),"audit_migrator",password));
        db=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),"audit_runtime",password));
        audit=new AuditStore(db);tx=new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource()));
        UUID legacy=create("legacy-support");
        Flyway.configure().dataSource(PG.getJdbcUrl(),"audit_migrator",password).target("2").load().migrate();
        var v2=new SupportAccessStore(db,audit);String legacyKey=key();
        var legacyCommand=new SupportAccessStore.Command("0","ISSUE",60,"CONTROL_REVIEW");
        var legacyReceipt=tx.execute(s->v2.command(legacy,"admin",legacyKey,"test-correlation",legacyCommand));
        String oldFingerprint=AuditEvent.hash(Json.text(Arrays.asList("SUPPORT_ACCOUNT_GRANT",legacy,"0","ISSUE",60,"CONTROL_REVIEW")));
        assertThat(db.queryForObject("select fingerprint from incident_command where actor='admin' and key=?",String.class,legacyKey)).isEqualTo(oldFingerprint);
        Flyway.configure().dataSource(PG.getJdbcUrl(),"audit_migrator",password).load().migrate();
        assertThat(audit.incident(legacy,"legacy-support",false).path("status").asText()).isEqualTo("OPEN");
        JsonNode replayed=tx.execute(s->v2.command(legacy,"admin",legacyKey,"test-correlation",legacyCommand));
        assertThat(replayed).isEqualTo(legacyReceipt);
        assertThat(v2.current(legacy,"legacy-support",false).path("expiresAt")).isEqualTo(legacyReceipt.path("expiresAt"));
        // Exercise the same transaction boundaries as Spring's proxied production store.
        grants=new SupportAccessStore(db,audit) {
            @Override public Admission admit(UUID id,String actor,String correlation){return tx.execute(s->super.admit(id,actor,correlation));}
            @Override public Admission admit(UUID id,String actor,String correlation,Resource resource){return tx.execute(s->super.admit(id,actor,correlation,resource));}
            @Override public boolean finish(Admission a,String c,String outcome){return Boolean.TRUE.equals(tx.execute(s->super.finish(a,c,outcome)));}
        };
    }
    @AfterAll static void stop(){PG.stop();}
    static String key(){return UUID.randomUUID().toString();}
    static UUID create(String actor){return tx.execute(s->UUID.fromString(audit.create(actor,false,key(),"test-correlation",new AuditStore.Create("ACCOUNT",UUID.randomUUID(),"MEDIUM","CUSTOMER_REPORTED")).path("incidentId").asText()));}
    static UUID createPayment(String actor){return tx.execute(s->UUID.fromString(audit.create(actor,false,key(),"test-correlation",new AuditStore.Create("PAYMENT",UUID.randomUUID(),"MEDIUM","CUSTOMER_REPORTED")).path("incidentId").asText()));}
    static JsonNode paymentCommand(UUID id,String key,String version,String action,Integer minutes){return tx.execute(s->grants.command(id,"admin",key,"test-correlation",new SupportAccessStore.Command(version,action,minutes,"CONTROL_REVIEW"),SupportAccessStore.Resource.PAYMENT));}
    static JsonNode command(UUID id,String key,String version,String action,Integer minutes){return tx.execute(s->grants.command(id,"admin",key,"test-correlation",new SupportAccessStore.Command(version,action,minutes,"CONTROL_REVIEW")));}
    static JsonNode issue(UUID id){return command(id,key(),"0","ISSUE",15);}
    static void status(UUID id,String actor,long version,String status){tx.execute(s->audit.change(id,actor,false,key(),"test-correlation",new AuditStore.Change(version,status,"INVESTIGATION_UPDATE")));}
    static void expire(UUID id){owner.update("update support_account_grant set version=version+1,issued_at=clock_timestamp()-interval '1 hour',expires_at=clock_timestamp()-interval '30 minutes' where incident_id=?",id);}
    static JsonNode history(UUID id){return Json.read(Json.text(audit.history(id,null,"admin",true))).path("items");}

    @Test void immutableRecipientAndTargetAreInheritedFromTheIncidentAndForeignScopeIsDenied() {
        UUID id=create("support");var result=issue(id);
        assertThat(result.path("recipient").asText()).isEqualTo("support");
        assertThat(result.path("accountId")).isEqualTo(audit.incident(id,"support",false).path("resourceId"));
        assertThatThrownBy(()->grants.current(id,"foreign",false)).isInstanceOf(ApiFailure.class).extracting("status").isEqualTo(404);
        assertThat(grants.admit(id,"foreign","test-correlation")).isNull();
        assertThat(grants.admit(UUID.randomUUID(),"foreign","test-correlation")).isNull();
        assertThat(history(id).get(0).path("details").path("outcome").asText()).isEqualTo("FOREIGN_INCIDENT");
        assertThat(grants.admit(id,"support","test-correlation")).isNotNull();
    }
    @Test void anIncidentReferenceAloneCannotAuthorizeAccessAndExpiredGrantsFailClosed() {
        UUID id=create("support");assertThat(grants.current(id,"support",false).path("state").asText()).isEqualTo("NONE");
        assertThat(grants.admit(id,"support","test-correlation")).isNull();issue(id);expire(id);
        assertThat(grants.current(id,"support",false).path("state").asText()).isEqualTo("EXPIRED");
        assertThat(grants.admit(id,"support","test-correlation")).isNull();
    }
    @Test void originalCommandReceiptsSurviveRevocationButCannotBeReusedForAnotherCommand() {
        UUID id=create("support");String key=key();var first=command(id,key,"0","ISSUE",5);
        command(id,key(),"1","REVOKE",null);
        assertThat(command(id,key,"0","ISSUE",5)).isEqualTo(first);
        assertThat(grants.current(id,"support",false).path("state").asText()).isEqualTo("REVOKED");
        assertThatThrownBy(()->command(id,key,"0","ISSUE",15)).isInstanceOf(ApiFailure.class).hasMessage("IDEMPOTENCY_CONFLICT");
        assertThatThrownBy(()->tx.execute(s->audit.create("admin",true,key,"test-correlation",new AuditStore.Create("ACCOUNT",UUID.randomUUID(),"LOW","CONTROL_REVIEW"))))
            .isInstanceOf(ApiFailure.class).hasMessage("IDEMPOTENCY_CONFLICT");
        String incidentKey=key();tx.execute(s->audit.create("admin",true,incidentKey,"test-correlation",new AuditStore.Create("ACCOUNT",UUID.randomUUID(),"LOW","CONTROL_REVIEW")));
        assertThatThrownBy(()->command(id,incidentKey,"2","ISSUE",15)).isInstanceOf(ApiFailure.class).hasMessage("IDEMPOTENCY_CONFLICT");
    }
    @Test void resolutionAtomicallyRevokesAndReopeningRequiresANewExplicitGrant() {
        UUID id=create("support");issue(id);status(id,"support",0,"INVESTIGATING");
        assertThat(grants.admit(id,"support","test-correlation")).isNotNull();
        status(id,"support",1,"RESOLVED");
        var resolved=grants.current(id,"support",false);assertThat(resolved.path("state").asText()).isEqualTo("REVOKED");
        assertThat(resolved.path("reason").asText()).isEqualTo("INCIDENT_RESOLVED");
        assertThat(resolved.path("version").asText()).isEqualTo("2");
        assertThatThrownBy(()->command(id,key(),"2","ISSUE",15)).isInstanceOf(ApiFailure.class).hasMessage("INCIDENT_RESOLVED");
        status(id,"support",2,"INVESTIGATING");assertThat(grants.admit(id,"support","test-correlation")).isNull();
        command(id,key(),"2","ISSUE",15);assertThat(grants.admit(id,"support","test-correlation")).isNotNull();
    }
    @Test void expiryRevocationOrReplacementAfterAdmissionWithholdsDisclosure() {
        for(String change:List.of("EXPIRE","REVOKE","REISSUE")) {
            UUID id=create("support");issue(id);var a=grants.admit(id,"support","test-correlation");
            if(change.equals("EXPIRE"))expire(id);else command(id,key(),"1",change.equals("REVOKE")?"REVOKE":"ISSUE",change.equals("REVOKE")?null:15);
            assertThat(grants.finish(a,"test-correlation","DISCLOSED")).isFalse();
            assertThat(history(id).get(0).path("details").path("outcome").asText()).isNotEqualTo("DISCLOSED");
        }
    }
    @Test void concurrentAdministratorsCannotBothChangeTheSameGrantVersion() throws Exception {
        UUID id=create("support");
        try(var workers=Executors.newFixedThreadPool(2)) {
            var go=new CountDownLatch(1);List<Future<Boolean>> futures=new ArrayList<>();
            for(int i=0;i<2;i++)futures.add(workers.submit(()->{go.await();try{issue(id);return true;}catch(ApiFailure conflict){assertThat(conflict.code).isEqualTo("GRANT_VERSION_CONFLICT");return false;}}));
            go.countDown();int wins=0;for(var f:futures)if(f.get(20,TimeUnit.SECONDS))wins++;
            assertThat(wins).isEqualTo(1);assertThat(grants.current(id,"support",false).path("version").asText()).isEqualTo("1");
        }
    }
    @Test void receiptFailureRollsBackGrantAndAuditAndGrantIdentityCannotBeRewritten() {
        UUID id=create("support");String key=key();
        owner.execute("create function reject_grant_receipt() returns trigger language plpgsql as $$ begin if NEW.key='"+key+"' then raise exception 'INJECTED'; end if; return NEW; end $$");
        owner.execute("create trigger reject_grant_receipt before insert on incident_command for each row execute function reject_grant_receipt()");
        try {assertThatThrownBy(()->command(id,key,"0","ISSUE",15)).isInstanceOf(DataAccessException.class);}
        finally {owner.execute("drop trigger reject_grant_receipt on incident_command");owner.execute("drop function reject_grant_receipt()");}
        assertThat(grants.current(id,"support",false).path("version").asText()).isEqualTo("0");assertThat(history(id)).hasSize(1);
        issue(id);
        for(var connection:List.of(db,owner))for(String sql:List.of("delete from support_account_grant","truncate support_account_grant","update support_account_grant set incident_id='"+UUID.randomUUID()+"'","update support_account_grant set version=version+2"))
            assertThatThrownBy(()->connection.execute(sql)).isInstanceOf(DataAccessException.class);
    }
    @Test void remoteObservationHoldsNoDatabaseLockAndInFlightRevocationPreventsThePayload() {
        UUID id=create("support");issue(id);Remote remote=mock(Remote.class);when(remote.serviceToken()).thenReturn("private-token");
        when(remote.call(anyString(),anyString(),anyString(),isNull())).thenAnswer(call->{
            // This separate transaction would block if admission held the incident lock over HTTP.
            try(var worker=Executors.newSingleThreadExecutor()){worker.submit(()->command(id,key(),"1","REVOKE",null)).get(5,TimeUnit.SECONDS);}
            return SupportAccountAccessTest.source(UUID.fromString(audit.incident(id,"support",false).path("resourceId").asText()));
        });
        assertThatThrownBy(()->new SupportAccountAccess(grants,remote,"http://account").inspect(id,"support","test-correlation"))
            .isInstanceOf(ApiFailure.class).hasMessage("SUPPORT_ACCESS_UNAVAILABLE");
        assertThat(history(id).toString()).doesNotContain("private-token","recordedStatus","currency");
    }
    @Test void aFailedFinalAuditWriteCannotReleaseTheAccountObservation() {
        UUID id=create("support");issue(id);Remote remote=mock(Remote.class);when(remote.serviceToken()).thenReturn("private-token");
        owner.execute("create function reject_disclosure() returns trigger language plpgsql as $$ begin if NEW.details->>'outcome'='DISCLOSED' then raise exception 'INJECTED'; end if; return NEW; end $$");
        owner.execute("create trigger reject_disclosure before insert on audit_record for each row execute function reject_disclosure()");
        when(remote.call(anyString(),anyString(),anyString(),isNull())).thenReturn(SupportAccountAccessTest.source(UUID.fromString(audit.incident(id,"support",false).path("resourceId").asText())));
        try {assertThatThrownBy(()->new SupportAccountAccess(grants,remote,"http://account").inspect(id,"support","test-correlation")).isInstanceOf(DataAccessException.class);}
        finally {owner.execute("drop trigger reject_disclosure on audit_record");owner.execute("drop function reject_disclosure()");}
        assertThat(history(id).toString()).doesNotContain("DISCLOSED");
    }
    @Test void paymentAndAccountPoliciesNeverAuthorizeEachOthersResources() {
        var account=create("support");var payment=createPayment("support");issue(account);
        var result=paymentCommand(payment,key(),"0","ISSUE",15);
        assertThat(result.path("permission").asText()).isEqualTo("PAYMENT_STATUS_READ");
        assertThat(result.path("paymentId")).isEqualTo(audit.incident(payment,"support",false).path("resourceId"));
        assertThat(result.has("accountId")).isFalse();
        assertThat(grants.admit(payment,"support","c")).isNull();
        assertThat(grants.admit(account,"support","c",SupportAccessStore.Resource.PAYMENT)).isNull();
        assertThat(grants.admit(payment,"foreign","c",SupportAccessStore.Resource.PAYMENT)).isNull();
        assertThatThrownBy(()->grants.current(payment,"foreign",false,SupportAccessStore.Resource.PAYMENT)).isInstanceOf(ApiFailure.class).hasMessage("INCIDENT_NOT_FOUND");
        assertThatThrownBy(()->grants.current(account,"support",false,SupportAccessStore.Resource.PAYMENT)).isInstanceOf(ApiFailure.class).hasMessage("PAYMENT_INCIDENT_REQUIRED");
        assertThatThrownBy(()->paymentCommand(account,key(),"1","REVOKE",null)).isInstanceOf(ApiFailure.class).hasMessage("PAYMENT_INCIDENT_REQUIRED");
        var admission=grants.admit(payment,"support","c",SupportAccessStore.Resource.PAYMENT);
        assertThat(grants.finish(new SupportAccessStore.Admission(payment,UUID.randomUUID(),"support",admission.grantVersion(),admission.attemptId(),SupportAccessStore.Resource.PAYMENT),"c","DISCLOSED")).isFalse();
        assertThat(history(payment).get(0).path("details").path("outcome").asText()).isEqualTo("RESOURCE_MISMATCH");
    }
    @Test void paymentReceiptsShareActorGlobalKeysAndNeverReviveResolvedPermission() {
        var account=create("support");var payment=createPayment("support");String shared=key();command(account,shared,"0","ISSUE",15);
        assertThatThrownBy(()->paymentCommand(payment,shared,"0","ISSUE",15)).isInstanceOf(ApiFailure.class).hasMessage("IDEMPOTENCY_CONFLICT");
        assertThat(grants.current(payment,"support",false,SupportAccessStore.Resource.PAYMENT).path("version").asText()).isEqualTo("0");
        String issuedKey=key();var issued=paymentCommand(payment,issuedKey,"0","ISSUE",5);
        status(payment,"support",0,"INVESTIGATING");status(payment,"support",1,"RESOLVED");
        var revoked=grants.current(payment,"support",false,SupportAccessStore.Resource.PAYMENT);
        assertThat(revoked.path("state").asText()).isEqualTo("REVOKED");assertThat(revoked.path("version").asText()).isEqualTo("2");
        assertThat(revoked.path("reason").asText()).isEqualTo("INCIDENT_RESOLVED");
        assertThat(paymentCommand(payment,issuedKey,"0","ISSUE",5)).isEqualTo(issued);
        status(payment,"support",2,"INVESTIGATING");assertThat(grants.admit(payment,"support","c",SupportAccessStore.Resource.PAYMENT)).isNull();
        paymentCommand(payment,key(),"2","ISSUE",15);assertThat(grants.admit(payment,"support","c",SupportAccessStore.Resource.PAYMENT)).isNotNull();
    }
    @Test void concurrentPaymentGrantCommandsHaveOneWinnerAndCannotRewriteIdentity() throws Exception {
        UUID id=createPayment("support");
        try(var workers=Executors.newFixedThreadPool(2)) {
            var go=new CountDownLatch(1);List<Future<Boolean>> futures=new ArrayList<>();
            for(int i=0;i<2;i++)futures.add(workers.submit(()->{go.await();try{paymentCommand(id,key(),"0","ISSUE",15);return true;}catch(ApiFailure conflict){assertThat(conflict.code).isEqualTo("GRANT_VERSION_CONFLICT");return false;}}));
            go.countDown();int winners=0;for(var f:futures)if(f.get(20,TimeUnit.SECONDS))winners++;assertThat(winners).isEqualTo(1);
        }
        for(var connection:List.of(db,owner))for(String sql:List.of("delete from support_payment_grant","truncate support_payment_grant","update support_payment_grant set version=version+2","update support_payment_grant set incident_id='"+UUID.randomUUID()+"'"))
            assertThatThrownBy(()->connection.execute(sql)).isInstanceOf(DataAccessException.class);
    }
    @Test void paymentDisclosureRechecksExpiryReplacementRevocationAndResolution() {
        for(String action:List.of("EXPIRE","REPLACE","REVOKE","RESOLVE")) {
            UUID id=createPayment("support");paymentCommand(id,key(),"0","ISSUE",15);status(id,"support",0,"INVESTIGATING");
            var admission=grants.admit(id,"support","c",SupportAccessStore.Resource.PAYMENT);
            if(action.equals("EXPIRE"))owner.update("update support_payment_grant set version=version+1,issued_at=clock_timestamp()-interval '1 hour',expires_at=clock_timestamp()-interval '30 minutes' where incident_id=?",id);
            else if(action.equals("RESOLVE"))status(id,"support",1,"RESOLVED");
            else paymentCommand(id,key(),"1",action.equals("REPLACE")?"ISSUE":"REVOKE",action.equals("REPLACE")?15:null);
            assertThat(grants.finish(admission,"c","DISCLOSED")).isFalse();
            assertThat(history(id).get(0).path("details").path("outcome").asText()).isNotEqualTo("DISCLOSED");
        }
    }
    @Test void failedResolutionReceiptRollsBackPaymentGrantRevocationTogetherWithIncidentState() {
        UUID id=createPayment("support");paymentCommand(id,key(),"0","ISSUE",15);status(id,"support",0,"INVESTIGATING");String command=key();
        owner.execute("create function reject_payment_resolution() returns trigger language plpgsql as $$ begin if NEW.key='"+command+"' then raise exception 'INJECTED'; end if; return NEW; end $$");
        owner.execute("create trigger reject_payment_resolution before insert on incident_command for each row execute function reject_payment_resolution()");
        try {assertThatThrownBy(()->tx.execute(s->audit.change(id,"support",false,command,"c",new AuditStore.Change(1L,"RESOLVED","ISSUE_RESOLVED")))).isInstanceOf(DataAccessException.class);}
        finally {owner.execute("drop trigger reject_payment_resolution on incident_command");owner.execute("drop function reject_payment_resolution()");}
        assertThat(audit.incident(id,"support",false).path("status").asText()).isEqualTo("INVESTIGATING");
        assertThat(grants.current(id,"support",false,SupportAccessStore.Resource.PAYMENT).path("state").asText()).isEqualTo("ACTIVE");
        assertThat(grants.admit(id,"support","c",SupportAccessStore.Resource.PAYMENT)).isNotNull();
    }
}
