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
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CustomerSearchController.class)
@Import({SecurityConfiguration.class, CorrelationFilter.class, JsonConfiguration.class, CustomerRiskSecurityTest.Decoders.class})
class CustomerAccessAuditSecurityTest {
    @Autowired MockMvc http;
    @MockitoBean Search search;
    @MockitoBean CustomerAccessAudit audit;
    final String path="/api/v1/admin/customers/search";
    String token(String role) { return SignedTokens.token("fincore-api","signed-staff","fincore-admin",List.of(role),Map.of()); }

    @Test void successRetainsOnlySignedIdentityAndRouteCategory() throws Exception {
        when(search.find(any())).thenReturn(new Page(List.of(new Match(UUID.randomUUID(),"PRIVATE_NAME","ACTIVE","0",Instant.now())),null,Instant.now()));
        http.perform(post(path+"?privateQuery=PRIVATE_QUERY").header("Authorization","Bearer "+token("ADMIN")).header("X-User-ID","FORGED_USER")
            .header("X-Correlation-ID","audit-success").contentType("application/json").content("{\"namePrefix\":\"PRIVATE_PREFIX\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].displayName").value("PRIVATE_NAME"));
        verify(audit).retain(new CustomerAccessAudit.Target("CUSTOMER_SEARCH",null),"POST",200,new CustomerAccessAudit.Actor("signed-staff"),"audit-success");
        verifyNoMoreInteractions(audit);
    }
    @Test void missingInvalidOrWrongAudienceCredentialsNeverBecomeSignedActors() throws Exception {
        for(String bearer:List.of("", "Bearer forged.PRIVATE_TOKEN.invalid", "Bearer "+SignedTokens.token("wrong-api","forged-subject","browser",List.of("ADMIN"),Map.of()))) {
            var request=post(path).header("X-User-ID","FORGED_USER").header("X-Roles","ADMIN").header("X-Correlation-ID","unknown-actor")
                .contentType("application/json").content("{\"namePrefix\":\"PRIVATE_PREFIX\"}");
            if(!bearer.isEmpty())request.header("Authorization",bearer);
            http.perform(request).andExpect(status().isUnauthorized());
        }
        verify(audit,times(3)).retain(new CustomerAccessAudit.Target("CUSTOMER_SEARCH",null),"POST",401,null,"unknown-actor");
        verifyNoInteractions(search);
    }
    @Test void signedDenialAndMalformedInputAreRetainedWithoutEchoingInputs() throws Exception {
        http.perform(post(path).header("Authorization","Bearer "+token("SUPPORT_AGENT")).header("X-Correlation-ID","role-denied").contentType("application/json").content("{}"))
            .andExpect(status().isForbidden());
        http.perform(post(path).header("Authorization","Bearer "+token("ADMIN")).header("X-Correlation-ID","invalid-input").contentType("application/json").content("{\"namePrefix\":true}"))
            .andExpect(status().isBadRequest());
        verify(audit).retain(new CustomerAccessAudit.Target("CUSTOMER_SEARCH",null),"POST",403,new CustomerAccessAudit.Actor("signed-staff"),"role-denied");
        verify(audit).retain(new CustomerAccessAudit.Target("CUSTOMER_SEARCH",null),"POST",400,new CustomerAccessAudit.Actor("signed-staff"),"invalid-input");
        verifyNoInteractions(search);
    }
    @Test void unavailableAuditWithholdsAnOtherwiseSuccessfulBodyAndReplacesDenials() throws Exception {
        when(search.find(any())).thenReturn(new Page(List.of(new Match(UUID.randomUUID(),"PRIVATE_NAME","ACTIVE","0",Instant.now())),null,Instant.now()));
        doThrow(new DataAccessResourceFailureException("PRIVATE_DATABASE_DETAILS")).when(audit).retain(any(),anyString(),anyInt(),nullable(CustomerAccessAudit.Actor.class),anyString());
        for(String role:List.of("ADMIN","SUPPORT_AGENT")) {
            var result=http.perform(post(path).header("Authorization","Bearer "+token(role)).contentType("application/json").content("{\"namePrefix\":\"PRIVATE_PREFIX\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AUDIT_UNAVAILABLE")).andReturn();
            assertThat(result.getResponse().getContentAsString()).doesNotContain("PRIVATE_");
        }
    }
    @Test void responseBytesAreStillBufferedWhenAuditCommitsAndUnscopedRoutesDoNotRetain() throws Exception {
        var response=new MockHttpServletResponse();var request=new MockHttpServletRequest("GET","/api/v1/admin/customers/invalid-private-reference");
        doAnswer(call->{ assertThat(response.getContentAsByteArray()).isEmpty(); return null; }).when(audit).retain(any(),anyString(),anyInt(),nullable(CustomerAccessAudit.Actor.class),anyString());
        new CustomerAccessAuditFilter(audit).doFilter(request,response,(req,res)->res.getWriter().write("PRIVATE_RESPONSE"));
        assertThat(response.getContentAsString()).isEqualTo("PRIVATE_RESPONSE");
        verify(audit).retain(eq(new CustomerAccessAudit.Target("CUSTOMER_INSPECT",null)),eq("GET"),eq(200),isNull(),anyString());
        clearInvocations(audit);
        new CustomerAccessAuditFilter(audit).doFilter(new MockHttpServletRequest("GET","/api/v1/customers/me"),new MockHttpServletResponse(),(req,res)->{});
        verifyNoInteractions(audit);
    }
    @Test void decodedServletRouteAndContainerErrorsKeepTheirFiniteCategoryWithoutEarlyWrites() throws Exception {
        var response=new MockHttpServletResponse();var request=new MockHttpServletRequest("POST","/api/v1/admin/customers/%73earch");request.setServletPath(path);
        doAnswer(call->{assertThat(response.getContentAsByteArray()).isEmpty();return null;}).when(audit).retain(any(),anyString(),anyInt(),nullable(CustomerAccessAudit.Actor.class),anyString());
        new CustomerAccessAuditFilter(audit).doFilter(request,response,(req,res)->((jakarta.servlet.http.HttpServletResponse)res).sendError(404,"PRIVATE_ERROR"));
        assertThat(response.getStatus()).isEqualTo(404);assertThat(response.getContentAsString()).doesNotContain("PRIVATE_ERROR");
        verify(audit).retain(eq(new CustomerAccessAudit.Target("CUSTOMER_SEARCH",null)),eq("POST"),eq(404),isNull(),anyString());
        clearInvocations(audit);var failedResponse=new MockHttpServletResponse();
        assertThatThrownBy(()->new CustomerAccessAuditFilter(audit).doFilter(new MockHttpServletRequest("GET",path),failedResponse,(req,res)->{res.getWriter().write("PRIVATE_PARTIAL_BODY");throw new jakarta.servlet.ServletException("PRIVATE_FAILURE");}))
            .isInstanceOf(jakarta.servlet.ServletException.class);
        assertThat(failedResponse.getContentAsByteArray()).isEmpty();
        verify(audit).retain(eq(new CustomerAccessAudit.Target("CUSTOMER_SEARCH",null)),eq("GET"),eq(500),isNull(),anyString());
    }
}
