package com.fincore.account;

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
public class AccountConfiguration {
    @Bean JwtDecoder jwtDecoder(@Value("${fincore.security.jwk-set-uri}")String jwks,@Value("${fincore.security.issuer}")String issuer){return ServiceSecurity.decoder(jwks,issuer,Set.of("fincore-api","account-service"));}
    @Bean SecurityFilterChain security(HttpSecurity http)throws Exception{
        var converter=new JwtAuthenticationConverter();converter.setJwtGrantedAuthoritiesConverter(jwt->ServiceSecurity.authorities(jwt,"account-service"));
        return http.csrf(c->c.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a->a.requestMatchers("/actuator/health","/actuator/health/**").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.GET,"/internal/v1/accounts/*").hasAuthority("account.lookup")
                .requestMatchers("/api/v1/accounts","/api/v1/accounts/*","/api/v1/accounts/*/balance","/api/v1/accounts/*/transactions","/api/v1/accounts/*/guard").hasRole("CUSTOMER").anyRequest().denyAll())
            .oauth2ResourceServer(o->o.jwt(j->j.jwtAuthenticationConverter(converter)).authenticationEntryPoint((q,r,e)->WebSupport.denied(q,r,401)))
            .exceptionHandling(e->e.authenticationEntryPoint((q,r,x)->WebSupport.denied(q,r,401)).accessDeniedHandler((q,r,x)->WebSupport.denied(q,r,403))).build();
    }
    @Bean Remote ledgerClient(@Value("${TOKEN_ENDPOINT:http://localhost:8180/realms/fincore/protocol/openid-connect/token}")String endpoint,@Value("${ACCOUNT_CLIENT_SECRET}")String secret){return new Remote(endpoint,"fincore-account",secret);}
}
