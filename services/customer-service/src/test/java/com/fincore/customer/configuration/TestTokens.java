package com.fincore.customer.configuration;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;

final class TestTokens {
    static final String ISSUER = "https://identity.example.invalid/realms/fincore";
    static final RSAKey KEY;
    static {
        try { KEY = new RSAKeyGenerator(2048).keyID("test-key").generate(); }
        catch (Exception exception) { throw new ExceptionInInitializerError(exception); }
    }
    static String token(String issuer, String audience, List<String> roles, Instant expiry) {
        var encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(KEY)));
        var claims = JwtClaimsSet.builder().issuer(issuer).subject("synthetic-customer-id")
            .audience(List.of(audience)).issuedAt(Instant.now().minusSeconds(600)).expiresAt(expiry)
            .claim("realm_access", Map.of("roles", roles)).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
    }
    static String customer() { return token(ISSUER, "fincore-api", List.of("CUSTOMER"), Instant.now().plusSeconds(120)); }
}
