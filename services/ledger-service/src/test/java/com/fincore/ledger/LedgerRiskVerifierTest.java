package com.fincore.ledger;
import com.fincore.support.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LedgerRiskVerifierTest {
 @Test void verifiesTheExactInstructionAndUsesTheAuthoritativeDeadline(){
  var remote=mock(Remote.class);var id=UUID.randomUUID();var payment=UUID.randomUUID();String fingerprint="f".repeat(64);var expires=Instant.now().plusSeconds(30);when(remote.serviceToken()).thenReturn("memory-only-token");
  when(remote.call("POST","http://fraud/internal/v1/risk-assessments/"+id+"/validate","memory-only-token",Map.of("paymentId",payment,"fingerprint",fingerprint))).thenReturn(Json.read(Json.text(Map.of("assessmentId",id,"paymentId",payment,"fingerprint",fingerprint,"expiresAt",expires.toString()))));
  assertThat(new LedgerRiskVerifier(remote,"http://fraud").verify(id,payment,fingerprint)).isEqualTo(expires);
 }
 @Test void missingReferenceAndUnavailableFraudHaveNoPermissiveFallback(){
  var remote=mock(Remote.class);var verifier=new LedgerRiskVerifier(remote,"http://fraud");
  assertThatThrownBy(()->verifier.verify(null,UUID.randomUUID(),"f".repeat(64))).hasMessage("RISK_AUTHORIZATION_REQUIRED");verifyNoInteractions(remote);
  when(remote.serviceToken()).thenReturn("memory-only-token");when(remote.call(anyString(),anyString(),anyString(),any())).thenThrow(new ApiFailure(503,"DEPENDENCY_UNAVAILABLE"));
  assertThatThrownBy(()->verifier.verify(UUID.randomUUID(),UUID.randomUUID(),"f".repeat(64))).hasMessage("DEPENDENCY_UNAVAILABLE");
 }
 @Test void aGrantForAnotherPaymentCannotAuthorizeThisInstruction(){
  var remote=mock(Remote.class);var id=UUID.randomUUID();when(remote.serviceToken()).thenReturn("memory-only-token");
  when(remote.call(anyString(),anyString(),anyString(),any())).thenReturn(Json.read(Json.text(Map.of("assessmentId",id,"paymentId",UUID.randomUUID(),"fingerprint","f".repeat(64),"expiresAt",Instant.now().plusSeconds(30).toString()))));
  assertThatThrownBy(()->new LedgerRiskVerifier(remote,"http://fraud").verify(id,UUID.randomUUID(),"f".repeat(64))).hasMessage("RISK_BINDING_MISMATCH");
 }
}
