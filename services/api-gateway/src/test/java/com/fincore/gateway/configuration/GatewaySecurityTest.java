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
        @GetMapping("/api/v1/admin/incidents/test/account-access") Map<String,Object> grant(){return Map.of("state","NONE");}
        @org.springframework.web.bind.annotation.PutMapping("/api/v1/admin/incidents/test/account-access") Map<String,Object> issue(){return Map.of("state","ACTIVE");}
        @org.springframework.web.bind.annotation.PostMapping("/api/v1/admin/incidents/test/account-status") Map<String,Object> support(){return Map.of("ledgerState","UNAVAILABLE");}
        @org.springframework.web.bind.annotation.PostMapping("/api/v1/admin/customers/search") Map<String,Object> search(){return Map.of("items",List.of());}
        @GetMapping({"/api/v1/admin/customers/test","/api/v1/admin/accounts/test","/api/v1/admin/transactions/test","/api/v1/admin/ledger/journals/test"}) Map<String,Object> inspection(){return Map.of("status","synthetic");}
        @GetMapping({"/api/v1/admin/audit/records","/api/v1/admin/audit/summary","/api/v1/admin/audit/quarantine","/api/v1/admin/incidents","/api/v1/admin/operations/me"}) Map<String,Object> operations(){return Map.of("items",List.of());}
        @GetMapping("/api/v1/admin/fraud-cases") Map<String,Object> fraud(){return Map.of("items",List.of());}
        @GetMapping("/api/v1/customers/me")
        Map<String, String> me(@org.springframework.web.bind.annotation.RequestParam(defaultValue = "false") boolean unavailable) {
            if (unavailable) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "sensitive internal detail");
            return Map.of("subject", "synthetic-customer-id");
        }
    }

    @Test void missingAndMalformedTokensAreRejected() {
        http.get().uri("/api/v1/customers/me").exchange().expectStatus().isUnauthorized()
            .expectHeader().contentType("application/problem+json").expectHeader().exists("X-Correlation-ID");
        get("not-a-token").expectStatus().isUnauthorized();
    }
    @Test void inspectionHasSeparateOperationalAndFinancialReadPermissions() {
        for(String role:List.of("ADMIN","FINANCE_OPERATOR","SUPPORT_AGENT","FRAUD_ANALYST","CUSTOMER")) {
            String token=TestTokens.token(TestTokens.ISSUER,"fincore-api",List.of(role),Instant.now().plusSeconds(120));
            for(String path:List.of("/api/v1/admin/customers/test","/api/v1/admin/accounts/test","/api/v1/admin/transactions/test","/api/v1/admin/ledger/journals/test")) {
                boolean allowed=path.contains("/ledger/")?role.equals("FINANCE_OPERATOR"):role.equals("ADMIN");
                var response=http.get().uri(path).headers(h->h.setBearerAuth(token)).exchange();if(allowed)response.expectStatus().isOk();else response.expectStatus().isForbidden();
                http.post().uri(path).headers(h->h.setBearerAuth(token)).exchange().expectStatus().isForbidden();
            }
        }
    }
    @Test void customerSearchIsAnAdminOnlyPostWithNoGetDirectory() {
        String path="/api/v1/admin/customers/search";
        http.post().uri(path).contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue("{\"namePrefix\":\"Syn\"}").exchange().expectStatus().isUnauthorized();
        for(String role:List.of("ADMIN","CUSTOMER","SUPPORT_AGENT","FINANCE_OPERATOR","FRAUD_ANALYST")) {
            String token=TestTokens.token(TestTokens.ISSUER,"fincore-api",List.of(role),Instant.now().plusSeconds(120));
            var response=http.post().uri(path).headers(h->h.setBearerAuth(token)).contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue("{\"namePrefix\":\"Syn\"}").exchange();
            if(role.equals("ADMIN"))response.expectStatus().isOk();else response.expectStatus().isForbidden();
            http.get().uri(path+"?namePrefix=Syn").headers(h->h.setBearerAuth(token)).exchange().expectStatus().isForbidden();
            http.put().uri(path).headers(h->h.setBearerAuth(token)).contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue("{}").exchange().expectStatus().isForbidden();
        }
    }
    @Test void investigationRoutesRequireFraudAnalystRatherThanImplicitAdmin() {
        for(var role:List.of("CUSTOMER","ADMIN"))http.get().uri("/api/v1/admin/fraud-cases").headers(h->h.setBearerAuth(TestTokens.token(TestTokens.ISSUER,"fincore-api",List.of(role),Instant.now().plusSeconds(120)))).exchange().expectStatus().isForbidden();
        http.get().uri("/api/v1/admin/fraud-cases").headers(h->h.setBearerAuth(TestTokens.token(TestTokens.ISSUER,"fincore-api",List.of("FRAUD_ANALYST"),Instant.now().plusSeconds(120)))).exchange().expectStatus().isOk();
    }
    @Test void auditAndIncidentsRequireTheirExplicitStaffRoles() {
        for(String role:List.of("CUSTOMER","FRAUD_ANALYST","FINANCE_OPERATOR","SUPPORT_AGENT","ADMIN")) {
            String token=TestTokens.token(TestTokens.ISSUER,"fincore-api",List.of(role),Instant.now().plusSeconds(120));
            for(String path:List.of("/api/v1/admin/audit/records","/api/v1/admin/audit/summary","/api/v1/admin/audit/quarantine")) {
                var response=http.get().uri(path).headers(h->h.setBearerAuth(token)).exchange();
                if(role.equals("ADMIN"))response.expectStatus().isOk();else response.expectStatus().isForbidden();
            }
            for(String path:List.of("/api/v1/admin/incidents","/api/v1/admin/operations/me")) {
                var response=http.get().uri(path).headers(h->h.setBearerAuth(token)).exchange();
                if(List.of("ADMIN","SUPPORT_AGENT").contains(role))response.expectStatus().isOk();else response.expectStatus().isForbidden();
            }
        }
        for(String token:List.of(TestTokens.token("https://wrong.invalid","fincore-api",List.of("ADMIN"),Instant.now().plusSeconds(120)),
            TestTokens.token(TestTokens.ISSUER,"wrong-api",List.of("ADMIN"),Instant.now().plusSeconds(120)),
            TestTokens.token(TestTokens.ISSUER,"fincore-api",List.of("ADMIN"),Instant.now().minusSeconds(120))))
            http.get().uri("/api/v1/admin/audit/records").headers(h->h.setBearerAuth(token)).exchange().expectStatus().isUnauthorized();
    }

    @Test void supportGrantRoutesNeverInheritUnrestrictedAdministrativeOrFinancialAccess() {
        for(String role:List.of("CUSTOMER","FRAUD_ANALYST","FINANCE_OPERATOR","SUPPORT_AGENT","ADMIN")) {
            String token=TestTokens.token(TestTokens.ISSUER,"fincore-api",List.of(role),Instant.now().plusSeconds(120));
            var read=http.get().uri("/api/v1/admin/incidents/test/account-access").headers(h->h.setBearerAuth(token)).exchange();
            if(List.of("ADMIN","SUPPORT_AGENT").contains(role))read.expectStatus().isOk();else read.expectStatus().isForbidden();
            var write=http.put().uri("/api/v1/admin/incidents/test/account-access").headers(h->h.setBearerAuth(token)).bodyValue("{}").exchange();
            if(role.equals("ADMIN"))write.expectStatus().isOk();else write.expectStatus().isForbidden();
            var inspect=http.post().uri("/api/v1/admin/incidents/test/account-status").headers(h->h.setBearerAuth(token)).bodyValue("{}").exchange();
            if(role.equals("SUPPORT_AGENT"))inspect.expectStatus().isOk();else inspect.expectStatus().isForbidden();
            http.get().uri("/api/v1/admin/incidents/test/account-status").headers(h->h.setBearerAuth(token)).exchange().expectStatus().isForbidden();
        }
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

    @Test void dependencyErrorsUseRedactedProblemDetails() {
        http.get().uri("/api/v1/customers/me?unavailable=true")
            .headers(headers -> headers.setBearerAuth(TestTokens.customer())).exchange()
            .expectStatus().isEqualTo(503).expectHeader().contentType("application/problem+json")
            .expectBody().jsonPath("$.status").isEqualTo(503)
            .jsonPath("$.detail").isEqualTo("The request could not be accepted.");
    }

    private WebTestClient.ResponseSpec get(String token) {
        return http.get().uri("/api/v1/customers/me").headers(headers -> headers.setBearerAuth(token)).exchange();
    }
}
