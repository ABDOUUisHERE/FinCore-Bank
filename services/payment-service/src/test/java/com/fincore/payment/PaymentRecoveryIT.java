package com.fincore.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fincore.support.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"PAYMENT_CLIENT_SECRET=synthetic-test-secret","PAYMENT_WORKER_ENABLED=false"})
@org.springframework.context.annotation.Import(PaymentRecoveryIT.IsolatedRiskPort.class)
class PaymentRecoveryIT {
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false) static class IsolatedRiskPort {
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary RiskPolicy isolatedRisk(){return new RiskPolicy.Local();}
    }
    static final DatabaseLab LAB=new DatabaseLab();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry values){values.add("spring.datasource.url",()->LAB.url("payment"));values.add("spring.datasource.password",()->LAB.password("payment","runtime"));}
    @Autowired PaymentStore store;
    @Autowired PaymentCoordinator coordinator;
    @Autowired PaymentWorker worker;
    @Autowired RiskPolicy risk;
    @MockitoBean Remote remote;
    AtomicBoolean discover=new AtomicBoolean(),loseResponse=new AtomicBoolean();
    static final String ACTOR="signed-customer-subject";
    @BeforeEach void network(){
        reset(remote);discover.set(false);loseResponse.set(false);when(remote.serviceToken()).thenReturn("memory-only-test-service-token");
        when(remote.call(anyString(),anyString(),anyString(),any())).thenAnswer(call->{
            String method=call.getArgument(0),url=call.getArgument(1);Object body=call.getArgument(3);
            if(method.equals("GET") && url.endsWith("/instructions")){
                var page=new LinkedHashMap<String,Object>();page.put("items",discover.get()?LAB.runtime("ledger").query("select to_jsonb(i)::text from ledger.instruction i where issuer='fincore-payment' order by id",(rs,n)->Json.read(rs.getString(1))):List.of());page.put("nextCursor",null);return Json.read(Json.text(page));
            }
            var id=UUID.fromString(url.substring(url.lastIndexOf("/instructions/")+14).split("/")[0]);
            if(method.equals("GET"))return Json.read(LAB.runtime("ledger").queryForObject("select (to_jsonb(i)||jsonb_build_object('outcome',o.response))::text from ledger.instruction i left join ledger.operation o on o.issuer=i.issuer and o.business_reference=i.id::text where i.id=?",String.class,id));
            var expires=((Map<?,?>)body).get("assessmentExpiresAt");var result=execute(id,expires.toString());
            if(loseResponse.compareAndSet(true,false))throw new ApiFailure(503,"DEPENDENCY_UNAVAILABLE");return result;
        });
    }
    JsonNode execute(UUID id,String expires){return Json.read(LAB.runtime("ledger").queryForObject("select ledger.execute_instruction('fincore-payment',?,?::timestamptz)::text",String.class,id,expires));}
    UUID account(String type){return UUID.fromString(Json.read(LAB.runtime("ledger").queryForObject("select ledger.provision(?,'MAD',?,?)::text",String.class,UUID.randomUUID().toString(),type,ACTOR)).path("id").asText());}
    record Fixture(UUID id,UUID source,UUID destination,JsonNode receipt) {}
    Fixture transfer(String amount){
        UUID source=account("CUSTOMER_LIABILITY"),destination=account("CUSTOMER_LIABILITY"),settlement=account("SETTLEMENT_ASSET"),id=UUID.randomUUID();
        var funding=Map.of("kind","DEPOSIT","businessReference",UUID.randomUUID(),"currency","MAD","entries",List.of(Map.of("accountId",settlement,"side","DEBIT","amount","100.00"),Map.of("accountId",source,"side","CREDIT","amount","100.00")));
        LAB.runtime("ledger").queryForObject("select ledger.post('test-funding',?,?,?::jsonb)::text",String.class,ACTOR,UUID.randomUUID().toString(),Json.text(funding));
        var metadata=new LinkedHashMap<String,Object>();metadata.put("sourceAccountId",UUID.randomUUID());metadata.put("destinationAccountId",UUID.randomUUID());metadata.put("amount",amount);metadata.put("currency","MAD");metadata.put("reference","Synthetic transfer");metadata.put("originalPaymentId",null);
        var receipt=Json.read(LAB.runtime("ledger").queryForObject("select ledger.authorize_instruction(?,'fincore-payment',?,?,'TRANSFER',?,?,?,'MAD',?::jsonb,null)::text",String.class,id,ACTOR,UUID.randomUUID().toString(),source,destination,amount,Json.text(metadata)));
        return new Fixture(id,source,destination,receipt);
    }
    String balance(UUID id){return LAB.runtime("ledger").queryForObject("select booked::text from ledger.balance where account_id=?",String.class,id);}
    void readyRetry(UUID id){LAB.owner("payment").update("update payment set lease_until=clock_timestamp()-interval '1 second',next_attempt=clock_timestamp() where id=?",id);}
    long operations(UUID id){return LAB.runtime("ledger").queryForObject("select count(*) from ledger.operation where issuer='fincore-payment' and business_reference=?",Long.class,id.toString());}
    @Test void recoversAuthorizationAcceptedBeforePaymentPersistence(){var f=transfer("10.00");assertThat(store.exists(f.id())).isFalse();discover.set(true);worker.cycle();assertThat(store.get(f.id()).path("status").asText()).isEqualTo("COMPLETED");assertThat(operations(f.id())).isEqualTo(1);assertThat(balance(f.source())).isEqualTo("90.00");}
    @Test void lostResponseAfterLedgerCommitNeverDuplicatesMoney(){
        var f=transfer("10.00");store.importInstruction(f.receipt());loseResponse.set(true);worker.cycle();
        assertThat(store.get(f.id()).path("status").asText()).isEqualTo("PROCESSING");assertThat(operations(f.id())).isEqualTo(1);readyRetry(f.id());
        new PaymentWorker(store,coordinator,risk,false).cycle();assertThat(store.get(f.id()).path("status").asText()).isEqualTo("COMPLETED");assertThat(operations(f.id())).isEqualTo(1);assertThat(balance(f.destination())).isEqualTo("10.00");
    }
    @Test void restartBeforeLedgerCallResumesSameInstruction(){
        var f=transfer("10.00");store.importInstruction(f.receipt());var job=store.claim();job=store.move(job,PaymentState.VALIDATING,null,null);store.move(job,PaymentState.PROCESSING,null,risk.assess("TRANSFER",new java.math.BigDecimal("10"),"MAD",false));readyRetry(f.id());
        new PaymentWorker(store,coordinator,risk,false).cycle();assertThat(store.get(f.id()).path("status").asText()).isEqualTo("COMPLETED");assertThat(operations(f.id())).isEqualTo(1);
    }
    @Test void crashAfterLedgerResponseBeforePaymentCommitUsesLookup(){
        var f=transfer("10.00");store.importInstruction(f.receipt());var job=store.claim();job=store.move(job,PaymentState.VALIDATING,null,null);store.move(job,PaymentState.PROCESSING,null,risk.assess("TRANSFER",new java.math.BigDecimal("10"),"MAD",false));execute(f.id(),java.time.Instant.now().plusSeconds(30).toString());readyRetry(f.id());
        new PaymentWorker(store,coordinator,risk,false).cycle();assertThat(store.get(f.id()).path("status").asText()).isEqualTo("COMPLETED");assertThat(operations(f.id())).isEqualTo(1);
    }
    @Test void staleLeaseCannotAdvanceWorkflow(){
        var f=transfer("10.00");store.importInstruction(f.receipt());var old=store.claim();readyRetry(f.id());var current=store.claim();
        assertThat(old.path("lease_token")).isNotEqualTo(current.path("lease_token"));assertThatThrownBy(()->store.move(old,PaymentState.VALIDATING,null,null)).isInstanceOf(ApiFailure.class).hasMessage("STALE_PAYMENT_LEASE");
        worker.process(current);assertThat(store.get(f.id()).path("status").asText()).isEqualTo("COMPLETED");
    }
    @Test void cancellationAndWorkerRaceCannotProduceCancelledMoney()throws Exception{
        var f=transfer("10.00");store.importInstruction(f.receipt());var job=store.claim();var gate=new CyclicBarrier(2);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var a=pool.submit(()->{gate.await();try{worker.process(job);}catch(ApiFailure expected){}return true;});
            var b=pool.submit(()->{gate.await();try{store.cancel(f.id(),ACTOR,0);}catch(ApiFailure expected){}return true;});a.get(20,TimeUnit.SECONDS);b.get(20,TimeUnit.SECONDS);
        }
        String status=store.get(f.id()).path("status").asText();assertThat(status).isIn("COMPLETED","REJECTED");assertThat(balance(f.source())).isEqualTo(status.equals("COMPLETED")?"90.00":"100.00");
    }
    @Test void reviewBlockAndCancellationCreateNoJournal(){
        var review=transfer("20000.00");store.importInstruction(review.receipt());worker.cycle();assertThat(store.get(review.id()).path("status").asText()).isEqualTo("PENDING_RISK_REVIEW");assertThat(operations(review.id())).isZero();
        var current=store.get(review.id());store.cancel(review.id(),ACTOR,current.path("version").asLong());assertThat(store.get(review.id()).path("terminal_code").asText()).isEqualTo("CANCELLED");
        var block=transfer("100000.01");store.importInstruction(block.receipt());worker.cycle();assertThat(store.get(block.id()).path("status").asText()).isEqualTo("REJECTED");assertThat(operations(block.id())).isZero();
    }
    @Test void stateConstraintsAndAppendOnlyFactsProtectWorkflow(){
        var f=transfer("10.00");store.importInstruction(f.receipt());
        assertThatThrownBy(()->LAB.runtime("payment").update("update payment set status='COMPLETED',version=version+1,journal_id=gen_random_uuid() where id=?",f.id())).hasStackTraceContaining("INVALID_PAYMENT_TRANSITION");
        assertThatThrownBy(()->LAB.runtime("payment").update("update payment set amount=20 where id=?",f.id())).hasStackTraceContaining("IMMUTABLE_PAYMENT_INTENT");
        assertThatThrownBy(()->LAB.runtime("payment").execute("delete from payment_status_history")).hasStackTraceContaining("permission denied");
        worker.cycle();var p=store.get(f.id());assertThat(store.history(f.id())).hasSize((int)p.path("version").asLong()+1);
        assertThat(LAB.runtime("payment").queryForObject("select count(*) from payment_outbox where payment_id=?",Long.class,f.id())).isEqualTo(p.path("version").asLong()+1);
        assertThatThrownBy(()->store.own(f.id(),"another-customer")).hasMessage("PAYMENT_NOT_FOUND");
    }
    @AfterAll static void close(){LAB.close();}
    @Test void filteredTransferListUsesOwnedChronologicalCursors(){
        var f=transfer("1.00");store.importInstruction(f.receipt());store.cancel(f.id(),ACTOR,0);
        assertThat(store.list(ACTOR,null,"REJECTED")).anySatisfy(row->assertThat(row.path("id").asText()).isEqualTo(f.id().toString()));
        assertThat(store.list("another-actor",null,"REJECTED")).isEmpty();
        assertThat(store.list(ACTOR,UUID.randomUUID(),"")).isEmpty();
        assertThatThrownBy(()->store.list(ACTOR,null,"invented")).isInstanceOf(ApiFailure.class).hasMessage("INVALID_PAYMENT_FILTER");
    }

}
