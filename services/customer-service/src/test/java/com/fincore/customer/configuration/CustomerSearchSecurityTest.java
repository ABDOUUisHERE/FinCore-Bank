package com.fincore.customer.configuration;

import com.fincore.customer.adapter.in.web.CustomerSearchController;
import com.fincore.customer.adapter.in.web.CustomerSearchController.*;
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
import static org.hamcrest.Matchers.*;

@WebMvcTest(CustomerSearchController.class)
@Import({SecurityConfiguration.class, CorrelationFilter.class, JsonConfiguration.class, CustomerRiskSecurityTest.Decoders.class})
class CustomerSearchSecurityTest {
    @Autowired MockMvc http;
    @MockitoBean Search search;
    final String path = "/api/v1/admin/customers/search";
    String token(String role) { return SignedTokens.token("fincore-api","staff","fincore-admin",List.of(role),Map.of()); }

    @Test void onlySignedAdminCanPostAndOtherMethodsStayDenied() throws Exception {
        http.perform(post(path).contentType("application/json").content("{\"namePrefix\":\"Syn\"}")).andExpect(status().isUnauthorized());
        for(String role:List.of("CUSTOMER","SUPPORT_AGENT","FINANCE_OPERATOR","FRAUD_ANALYST"))
            http.perform(post(path).header("Authorization","Bearer "+token(role)).header("X-User-ID","admin").header("X-Roles","ADMIN").contentType("application/json").content("{\"namePrefix\":\"Syn\"}"))
                .andExpect(status().isForbidden());
        http.perform(post(path).header("Authorization","Bearer "+SignedTokens.token("wrong-api","staff","browser",List.of("ADMIN"),Map.of())).contentType("application/json").content("{\"namePrefix\":\"Syn\"}"))
            .andExpect(status().isUnauthorized());
        http.perform(post(path).header("Authorization","Bearer "+SignedTokens.token("customer-service","machine","fincore-fraud",List.of(),Map.of())).contentType("application/json").content("{\"namePrefix\":\"Syn\"}"))
            .andExpect(status().isForbidden());
        http.perform(get(path+"?namePrefix=Syn").header("Authorization","Bearer "+token("ADMIN"))).andExpect(status().isForbidden());
        http.perform(put(path).header("Authorization","Bearer "+token("ADMIN")).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        verifyNoInteractions(search);
    }
    @Test void adminReceivesOnlyBoundedMatchMetadataWithNoStore() throws Exception {
        UUID id=UUID.randomUUID(); Instant at=Instant.parse("2026-11-04T17:00:00Z");
        when(search.find(new Input("Syn",null))).thenReturn(new Page(List.of(new Match(id,"Synthetic Customer","ACTIVE","9007199254740993",at)),null,at));
        http.perform(post(path).header("Authorization","Bearer "+token("ADMIN")).contentType("application/json").content("{\"namePrefix\":\"Syn\"}"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].customerId").value(id.toString()))
            .andExpect(jsonPath("$.items[0].displayName").value("Synthetic Customer")).andExpect(jsonPath("$.items[0].profileVersion").value("9007199254740993"))
            .andExpect(jsonPath("$.items[0].subject").doesNotExist()).andExpect(jsonPath("$.items[0].countryCode").doesNotExist())
            .andExpect(jsonPath("$.items[0].accounts").doesNotExist()).andExpect(jsonPath("$.total").doesNotExist()).andExpect(jsonPath("$.nextAfterId").isEmpty());
    }
    @Test void malformedBodiesAndStorageFailuresDoNotEchoPrivateInputs() throws Exception {
        for(String body:List.of("{","{\"namePrefix\":123}","{\"namePrefix\":true}","{\"namePrefix\":\"Private canary\",\"unrecognized\":true}"))
            http.perform(post(path).header("Authorization","Bearer "+token("ADMIN")).contentType("application/json").content(body))
                .andExpect(status().isBadRequest()).andExpect(content().string(not(containsString("Private canary"))));
        verifyNoInteractions(search);
        when(search.find(any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("Private name and connection details"));
        http.perform(post(path).header("Authorization","Bearer "+token("ADMIN")).contentType("application/json").content("{\"namePrefix\":\"Private canary\"}"))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("STORAGE_UNAVAILABLE"))
            .andExpect(content().string(not(containsString("Private"))));
    }
}
