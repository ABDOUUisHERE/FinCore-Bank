package com.fincore.audit;

import com.fincore.support.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SupportAccessController.class)
@Import({AuditConfiguration.class,AuditSecurityTest.Decoders.class})
class SupportAccessSecurityTest {
    @Autowired MockMvc http;@MockitoBean SupportAccessStore grants;@MockitoBean SupportAccountAccess access;@MockitoBean Remote remote;
    final UUID id=UUID.randomUUID();final String path="/api/v1/admin/incidents/"+id;
    String token(String role){return AuditSecurityTest.token("signed-staff",role);}
    @Test void grantAdministrationAndRecipientObservationHaveSeparateExplicitRoles()throws Exception {
        for(String role:List.of("ADMIN","SUPPORT_AGENT","CUSTOMER","FINANCE_OPERATOR","FRAUD_ANALYST")) {
            String bearer="Bearer "+token(role);
            http.perform(get(path+"/account-access").header("Authorization",bearer)).andExpect(status().is(role.equals("ADMIN")||role.equals("SUPPORT_AGENT")?200:403));
            http.perform(put(path+"/account-access").header("Authorization",bearer).header("Idempotency-Key","command-key").contentType("application/json").content("{\"expectedVersion\":\"0\",\"action\":\"ISSUE\",\"minutes\":15,\"reason\":\"CONTROL_REVIEW\"}"))
                .andExpect(status().is(role.equals("ADMIN")?200:403));
            http.perform(post(path+"/account-status").header("Authorization",bearer).header("X-User-ID","forged-admin").contentType("application/json").content("{}"))
                .andExpect(status().is(role.equals("SUPPORT_AGENT")?200:403));
        }
        verify(grants).command(eq(id),eq("signed-staff"),eq("command-key"),anyString(),any());
        verify(access).inspect(eq(id),eq("signed-staff"),anyString());
    }
    @Test void anUnknownActorOrAccountFieldCannotExpandTheScopeAndResponsesAreNotCached()throws Exception {
        for(String body:List.of("{\"accountId\":\""+UUID.randomUUID()+"\"}","{\"actor\":\"admin\"}"))
            http.perform(post(path+"/account-status").header("Authorization","Bearer "+token("SUPPORT_AGENT")).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(access);
        when(access.inspect(eq(id),eq("signed-staff"),anyString())).thenThrow(new ApiFailure(403,"SUPPORT_ACCESS_UNAVAILABLE"));
        http.perform(post(path+"/account-status").header("Authorization","Bearer "+token("SUPPORT_AGENT")).contentType("application/json").content("{}"))
            .andExpect(status().isForbidden()).andExpect(header().string("Cache-Control","no-store"));
        http.perform(get(path+"/account-access")).andExpect(status().isUnauthorized());
        http.perform(post(path+"/account-status").header("Authorization","Bearer "+SignedTokens.token("wrong-api","support","fincore-admin",List.of("SUPPORT_AGENT"),Map.of())).contentType("application/json").content("{}"))
            .andExpect(status().isUnauthorized());
    }
}
