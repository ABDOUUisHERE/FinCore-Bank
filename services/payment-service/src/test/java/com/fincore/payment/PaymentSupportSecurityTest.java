package com.fincore.payment;

import com.fincore.support.*;
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

@WebMvcTest(PaymentSupportController.class)
@Import({PaymentConfiguration.class,PaymentSecurityTest.Decoders.class})
class PaymentSupportSecurityTest {
    @Autowired MockMvc http;@MockitoBean PaymentStore store;@MockitoBean Remote remote;
    final UUID id=UUID.randomUUID();final String path="/internal/v1/payments/"+id+"/support-status";
    String machine(String audience,String client,String scope) {return SignedTokens.token(audience,"worker",client,List.of(),Map.of("payment-service",Map.of("roles",List.of(scope))));}
    @Test void onlyTheSignedAuditClientWithItsNarrowRoleCanRead()throws Exception {
        http.perform(get(path)).andExpect(status().isUnauthorized());
        for(String role:List.of("ADMIN","SUPPORT_AGENT","CUSTOMER","FINANCE_OPERATOR","FRAUD_ANALYST"))
            http.perform(get(path).header("Authorization","Bearer "+SignedTokens.token("fincore-api","staff","fincore-admin",List.of(role),Map.of()))).andExpect(status().isForbidden());
        for(String client:List.of("fincore-audit","fincore-payment-operator","fincore-fraud"))for(String scope:List.of("payment.inspect","payment.risk.read","payment.support.read")) {
            if(client.equals("fincore-audit")&&scope.equals("payment.support.read"))continue;
            http.perform(get(path).header("Authorization","Bearer "+machine("payment-service",client,scope)).header("X-Client-ID","fincore-audit")).andExpect(status().isForbidden());
        }
        verifyNoInteractions(store);
        when(store.supportStatus(id)).thenReturn(Json.read("{\"paymentId\":\""+id+"\",\"kind\":\"TRANSFER\",\"status\":\"PROCESSING\",\"version\":\"9007199254740993\",\"createdAt\":\"2026-11-05T10:00:00Z\",\"observedAt\":\"2026-11-05T10:01:00Z\"}"));
        http.perform(get(path).header("Authorization","Bearer "+machine("payment-service","fincore-audit","payment.support.read"))).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.version").value("9007199254740993")).andExpect(jsonPath("$.amount").doesNotExist());
        verify(store).supportStatus(id);
    }
    @Test void supportMachineHasNoMutationListingAdminOrFinancialInspectionPrivilege()throws Exception {
        var bearer="Bearer "+machine("payment-service","fincore-audit","payment.support.read");
        for(String route:List.of("/internal/v1/payments/"+id,"/api/v1/admin/transactions/"+id,"/api/v1/transfers"))http.perform(get(route).header("Authorization",bearer)).andExpect(status().isForbidden());
        for(String route:List.of(path,"/internal/v1/payments/funding","/internal/v1/payments/"+id+"/decision","/internal/v1/payments/"+id+"/reversal","/api/v1/transfers/"+id+"/cancel"))
            http.perform(post(route).header("Authorization",bearer).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        http.perform(get(path).header("Authorization","Bearer "+machine("ledger-service","fincore-audit","payment.support.read"))).andExpect(status().isUnauthorized());
        http.perform(get("/internal/v1/payments/invalid/support-status").header("Authorization",bearer)).andExpect(status().isBadRequest());
        verifyNoInteractions(store);
    }
}
