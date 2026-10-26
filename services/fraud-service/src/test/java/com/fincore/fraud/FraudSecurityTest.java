package com.fincore.fraud;

import com.fincore.support.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value=FraudController.class,properties="FRAUD_CLIENT_SECRET=synthetic-test-secret")
@Import({FraudConfiguration.class,FraudSecurityTest.Decoders.class})
class FraudSecurityTest {
 @Autowired MockMvc http;@MockitoBean FraudStore store;@MockitoBean FraudContextClient context;@MockitoBean Remote remote;
 @TestConfiguration(proxyBeanMethods=false)static class Decoders{
  @Bean @Primary JwtDecoder decoder()throws Exception{var d=NimbusJwtDecoder.withPublicKey(SignedTokens.KEY.toRSAPublicKey()).build();d.setJwtValidator(ServiceSecurity.validator(SignedTokens.ISSUER,Set.of("fincore-api","fraud-service")));return d;}
 }
 static String technical(String scope){return SignedTokens.token("fraud-service","worker","fincore-payment",List.of(),Map.of("fraud-service",Map.of("roles",List.of(scope))));}
 static String analyst(){return SignedTokens.token("fincore-api","signed-analyst","fincore-admin",List.of("FRAUD_ANALYST"),Map.of());}
 @Test void rejectsMissingWrongAudienceCustomerAndImplicitAdminAccess()throws Exception{
  http.perform(get("/api/v1/admin/fraud-cases")).andExpect(status().isUnauthorized());
  for(var token:List.of(SignedTokens.customer(),SignedTokens.token("fincore-api","admin","fincore-admin",List.of("ADMIN"),Map.of())))http.perform(get("/api/v1/admin/fraud-cases").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
  http.perform(get("/api/v1/admin/fraud-cases").header("Authorization","Bearer "+SignedTokens.token("account-service","analyst","browser",List.of("FRAUD_ANALYST"),Map.of()))).andExpect(status().isUnauthorized());
  verifyNoInteractions(store,context);
 }
 @Test void assessmentIdentityComesOnlyFromRequiredOwnerContext()throws Exception{
  var payment=UUID.randomUUID();var facts=Json.read("{\"canonical\":true}");when(context.context(payment)).thenReturn(facts);when(store.assess(facts)).thenReturn(Json.read("{\"verdict\":\"ALLOW\"}"));
  http.perform(post("/internal/v1/risk-assessments").header("Authorization","Bearer "+technical("fraud.assess")).contentType("application/json").content("{\"paymentId\":\""+payment+"\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.verdict").value("ALLOW"));
  verify(context).context(payment);verify(store).assess(facts);
  http.perform(post("/internal/v1/risk-assessments").header("Authorization","Bearer "+technical("fraud.assess")).contentType("application/json").content("{\"paymentId\":\""+payment+"\",\"actor\":\"forged\",\"reviewed\":true}")).andExpect(status().isBadRequest());
  verifyNoMoreInteractions(store,context);
 }
 @Test void technicalAssessorCannotInvestigateAndAnalystCannotImpersonateWorker()throws Exception{
  http.perform(post("/internal/v1/risk-assessments").header("Authorization","Bearer "+analyst()).contentType("application/json").content("{\"paymentId\":\""+UUID.randomUUID()+"\"}")).andExpect(status().isForbidden());
  http.perform(get("/api/v1/admin/fraud-cases").header("Authorization","Bearer "+technical("fraud.assess"))).andExpect(status().isForbidden());
  http.perform(post("/internal/v1/fraud/payments/"+UUID.randomUUID()+"/decision").header("Authorization","Bearer "+technical("fraud.assess")).contentType("application/json").content("{\"expectedVersion\":1,\"approve\":true,\"reason\":\"FORGED_APPROVAL\"}")).andExpect(status().isForbidden());
  verifyNoInteractions(store,context);
 }
 @Test void recordedDecisionReplayDoesNotNeedAnAvailablePaymentDependency()throws Exception{
  var id=UUID.randomUUID();var response=Json.read("{\"status\":\"APPROVED\"}");
  when(store.decisionReplay(id,"stable-request",1L,true,"REVIEW_CONFIRMED","signed-analyst")).thenReturn(response);
  http.perform(post("/api/v1/admin/fraud-cases/"+id+"/decisions").header("Authorization","Bearer "+analyst()).header("Idempotency-Key","stable-request").contentType("application/json").content("{\"expectedVersion\":1,\"approve\":true,\"reason\":\"REVIEW_CONFIRMED\"}")).andExpect(status().isOk());
  verifyNoInteractions(context);
 }
 @Test void analystDecisionUsesSignedActorAndRequiresCurrentPendingPaymentForApproval()throws Exception{
  var id=UUID.randomUUID();var payment=UUID.randomUUID();when(store.caseDetail(id)).thenReturn(Json.read("{\"paymentId\":\""+payment+"\"}"));
  when(context.payment(payment)).thenReturn(Json.read("{\"paymentStatus\":\"PENDING_RISK_REVIEW\"}"));when(store.decide(id,1,true,"REVIEW_CONFIRMED","signed-analyst","stable-request")).thenReturn(Json.read("{\"status\":\"APPROVED\"}"));
  http.perform(post("/api/v1/admin/fraud-cases/"+id+"/decisions").header("Authorization","Bearer "+analyst()).header("Idempotency-Key","stable-request").contentType("application/json").content("{\"expectedVersion\":1,\"approve\":true,\"reason\":\"REVIEW_CONFIRMED\"}")).andExpect(status().isOk());
  verify(store).decide(id,1,true,"REVIEW_CONFIRMED","signed-analyst","stable-request");
  when(context.payment(payment)).thenReturn(Json.read("{\"paymentStatus\":\"REJECTED\"}"));
  http.perform(post("/api/v1/admin/fraud-cases/"+id+"/decisions").header("Authorization","Bearer "+analyst()).header("Idempotency-Key","different-request").contentType("application/json").content("{\"expectedVersion\":1,\"approve\":true,\"reason\":\"REVIEW_CONFIRMED\"}")).andExpect(status().isConflict());
 }
}
