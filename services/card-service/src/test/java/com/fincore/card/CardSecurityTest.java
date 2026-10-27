package com.fincore.card;

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

@WebMvcTest(value=CardController.class,properties="CARD_CLIENT_SECRET=synthetic-test-secret")
@Import({CardConfiguration.class,CardSecurityTest.Decoders.class})
class CardSecurityTest {
 @Autowired MockMvc http;@MockitoBean CardCoordinator cards;@MockitoBean CardStore store;@MockitoBean Remote remote;
 @TestConfiguration(proxyBeanMethods=false)static class Decoders{@Bean @Primary JwtDecoder decoder()throws Exception{var d=NimbusJwtDecoder.withPublicKey(SignedTokens.KEY.toRSAPublicKey()).build();d.setJwtValidator(ServiceSecurity.validator(SignedTokens.ISSUER,Set.of("fincore-api","card-service")));return d;}}
 @Test void publicCardsRequireCustomerAndPrivateRiskReadRequiresIndependentScope()throws Exception{
  http.perform(get("/api/v1/cards")).andExpect(status().isUnauthorized());
  for(String token:List.of(SignedTokens.token("fincore-api","admin","browser",List.of("ADMIN"),Map.of()),SignedTokens.token("card-service","worker","fincore-card",List.of(),Map.of())))http.perform(get("/api/v1/cards").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
  http.perform(get("/internal/v1/cards/"+UUID.randomUUID()+"/risk-context").header("Authorization","Bearer "+SignedTokens.customer())).andExpect(status().isForbidden());verifyNoInteractions(cards,store);
 }
 @Test void canonicalRiskReadDoesNotAcceptBrowserSuppliedFacts()throws Exception{var id=UUID.randomUUID();var token=SignedTokens.token("card-service","fraud","fincore-fraud",List.of(),Map.of("card-service",Map.of("roles",List.of("card.risk.read"))));when(store.risk(id)).thenReturn(Json.read("{\"kind\":\"MERCHANT_PAYMENT\"}"));http.perform(get("/internal/v1/cards/"+id+"/risk-context").header("Authorization","Bearer "+token)).andExpect(status().isOk());verify(store).risk(id);}
 @Test void customerCommandsTakeTheirActorFromTheSignedTokenAndRejectForgedFields()throws Exception{
  var id=UUID.randomUUID();when(cards.create(eq("verified-customer"),anyString(),eq("same-key"),any())).thenReturn(Json.read("{\"status\":\"ACTIVE\"}"));String body=Json.text(Map.of("accountId",id,"transactionLimit","50.00","dailyLimit","100.00"));http.perform(post("/api/v1/cards").header("Authorization","Bearer "+SignedTokens.customer()).header("X-User-ID","forged").header("Idempotency-Key","same-key").contentType("application/json").content(body)).andExpect(status().isOk());verify(cards).create(eq("verified-customer"),anyString(),eq("same-key"),any());
  http.perform(post("/api/v1/cards").header("Authorization","Bearer "+SignedTokens.customer()).header("Idempotency-Key","same-key").contentType("application/json").content(body.replace("\"accountId\"","\"actor\":\"forged\",\"accountId\""))).andExpect(status().isBadRequest());
  http.perform(post("/api/v1/cards").header("Authorization","Bearer "+SignedTokens.customer()).header("Idempotency-Key","same-key").contentType("application/json").content(body.replace("\"50.00\"","50.00"))).andExpect(status().isBadRequest());verifyNoMoreInteractions(cards);
 }
}
