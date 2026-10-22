package com.fincore.ledger;

import java.time.Instant;
import java.util.*;
import com.fincore.ledger.configuration.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(LedgerController.class)
@Import({SecurityConfiguration.class, JsonConfiguration.class, CorrelationFilter.class, BodyLimitFilter.class, LedgerSecurityTest.Decoders.class})
class LedgerSecurityTest {
    static final String ISSUER="https://identity.example.invalid/realms/fincore";
    static final RSAKey KEY;
    static { try { KEY=new RSAKeyGenerator(2048).keyID("test-ledger").generate(); } catch(Exception e){ throw new ExceptionInInitializerError(e); } }
    @Autowired MockMvc http;
    @MockitoBean LedgerStore store;
    static final String COMMAND="""
        {"kind":"TRANSFER","businessReference":"transfer-test","currency":"MAD","entries":[
        {"accountId":"00000000-0000-0000-0000-000000000001","side":"DEBIT","amount":"1.00"},
        {"accountId":"00000000-0000-0000-0000-000000000002","side":"CREDIT","amount":"1.00"}]}
        """;
    @TestConfiguration(proxyBeanMethods=false) static class Decoders {
        @Bean @Primary JwtDecoder testDecoder() throws Exception {
            var decoder=NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build();
            decoder.setJwtValidator(SecurityConfiguration.validator(ISSUER,"ledger-service")); return decoder;
        }
    }
    static String token(String issuer,String audience,List<String> roles,Instant expiry,boolean azp) {
        var claims=JwtClaimsSet.builder().issuer(issuer).subject("signed-service-subject").audience(List.of(audience))
            .issuedAt(Instant.now().minusSeconds(600)).expiresAt(expiry)
            .claim("realm_access",Map.of("roles",List.of("CUSTOMER","ADMIN","FINANCE_OPERATOR")))
            .claim("resource_access",Map.of("ledger-service",Map.of("roles",roles)));
        if(azp) claims.claim("azp","verified-operator");
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(KEY))).encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(),claims.build())).getTokenValue();
    }
    static String token(String... roles) { return token(ISSUER,"ledger-service",List.of(roles),Instant.now().plusSeconds(120),true); }
    @Test void rejectsMissingMalformedAndForgedTokens() throws Exception {
        http.perform(get("/internal/v1/ledger/reconciliation")).andExpect(status().isUnauthorized());
        http.perform(get("/internal/v1/ledger/reconciliation").header("Authorization","Bearer broken")).andExpect(status().isUnauthorized());
        String real=token("ledger.read"); String[] pieces=real.split("\\.");
        String forged=pieces[0]+"."+pieces[1]+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[256]);
        http.perform(get("/internal/v1/ledger/reconciliation").header("Authorization","Bearer "+forged)).andExpect(status().isUnauthorized());
        verifyNoInteractions(store);
    }
    @Test void validatesIssuerAudienceExpiryAndAuthorizedParty() throws Exception {
        for(String invalid:List.of(token("wrong","ledger-service",List.of("ledger.read"),Instant.now().plusSeconds(120),true),
            token(ISSUER,"fincore-api",List.of("ledger.read"),Instant.now().plusSeconds(120),true),
            token(ISSUER,"ledger-service",List.of("ledger.read"),Instant.now().minusSeconds(120),true),
            token(ISSUER,"ledger-service",List.of("ledger.read"),Instant.now().plusSeconds(120),false))) {
            http.perform(get("/internal/v1/ledger/reconciliation").header("Authorization","Bearer "+invalid)).andExpect(status().isUnauthorized());
        }
    }
    @Test void customerAndAdminRealmRolesCannotAccessLedger() throws Exception {
        http.perform(get("/internal/v1/ledger/reconciliation").header("Authorization","Bearer "+token())).andExpect(status().isForbidden());
        http.perform(post("/internal/v1/ledger/operations").header("Authorization","Bearer "+token("ledger.read")).contentType("application/json").content(COMMAND).header("Idempotency-Key","test")).andExpect(status().isForbidden());
        verifyNoInteractions(store);
    }
    @Test void takesActorAndCallerOnlyFromValidatedToken() throws Exception {
        when(store.post(eq("verified-operator"),eq("signed-service-subject"),eq("test"),any())).thenReturn(new ObjectMapper().readTree("{\"status\":\"POSTED\"}"));
        http.perform(post("/internal/v1/ledger/operations").header("Authorization","Bearer "+token("ledger.post"))
            .header("X-User-ID","forged").header("X-Caller-Service","forged").header("Idempotency-Key","test")
            .contentType("application/json").content(COMMAND)).andExpect(status().isOk());
        verify(store).post(eq("verified-operator"),eq("signed-service-subject"),eq("test"),any());
    }
    @Test void fundingAndReversalNeedAdditionalTechnicalRoles() throws Exception {
        http.perform(post("/internal/v1/ledger/operations").header("Authorization","Bearer "+token("ledger.post"))
            .header("Idempotency-Key","test").contentType("application/json").content(COMMAND.replace("TRANSFER","DEPOSIT"))).andExpect(status().isForbidden());
        http.perform(post("/internal/v1/ledger/journals/00000000-0000-0000-0000-000000000001/reversal").header("Authorization","Bearer "+token("ledger.post"))
            .header("Idempotency-Key","test").contentType("application/json").content("{\"businessReference\":\"reverse-test\"}")).andExpect(status().isForbidden());
        verifyNoInteractions(store);
    }
    @Test void rejectsNumericAmountsUnknownActorFieldsAndMissingKey() throws Exception {
        for(String bad:List.of(COMMAND.replace("\"1.00\"","1.00"),COMMAND.replace("\"kind\"","\"actor\":\"forged\",\"kind\""))) {
            http.perform(post("/internal/v1/ledger/operations").header("Authorization","Bearer "+token("ledger.post"))
                .header("Idempotency-Key","test").contentType("application/json").content(bad)).andExpect(status().isBadRequest());
        }
        http.perform(post("/internal/v1/ledger/operations").header("Authorization","Bearer "+token("ledger.post"))
            .contentType("application/json").content(COMMAND)).andExpect(status().isBadRequest());
        verifyNoInteractions(store);
    }
    @Test void blocksUnlistedRoutesAndSanitizesCorrelation() throws Exception {
        http.perform(get("/actuator/env").header("Authorization","Bearer "+token("ledger.read"))).andExpect(status().isForbidden());
        http.perform(get("/internal/v1/ledger/reconciliation").header("X-Correlation-ID","invalid value"))
            .andExpect(status().isUnauthorized()).andExpect(header().string("X-Correlation-ID",org.hamcrest.Matchers.matchesPattern("[A-Za-z0-9._-]{1,64}")));
    }
    @Test void rejectsOversizedAndChunkedCommandBodies() throws Exception {
        http.perform(post("/internal/v1/ledger/operations").contentType("application/json").content("x".repeat(16385))).andExpect(status().isPayloadTooLarge());
        http.perform(post("/internal/v1/ledger/operations").header("Transfer-Encoding","chunked").contentType("application/json").content(COMMAND)).andExpect(status().isLengthRequired());
        verifyNoInteractions(store);
    }
    @Test void restrictionChangesCannotOmitExpectedRevision() throws Exception {
        http.perform(put("/internal/v1/ledger/accounts/00000000-0000-0000-0000-000000000001/guard")
            .header("Authorization","Bearer "+token("ledger.guard")).contentType("application/json").content("{\"status\":\"FROZEN\"}"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(store);
    }
}
