package com.fincore.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fincore.support.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SupportPaymentAccessTest {
    static JsonNode source(UUID id) {return Json.read("""
        {"paymentId":"%s","kind":"TRANSFER","status":"PROCESSING","version":"9007199254740993",
        "createdAt":"2026-11-05T10:00:00Z","observedAt":"2026-11-05T10:00:01Z",
        "amount":"PRIVATE_AMOUNT","actor":"PRIVATE_ACTOR","journalId":"PRIVATE_JOURNAL","instruction":"PRIVATE_INSTRUCTION"}
        """.formatted(id));}
    @Test void validatesTheExactResourceAndProjectsOnlySixMetadataFields() {
        UUID id=UUID.randomUUID();var source=source(id);var result=SupportPaymentAccess.validate(id,source);
        assertThat(result.version()).isEqualTo("9007199254740993");assertThat(result.status()).isEqualTo("PROCESSING");
        assertThat(Json.text(result)).doesNotContain("PRIVATE_","amount","actor","journalId","instruction");
        assertThatThrownBy(()->SupportPaymentAccess.validate(UUID.randomUUID(),source)).isInstanceOf(ApiFailure.class);
        for(String malformed:List.of(source.toString().replace("\"9007199254740993\"","9007199254740993"),source.toString().replace("9007199254740993","9223372036854775808"),source.toString().replace("TRANSFER","UNKNOWN"),source.toString().replace("PROCESSING","UNKNOWN"),source.toString().replace("2026-11-05T10:00:00Z","invalid")))
            assertThatThrownBy(()->SupportPaymentAccess.validate(id,Json.read(malformed))).isInstanceOf(ApiFailure.class).hasMessage("INVALID_SUPPORT_PAYMENT_SOURCE");
    }
    @Test void deniedOrFailedAdmissionNeverCallsPayment() {
        var grants=mock(SupportAccessStore.class);var remote=mock(Remote.class);var access=new SupportPaymentAccess(grants,remote,"http://payment");var id=UUID.randomUUID();
        assertThatThrownBy(()->access.inspect(id,"support","c")).hasMessage("SUPPORT_ACCESS_UNAVAILABLE");verifyNoInteractions(remote);
        when(grants.admit(id,"support","c",SupportAccessStore.Resource.PAYMENT)).thenThrow(new DataAccessResourceFailureException("private-db-message"));
        assertThatThrownBy(()->access.inspect(id,"support","c")).isInstanceOf(DataAccessResourceFailureException.class);verifyNoInteractions(remote);
    }
    @Test void revocationAfterTheOwnerReadWithholdsTheValidResultAndUsesTheAuthorizedResource() {
        var grants=mock(SupportAccessStore.class);var remote=mock(Remote.class);var incident=UUID.randomUUID();var payment=UUID.randomUUID();
        var admission=new SupportAccessStore.Admission(incident,payment,"support","1",UUID.randomUUID(),SupportAccessStore.Resource.PAYMENT);
        when(grants.admit(incident,"support","c",SupportAccessStore.Resource.PAYMENT)).thenReturn(admission);when(remote.serviceToken()).thenReturn("private-token");
        when(remote.call("GET","http://payment/internal/v1/payments/"+payment+"/support-status","private-token",null)).thenReturn(source(payment));
        assertThatThrownBy(()->new SupportPaymentAccess(grants,remote,"http://payment").inspect(incident,"support","c")).hasMessage("SUPPORT_ACCESS_UNAVAILABLE");
        verify(grants).finish(admission,"c","DISCLOSED");
    }
    @Test void ownerFailureIsRecordedWithoutReturningItsPrivateDetails() {
        var grants=mock(SupportAccessStore.class);var remote=mock(Remote.class);var id=UUID.randomUUID();
        var admission=new SupportAccessStore.Admission(id,UUID.randomUUID(),"support","1",UUID.randomUUID(),SupportAccessStore.Resource.PAYMENT);
        when(grants.admit(id,"support","c",SupportAccessStore.Resource.PAYMENT)).thenReturn(admission);when(remote.serviceToken()).thenThrow(new ApiFailure(503,"PRIVATE_ERROR"));
        when(grants.finish(admission,"c","SOURCE_UNAVAILABLE")).thenReturn(true);
        assertThatThrownBy(()->new SupportPaymentAccess(grants,remote,"http://payment").inspect(id,"support","c")).hasMessage("SUPPORT_PAYMENT_UNAVAILABLE");
        verify(grants).finish(admission,"c","SOURCE_UNAVAILABLE");
    }
    @Test void successfulDisclosureRequiresAFinalCommittedAuditAndAnAuditFailureCannotReturnThePayload() {
        var grants=mock(SupportAccessStore.class);var remote=mock(Remote.class);var id=UUID.randomUUID();var payment=UUID.randomUUID();
        var admission=new SupportAccessStore.Admission(id,payment,"support","1",UUID.randomUUID(),SupportAccessStore.Resource.PAYMENT);
        when(grants.admit(id,"support","c",SupportAccessStore.Resource.PAYMENT)).thenReturn(admission);when(remote.serviceToken()).thenReturn("private-token");
        when(remote.call(anyString(),anyString(),anyString(),isNull())).thenReturn(source(payment));when(grants.finish(admission,"c","DISCLOSED")).thenReturn(true);
        var access=new SupportPaymentAccess(grants,remote,"http://payment");assertThat(access.inspect(id,"support","c").paymentId()).isEqualTo(payment);
        when(grants.finish(admission,"c","DISCLOSED")).thenThrow(new DataAccessResourceFailureException("private-audit-failure"));
        assertThatThrownBy(()->access.inspect(id,"support","c")).isInstanceOf(DataAccessResourceFailureException.class);
    }
}
