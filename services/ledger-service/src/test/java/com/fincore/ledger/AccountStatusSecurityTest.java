package com.fincore.ledger;

import com.fincore.ledger.configuration.*;
import com.fincore.support.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AccountStatusController.class)
@Import({SecurityConfiguration.class,JsonConfiguration.class,CorrelationFilter.class,BodyLimitFilter.class,LedgerSecurityTest.Decoders.class})
class AccountStatusSecurityTest {
    @Autowired MockMvc http;@MockitoBean LedgerStore store;
    String signed(String client,String audience,String...roles){
        var claims=JwtClaimsSet.builder().issuer(LedgerSecurityTest.ISSUER).subject("worker").audience(List.of(audience)).issuedAt(Instant.now().minusSeconds(10)).expiresAt(Instant.now().plusSeconds(120)).claim("azp",client).claim("realm_access",Map.of("roles",List.of("ADMIN","FINANCE_OPERATOR"))).claim("resource_access",Map.of("ledger-service",Map.of("roles",List.of(roles)))).build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(LedgerSecurityTest.KEY))).encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(),claims)).getTokenValue();
    }
    String token(String client,String...roles){return signed(client,"ledger-service",roles);}
    @Test void accountIssuerAndExistingReadScopeAreBothRequired()throws Exception{
        var id=UUID.randomUUID();var path="/internal/v1/ledger/accounts/"+id+"/operational-status";
        http.perform(get(path)).andExpect(status().isUnauthorized());
        for(String denied:List.of(token("browser"),token("fincore-account","ledger.provision"),token("fincore-reporting","ledger.read"),token("fincore-card","ledger.read")))http.perform(get(path).header("Authorization","Bearer "+denied)).andExpect(status().isForbidden());
        http.perform(get(path).header("Authorization","Bearer "+signed("browser","fincore-api"))).andExpect(status().isUnauthorized());
        verifyNoInteractions(store);
        when(store.operationalStatus(id)).thenReturn(Json.read("{\"status\":\"FROZEN\",\"guardRevision\":\"9007199254740993\"}"));
        http.perform(get(path).header("Authorization","Bearer "+token("fincore-account","ledger.read"))).andExpect(status().isOk()).andExpect(jsonPath("$.guardRevision").value("9007199254740993"));verify(store).operationalStatus(id);
    }
}
