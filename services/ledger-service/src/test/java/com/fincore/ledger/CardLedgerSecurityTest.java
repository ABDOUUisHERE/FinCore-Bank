package com.fincore.ledger;

import com.fincore.ledger.configuration.*;
import com.fincore.support.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({CardLedgerController.class,StatementController.class})
@Import({SecurityConfiguration.class,JsonConfiguration.class,CorrelationFilter.class,BodyLimitFilter.class,CardLedgerSecurityTest.Decoders.class})
class CardLedgerSecurityTest {
 @Autowired MockMvc http;@MockitoBean CardLedgerStore store;@MockitoBean LedgerRiskVerifier risk;
 @MockitoBean StatementStore statements;@MockitoBean LedgerStore ledger;
 @TestConfiguration(proxyBeanMethods=false)static class Decoders{
  @Bean @Primary JwtDecoder primary()throws Exception{var d=NimbusJwtDecoder.withPublicKey(LedgerSecurityTest.KEY.toRSAPublicKey()).build();d.setJwtValidator(SecurityConfiguration.validator(LedgerSecurityTest.ISSUER,"ledger-service"));return d;}
 }
 String token(String client,boolean customer,String...roles){var claims=JwtClaimsSet.builder().issuer(LedgerSecurityTest.ISSUER).subject("signed-owner").audience(List.of("ledger-service")).issuedAt(Instant.now().minusSeconds(10)).expiresAt(Instant.now().plusSeconds(120)).claim("azp",client).claim("realm_access",Map.of("roles",customer?List.of("CUSTOMER"):List.of())).claim("resource_access",Map.of("ledger-service",Map.of("roles",List.of(roles)))).build();return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(LedgerSecurityTest.KEY))).encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(),claims)).getTokenValue();}
 String create(){return Json.text(Map.of("id",Identifiers.stable("card","signed-owner","key"),"key","key","ledgerAccountId",UUID.randomUUID(),"transactionLimit","50.00","dailyLimit","100.00"));}
 @Test void cardWorkerCannotCreateOrSettleCustomerCommands()throws Exception{
  for(String role:List.of("ledger.card.inspect","ledger.card.reserve","ledger.card.manage"))http.perform(post("/internal/v1/ledger/cards").header("Authorization","Bearer "+token("fincore-card",false,role)).contentType("application/json").content(create())).andExpect(status().isForbidden());
  http.perform(post("/internal/v1/ledger/card-authorizations/"+UUID.randomUUID()+"/settle").header("Authorization","Bearer "+token("fincore-card",false,"ledger.card.reserve")).contentType("application/json").content("{\"key\":\"key\",\"action\":\"CAPTURE\"}")).andExpect(status().isForbidden());verifyNoInteractions(store,risk);
 }
 @Test void realmRoleAndGenericLedgerReadDoNotGrantCardInspectionOrManagement()throws Exception{
  http.perform(get("/internal/v1/ledger/cards").header("Authorization","Bearer "+token("fincore-payment",true,"ledger.read"))).andExpect(status().isForbidden());
  http.perform(post("/internal/v1/ledger/cards").header("Authorization","Bearer "+token("fincore-customer",true,"ledger.card.manage")).contentType("application/json").content(create())).andExpect(status().isForbidden());verifyNoInteractions(store,risk);
 }
 @Test void actorIsSignedAndSyntheticIdentityIsBoundToThatActorAndKey()throws Exception{
  when(store.create(any(),eq("signed-owner"),eq("key"),any(),eq("50.00"),eq("100.00"))).thenReturn(Json.read("{\"status\":\"ACTIVE\"}"));
  http.perform(post("/internal/v1/ledger/cards").header("Authorization","Bearer "+token("fincore-card",true,"ledger.card.manage")).header("X-User-ID","forged").contentType("application/json").content(create())).andExpect(status().isOk());
  verify(store).create(eq(Identifiers.stable("card","signed-owner","key")),eq("signed-owner"),eq("key"),any(),eq("50.00"),eq("100.00"));
  http.perform(post("/internal/v1/ledger/cards").header("Authorization","Bearer "+token("fincore-card",true,"ledger.card.manage")).contentType("application/json").content(create().replace(Identifiers.stable("card","signed-owner","key").toString(),UUID.randomUUID().toString()))).andExpect(status().isBadRequest());
 }
 @Test void reserveUsesTheVerifiedCanonicalGrantAndFailsClosed()throws Exception{
  var id=UUID.randomUUID();var assessment=UUID.randomUUID();var expiry=Instant.now().plusSeconds(20);var receipt=Json.read("{\"status\":\"AUTHORIZING\",\"fingerprint\":\""+"f".repeat(64)+"\"}");when(store.authorization(id)).thenReturn(receipt);when(risk.verify(assessment,id,"f".repeat(64))).thenReturn(expiry);when(store.reserve(id,assessment,expiry)).thenReturn(Json.read("{\"status\":\"HELD\"}"));
  http.perform(post("/internal/v1/ledger/card-authorizations/"+id+"/reserve").header("Authorization","Bearer "+token("fincore-card",false,"ledger.card.reserve")).contentType("application/json").content(Json.text(Map.of("assessmentId",assessment)))).andExpect(status().isOk());verify(store).reserve(id,assessment,expiry);
  reset(store,risk);when(store.authorization(id)).thenReturn(receipt);when(risk.verify(assessment,id,"f".repeat(64))).thenThrow(new ApiFailure(503,"DEPENDENCY_UNAVAILABLE"));
  http.perform(post("/internal/v1/ledger/card-authorizations/"+id+"/reserve").header("Authorization","Bearer "+token("fincore-card",false,"ledger.card.reserve")).contentType("application/json").content(Json.text(Map.of("assessmentId",assessment)))).andExpect(status().isServiceUnavailable());verify(store,never()).reserve(any(),any(),any());
 }
 @Test void alreadyHeldReceiptIsRecoverableDuringFraudOutage()throws Exception{
  var id=UUID.randomUUID();when(store.authorization(id)).thenReturn(Json.read("{\"status\":\"HELD\"}"));http.perform(post("/internal/v1/ledger/card-authorizations/"+id+"/reserve").header("Authorization","Bearer "+token("fincore-card",false,"ledger.card.reserve")).contentType("application/json").content("{}")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("HELD"));verifyNoInteractions(risk);
 }
 @Test void statementReadsRequireTheDelegatedOwnerAndNarrowReportingScope()throws Exception{
  var id=UUID.randomUUID();String path="/internal/v1/ledger/reports/accounts/"+id+"/statement?month=2026-01";
  for(String invalid:List.of(token("fincore-reporting",false,"ledger.statement.read"),token("fincore-customer",true,"ledger.statement.read"),token("fincore-reporting",true,"ledger.read")))http.perform(get(path).header("Authorization","Bearer "+invalid)).andExpect(status().isForbidden());
  verifyNoInteractions(statements);
  when(statements.statement(id,"signed-owner","2026-01")).thenReturn(Json.read("{\"reconciled\":true}"));http.perform(get(path).header("Authorization","Bearer "+token("fincore-reporting",true,"ledger.statement.read")).header("X-User-ID","forged")).andExpect(status().isOk());verify(statements).statement(id,"signed-owner","2026-01");
 }
 @Test void reportingMachineReconciliationDoesNotGrantCustomerStatements()throws Exception{
  when(ledger.reconcile()).thenReturn(Json.read("{\"balanceDiscrepancies\":[],\"invalidJournals\":[]}"));
  http.perform(get("/internal/v1/ledger/reports/reconciliation").header("Authorization","Bearer "+token("fincore-reporting",false,"ledger.reconcile.read"))).andExpect(status().isOk());
  http.perform(get("/internal/v1/ledger/reports/reconciliation").header("Authorization","Bearer "+token("fincore-card",false,"ledger.reconcile.read"))).andExpect(status().isForbidden());
  http.perform(get("/internal/v1/ledger/reports/accounts/"+UUID.randomUUID()+"/statement?month=2026-01").header("Authorization","Bearer "+token("fincore-reporting",false,"ledger.reconcile.read"))).andExpect(status().isForbidden());verifyNoInteractions(statements);
 }
}
