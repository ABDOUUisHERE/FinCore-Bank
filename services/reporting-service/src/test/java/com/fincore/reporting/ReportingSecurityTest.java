package com.fincore.reporting;

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

@WebMvcTest(value=ReportingController.class,properties="REPORTING_CLIENT_SECRET=synthetic-test-secret")
@Import({ReportingConfiguration.class,ReportingSecurityTest.Decoders.class})
class ReportingSecurityTest {
 @Autowired MockMvc http;@MockitoBean StatementStore store;@MockitoBean ReportingCoordinator coordinator;@MockitoBean Remote remote;
 @TestConfiguration(proxyBeanMethods=false)static class Decoders{@Bean @Primary JwtDecoder decoder()throws Exception{var d=NimbusJwtDecoder.withPublicKey(SignedTokens.KEY.toRSAPublicKey()).build();d.setJwtValidator(ServiceSecurity.validator(SignedTokens.ISSUER,Set.of("fincore-api","reporting-service")));return d;}}
 @Test void reconciliationRequiresExplicitFinanceAndAdminDoesNotInheritIt()throws Exception{for(String token:List.of(SignedTokens.customer(),SignedTokens.token("fincore-api","admin","browser",List.of("ADMIN"),Map.of())))http.perform(get("/api/v1/admin/reports/reconciliation").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());verifyNoInteractions(coordinator);when(coordinator.reconcile()).thenReturn(Json.read("{\"reconciled\":true}"));http.perform(get("/api/v1/admin/reports/reconciliation").header("Authorization","Bearer "+SignedTokens.token("fincore-api","finance","fincore-admin",List.of("FINANCE_OPERATOR"),Map.of()))).andExpect(status().isOk());}
 @Test void exportsCannotBypassOwnershipOrAcceptAnActorHeader()throws Exception{var id=UUID.randomUUID();when(store.own(id,"verified-customer")).thenThrow(new ApiFailure(404,"STATEMENT_NOT_FOUND"));for(String suffix:List.of("","/csv","/pdf"))http.perform(get("/api/v1/statements/"+id+suffix).header("Authorization","Bearer "+SignedTokens.customer()).header("X-User-ID","other-owner")).andExpect(status().isNotFound());verify(store,times(3)).own(id,"verified-customer");verifyNoInteractions(coordinator);}
 @Test void machineScopeCannotBecomeACustomerOrFinanceRole()throws Exception{var token=SignedTokens.token("reporting-service","worker","fincore-reporting",List.of(),Map.of("ledger-service",Map.of("roles",List.of("ledger.statement.read","ledger.reconcile.read"))));http.perform(get("/api/v1/statements").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());http.perform(get("/api/v1/admin/reports/reconciliation").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());verifyNoInteractions(store,coordinator);}
}
