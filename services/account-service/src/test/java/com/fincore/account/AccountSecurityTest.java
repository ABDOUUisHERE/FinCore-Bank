package com.fincore.account;

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

@WebMvcTest(value=AccountController.class,properties="ACCOUNT_CLIENT_SECRET=synthetic-test-secret")
@Import({AccountConfiguration.class,AccountSecurityTest.Decoders.class})
class AccountSecurityTest {
    @Autowired MockMvc http;
    @MockitoBean AccountStore store;
    @MockitoBean AccountCoordinator coordinator;
    @MockitoBean Remote remote;
    @TestConfiguration(proxyBeanMethods=false)static class Decoders{
        @Bean @Primary JwtDecoder primary()throws Exception{var decoder=NimbusJwtDecoder.withPublicKey(SignedTokens.KEY.toRSAPublicKey()).build();decoder.setJwtValidator(ServiceSecurity.validator(SignedTokens.ISSUER,Set.of("fincore-api","account-service")));return decoder;}
    }
    @Test void independentAuthenticationAudienceAndRoleChecks()throws Exception{
        http.perform(get("/api/v1/accounts")).andExpect(status().isUnauthorized());
        http.perform(get("/api/v1/accounts").header("Authorization","Bearer "+SignedTokens.token("wrong-api","user","browser",List.of("CUSTOMER"),Map.of()))).andExpect(status().isUnauthorized());
        http.perform(get("/api/v1/accounts").header("Authorization","Bearer "+SignedTokens.token("fincore-api","staff","browser",List.of("ADMIN"),Map.of()))).andExpect(status().isForbidden());
        http.perform(get("/internal/v1/accounts/"+UUID.randomUUID()).header("Authorization","Bearer "+SignedTokens.customer())).andExpect(status().isForbidden());
        verifyNoInteractions(store);
    }
    @Test void accountOwnerComesOnlyFromSignedSubject()throws Exception{
        UUID id=UUID.randomUUID(),customer=UUID.randomUUID();var account=new AccountStore.Account(id,customer,"verified-customer","MAD","ACTIVE",UUID.randomUUID(),1,0,null,null,null);
        when(remote.call(anyString(),anyString(),anyString(),isNull())).thenReturn(Json.read("{\"customerId\":\""+customer+"\"}"));
        when(store.create(eq("verified-customer"),eq(customer),eq("MAD"),eq("request-key"))).thenReturn(account);when(store.own(id,"verified-customer")).thenReturn(account);
        http.perform(post("/api/v1/accounts").header("Authorization","Bearer "+SignedTokens.customer()).header("X-User-ID","forged-other").header("Idempotency-Key","request-key").contentType("application/json").content("{\"currency\":\"MAD\"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.accountId").value(id.toString()));
        verify(store).create("verified-customer",customer,"MAD","request-key");
    }
    @Test void rejectsInjectedOwnerAndMissingVersion()throws Exception{
        http.perform(post("/api/v1/accounts").header("Authorization","Bearer "+SignedTokens.customer()).header("Idempotency-Key","request-key").contentType("application/json").content("{\"currency\":\"MAD\",\"ownerSubject\":\"forged\"}")).andExpect(status().isBadRequest());
        http.perform(put("/api/v1/accounts/"+UUID.randomUUID()+"/guard").header("Authorization","Bearer "+SignedTokens.customer()).contentType("application/json").content("{\"status\":\"FROZEN\"}")).andExpect(status().isBadRequest());verifyNoInteractions(store);
    }
}
