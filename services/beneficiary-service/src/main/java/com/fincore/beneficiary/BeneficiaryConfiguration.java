package com.fincore.beneficiary;

import com.fincore.support.*;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@Import(SupportConfiguration.class)
public class BeneficiaryConfiguration {
    @Bean JwtDecoder jwtDecoder(@Value("${fincore.security.jwk-set-uri}") String jwks,
                                @Value("${fincore.security.issuer}") String issuer) {
        return ServiceSecurity.decoder(jwks, issuer, Set.of("fincore-api","beneficiary-service"));
    }
    @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> ServiceSecurity.authorities(jwt, "beneficiary-service"));
        return http.csrf(c -> c.disable()).sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers(HttpMethod.POST,"/internal/v1/beneficiaries/risk-context").hasAuthority("beneficiary.risk.read")
                .requestMatchers(HttpMethod.GET, "/api/v1/beneficiaries", "/api/v1/beneficiaries/*", "/api/v1/beneficiaries/*/history").hasRole("CUSTOMER")
                .requestMatchers(HttpMethod.POST, "/api/v1/beneficiaries").hasRole("CUSTOMER")
                .requestMatchers(HttpMethod.PUT, "/api/v1/beneficiaries/*").hasRole("CUSTOMER").anyRequest().denyAll())
            .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter))
                .authenticationEntryPoint((q,r,e) -> WebSupport.denied(q,r,401)))
            .exceptionHandling(e -> e.authenticationEntryPoint((q,r,x) -> WebSupport.denied(q,r,401))
                .accessDeniedHandler((q,r,x) -> WebSupport.denied(q,r,403))).build();
    }
    @Bean Remote accountClient(@Value("${TOKEN_ENDPOINT:http://localhost:8180/realms/fincore/protocol/openid-connect/token}") String endpoint,
                               @Value("${BENEFICIARY_CLIENT_SECRET}") String secret) {
        return new Remote(endpoint, "fincore-beneficiary", secret);
    }
}
