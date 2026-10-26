package com.fincore.notification;

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

@WebMvcTest(value=NotificationController.class,properties="NOTIFICATION_CLIENT_SECRET=synthetic-test-secret")
@Import({NotificationConfiguration.class,NotificationSecurityTest.Decoders.class})
class NotificationSecurityTest {
    @Autowired MockMvc http;
    @MockitoBean NotificationStore store;
    @MockitoBean Remote remote;
    @TestConfiguration(proxyBeanMethods=false) static class Decoders {
        @Bean @Primary JwtDecoder decoder() throws Exception {
            var decoder=NimbusJwtDecoder.withPublicKey(SignedTokens.KEY.toRSAPublicKey()).build();
            decoder.setJwtValidator(ServiceSecurity.validator(SignedTokens.ISSUER,Set.of("fincore-api","notification-service")));return decoder;
        }
    }
    @Test void independentlyRejectsMissingWrongAudienceStaffAndInternalAccess() throws Exception {
        http.perform(get("/api/v1/notifications")).andExpect(status().isUnauthorized());
        http.perform(get("/api/v1/notifications").header("Authorization","Bearer "+SignedTokens.token("wrong","actor","browser",List.of("CUSTOMER"),Map.of()))).andExpect(status().isUnauthorized());
        http.perform(get("/api/v1/notifications").header("Authorization","Bearer "+SignedTokens.token("fincore-api","staff","browser",List.of("ADMIN"),Map.of()))).andExpect(status().isForbidden());
        http.perform(get("/internal/v1/notifications").header("Authorization","Bearer "+SignedTokens.customer())).andExpect(status().isForbidden());
        verifyNoInteractions(store,remote);
    }
    @Test void bindsInboxReadAndPreferencesToSignedSubject() throws Exception {
        when(store.list(anyString(),isNull(),eq(false))).thenReturn(Map.of("items",List.of(),"nextCursor","", "unreadCount",0));
        http.perform(get("/api/v1/notifications").header("Authorization","Bearer "+SignedTokens.customer()).header("X-User-ID","forged")).andExpect(status().isOk());
        verify(store).list("verified-customer",null,false);
        http.perform(put("/api/v1/notifications/"+UUID.randomUUID()+"/read").header("Authorization","Bearer "+SignedTokens.customer()).contentType("application/json").content("{}")).andExpect(status().isOk());
        verify(store).read(eq("verified-customer"),any());
    }
    @Test void replayIsTechnicalAndInjectedPreferenceIdentityIsRejected() throws Exception {
        http.perform(post("/internal/v1/notifications/dead-letters/"+UUID.randomUUID()+"/replay").header("Authorization","Bearer "+SignedTokens.customer()).contentType("application/json").content("{\"expectedVersion\":0,\"reason\":\"RETRY\"}")).andExpect(status().isForbidden());
        http.perform(put("/api/v1/notifications/preferences").header("Authorization","Bearer "+SignedTokens.customer()).contentType("application/json").content("{\"inApp\":true,\"email\":false,\"payments\":true,\"security\":true,\"expectedVersion\":0,\"subject\":\"forged\"}")).andExpect(status().isBadRequest());
        verifyNoInteractions(store);
    }
    @Test void narrowTechnicalIdentityCanInspectAndRequestAuditedReplay() throws Exception {
        var id=UUID.randomUUID();var replay=UUID.randomUUID();
        var token=SignedTokens.token("notification-service","notification-operator","fincore-notification-operator",List.of(),Map.of("notification-service",Map.of("roles",List.of("notification.inspect","notification.replay"))));
        when(store.deadLetters()).thenReturn(List.of());when(store.replay(eq(id),eq(0L),eq("notification-operator"),eq("DEPENDENCY_RECOVERED"))).thenReturn(replay);
        http.perform(get("/internal/v1/notifications/dead-letters").header("Authorization","Bearer "+token)).andExpect(status().isOk());
        http.perform(post("/internal/v1/notifications/dead-letters/"+id+"/replay").header("Authorization","Bearer "+token).contentType("application/json").content("{\"expectedVersion\":0,\"reason\":\"DEPENDENCY_RECOVERED\"}")).andExpect(status().isAccepted()).andExpect(jsonPath("$.replayId").value(replay.toString()));
        verify(store).replay(id,0,"notification-operator","DEPENDENCY_RECOVERED");
        http.perform(get("/api/v1/notifications").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
    }
}
