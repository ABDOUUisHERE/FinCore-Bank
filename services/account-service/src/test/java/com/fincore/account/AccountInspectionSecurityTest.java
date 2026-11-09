package com.fincore.account;

import com.fincore.support.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value=AccountInspectionController.class,properties="ACCOUNT_CLIENT_SECRET=synthetic-test-secret")
@Import({AccountConfiguration.class,AccountSecurityTest.Decoders.class})
class AccountInspectionSecurityTest {
    @org.springframework.test.context.bean.override.mockito.MockitoBean com.fincore.support.AdministrativeReadAudit readAudit;
    @Autowired MockMvc http;@MockitoBean AccountInspection inspection;@MockitoBean Remote remote;
    final UUID id=UUID.randomUUID();
    String token(String role){return SignedTokens.token("fincore-api","staff","fincore-admin",List.of(role),Map.of());}
    @Test void onlyExplicitAdminCanReadAndNoStaffMutationListOrFinancialPathIsGranted()throws Exception{
        var path="/api/v1/admin/accounts/"+id;
        http.perform(get(path)).andExpect(status().isUnauthorized());
        for(var role:List.of("CUSTOMER","SUPPORT_AGENT","FINANCE_OPERATOR","FRAUD_ANALYST"))http.perform(get(path).header("Authorization","Bearer "+token(role)).header("X-User-ID","admin")).andExpect(status().isForbidden());
        var machine=SignedTokens.token("account-service","worker","fincore-payment",List.of(),Map.of("account-service",Map.of("roles",List.of("account.lookup"))));http.perform(get(path).header("Authorization","Bearer "+machine)).andExpect(status().isForbidden());
        for(var suffix:List.of("","/guard"))http.perform(put(path+suffix).header("Authorization","Bearer "+token("ADMIN")).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        for(var route:List.of("/api/v1/admin/accounts",path+"/balance","/api/v1/accounts/"+id+"/balance"))http.perform(get(route).header("Authorization","Bearer "+token("ADMIN"))).andExpect(status().isForbidden());
        verifyNoInteractions(inspection);
        when(inspection.inspect(id)).thenReturn(new AccountInspection.Status(id,UUID.randomUUID(),"MAD","ACTIVE","9007199254740993",null,null,"0",Instant.now(),"UNAVAILABLE",null));
        http.perform(get(path).header("Authorization","Bearer "+token("ADMIN"))).andExpect(status().isOk()).andExpect(jsonPath("$.accountVersion").value("9007199254740993")).andExpect(jsonPath("$.accountObservedAt").isString()).andExpect(jsonPath("$.ledgerState").value("UNAVAILABLE")).andExpect(jsonPath("$.ownerSubject").doesNotExist());
    }
    @Test void supportRouteRequiresBothNarrowAuthorityAndTheSignedAuditClient()throws Exception{
        String path="/internal/v1/accounts/"+id+"/support-status";
        for(String client:List.of("fincore-payment","fincore-admin","fincore-audit"))for(String scope:List.of("account.lookup","account.support.read")) {
            boolean allowed=client.equals("fincore-audit")&&scope.equals("account.support.read");
            if(allowed)continue;
            var bearer=SignedTokens.token("account-service","worker",client,List.of("ADMIN"),Map.of("account-service",Map.of("roles",List.of(scope))));
            http.perform(get(path).header("Authorization","Bearer "+bearer).header("X-Client-ID","fincore-audit")).andExpect(status().isForbidden());
        }
        verifyNoInteractions(inspection);
        var bearer=SignedTokens.token("account-service","worker","fincore-audit",List.of(),Map.of("account-service",Map.of("roles",List.of("account.support.read"))));
        when(inspection.inspect(id)).thenReturn(new AccountInspection.Status(id,UUID.randomUUID(),"MAD","ACTIVE","1",null,null,"0",Instant.now(),"UNAVAILABLE",null));
        http.perform(get(path).header("Authorization","Bearer "+bearer)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.accountId").value(id.toString())).andExpect(jsonPath("$.accountObservedAt").isString()).andExpect(jsonPath("$.customerId").doesNotExist()).andExpect(jsonPath("$.balance").doesNotExist());
        http.perform(get("/api/v1/admin/accounts/"+id).header("Authorization","Bearer "+bearer)).andExpect(status().isForbidden());
    }
    @Test void wrongAudienceAndMalformedReferenceCannotReachInspection()throws Exception{
        http.perform(get("/api/v1/admin/accounts/"+id).header("Authorization","Bearer "+SignedTokens.token("wrong-api","admin","browser",List.of("ADMIN"),Map.of()))).andExpect(status().isUnauthorized());
        http.perform(get("/api/v1/admin/accounts/invalid").header("Authorization","Bearer "+token("ADMIN"))).andExpect(status().isBadRequest());verifyNoInteractions(inspection);
    }
}
