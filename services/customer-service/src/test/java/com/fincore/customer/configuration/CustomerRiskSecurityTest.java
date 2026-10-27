package com.fincore.customer.configuration;
import com.fincore.support.*;
import com.fincore.customer.adapter.in.web.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CustomerRiskContextController.class)
@Import({SecurityConfiguration.class,CorrelationFilter.class,CustomerRiskSecurityTest.Decoders.class})
class CustomerRiskSecurityTest {
 @Autowired MockMvc http;@MockitoBean ProfileController.Profiles profiles;
 @TestConfiguration(proxyBeanMethods=false)static class Decoders{
  @Bean @Primary JwtDecoder decoder()throws Exception{var d=NimbusJwtDecoder.withPublicKey(SignedTokens.KEY.toRSAPublicKey()).build();d.setJwtValidator(SecurityConfiguration.validator(SignedTokens.ISSUER,"fincore-api"));return d;}
 }
 static String token(){return SignedTokens.token("customer-service","fraud-worker","fincore-fraud",List.of(),Map.of("customer-service",Map.of("roles",List.of("customer.risk.read"))));}
 @Test void customerAndAdminCannotForgeATechnicalContextRequest()throws Exception{
  for(var token:List.of(SignedTokens.customer(),SignedTokens.token("fincore-api","admin","browser",List.of("ADMIN"),Map.of())))http.perform(post("/internal/v1/customers/risk-context").header("Authorization","Bearer "+token).contentType("application/json").content("{\"subject\":\"other-owner\"}")).andExpect(status().isForbidden());
  verifyNoInteractions(profiles);
 }
 @Test void narrowFraudIdentityGetsOnlyDeclaredCountryAndVersion()throws Exception{
  when(profiles.get("verified-actor")).thenReturn(Json.read("{\"displayName\":\"Private synthetic name\",\"countryCode\":\"MA\",\"version\":2,\"subject\":\"verified-actor\"}"));
  http.perform(post("/internal/v1/customers/risk-context").header("Authorization","Bearer "+token()).contentType("application/json").content("{\"subject\":\"verified-actor\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.countryCode").value("MA")).andExpect(jsonPath("$.profileVersion").value(2)).andExpect(jsonPath("$.displayName").doesNotExist()).andExpect(jsonPath("$.subject").doesNotExist());
  verify(profiles).get("verified-actor");
  http.perform(get("/api/v1/customers/me").header("Authorization","Bearer "+token())).andExpect(status().isForbidden());
 }
}
