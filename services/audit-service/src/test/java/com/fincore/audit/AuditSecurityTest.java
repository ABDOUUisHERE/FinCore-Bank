package com.fincore.audit;

import com.fincore.support.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuditController.class)
@Import({AuditConfiguration.class,AuditSecurityTest.Decoders.class})
class AuditSecurityTest {
    @Autowired MockMvc http;@MockitoBean AuditStore store;@MockitoBean Remote remote;
    @TestConfiguration(proxyBeanMethods=false)static class Decoders {
        @Bean @Primary JwtDecoder decoder()throws Exception{var d=NimbusJwtDecoder.withPublicKey(SignedTokens.KEY.toRSAPublicKey()).build();d.setJwtValidator(ServiceSecurity.validator(SignedTokens.ISSUER,Set.of("fincore-api","audit-service")));return d;}
    }
    static String token(String subject,String role) { return SignedTokens.token("fincore-api",subject,"fincore-admin",List.of(role),Map.of()); }
    @Test void onlyExplicitAdminMayReadGlobalFactsAndQuarantine()throws Exception {
        for(String role:List.of("CUSTOMER","SUPPORT_AGENT","FRAUD_ANALYST","FINANCE_OPERATOR"))for(String path:List.of("records","summary","quarantine"))
            http.perform(get("/api/v1/admin/audit/"+path).header("Authorization","Bearer "+token("other",role))).andExpect(status().isForbidden());
        verifyNoInteractions(store);
        when(store.facts(null,null,null)).thenReturn(Map.of("items",List.of()));
        http.perform(get("/api/v1/admin/audit/records").header("Authorization","Bearer "+token("admin","ADMIN"))).andExpect(status().isOk());
    }
    @Test void supportScopeUsesTheSignedSubjectDespiteForgedActorHeaders()throws Exception {
        var id=UUID.randomUUID();when(store.incident(id,"support-owner",false)).thenThrow(new ApiFailure(404,"INCIDENT_NOT_FOUND"));
        http.perform(get("/api/v1/admin/incidents/"+id).header("Authorization","Bearer "+token("support-owner","SUPPORT_AGENT")).header("X-User-ID","admin")).andExpect(status().isNotFound());
        http.perform(get("/api/v1/admin/operations/me").header("Authorization","Bearer "+token("support-owner","SUPPORT_AGENT"))).andExpect(status().isOk()).andExpect(jsonPath("$.subject").value("support-owner")).andExpect(jsonPath("$.scope").value("OWN_INCIDENTS"));
        verify(store).incident(id,"support-owner",false);
    }
    @Test void financialRolesAndMachineScopesDoNotGrantIncidentPrivileges()throws Exception {
        for(String role:List.of("CUSTOMER","FRAUD_ANALYST","FINANCE_OPERATOR"))
            http.perform(get("/api/v1/admin/incidents").header("Authorization","Bearer "+token("other",role))).andExpect(status().isForbidden());
        var machine=SignedTokens.token("audit-service","worker","machine",List.of(),Map.of("audit-service",Map.of("roles",List.of("audit.read","incident.manage"))));
        http.perform(get("/api/v1/admin/incidents").header("Authorization","Bearer "+machine)).andExpect(status().isForbidden());
        http.perform(get("/api/v1/admin/audit/records")).andExpect(status().isUnauthorized());verifyNoInteractions(store);
    }
    @Test void unknownBodyFieldsCannotChooseTheIncidentOwner()throws Exception {
        http.perform(post("/api/v1/admin/incidents").header("Authorization","Bearer "+token("owner","SUPPORT_AGENT")).header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content("{\"createdBy\":\"another-owner\"}")).andExpect(status().isBadRequest());verifyNoInteractions(store);
    }
    @Test void signedTokensWithWrongIssuerAudienceOrExpiredLifetimeAreRejected()throws Exception {
        var now=java.time.Instant.now();
        for(var input:List.of(List.of("https://foreign.invalid","fincore-api","120"),List.of(SignedTokens.ISSUER,"foreign-api","120"),List.of(SignedTokens.ISSUER,"fincore-api","-300"))) {
            var claims=JwtClaimsSet.builder().issuer(input.get(0)).subject("admin").audience(List.of(input.get(1)))
                .issuedAt(now.minusSeconds(600)).expiresAt(now.plusSeconds(Long.parseLong(input.get(2))))
                .claim("realm_access",Map.of("roles",List.of("ADMIN"))).build();
            var encoder=new NimbusJwtEncoder(new com.nimbusds.jose.jwk.source.ImmutableJWKSet<>(new com.nimbusds.jose.jwk.JWKSet(SignedTokens.KEY)));
            var signed=encoder.encode(JwtEncoderParameters.from(JwsHeader.with(org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256).build(),claims)).getTokenValue();
            http.perform(get("/api/v1/admin/audit/records").header("Authorization","Bearer "+signed)).andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(store);
    }
}
