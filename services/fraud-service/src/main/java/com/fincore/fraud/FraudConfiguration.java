package com.fincore.fraud;

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
public class FraudConfiguration {
 @Bean JwtDecoder jwtDecoder(@Value("${fincore.security.jwk-set-uri}")String jwks,@Value("${fincore.security.issuer}")String issuer){return ServiceSecurity.decoder(jwks,issuer,Set.of("fincore-api","fraud-service"));}
 @Bean SecurityFilterChain security(HttpSecurity http)throws Exception{
  var converter=new JwtAuthenticationConverter();converter.setJwtGrantedAuthoritiesConverter(jwt->ServiceSecurity.authorities(jwt,"fraud-service"));
  return http.csrf(c->c.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
   .authorizeHttpRequests(a->a.requestMatchers("/actuator/health","/actuator/health/**").permitAll()
    .requestMatchers(HttpMethod.POST,"/internal/v1/risk-assessments").hasAuthority("fraud.assess")
    .requestMatchers(HttpMethod.POST,"/internal/v1/card-risk-assessments").hasAuthority("fraud.card.assess")
    .requestMatchers(HttpMethod.POST,"/internal/v1/risk-assessments/*/validate").hasAuthority("fraud.validate")
    .requestMatchers(HttpMethod.POST,"/internal/v1/fraud/payments/*/decision").hasAuthority("fraud.review")
    .requestMatchers("/api/v1/admin/fraud-alerts","/api/v1/admin/fraud-cases","/api/v1/admin/fraud-cases/*","/api/v1/admin/fraud-cases/*/decisions","/api/v1/admin/fraud-rules").hasRole("FRAUD_ANALYST")
    .anyRequest().denyAll())
   .oauth2ResourceServer(o->o.jwt(j->j.jwtAuthenticationConverter(converter)).authenticationEntryPoint((q,r,e)->WebSupport.denied(q,r,401)))
   .exceptionHandling(e->e.authenticationEntryPoint((q,r,x)->WebSupport.denied(q,r,401)).accessDeniedHandler((q,r,x)->WebSupport.denied(q,r,403))).build();
 }
 @Bean Remote remote(@Value("${TOKEN_ENDPOINT:http://localhost:8180/realms/fincore/protocol/openid-connect/token}")String endpoint,@Value("${FRAUD_CLIENT_SECRET}")String secret){return new Remote(endpoint,"fincore-fraud",secret);}
}
