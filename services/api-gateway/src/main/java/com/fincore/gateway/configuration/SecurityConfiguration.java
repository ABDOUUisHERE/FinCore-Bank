package com.fincore.gateway.configuration;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfiguration {
    public static OAuth2TokenValidator<Jwt> validator(String issuer, String audience) {
        OAuth2TokenValidator<Jwt> audienceValidator = jwt -> jwt.getAudience().contains(audience)
            && jwt.getExpiresAt() != null && jwt.getSubject() != null && !jwt.getSubject().isBlank()
            ? OAuth2TokenValidatorResult.success()
            : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid audience", null));
        return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), audienceValidator);
    }

    @Bean
    ReactiveJwtDecoder jwtDecoder(@Value("${fincore.security.jwk-set-uri}") String jwks,
                                 @Value("${fincore.security.issuer}") String issuer,
                                 @Value("${fincore.security.audience}") String audience) {
        var decoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwks).build();
        decoder.setJwtValidator(validator(issuer, audience));
        return decoder;
    }

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http,
            @Value("${fincore.security.origins}") List<String> origins) {
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(origins);
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Correlation-ID", "Idempotency-Key"));
        cors.setExposedHeaders(List.of("X-Correlation-ID", "Location", "Retry-After"));
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Map<String, Object> realm = jwt.getClaimAsMap("realm_access");
            if (realm == null || !(realm.get("roles") instanceof List<?> roles)) return List.of();
            var allowed = List.of("CUSTOMER", "SUPPORT_AGENT", "FRAUD_ANALYST", "FINANCE_OPERATOR", "ADMIN");
            return roles.stream().filter(String.class::isInstance).map(String.class::cast)
                .filter(allowed::contains).distinct().map(role -> (org.springframework.security.core.GrantedAuthority)
                    new SimpleGrantedAuthority("ROLE_" + role)).toList();
        });
        return http.csrf(csrf -> csrf.disable()).cors(c -> c.configurationSource(source))
            .addFilterBefore(new ApiRateLimitFilter(), org.springframework.security.config.web.server.SecurityWebFiltersOrder.AUTHORIZATION)
            .authorizeExchange(auth -> auth
                .pathMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .pathMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/customers/me").hasRole("CUSTOMER")
                .pathMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/customers/me/profile", "/api/v1/accounts", "/api/v1/accounts/*", "/api/v1/accounts/*/balance", "/api/v1/accounts/*/transactions", "/api/v1/accounts/*/activity", "/api/v1/beneficiaries", "/api/v1/beneficiaries/*", "/api/v1/beneficiaries/*/history", "/api/v1/transfers", "/api/v1/transfers/*", "/api/v1/transfers/*/history", "/api/v1/notifications", "/api/v1/notifications/stream", "/api/v1/notifications/preferences", "/api/v1/notifications/emails").hasRole("CUSTOMER")
                .pathMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/customers/me/onboarding", "/api/v1/accounts", "/api/v1/transfers", "/api/v1/transfers/*/cancel", "/api/v1/beneficiaries").hasRole("CUSTOMER")
                .pathMatchers(org.springframework.http.HttpMethod.PATCH, "/api/v1/customers/me/profile").hasRole("CUSTOMER")
                .pathMatchers(org.springframework.http.HttpMethod.PUT, "/api/v1/accounts/*/guard", "/api/v1/beneficiaries/*", "/api/v1/notifications/*/read", "/api/v1/notifications/preferences").hasRole("CUSTOMER")
                .anyExchange().denyAll())
            .oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.jwtAuthenticationConverter(
                new ReactiveJwtAuthenticationConverterAdapter(converter)))
                .authenticationEntryPoint((exchange, error) -> ProblemResponses.write(exchange, 401)))
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((exchange, error) -> ProblemResponses.write(exchange, 401))
                .accessDeniedHandler((exchange, error) -> ProblemResponses.write(exchange, 403)))
            .build();
    }
}
