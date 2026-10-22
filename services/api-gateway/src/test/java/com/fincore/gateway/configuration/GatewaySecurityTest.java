package com.fincore.gateway.configuration;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({GatewaySecurityTest.Decoders.class, GatewaySecurityTest.Endpoint.class})
class GatewaySecurityTest {
    @Autowired WebTestClient http;

    @TestConfiguration(proxyBeanMethods = false)
    static class Decoders {
        @Bean @Primary
        ReactiveJwtDecoder testDecoder() throws Exception {
            var decoder = NimbusReactiveJwtDecoder.withPublicKey(TestTokens.KEY.toRSAPublicKey()).build();
            decoder.setJwtValidator(SecurityConfiguration.validator(TestTokens.ISSUER, "fincore-api"));
            return decoder;
        }
    }

    @RestController
    static class Endpoint {
        @GetMapping("/api/v1/customers/me")
        Map<String, String> me() { return Map.of("subject", "synthetic-customer-id"); }
    }

    @Test void missingAndMalformedTokensAreRejected() {
        http.get().uri("/api/v1/customers/me").exchange().expectStatus().isUnauthorized()
            .expectHeader().contentType("application/problem+json").expectHeader().exists("X-Correlation-ID");
        get("not-a-token").expectStatus().isUnauthorized();
    }

    @Test void validatesSignedIssuerAudienceExpiryAndRole() {
        get(TestTokens.customer()).expectStatus().isOk().expectBody().jsonPath("$.subject").isEqualTo("synthetic-customer-id");
        get(TestTokens.token("https://wrong.invalid", "fincore-api", List.of("CUSTOMER"), Instant.now().plusSeconds(120)))
            .expectStatus().isUnauthorized();
        get(TestTokens.token(TestTokens.ISSUER, "wrong-api", List.of("CUSTOMER"), Instant.now().plusSeconds(120)))
            .expectStatus().isUnauthorized();
        get(TestTokens.token(TestTokens.ISSUER, "fincore-api", List.of("CUSTOMER"), Instant.now().minusSeconds(120)))
            .expectStatus().isUnauthorized();
        get(TestTokens.token(TestTokens.ISSUER, "fincore-api", List.of("ADMIN"), Instant.now().plusSeconds(120)))
            .expectStatus().isForbidden();
    }

    @Test void healthDoesNotExposeConfigurationAndUnknownRoutesAreProtected() {
        http.get().uri("/actuator/health/liveness").exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.status").isEqualTo("UP").jsonPath("$.components").doesNotExist();
        http.get().uri("/actuator/env").exchange().expectStatus().isUnauthorized();
        http.get().uri("/internal/v1/ledger/operations").exchange().expectStatus().isUnauthorized();
    }

    @Test void restrictsCorsAndSanitizesCorrelationIds() {
        http.options().uri("/api/v1/customers/me").header("Origin", "http://localhost:5173")
            .header("Access-Control-Request-Method", "GET").exchange().expectStatus().isOk()
            .expectHeader().valueEquals("Access-Control-Allow-Origin", "http://localhost:5173");
        http.options().uri("/api/v1/customers/me").header("Origin", "https://attacker.invalid")
            .header("Access-Control-Request-Method", "GET").exchange().expectStatus().isForbidden();
        http.get().uri("/api/v1/customers/me").header("X-Correlation-ID", "unsafe value")
            .exchange().expectStatus().isUnauthorized().expectHeader().valueMatches("X-Correlation-ID", "[a-f0-9-]{36}");
    }

    private WebTestClient.ResponseSpec get(String token) {
        return http.get().uri("/api/v1/customers/me").headers(headers -> headers.setBearerAuth(token)).exchange();
    }
}
