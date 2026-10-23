package com.fincore.support;

import java.util.*;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;

public final class ServiceSecurity {
    private ServiceSecurity() {}
    public static NimbusJwtDecoder decoder(String jwks,String issuer,Set<String> audiences) {
        var decoder=NimbusJwtDecoder.withJwkSetUri(jwks).build();decoder.setJwtValidator(validator(issuer,audiences));return decoder;
    }
    public static OAuth2TokenValidator<Jwt> validator(String issuer,Set<String> audiences) {
        OAuth2TokenValidator<Jwt> required=jwt->jwt.getSubject()!=null && !jwt.getSubject().isBlank() && jwt.getExpiresAt()!=null
            && jwt.getAudience().stream().anyMatch(audiences::contains)
            ?OAuth2TokenValidatorResult.success():OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer),required);
    }
    public static Collection<GrantedAuthority> authorities(Jwt jwt,String resource) {
        var result=new ArrayList<GrantedAuthority>();
        var realm=jwt.getClaimAsMap("realm_access");
        if(realm!=null && realm.get("roles") instanceof List<?> roles) for(Object role:roles) {
            if(role instanceof String text && Set.of("CUSTOMER","ADMIN","FRAUD_ANALYST","FINANCE_OPERATOR").contains(text))result.add(new SimpleGrantedAuthority("ROLE_"+text));
        }
        var resources=jwt.getClaimAsMap("resource_access");
        if(resources!=null && resources.get(resource) instanceof Map<?,?> client && client.get("roles") instanceof List<?> roles) {
            for(Object role:roles)if(role instanceof String text && text.matches("[a-z.]{1,60}"))result.add(new SimpleGrantedAuthority(text));
        }
        return result;
    }
}
