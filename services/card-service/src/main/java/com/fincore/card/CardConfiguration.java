package com.fincore.card;

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
public class CardConfiguration {
    @Bean JwtDecoder jwtDecoder(@Value("${fincore.security.jwk-set-uri}")String jwks,@Value("${fincore.security.issuer}")String issuer){return ServiceSecurity.decoder(jwks,issuer,Set.of("fincore-api","card-service"));}
    @Bean SecurityFilterChain security(HttpSecurity http)throws Exception{
        var converter=new JwtAuthenticationConverter();converter.setJwtGrantedAuthoritiesConverter(jwt->ServiceSecurity.authorities(jwt,"card-service"));
        return http.csrf(c->c.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a->a.requestMatchers("/actuator/health","/actuator/health/**").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.GET,"/internal/v1/cards/*/risk-context").hasAuthority("card.risk.read")
                .requestMatchers("/api/v1/cards","/api/v1/cards/*","/api/v1/cards/*/authorizations","/api/v1/card-merchants","/api/v1/card-authorizations","/api/v1/card-authorizations/*","/api/v1/card-authorizations/*/settle").hasRole("CUSTOMER").anyRequest().denyAll())
            .oauth2ResourceServer(o->o.jwt(j->j.jwtAuthenticationConverter(converter)).authenticationEntryPoint((q,r,e)->WebSupport.denied(q,r,401)))
            .exceptionHandling(e->e.authenticationEntryPoint((q,r,x)->WebSupport.denied(q,r,401)).accessDeniedHandler((q,r,x)->WebSupport.denied(q,r,403))).build();
    }
    @Bean Remote remote(@Value("${TOKEN_ENDPOINT:http://localhost:8180/realms/fincore/protocol/openid-connect/token}")String endpoint,@Value("${CARD_CLIENT_SECRET}")String secret){return new Remote(endpoint,"fincore-card",secret);}
}
