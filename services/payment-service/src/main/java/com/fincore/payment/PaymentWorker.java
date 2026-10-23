package com.fincore.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fincore.support.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class PaymentWorker {
    private final PaymentStore store;private final PaymentCoordinator coordinator;private final RiskPolicy risk;private final boolean enabled;
    private UUID scanAfter;
    public PaymentWorker(PaymentStore store,PaymentCoordinator coordinator,RiskPolicy risk,@Value("${PAYMENT_WORKER_ENABLED:true}")boolean enabled){this.store=store;this.coordinator=coordinator;this.risk=risk;this.enabled=enabled;}
    @Scheduled(fixedDelayString="${PAYMENT_RECOVERY_DELAY_MS:1000}") public void tick(){if(enabled)cycle();}
    public void cycle(){
        try{
            try{
                var page=coordinator.ledger("GET","/instructions"+(scanAfter==null?"":"?after="+scanAfter),null);
                for(var receipt:page.path("items"))if(!store.exists(PaymentStore.uuid(receipt,"id")))store.importInstruction(receipt);
                scanAfter=page.path("nextCursor").isNull()?null:UUID.fromString(page.path("nextCursor").asText());
            }catch(ApiFailure ignored){/* Existing workflows remain recoverable while discovery is unavailable. */}
            for(int n=0;n<5;n++){
                var payment=store.claim();if(payment==null)break;
                try{process(payment);}catch(ApiFailure failure){store.retry(payment,failure.code);}catch(org.springframework.dao.DataAccessException ignored){/* Lease expires; another worker can recover. */}
            }
        }catch(org.springframework.dao.DataAccessException ignored){/* Readiness reports storage unavailability. */}
    }
    public void process(JsonNode payment){
        var id=PaymentStore.uuid(payment,"id");String status=payment.path("status").asText();
        if(status.equals("CREATED")){payment=store.move(payment,PaymentState.VALIDATING,null,null);status="VALIDATING";}
        if(status.equals("PROCESSING")){
            var saved=coordinator.ledger("GET","/instructions/"+id,null).path("outcome");
            if(!saved.isNull() && !saved.isMissingNode()){store.completed(payment,saved);return;}
        }
        var assessment=risk.assess(payment.path("kind").asText(),new BigDecimal(payment.path("amount").asText()),payment.path("currency").asText(),payment.path("approved").asBoolean());
        if(assessment.verdict()!=RiskPolicy.Verdict.ALLOW){
            if(status.equals("PROCESSING"))throw new ApiFailure(503,"RISK_REASSESSMENT_REQUIRED");
            payment=store.move(payment,assessment.verdict()==RiskPolicy.Verdict.REVIEW?PaymentState.PENDING_RISK_REVIEW:PaymentState.REJECTED,assessment.reason(),assessment);store.release(payment);return;
        }
        if(status.equals("VALIDATING"))payment=store.move(payment,PaymentState.PROCESSING,null,assessment);
        store.assessed(payment,assessment);
        var outcome=coordinator.ledger("POST","/instructions/"+id+"/execute",Map.of("assessmentExpiresAt",assessment.expiresAt().toString()));
        store.completed(payment,outcome);
    }
}
