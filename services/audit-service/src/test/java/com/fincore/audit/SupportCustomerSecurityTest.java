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

@WebMvcTest(SupportCustomerController.class)
@Import({AuditConfiguration.class,AuditSecurityTest.Decoders.class})
class SupportCustomerSecurityTest {
    @Autowired MockMvc http;@MockitoBean SupportAccessStore grants;@MockitoBean SupportCustomerAccess access;@MockitoBean Remote remote;
    final UUID id=UUID.randomUUID();final String path="/api/v1/admin/incidents/"+id;
    String token(String role){return AuditSecurityTest.token("signed-staff",role);}
    @Test void signedRolesSeparateGrantManagementFromSupportConsultation()throws Exception {
        for(String role:List.of("ADMIN","SUPPORT_AGENT","CUSTOMER","FINANCE_OPERATOR","FRAUD_ANALYST")) {
            String bearer="Bearer "+token(role);
            http.perform(get(path+"/customer-access").header("Authorization",bearer)).andExpect(status().is(List.of("ADMIN","SUPPORT_AGENT").contains(role)?200:403));
            http.perform(put(path+"/customer-access").header("Authorization",bearer).header("Idempotency-Key","command-key").contentType("application/json").content("{\"expectedVersion\":\"0\",\"action\":\"ISSUE\",\"minutes\":15,\"reason\":\"CONTROL_REVIEW\"}"))
                .andExpect(status().is(role.equals("ADMIN")?200:403));
            http.perform(post("/api/v1/admin/support/customers/search").header("Authorization",bearer).header("X-User-ID","forged").contentType("application/json").content("{\"namePrefix\":\"Private\"}")).andExpect(status().is(role.equals("SUPPORT_AGENT")?200:403));
            http.perform(post(path+"/customer-profile").header("Authorization",bearer).header("X-User-ID","forged").contentType("application/json").content("{}"))
                .andExpect(status().is(role.equals("SUPPORT_AGENT")?200:403));
        }
        verify(access).search(any(),eq("signed-staff"),anyString());
        verify(grants).command(eq(id),eq("signed-staff"),eq("command-key"),anyString(),any(),eq(SupportAccessStore.Resource.CUSTOMER));
        verify(access).inspect(eq(id),eq("signed-staff"),anyString());
    }
    @Test void callersCannotChooseAnotherCustomerOrRecipientAndDeniedResultsAreNotCached()throws Exception {
        for(String body:List.of("{\"customerId\":\""+UUID.randomUUID()+"\"}","{\"actor\":\"admin\"}"))
            http.perform(post(path+"/customer-profile").header("Authorization","Bearer "+token("SUPPORT_AGENT")).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        http.perform(put(path+"/customer-access").header("Authorization","Bearer "+token("ADMIN")).header("Idempotency-Key","k").contentType("application/json").content("{\"recipient\":\"foreign\"}")).andExpect(status().isBadRequest());
        http.perform(post("/api/v1/admin/support/customers/search").header("Authorization","Bearer "+token("SUPPORT_AGENT")).contentType("application/json").content("{\"namePrefix\":\"Private\",\"customerIds\":[]}")).andExpect(status().isBadRequest());
        verifyNoInteractions(access,grants);
        when(access.inspect(eq(id),eq("signed-staff"),anyString())).thenThrow(new ApiFailure(403,"SUPPORT_ACCESS_UNAVAILABLE"));
        http.perform(post(path+"/customer-profile").header("Authorization","Bearer "+token("SUPPORT_AGENT")).contentType("application/json").content("{}"))
            .andExpect(status().isForbidden()).andExpect(header().string("Cache-Control","no-store"));
        http.perform(get(path+"/customer-access")).andExpect(status().isUnauthorized());
        http.perform(post(path+"/customer-profile").header("Authorization","Bearer "+SignedTokens.token("wrong-api","support","fincore-admin",List.of("SUPPORT_AGENT"),Map.of())).contentType("application/json").content("{}"))
            .andExpect(status().isUnauthorized());
    }
}
