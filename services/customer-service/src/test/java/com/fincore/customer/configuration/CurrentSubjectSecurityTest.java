package com.fincore.customer.configuration;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.MockMvc;
import com.fincore.customer.adapter.in.web.CurrentSubjectController;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CurrentSubjectController.class)
@Import({SecurityConfiguration.class, CorrelationFilter.class, CurrentSubjectSecurityTest.Decoders.class})
class CurrentSubjectSecurityTest {
    @Autowired MockMvc http;

    @TestConfiguration(proxyBeanMethods = false)
    static class Decoders {
        @Bean @Primary
        JwtDecoder testDecoder() throws Exception {
            var decoder = NimbusJwtDecoder.withPublicKey(TestTokens.KEY.toRSAPublicKey()).build();
            decoder.setJwtValidator(SecurityConfiguration.validator(TestTokens.ISSUER, "fincore-api"));
            return decoder;
        }
    }

    @Test void returnsOnlyValidatedSubjectAndKnownRoles() throws Exception {
        http.perform(get("/api/v1/customers/me").header("Authorization", "Bearer " + TestTokens.customer())
                .header("X-User-ID", "forged-other-customer").header("X-Correlation-ID", "test-request-42"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.subject").value("synthetic-customer-id"))
            .andExpect(jsonPath("$.roles[0]").value("CUSTOMER"))
            .andExpect(header().string("X-Correlation-ID", "test-request-42"));
    }

    @Test void rejectsUnauthenticatedAndMalformedTokensDirectly() throws Exception {
        http.perform(get("/api/v1/customers/me")).andExpect(status().isUnauthorized())
            .andExpect(content().contentType("application/problem+json"))
            .andExpect(jsonPath("$.status").value(401));
        http.perform(get("/api/v1/customers/me").header("Authorization", "Bearer broken"))
            .andExpect(status().isUnauthorized());
    }

    @Test void rejectsWrongIssuerAudienceExpiryAndRoleDirectly() throws Exception {
        expect(TestTokens.token("https://wrong.invalid", "fincore-api", List.of("CUSTOMER"), Instant.now().plusSeconds(120)), 401);
        expect(TestTokens.token(TestTokens.ISSUER, "wrong-api", List.of("CUSTOMER"), Instant.now().plusSeconds(120)), 401);
        expect(TestTokens.token(TestTokens.ISSUER, "fincore-api", List.of("CUSTOMER"), Instant.now().minusSeconds(120)), 401);
        expect(TestTokens.token(TestTokens.ISSUER, "fincore-api", List.of("ADMIN"), Instant.now().plusSeconds(120)), 403);
    }

    @Test void blocksOtherRoutesEvenForCustomer() throws Exception {
        http.perform(get("/actuator/env").header("Authorization", "Bearer " + TestTokens.customer()))
            .andExpect(status().isForbidden());
        http.perform(get("/api/v1/customers/another-user").header("Authorization", "Bearer " + TestTokens.customer()))
            .andExpect(status().isForbidden());
    }

    private void expect(String token, int status) throws Exception {
        http.perform(get("/api/v1/customers/me").header("Authorization", "Bearer " + token)).andExpect(status().is(status));
    }
}
