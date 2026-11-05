package com.fincore.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fincore.support.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SupportAccountAccessTest {
    static JsonNode source(UUID id) {return Json.read("""
        {"accountId":"%s","currency":"MAD","recordedStatus":"ACTIVE","accountVersion":"9007199254740993",
        "pendingStatus":null,"lastGuardError":null,"recordedGuardRevision":"0","accountObservedAt":"2026-11-05T10:00:00Z",
        "ledgerState":"OBSERVED","ledger":{"status":"FROZEN","guardRevision":"1","observedAt":"2026-11-05T10:00:01Z"},
        "balance":"PRIVATE_BALANCE","customerId":"PRIVATE_CUSTOMER","ownerSubject":"PRIVATE_SUBJECT"}
        """.formatted(id));}
    @Test void consumerValidatesExactAccountAndProjectsOnlyRedactedStatusFields() {
        UUID id=UUID.randomUUID();var source=source(id);var result=SupportAccountAccess.validate(id,source);
        assertThat(result.accountVersion()).isEqualTo("9007199254740993");assertThat(result.ledger().status()).isEqualTo("FROZEN");
        assertThat(Json.text(result)).doesNotContain("PRIVATE_","balance","customerId","ownerSubject");
        assertThatThrownBy(()->SupportAccountAccess.validate(UUID.randomUUID(),source)).isInstanceOf(ApiFailure.class);
        for(String malformed:List.of(source.toString().replace("\"9007199254740993\"","9007199254740993"),source.toString().replace("\"guardRevision\":\"1\"","\"guardRevision\":\"9223372036854775808\""),source.toString().replace("FROZEN","UNKNOWN"),source.toString().replace("2026-11-05T10:00:00Z","invalid")))
            assertThatThrownBy(()->SupportAccountAccess.validate(id,Json.read(malformed))).isInstanceOf(ApiFailure.class);
    }
    @Test void noGrantOrFailedAdmissionAuditNeverCallsTheOwner() {
        var grants=mock(SupportAccessStore.class);var remote=mock(Remote.class);var access=new SupportAccountAccess(grants,remote,"http://account");var id=UUID.randomUUID();
        assertThatThrownBy(()->access.inspect(id,"support","c")).hasMessage("SUPPORT_ACCESS_UNAVAILABLE");verifyNoInteractions(remote);
        when(grants.admit(id,"support","c")).thenThrow(new DataAccessResourceFailureException("private-db-message"));
        assertThatThrownBy(()->access.inspect(id,"support","c")).isInstanceOf(DataAccessResourceFailureException.class);verifyNoInteractions(remote);
    }
    @Test void unavailableSourceIsAuditedAndDoesNotBecomeAnEmptySuccessfulObservation() {
        var grants=mock(SupportAccessStore.class);var remote=mock(Remote.class);var id=UUID.randomUUID();
        var admission=new SupportAccessStore.Admission(id,UUID.randomUUID(),"support","1",UUID.randomUUID());
        when(grants.admit(id,"support","c")).thenReturn(admission);when(remote.serviceToken()).thenThrow(new ApiFailure(503,"PRIVATE_ERROR"));
        when(grants.finish(admission,"c","SOURCE_UNAVAILABLE")).thenReturn(true);
        assertThatThrownBy(()->new SupportAccountAccess(grants,remote,"http://account").inspect(id,"support","c")).hasMessage("SUPPORT_ACCOUNT_UNAVAILABLE");
        verify(grants).finish(admission,"c","SOURCE_UNAVAILABLE");
    }
}
