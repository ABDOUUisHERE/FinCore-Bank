package com.fincore.support;

import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.time.Instant;
import java.util.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;

public final class SignedTokens {
    public static final String ISSUER="https://identity.example.invalid/realms/fincore";
    public static final RSAKey KEY;
    static{try{KEY=new RSAKeyGenerator(2048).keyID("synthetic-business-key").generate();}catch(Exception error){throw new ExceptionInInitializerError(error);}}
    private SignedTokens(){}
    public static String token(String audience,String subject,String client,List<String> realm,Map<String,Object> resources){
        var claims=JwtClaimsSet.builder().issuer(ISSUER).subject(subject).audience(List.of(audience)).issuedAt(Instant.now().minusSeconds(10)).expiresAt(Instant.now().plusSeconds(120))
            .claim("azp",client).claim("realm_access",Map.of("roles",realm)).claim("resource_access",resources).build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(KEY))).encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(),claims)).getTokenValue();
    }
    public static String customer(){return token("fincore-api","verified-customer","fincore-customer",List.of("CUSTOMER"),Map.of());}
}
