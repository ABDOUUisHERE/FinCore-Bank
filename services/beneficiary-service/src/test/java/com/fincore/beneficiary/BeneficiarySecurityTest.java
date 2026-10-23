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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value=BeneficiaryController.class,properties="BENEFICIARY_CLIENT_SECRET=synthetic-test-secret")
@Import({BeneficiaryConfiguration.class,BeneficiarySecurityTest.Decoders.class})
class BeneficiarySecurityTest {
    @Autowired MockMvc http;
    @MockitoBean BeneficiaryStore store;
    @MockitoBean Remote remote;
    @TestConfiguration(proxyBeanMethods=false) static class Decoders {
        @Bean @Primary JwtDecoder decoder() throws Exception {
            var decoder=NimbusJwtDecoder.withPublicKey(SignedTokens.KEY.toRSAPublicKey()).build();
            decoder.setJwtValidator(ServiceSecurity.validator(SignedTokens.ISSUER,Set.of("fincore-api")));return decoder;
        }
    }
    @Test void independentlyRejectsMissingWrongAudienceStaffAndInternalAccess() throws Exception {
        http.perform(get("/api/v1/beneficiaries")).andExpect(status().isUnauthorized());
        http.perform(get("/api/v1/beneficiaries").header("Authorization","Bearer "+SignedTokens.token("wrong","actor","browser",List.of("CUSTOMER"),Map.of()))).andExpect(status().isUnauthorized());
        http.perform(get("/api/v1/beneficiaries").header("Authorization","Bearer "+SignedTokens.token("fincore-api","staff","browser",List.of("ADMIN"),Map.of()))).andExpect(status().isForbidden());
        http.perform(get("/internal/v1/beneficiaries").header("Authorization","Bearer "+SignedTokens.customer())).andExpect(status().isForbidden());
        verifyNoInteractions(store,remote);
    }
    @Test void bindsOwnerToSignedSubjectAndDoesNotExposePrivateColumns() throws Exception {
        UUID destination=UUID.randomUUID(),id=UUID.randomUUID();
        var row=Json.read("{\"id\":\""+id+"\",\"owner_subject\":\"verified-customer\",\"key\":\"private-key\",\"destination_account_id\":\""+destination+"\",\"currency\":\"MAD\",\"label\":\"Synthetic recipient\",\"initial_label\":\"Synthetic recipient\",\"status\":\"ACTIVE\",\"version\":0,\"created_at\":\"2026-10-23T00:00:00Z\"}");
        when(store.replay("verified-customer","request-key",destination,"Synthetic recipient")).thenReturn(null);
        when(remote.serviceToken()).thenReturn("memory-only-service-token");
        when(remote.call(eq("GET"),contains(destination.toString()),anyString(),isNull())).thenReturn(Json.read("{\"status\":\"ACTIVE\",\"currency\":\"MAD\"}"));
        when(store.create("verified-customer","request-key",destination,"MAD","Synthetic recipient")).thenReturn(row);
        http.perform(post("/api/v1/beneficiaries").header("Authorization","Bearer "+SignedTokens.customer()).header("X-User-ID","forged").header("Idempotency-Key","request-key")
            .contentType("application/json").content("{\"label\":\"Synthetic recipient\",\"destinationAccountId\":\""+destination+"\"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.beneficiaryId").value(id.toString())).andExpect(jsonPath("$.owner_subject").doesNotExist()).andExpect(jsonPath("$.key").doesNotExist());
        verify(store).create("verified-customer","request-key",destination,"MAD","Synthetic recipient");
    }
    @Test void rejectsInjectedOwnerAndMissingVersionBeforeStoreAccess() throws Exception {
        http.perform(post("/api/v1/beneficiaries").header("Authorization","Bearer "+SignedTokens.customer()).header("Idempotency-Key","key")
            .contentType("application/json").content("{\"label\":\"Name\",\"destinationAccountId\":\""+UUID.randomUUID()+"\",\"ownerSubject\":\"forged\"}")).andExpect(status().isBadRequest());
        http.perform(put("/api/v1/beneficiaries/"+UUID.randomUUID()).header("Authorization","Bearer "+SignedTokens.customer())
            .contentType("application/json").content("{\"label\":\"Name\",\"status\":\"DISABLED\"}")).andExpect(status().isBadRequest());
        verifyNoInteractions(store,remote);
    }
    @Test void definitiveReplayDoesNotDependOnRecipientAvailability() throws Exception {
        UUID destination=UUID.randomUUID();
        when(store.replay(anyString(),eq("key"),eq(destination),eq("Name"))).thenReturn(Json.read("{\"id\":\""+UUID.randomUUID()+"\",\"destination_account_id\":\""+destination+"\",\"label\":\"Renamed\",\"currency\":\"MAD\",\"status\":\"DISABLED\",\"version\":1}"));
        http.perform(post("/api/v1/beneficiaries").header("Authorization","Bearer "+SignedTokens.customer()).header("Idempotency-Key","key")
            .contentType("application/json").content("{\"label\":\"Name\",\"destinationAccountId\":\""+destination+"\"}")).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("DISABLED"));
        verifyNoInteractions(remote);
    }
}
