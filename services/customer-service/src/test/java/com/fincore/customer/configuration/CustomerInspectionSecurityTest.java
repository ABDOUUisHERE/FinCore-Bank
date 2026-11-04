package com.fincore.customer.configuration;

import com.fincore.customer.adapter.in.web.*;
import com.fincore.support.SignedTokens;
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

@WebMvcTest(CustomerInspectionController.class)
@Import({SecurityConfiguration.class, CorrelationFilter.class, CustomerRiskSecurityTest.Decoders.class})
class CustomerInspectionSecurityTest {
    @Autowired MockMvc http;
    @MockitoBean CustomerInspectionController.Inspection store;
    final UUID id = UUID.randomUUID();
    String token(String role) { return SignedTokens.token("fincore-api", "staff", "fincore-admin", List.of(role), Map.of()); }

    @Test void exactStatusRequiresAdminAndCannotModifyOrSearchProfiles() throws Exception {
        var path = "/api/v1/admin/customers/" + id;
        http.perform(get(path)).andExpect(status().isUnauthorized());
        for (String role : List.of("CUSTOMER", "SUPPORT_AGENT", "FRAUD_ANALYST", "FINANCE_OPERATOR"))
            http.perform(get(path).header("Authorization", "Bearer " + token(role)).header("X-User-ID", "admin")).andExpect(status().isForbidden());
        http.perform(get("/api/v1/admin/customers").header("Authorization", "Bearer " + token("ADMIN"))).andExpect(status().isForbidden());
        http.perform(put(path).header("Authorization", "Bearer " + token("ADMIN")).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        verifyNoInteractions(store);
        when(store.get(id)).thenReturn(new CustomerInspectionController.Status(id, "ACTIVE", "9007199254740993", "2026-01-01T00:00:00Z", "2026-11-04T00:00:00Z"));
        http.perform(get(path).header("Authorization", "Bearer " + token("ADMIN"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.profileVersion").value("9007199254740993"))
            .andExpect(jsonPath("$.subject").doesNotExist()).andExpect(jsonPath("$.displayName").doesNotExist()).andExpect(jsonPath("$.countryCode").doesNotExist());
    }

    @Test void invalidAudienceOrReferenceNeverReachesStorage() throws Exception {
        http.perform(get("/api/v1/admin/customers/" + id).header("Authorization", "Bearer " + SignedTokens.token("wrong-api", "admin", "browser", List.of("ADMIN"), Map.of()))).andExpect(status().isUnauthorized());
        http.perform(get("/api/v1/admin/customers/invalid").header("Authorization", "Bearer " + token("ADMIN"))).andExpect(status().isBadRequest());
        verifyNoInteractions(store);
    }
}
