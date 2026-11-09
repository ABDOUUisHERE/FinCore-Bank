package com.fincore.audit;

import com.fincore.support.*;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@Import(SupportConfiguration.class)
public class AuditConfiguration {
    @Bean JwtDecoder jwtDecoder(@Value("${fincore.security.jwk-set-uri}")String jwks,@Value("${fincore.security.issuer}")String issuer) {
        return ServiceSecurity.decoder(jwks,issuer,Set.of("fincore-api","audit-service"));
    }
    @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
        var converter=new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt->ServiceSecurity.authorities(jwt,"audit-service"));
        return http.csrf(c->c.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a->a.requestMatchers("/actuator/health","/actuator/health/**").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.GET,"/api/v1/admin/audit/records","/api/v1/admin/audit/quarantine","/api/v1/admin/audit/summary").hasRole("ADMIN")
                .requestMatchers(org.springframework.http.HttpMethod.GET,"/api/v1/admin/operations/me","/api/v1/admin/incidents","/api/v1/admin/incidents/*","/api/v1/admin/incidents/*/history").hasAnyRole("ADMIN","SUPPORT_AGENT")
                .requestMatchers(org.springframework.http.HttpMethod.POST,"/api/v1/admin/incidents").hasAnyRole("ADMIN","SUPPORT_AGENT")
                .requestMatchers(org.springframework.http.HttpMethod.PUT,"/api/v1/admin/incidents/*/status").hasAnyRole("ADMIN","SUPPORT_AGENT")
                .requestMatchers(org.springframework.http.HttpMethod.GET,"/api/v1/admin/incidents/*/account-access").hasAnyRole("ADMIN","SUPPORT_AGENT")
                .requestMatchers(org.springframework.http.HttpMethod.PUT,"/api/v1/admin/incidents/*/account-access").hasRole("ADMIN")
                .requestMatchers(org.springframework.http.HttpMethod.POST,"/api/v1/admin/incidents/*/account-status").hasRole("SUPPORT_AGENT")
                .requestMatchers(org.springframework.http.HttpMethod.GET,"/api/v1/admin/incidents/*/payment-access").hasAnyRole("ADMIN","SUPPORT_AGENT")
                .requestMatchers(org.springframework.http.HttpMethod.PUT,"/api/v1/admin/incidents/*/payment-access").hasRole("ADMIN")
                .requestMatchers(org.springframework.http.HttpMethod.POST,"/api/v1/admin/incidents/*/payment-status").hasRole("SUPPORT_AGENT")
                .requestMatchers(org.springframework.http.HttpMethod.GET,"/api/v1/admin/incidents/*/customer-access").hasAnyRole("ADMIN","SUPPORT_AGENT")
                .requestMatchers(org.springframework.http.HttpMethod.PUT,"/api/v1/admin/incidents/*/customer-access").hasRole("ADMIN")
                .requestMatchers(org.springframework.http.HttpMethod.POST,"/api/v1/admin/incidents/*/customer-profile","/api/v1/admin/support/customers/search").hasRole("SUPPORT_AGENT")
                .anyRequest().denyAll())
            .oauth2ResourceServer(o->o.jwt(j->j.jwtAuthenticationConverter(converter)).authenticationEntryPoint((q,r,e)->WebSupport.denied(q,r,401)))
            .exceptionHandling(e->e.authenticationEntryPoint((q,r,x)->WebSupport.denied(q,r,401)).accessDeniedHandler((q,r,x)->WebSupport.denied(q,r,403))).build();
    }
    @Bean Remote accountClient(@Value("${TOKEN_ENDPOINT:http://localhost:8180/realms/fincore/protocol/openid-connect/token}")String endpoint,@Value("${AUDIT_CLIENT_SECRET}")String secret) {
        return new Remote(endpoint,"fincore-audit",secret);
    }
}
