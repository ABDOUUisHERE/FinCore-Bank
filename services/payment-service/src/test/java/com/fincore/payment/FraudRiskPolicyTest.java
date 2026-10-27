package com.fincore.payment;

import com.fincore.support.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FraudRiskPolicyTest {
 @Test void productionPolicySendsOnlyTheDurableIdentityAndValidatesTheReturnedGrant(){
  var remote=mock(Remote.class);var id=UUID.randomUUID();var assessment=UUID.randomUUID();when(remote.serviceToken()).thenReturn("memory-only-token");
  when(remote.call(eq("POST"),eq("http://fraud/internal/v1/risk-assessments"),eq("memory-only-token"),eq(Map.of("paymentId",id))))
   .thenReturn(Json.read(Json.text(Map.of("paymentId",id,"assessmentId",assessment,"rulesVersion",3,"verdict","ALLOW","expiresAt",Instant.now().plusSeconds(30).toString(),"reasons",List.of()))));
  var input=Json.read("{\"id\":\""+id+"\",\"approved\":true,\"actor\":\"ignored\"}");var result=new FraudRiskPolicy(remote,"http://fraud").assess(input);
  assertThat(result.reference()).isEqualTo(assessment);assertThat(result.verdict()).isEqualTo(RiskPolicy.Verdict.ALLOW);assertThat(result.ruleset()).isEqualTo("fraud-v3");
  verify(remote).call("POST","http://fraud/internal/v1/risk-assessments","memory-only-token",Map.of("paymentId",id));
 }
 @Test void unavailableFraudHasNoLocalAllowFallbackAndNoLedgerExecution(){
  var store=mock(PaymentStore.class);var coordinator=mock(PaymentCoordinator.class);var remote=mock(Remote.class);var id=UUID.randomUUID();
  var payment=Json.read("{\"id\":\""+id+"\",\"status\":\"VALIDATING\"}");when(remote.serviceToken()).thenReturn("memory-only-token");when(remote.call(anyString(),anyString(),anyString(),any())).thenThrow(new ApiFailure(503,"DEPENDENCY_UNAVAILABLE"));
  var worker=new PaymentWorker(store,coordinator,new FraudRiskPolicy(remote,"http://fraud"),false);
  assertThatThrownBy(()->worker.process(payment)).hasMessage("DEPENDENCY_UNAVAILABLE");verifyNoInteractions(coordinator,store);
 }
 @Test void anAlreadyPostedOutcomeIsRecoveredBeforeCallingUnavailableFraud(){
  var store=mock(PaymentStore.class);var coordinator=mock(PaymentCoordinator.class);var risk=mock(RiskPolicy.class);var id=UUID.randomUUID();
  var payment=Json.read("{\"id\":\""+id+"\",\"status\":\"PROCESSING\"}");var outcome=Json.read("{\"status\":\"POSTED\"}");when(coordinator.ledger("GET","/instructions/"+id,null)).thenReturn(Json.read("{\"outcome\":{\"status\":\"POSTED\"}}"));
  new PaymentWorker(store,coordinator,risk,false).process(payment);verify(store).completed(payment,outcome);verifyNoInteractions(risk);
 }
 @Test void expiredAndMismatchedGrantsCannotAuthorizeAnInstruction(){
  var remote=mock(Remote.class);var id=UUID.randomUUID();when(remote.serviceToken()).thenReturn("memory-only-token");var policy=new FraudRiskPolicy(remote,"http://fraud");var payment=Json.read("{\"id\":\""+id+"\"}");
  when(remote.call(anyString(),anyString(),anyString(),any())).thenReturn(Json.read(Json.text(Map.of("paymentId",id,"assessmentId",UUID.randomUUID(),"rulesVersion",1,"verdict","ALLOW","expiresAt",Instant.now().minusSeconds(1).toString()))));
  assertThatThrownBy(()->policy.assess(payment)).hasMessage("ASSESSMENT_EXPIRED");
  when(remote.call(anyString(),anyString(),anyString(),any())).thenReturn(Json.read(Json.text(Map.of("paymentId",UUID.randomUUID(),"assessmentId",UUID.randomUUID(),"rulesVersion",1,"verdict","ALLOW","expiresAt",Instant.now().plusSeconds(30).toString()))));
  assertThatThrownBy(()->policy.assess(payment)).hasMessage("INVALID_RISK_RESPONSE");
 }
}
