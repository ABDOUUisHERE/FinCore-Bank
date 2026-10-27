package com.fincore.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fincore.ledger.configuration.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({InstructionController.class,ActivityController.class})
@Import({SecurityConfiguration.class,JsonConfiguration.class,CorrelationFilter.class,BodyLimitFilter.class,InstructionSecurityTest.Decoders.class})
class InstructionSecurityTest {
    @Autowired MockMvc http;
    @MockitoBean JdbcTemplate jdbc;
    @MockitoBean LedgerStore store;
    @MockitoBean LedgerRiskVerifier risk;
    static final String BODY="{\"id\":\"00000000-0000-0000-0000-000000000003\",\"key\":\"stable-key\",\"kind\":\"TRANSFER\",\"debitAccountId\":\"00000000-0000-0000-0000-000000000001\",\"creditAccountId\":\"00000000-0000-0000-0000-000000000002\",\"amount\":\"10.00\",\"currency\":\"MAD\",\"metadata\":{}}";
    @TestConfiguration(proxyBeanMethods=false)static class Decoders{
        @Bean @Primary JwtDecoder primary()throws Exception{var decoder=NimbusJwtDecoder.withPublicKey(LedgerSecurityTest.KEY.toRSAPublicKey()).build();decoder.setJwtValidator(SecurityConfiguration.validator(LedgerSecurityTest.ISSUER,"ledger-service"));return decoder;}
    }
    String token(String client,String...roles){
        var claims=JwtClaimsSet.builder().issuer(LedgerSecurityTest.ISSUER).subject("signed-original-customer").audience(List.of("ledger-service")).issuedAt(Instant.now().minusSeconds(10)).expiresAt(Instant.now().plusSeconds(120))
            .claim("azp",client).claim("realm_access",Map.of("roles",List.of("CUSTOMER"))).claim("resource_access",Map.of("ledger-service",Map.of("roles",List.of(roles)))).build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(LedgerSecurityTest.KEY))).encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(),claims)).getTokenValue();
    }
    @Test void signedCustomerTokenStillNeedsExchangedAuthorizedParty()throws Exception{
        http.perform(post("/internal/v1/ledger/instructions").header("Authorization","Bearer "+token("fincore-customer","ledger.prepare")).contentType("application/json").content(BODY)).andExpect(status().isForbidden());verifyNoInteractions(jdbc);
    }
    @Test void bindsReceiptActorAndIssuerOnlyToValidatedClaims()throws Exception{
        when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<JsonNode>>any(),any(Object[].class))).thenAnswer(call->{
            var parameters=(Object[])call.getRawArguments()[2];assertThat(parameters[1]).isEqualTo("fincore-payment");assertThat(parameters[2]).isEqualTo("signed-original-customer");return List.of(new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"status\":\"AUTHORIZED\"}"));
        });
        http.perform(post("/internal/v1/ledger/instructions").header("Authorization","Bearer "+token("fincore-payment","ledger.prepare")).header("X-User-ID","forged").contentType("application/json").content(BODY)).andExpect(status().isOk());
    }
    @Test void workerCannotPrepareNewCustomerCommandsAndCustomerCannotExecuteOrFund()throws Exception{
        http.perform(post("/internal/v1/ledger/instructions").header("Authorization","Bearer "+token("fincore-payment","ledger.execute")).contentType("application/json").content(BODY)).andExpect(status().isForbidden());
        http.perform(post("/internal/v1/ledger/instructions").header("Authorization","Bearer "+token("fincore-payment","ledger.prepare")).contentType("application/json").content(BODY.replace("TRANSFER","DEPOSIT"))).andExpect(status().isForbidden());
        http.perform(post("/internal/v1/ledger/instructions/00000000-0000-0000-0000-000000000003/execute").header("Authorization","Bearer "+token("fincore-payment","ledger.prepare")).contentType("application/json").content("{}")).andExpect(status().isForbidden());verifyNoInteractions(jdbc);
    }
    @Test void newActivityReadsStillRequirePrivateLedgerReadScope()throws Exception{
        String path="/internal/v1/ledger/accounts/"+UUID.randomUUID()+"/activity";
        http.perform(get(path).header("Authorization","Bearer "+token("fincore-customer","ledger.prepare"))).andExpect(status().isForbidden());
        http.perform(get(path+"?month=invalid").header("Authorization","Bearer "+token("fincore-account","ledger.read"))).andExpect(status().isBadRequest());
        verifyNoInteractions(jdbc);
    }
    @Test void callerExpiryCannotReplaceTheVerifiedGrantDeadline()throws Exception{
        var id=UUID.randomUUID();var assessment=UUID.randomUUID();var deadline=Instant.now().plusSeconds(20);var result=new ObjectMapper().readTree("{\"status\":\"POSTED\"}");
        when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<JsonNode>>any(),any(Object[].class))).thenReturn(List.of(new ObjectMapper().readTree("{\"fingerprint\":\""+"f".repeat(64)+"\",\"outcome\":null}")));
        when(risk.verify(assessment,id,"f".repeat(64))).thenReturn(deadline);when(store.executeInstruction("fincore-payment",id,deadline)).thenReturn(result);
        http.perform(post("/internal/v1/ledger/instructions/"+id+"/execute").header("Authorization","Bearer "+token("fincore-payment","ledger.execute")).contentType("application/json").content("{\"assessmentId\":\""+assessment+"\",\"assessmentExpiresAt\":\"2099-01-01T00:00:00Z\"}")).andExpect(status().isOk());
        verify(store).executeInstruction("fincore-payment",id,deadline);verify(risk).verify(assessment,id,"f".repeat(64));
    }
    @Test void alreadyRecordedOutcomeRemainsRecoverableWithoutFraud()throws Exception{
        var id=UUID.randomUUID();when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<JsonNode>>any(),any(Object[].class))).thenReturn(List.of(new ObjectMapper().readTree("{\"outcome\":{\"status\":\"POSTED\"}}")));
        http.perform(post("/internal/v1/ledger/instructions/"+id+"/execute").header("Authorization","Bearer "+token("fincore-payment","ledger.execute")).contentType("application/json").content("{}")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("POSTED"));
        verifyNoInteractions(risk,store);
    }

}
