package com.fincore.payment;

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

@WebMvcTest(value=PaymentController.class,properties="PAYMENT_CLIENT_SECRET=synthetic-test-secret")
@Import({PaymentConfiguration.class,PaymentSecurityTest.Decoders.class})
class PaymentSecurityTest {
    @Autowired MockMvc http;
    @MockitoBean PaymentStore store;
    @MockitoBean PaymentCoordinator coordinator;
    @MockitoBean Remote remote;
    static final String BODY="{\"sourceAccountId\":\"00000000-0000-0000-0000-000000000001\",\"destinationAccountId\":\"00000000-0000-0000-0000-000000000002\",\"amount\":\"10.00\",\"currency\":\"MAD\"}";
    @TestConfiguration(proxyBeanMethods=false)static class Decoders{
        @Bean @Primary JwtDecoder primary()throws Exception{var decoder=NimbusJwtDecoder.withPublicKey(SignedTokens.KEY.toRSAPublicKey()).build();decoder.setJwtValidator(ServiceSecurity.validator(SignedTokens.ISSUER,Set.of("fincore-api","payment-service")));return decoder;}
    }
    @Test void rejectsMissingWrongAudienceAndImplicitAdminPrivileges()throws Exception{
        http.perform(get("/api/v1/transfers")).andExpect(status().isUnauthorized());
        http.perform(get("/api/v1/transfers").header("Authorization","Bearer "+SignedTokens.token("ledger-service","user","browser",List.of("CUSTOMER"),Map.of()))).andExpect(status().isUnauthorized());
        http.perform(get("/api/v1/transfers").header("Authorization","Bearer "+SignedTokens.token("fincore-api","staff","browser",List.of("ADMIN"),Map.of()))).andExpect(status().isForbidden());verifyNoInteractions(store);
    }
    @Test void bindsCustomerActorToVerifiedToken()throws Exception{
        UUID id=UUID.randomUUID();when(coordinator.transfer(eq("verified-customer"),anyString(),eq("stable-key"),any())).thenReturn(Json.read("{\"id\":\""+id+"\",\"initial_response\":{\"status\":\"CREATED\"}}"));
        http.perform(post("/api/v1/transfers").header("Authorization","Bearer "+SignedTokens.customer()).header("X-User-ID","forged").header("Idempotency-Key","stable-key").contentType("application/json").content(BODY))
            .andExpect(status().isAccepted()).andExpect(header().string("Location","/api/v1/transfers/"+id));
        verify(coordinator).transfer(eq("verified-customer"),anyString(),eq("stable-key"),any());
    }
    @Test void rejectsNumericAmountsActorInjectionAndMissingKey()throws Exception{
        for(String body:List.of(BODY.replace("\"10.00\"","10.00"),BODY.replace("\"currency\"","\"actor\":\"forged\",\"currency\""))){
            http.perform(post("/api/v1/transfers").header("Authorization","Bearer "+SignedTokens.customer()).header("Idempotency-Key","stable-key").contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
        http.perform(post("/api/v1/transfers").header("Authorization","Bearer "+SignedTokens.customer()).contentType("application/json").content(BODY)).andExpect(status().isBadRequest());verifyNoInteractions(coordinator);
    }
    @Test void customerCannotFundReverseOrReview()throws Exception{
        for(String path:List.of("/internal/v1/payments/funding","/internal/v1/payments/"+UUID.randomUUID()+"/reversal","/internal/v1/payments/"+UUID.randomUUID()+"/decision"))
            http.perform(post(path).header("Authorization","Bearer "+SignedTokens.customer()).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        verifyNoInteractions(coordinator,store);
    }
}
