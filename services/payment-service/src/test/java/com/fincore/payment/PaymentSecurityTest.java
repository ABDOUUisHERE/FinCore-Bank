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
    @Test void operationalStatusIsAnAdminOnlyReadAndHasNoImplicitCustomerOrTechnicalScope()throws Exception{
        var id=UUID.randomUUID();var path="/api/v1/admin/transactions/"+id;
        http.perform(get(path)).andExpect(status().isUnauthorized());
        for(String role:List.of("CUSTOMER","SUPPORT_AGENT","FRAUD_ANALYST","FINANCE_OPERATOR"))http.perform(get(path).header("Authorization","Bearer "+SignedTokens.token("fincore-api","staff","browser",List.of(role),Map.of()))).andExpect(status().isForbidden());
        var machine=SignedTokens.token("payment-service","operator","fincore-payment-operator",List.of(),Map.of("payment-service",Map.of("roles",List.of("payment.inspect"))));
        http.perform(get(path).header("Authorization","Bearer "+machine)).andExpect(status().isForbidden());
        var admin=SignedTokens.token("fincore-api","admin","browser",List.of("ADMIN"),Map.of());
        http.perform(post(path).header("Authorization","Bearer "+admin).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        http.perform(get("/api/v1/admin/transactions").header("Authorization","Bearer "+admin)).andExpect(status().isForbidden());
        verifyNoInteractions(store,coordinator);
        when(store.inspection(id,7L)).thenReturn(Json.read("{\"paymentId\":\""+id+"\",\"history\":[],\"nextCursor\":null}"));
        http.perform(get(path+"?beforeVersion=7").header("Authorization","Bearer "+admin).header("X-User-ID","forged"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.paymentId").value(id.toString()));verify(store).inspection(id,7L);
    }
    @Test void onlyNarrowRiskReaderCanInspectCanonicalActorContext()throws Exception{
        var id=UUID.randomUUID();when(store.riskContext(id)).thenReturn(Map.of("paymentId",id,"actor","verified-owner","fingerprint","f".repeat(64)));
        http.perform(get("/internal/v1/payments/"+id+"/risk-context").header("Authorization","Bearer "+SignedTokens.customer())).andExpect(status().isForbidden());
        var token=SignedTokens.token("payment-service","fraud-worker","fincore-fraud",List.of(),Map.of("payment-service",Map.of("roles",List.of("payment.risk.read"))));
        http.perform(get("/internal/v1/payments/"+id+"/risk-context").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.actor").value("verified-owner"));
        verify(store).riskContext(id);http.perform(get("/api/v1/transfers").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
    }
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
