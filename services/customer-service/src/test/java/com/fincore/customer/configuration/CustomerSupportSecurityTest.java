package com.fincore.customer.configuration;

import com.fincore.customer.adapter.in.web.CustomerSupportController;
import com.fincore.customer.adapter.in.web.CustomerSupportController.*;
import com.fincore.support.SignedTokens;
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

@WebMvcTest(CustomerSupportController.class)
@Import({SecurityConfiguration.class,CorrelationFilter.class,JsonConfiguration.class,CustomerRiskSecurityTest.Decoders.class})
class CustomerSupportSecurityTest {
    @Autowired MockMvc http;
    @MockitoBean CustomerAccessAudit accessAudit;@MockitoBean Store store;
    final UUID id=UUID.randomUUID();final String profile="/internal/v1/customers/"+id+"/support-profile",search="/internal/v1/customers/support-search";
    String machine(String audience,String client,String scope){return SignedTokens.token(audience,"worker",client,List.of(),Map.of("customer-service",Map.of("roles",List.of(scope))));}
    String body(){return "{\"namePrefix\":\"Private\",\"customerIds\":[\""+id+"\"]}";}
    @Test void onlyTheSignedAuditClientWithTheNarrowRoleCanUseEitherProjection()throws Exception {
        http.perform(get(profile)).andExpect(status().isUnauthorized());
        for(String role:List.of("ADMIN","SUPPORT_AGENT","CUSTOMER","FINANCE_OPERATOR","FRAUD_ANALYST")) {
            var token=SignedTokens.token("fincore-api","staff","browser",List.of(role),Map.of());
            http.perform(get(profile).header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
            http.perform(post(search).header("Authorization","Bearer "+token).contentType("application/json").content(body())).andExpect(status().isForbidden());
        }
        for(var pair:List.of(List.of("fincore-audit","customer.risk.read"),List.of("fincore-fraud","customer.support.read")))http.perform(post(search).header("Authorization","Bearer "+machine("customer-service",pair.get(0),pair.get(1))).header("X-Client-ID","fincore-audit").contentType("application/json").content(body())).andExpect(status().isForbidden());
        verifyNoInteractions(store);var at=Instant.parse("2026-11-09T10:00:00Z");var item=new Profile(id,"Synthetic Name","ACTIVE","9007199254740993",at,at);
        when(store.profile(id)).thenReturn(item);when(store.search(new Input("Private",List.of(id)))).thenReturn(new Results(List.of(item),false));
        var token="Bearer "+machine("customer-service","fincore-audit","customer.support.read");
        http.perform(get(profile).header("Authorization",token)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.profileVersion").value("9007199254740993")).andExpect(jsonPath("$.subject").doesNotExist());
        http.perform(post(search).header("Authorization",token).contentType("application/json").content(body())).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.items.length()").value(1));
    }
    @Test void narrowMachineCannotUseRiskAdminDirectoryOrCustomerMutationRoutes()throws Exception {
        var token="Bearer "+machine("customer-service","fincore-audit","customer.support.read");
        for(String path:List.of("/api/v1/customers/me/profile","/api/v1/admin/customers/"+id,search))http.perform(get(path).header("Authorization",token)).andExpect(status().isForbidden());
        for(String path:List.of("/internal/v1/customers/risk-context","/api/v1/admin/customers/search","/api/v1/customers/me/onboarding",profile))http.perform(post(path).header("Authorization",token).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        http.perform(get(profile).header("Authorization","Bearer "+machine("wrong-api","fincore-audit","customer.support.read"))).andExpect(status().isUnauthorized());
        http.perform(post(search).header("Authorization",token).contentType("application/json").content("{\"namePrefix\":\"Private\",\"customerIds\":[],\"actor\":\"admin\"}")).andExpect(status().isBadRequest());
        verifyNoInteractions(store);
    }
}
