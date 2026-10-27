package com.fincore.beneficiary;
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
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value=BeneficiaryRiskContextController.class,properties="BENEFICIARY_CLIENT_SECRET=synthetic-test-secret")
@Import({BeneficiaryConfiguration.class,BeneficiaryRiskSecurityTest.Decoders.class})
class BeneficiaryRiskSecurityTest {
 @Autowired MockMvc http;@MockitoBean BeneficiaryStore store;@MockitoBean Remote remote;
 @TestConfiguration(proxyBeanMethods=false)static class Decoders{
  @Bean @Primary JwtDecoder decoder()throws Exception{var d=NimbusJwtDecoder.withPublicKey(SignedTokens.KEY.toRSAPublicKey()).build();d.setJwtValidator(ServiceSecurity.validator(SignedTokens.ISSUER,Set.of("fincore-api","beneficiary-service")));return d;}
 }
 @Test void onlyTheNarrowFraudIdentityCanReadAnotherSubjectsAgeContext()throws Exception{
  var dest=UUID.randomUUID();String body="{\"subject\":\"canonical-actor\",\"destinationAccountId\":\""+dest+"\"}";
  http.perform(post("/internal/v1/beneficiaries/risk-context").header("Authorization","Bearer "+SignedTokens.customer()).contentType("application/json").content(body)).andExpect(status().isForbidden());verifyNoInteractions(store,remote);
  var token=SignedTokens.token("beneficiary-service","fraud-worker","fincore-fraud",List.of(),Map.of("beneficiary-service",Map.of("roles",List.of("beneficiary.risk.read"))));
  when(store.riskContext("canonical-actor",dest)).thenReturn(Json.read("{\"saved\":false,\"createdAt\":null}"));
  http.perform(post("/internal/v1/beneficiaries/risk-context").header("Authorization","Bearer "+token).contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.saved").value(false)).andExpect(jsonPath("$.label").doesNotExist());
  verify(store).riskContext("canonical-actor",dest);verifyNoInteractions(remote);
  http.perform(get("/api/v1/beneficiaries").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
 }
}
