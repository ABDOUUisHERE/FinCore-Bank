package com.fincore.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fincore.support.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"ACCOUNT_CLIENT_SECRET=synthetic-test-secret","ACCOUNT_RECOVERY_ENABLED=false"})
class AccountRecoveryIT {
    static final DatabaseLab LAB=new DatabaseLab();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry values){values.add("spring.datasource.url",()->LAB.url("account"));values.add("spring.datasource.password",()->LAB.password("account","runtime"));}
    @Autowired AccountStore store;
    @Autowired AccountCoordinator coordinator;
    @MockitoBean Remote remote;
    AtomicBoolean lose=new AtomicBoolean();
    @BeforeEach void network(){
        reset(remote);lose.set(false);when(remote.serviceToken()).thenReturn("memory-only-service-test-token");
        when(remote.call(anyString(),anyString(),anyString(),any())).thenAnswer(call->{
            String method=call.getArgument(0),url=call.getArgument(1);Map<?,?> body=call.getArgument(3);String result;
            if(method.equals("GET")){
                var id=UUID.fromString(url.substring(url.lastIndexOf('/')+1));result=LAB.runtime("ledger").queryForObject("select to_jsonb(a)::text from ledger.account a where id=?",String.class,id);
            }else if(method.equals("POST"))result=LAB.runtime("ledger").queryForObject("select ledger.provision(?,?,?,?)::text",String.class,body.get("externalReference"),body.get("currency"),body.get("type"),body.get("ownerSubject"));
            else{
                var id=UUID.fromString(url.substring(url.lastIndexOf("/accounts/")+10).split("/")[0]);
                result=LAB.runtime("ledger").queryForObject("select ledger.set_guard(?,?,?)::text",String.class,id,body.get("status"),body.get("expectedRevision"));
            }
            if(lose.compareAndSet(true,false) && !method.equals("GET"))throw new ApiFailure(503,"DEPENDENCY_UNAVAILABLE");return Json.read(result);
        });
    }
    @Test void lostProvisioningResponseRecoversWithoutAnotherLedgerAccount(){
        String subject=UUID.randomUUID().toString(),key=UUID.randomUUID().toString();UUID customer=UUID.randomUUID();
        var account=store.create(subject,customer,"MAD",key);lose.set(true);
        assertThatThrownBy(()->coordinator.sync(account)).hasMessage("DEPENDENCY_UNAVAILABLE");assertThat(store.get(account.id()).status()).isEqualTo("PROVISIONING");
        new AccountCoordinator(store,remote,"http://localhost:8082").sync(store.get(account.id()));
        var active=store.get(account.id());assertThat(active.status()).isEqualTo("ACTIVE");assertThat(active.ledgerId()).isNotNull();
        assertThat(store.create(subject,customer,"MAD",key).id()).isEqualTo(account.id());
        assertThat(LAB.runtime("ledger").queryForObject("select count(*) from ledger.account where external_reference=?",Long.class,"account:"+account.id())).isEqualTo(1);
        assertThatThrownBy(()->store.create(subject,customer,"EUR",key)).hasMessage("IDEMPOTENCY_CONFLICT");
    }
    @Test void lostRestrictionResponseReconcilesRevisionWithoutFlippingStatus(){
        String subject=UUID.randomUUID().toString();var before=store.create(subject,UUID.randomUUID(),"MAD",UUID.randomUUID().toString());coordinator.sync(before);var active=store.get(before.id());
        var pending=store.guard(active.id(),subject,"FROZEN",active.version());
        // Inject failure after the ledger writes its guard, not during the preceding read.
        doAnswer(call->{var body=(Map<?,?>)call.getArgument(3);var result=LAB.runtime("ledger").queryForObject("select ledger.set_guard(?,?,?)::text",String.class,active.ledgerId(),body.get("status"),body.get("expectedRevision"));throw new ApiFailure(503,"DEPENDENCY_UNAVAILABLE");}).when(remote).call(eq("PUT"),anyString(),anyString(),any());
        assertThatThrownBy(()->coordinator.sync(pending)).hasMessage("DEPENDENCY_UNAVAILABLE");assertThat(store.get(active.id()).status()).isEqualTo("GUARD_PENDING");
        coordinator.sync(store.get(active.id()));assertThat(store.get(active.id()).status()).isEqualTo("FROZEN");assertThat(store.get(active.id()).guardRevision()).isEqualTo(1);
        assertThatThrownBy(()->store.guard(active.id(),subject,"ACTIVE",active.version())).hasMessage("ACCOUNT_VERSION_CONFLICT");
    }
    @Test void ownershipAndRuntimeDatabaseIsolationAreIndependentOfGateway(){
        String subject=UUID.randomUUID().toString();var account=store.create(subject,UUID.randomUUID(),"EUR",UUID.randomUUID().toString());
        assertThatThrownBy(()->store.own(account.id(),"another-subject")).hasMessage("ACCOUNT_NOT_FOUND");assertThat(store.list("another-subject",null)).isEmpty();
        assertThatThrownBy(()->java.sql.DriverManager.getConnection(LAB.url("ledger"),"account_runtime",LAB.password("account","runtime"))).isInstanceOf(java.sql.SQLException.class).hasMessageContaining("permission denied");
        assertThatThrownBy(()->LAB.runtime("account").execute("delete from account_outbox")).hasStackTraceContaining("permission denied");
    }
    @AfterAll static void close(){LAB.close();}
}
