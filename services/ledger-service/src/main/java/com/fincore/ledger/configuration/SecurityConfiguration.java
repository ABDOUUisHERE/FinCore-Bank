package com.fincore.ledger.configuration;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.http.MediaType;

@Configuration
public class SecurityConfiguration {
    public static OAuth2TokenValidator<Jwt> validator(String issuer, String audience) {
        OAuth2TokenValidator<Jwt> audienceValidator = jwt -> jwt.getAudience().contains(audience)
            && jwt.getClaimAsString("azp") != null && !jwt.getClaimAsString("azp").isBlank()
            && jwt.getExpiresAt() != null && jwt.getSubject() != null && !jwt.getSubject().isBlank()
            ? OAuth2TokenValidatorResult.success()
            : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid audience", null));
        return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), audienceValidator);
    }

    @Bean
    JwtDecoder jwtDecoder(@Value("${fincore.security.jwk-set-uri}") String jwks,
                         @Value("${fincore.security.issuer}") String issuer,
                         @Value("${fincore.security.audience}") String audience) {
        var decoder = NimbusJwtDecoder.withJwkSetUri(jwks).build();
        decoder.setJwtValidator(validator(issuer, audience));
        return decoder;
    }

    public static Collection<GrantedAuthority> roles(Jwt jwt) {
        Map<String, Object> resources = jwt.getClaimAsMap("resource_access");
        if (resources == null || !(resources.get("ledger-service") instanceof Map<?, ?> client)
            || !(client.get("roles") instanceof List<?> values)) return List.of();
        return values.stream().filter(String.class::isInstance).map(String.class::cast)
            .filter(role -> List.of("ledger.provision", "ledger.guard", "ledger.post", "ledger.fund", "ledger.reverse", "ledger.read", "ledger.prepare", "ledger.prepare.fund", "ledger.prepare.reverse", "ledger.execute", "ledger.instructions.read").contains(role))
            .distinct().<GrantedAuthority>map(SimpleGrantedAuthority::new).toList();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(SecurityConfiguration::roles);
        return http.csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.POST, "/internal/v1/ledger/instructions").hasAnyAuthority("ledger.prepare", "ledger.prepare.fund", "ledger.prepare.reverse")
                .requestMatchers(org.springframework.http.HttpMethod.POST, "/internal/v1/ledger/instructions/*/execute").hasAuthority("ledger.execute")
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/internal/v1/ledger/settlement/*").hasAuthority("ledger.execute")
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/internal/v1/ledger/instructions", "/internal/v1/ledger/instructions/*").hasAuthority("ledger.instructions.read")
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/internal/v1/ledger/**").hasAuthority("ledger.read")
                .requestMatchers(org.springframework.http.HttpMethod.POST, "/internal/v1/ledger/accounts").hasAuthority("ledger.provision")
                .requestMatchers(org.springframework.http.HttpMethod.PUT, "/internal/v1/ledger/accounts/*/guard").hasAuthority("ledger.guard")
                .requestMatchers(org.springframework.http.HttpMethod.POST, "/internal/v1/ledger/operations").hasAuthority("ledger.post")
                .requestMatchers(org.springframework.http.HttpMethod.POST, "/internal/v1/ledger/journals/*/reversal").hasAuthority("ledger.post")
                .anyRequest().denyAll())
            .oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                .authenticationEntryPoint((request, response, exception) -> problem(request, response, 401)))
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, exception) -> problem(request, response, 401))
                .accessDeniedHandler((request, response, exception) -> problem(request, response, 403)))
            .build();
    }

    private static void problem(jakarta.servlet.http.HttpServletRequest request,
                                jakarta.servlet.http.HttpServletResponse response, int status) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        if (status == 401) response.setHeader("WWW-Authenticate", "Bearer");
        var id = response.getHeader("X-Correlation-ID");
        var body = Map.of("type", "about:blank", "title", status == 401 ? "Unauthorized" : "Forbidden",
            "status", status, "detail", "This request is not authorized.", "code", status == 401 ? "UNAUTHORIZED" : "FORBIDDEN",
            "correlationId", id == null ? "unavailable" : id);
        new com.fasterxml.jackson.databind.ObjectMapper().writeValue(response.getOutputStream(), body);
    }
}
