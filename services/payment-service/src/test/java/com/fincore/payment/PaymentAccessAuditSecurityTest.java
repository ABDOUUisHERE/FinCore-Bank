package com.fincore.payment;

import com.fincore.support.*;
import com.fincore.support.AdministrativeReadAudit.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value=PaymentController.class,properties="PAYMENT_CLIENT_SECRET=synthetic-test-secret")
@Import({PaymentConfiguration.class,PaymentSecurityTest.Decoders.class})
class PaymentAccessAuditSecurityTest {
    @Autowired MockMvc http;
    @MockitoBean PaymentStore inspection;
    @MockitoBean PaymentCoordinator coordinator;
    @MockitoBean Remote remote;
    @MockitoBean AdministrativeReadAudit readAudit;
    final UUID id=UUID.randomUUID();
    final String base="/api/v1/admin/transactions/";
    String path() { return base+id+"?beforeVersion=7"; }
    String token(String role) { return SignedTokens.token("fincore-api","current-staff","fincore-admin",List.of(role),Map.of()); }
    Target target() { return new Target("PAYMENT_INSPECT",id); }
    void success() { when(inspection.inspection(id,7L)).thenReturn(Json.read(Json.text(Map.of("paymentId",id,"status","COMPLETED","version","9007199254740993","history",List.of(),"nextCursor","4")))); }

    @Test void preparedReadRetainsCurrentSignedStaffRatherThanOwnerOrForgedHeader() throws Exception {
        success();http.perform(get(path()).header("Authorization","Bearer "+token("ADMIN")).header("X-User-ID","FORGED_OWNER").header("X-Correlation-ID","owner-read-success"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        verify(readAudit).retain(target(),"GET",200,new Actor("current-staff"),"owner-read-success");verifyNoMoreInteractions(readAudit);
    }
    @Test void missingInvalidAndWrongAudienceCredentialsHaveNoTrustedActor() throws Exception {
        for(String bearer:List.of("","Bearer forged.PRIVATE_TOKEN.invalid","Bearer "+SignedTokens.token("wrong-api","forged-actor","browser",List.of("ADMIN"),Map.of()))) {
            var request=get(path()).header("X-Correlation-ID","unknown-identity").header("X-User-ID","FORGED_OWNER");if(!bearer.isEmpty())request.header("Authorization",bearer);
            http.perform(request).andExpect(status().isUnauthorized());
        }
        verify(readAudit,times(3)).retain(target(),"GET",401,null,"unknown-identity");verifyNoInteractions(inspection);
    }
    @Test void signedRoleAndMethodDenialsRetainTheVerifiedSubject() throws Exception {
        for(String role:List.of("CUSTOMER","SUPPORT_AGENT","FINANCE_OPERATOR","FRAUD_ANALYST"))
            http.perform(get(path()).header("Authorization","Bearer "+token(role)).header("X-Correlation-ID","signed-denial")).andExpect(status().isForbidden());
        verify(readAudit,times(4)).retain(target(),"GET",403,new Actor("current-staff"),"signed-denial");
        http.perform(post(base+id).header("Authorization","Bearer "+token("ADMIN")).header("X-Correlation-ID","method-denial").contentType("application/json").content("{}"))
            .andExpect(status().isForbidden());
        verify(readAudit).retain(target(),"POST",403,new Actor("current-staff"),"method-denial");verifyNoInteractions(inspection);
    }
    @Test void invalidReferenceAndOwnerStorageFailureHaveStructuredRedactedOutcomes() throws Exception {
        http.perform(get(base+"PRIVATE_INVALID_REFERENCE").header("Authorization","Bearer "+token("ADMIN")).header("X-Correlation-ID","invalid-reference")).andExpect(status().isBadRequest());
        verify(readAudit).retain(new Target("PAYMENT_INSPECT",null),"GET",400,new Actor("current-staff"),"invalid-reference");
        when(inspection.inspection(id,7L)).thenThrow(new DataAccessResourceFailureException("PRIVATE_CONNECTION_DETAILS"));
        var response=http.perform(get(path()).header("Authorization","Bearer "+token("ADMIN")).header("X-Correlation-ID","owner-unavailable")).andExpect(status().isServiceUnavailable()).andReturn();
        assertThat(response.getResponse().getContentAsString()).doesNotContain("PRIVATE_CONNECTION_DETAILS");
        verify(readAudit).retain(target(),"GET",503,new Actor("current-staff"),"owner-unavailable");
    }
    @Test void failedReceiptInsertBeginOrCommitWithholdsSuccessAndReplacesUnretainedDenial() throws Exception {
        success();for(RuntimeException failure:List.of(new DataAccessResourceFailureException("PRIVATE_INSERT"),new org.springframework.transaction.CannotCreateTransactionException("PRIVATE_BEGIN"),new org.springframework.transaction.TransactionSystemException("PRIVATE_COMMIT"))) {
            doThrow(failure).when(readAudit).retain(any(),anyString(),anyInt(),nullable(Actor.class),anyString());
            for(String bearer:List.of("Bearer "+token("ADMIN"),"Bearer "+token("SUPPORT_AGENT"),"")) {
                var request=get(path());if(!bearer.isEmpty())request.header("Authorization",bearer);
                var response=http.perform(request).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AUDIT_UNAVAILABLE"))
                    .andExpect(header().doesNotExist("WWW-Authenticate")).andReturn();
                assertThat(response.getResponse().getContentAsString()).doesNotContain(id.toString(),"PRIVATE_");
            }
        }
    }
    @Test void listsSuffixesAndOtherOwnerApisDoNotBecomePartOfThisRegistry() throws Exception {
        for(String uri:List.of(base.substring(0,base.length()-1),base+id+"/balance","/api/v1/accounts"))
            http.perform(get(uri).header("Authorization","Bearer "+token("ADMIN"))).andExpect(status().isForbidden());
        verifyNoInteractions(readAudit,inspection);
    }
}
