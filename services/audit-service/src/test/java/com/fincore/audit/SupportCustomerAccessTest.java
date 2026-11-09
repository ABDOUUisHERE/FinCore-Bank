package com.fincore.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fincore.support.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SupportCustomerAccessTest {
    static JsonNode source(UUID id){return Json.read("""
        {"customerId":"%s","displayName":"PRIVATE_NAME","status":"ACTIVE","profileVersion":"9007199254740993",
        "createdAt":"2026-11-09T10:00:00Z","observedAt":"2026-11-09T10:01:00Z","subject":"PRIVATE_SUBJECT","countryCode":"MA"}
        """.formatted(id));}
    final SupportAccessStore grants=mock(SupportAccessStore.class);final Remote remote=mock(Remote.class);
    final SupportCustomerAccess access=new SupportCustomerAccess(grants,remote,"http://customer");
    final UUID incident=UUID.randomUUID(),customer=UUID.randomUUID();
    SupportAccessStore.SearchAdmission admit() {
        var a=new SupportAccessStore.Admission(incident,customer,"support","1",UUID.randomUUID(),SupportAccessStore.Resource.CUSTOMER);
        var result=new SupportAccessStore.SearchAdmission(a.attemptId(),"support",List.of(a),Instant.now().plusSeconds(300));
        when(grants.admitCustomerSearch("support","c")).thenReturn(result);when(remote.serviceToken()).thenReturn("private-token");return result;
    }
    @Test void validatesStrictProfileFieldsAndDiscardsPrivateSourceExtras() {
        var result=SupportCustomerAccess.validate(customer,source(customer));assertThat(result.profileVersion()).isEqualTo("9007199254740993");assertThat(Json.text(result)).doesNotContain("PRIVATE_SUBJECT","countryCode");
        for(String bad:List.of(source(customer).toString().replace("9007199254740993","9223372036854775808"),source(customer).toString().replace("ACTIVE","UNKNOWN"),source(customer).toString().replace("2026-11-09T10:01:00Z","bad")))assertThatThrownBy(()->SupportCustomerAccess.validate(customer,Json.read(bad))).hasMessage("INVALID_SUPPORT_CUSTOMER_SOURCE");
        assertThatThrownBy(()->SupportCustomerAccess.validate(UUID.randomUUID(),source(customer))).hasMessage("INVALID_SUPPORT_CUSTOMER_SOURCE");
    }
    @Test void invalidPrefixesAndAbsentScopeNeverReachTheOwner() {
        for(String prefix:new String[]{null,""," ab ","abc\nprivate","x".repeat(81)})assertThatThrownBy(()->access.search(new SupportCustomerAccess.Input(prefix),"support","c")).hasMessage("INVALID_SEARCH_PREFIX");
        verifyNoInteractions(grants,remote);assertThatThrownBy(()->access.search(new SupportCustomerAccess.Input("Valid"),"support","c")).hasMessage("SUPPORT_ACCESS_UNAVAILABLE");verifyNoInteractions(remote);
    }
    @Test void theOwnerReceivesOnlyServerSelectedIdsAndProjectionUsesTheAuthorizedIncident() {
        var a=admit();when(remote.call(eq("POST"),anyString(),anyString(),any())).thenReturn(Json.read("{\"items\":["+source(customer)+"],\"hasMore\":false}"));
        when(grants.finishCustomerSearch(a,"c","DISCLOSED",1)).thenReturn(true);
        var result=access.search(new SupportCustomerAccess.Input("  Private  "),"support","c");assertThat(result.items()).hasSize(1);assertThat(result.items().getFirst().incidentId()).isEqualTo(incident);assertThat(Json.text(result)).doesNotContain("PRIVATE_SUBJECT","profileVersion");
        verify(remote).call("POST","http://customer/internal/v1/customers/support-search","private-token",Map.of("namePrefix","Private","customerIds",Set.of(customer)));
    }
    @Test void foreignDuplicateOversizedAndMalformedMatchesAreRejected() {
        var allowed=Map.of(customer,incident);var expiry=Instant.now().plusSeconds(30);
        for(String items:List.of(source(UUID.randomUUID()).toString(),source(customer)+","+source(customer),String.join(",",Collections.nCopies(21,source(customer).toString()))))assertThatThrownBy(()->SupportCustomerAccess.project(Json.read("{\"items\":["+items+"],\"hasMore\":false}"),allowed,expiry)).hasMessage("INVALID_SUPPORT_CUSTOMER_SOURCE");
        assertThatThrownBy(()->SupportCustomerAccess.project(Json.read("{\"items\":[],\"hasMore\":true}"),allowed,expiry)).hasMessage("INVALID_SUPPORT_CUSTOMER_SOURCE");
    }
    @Test void aFinalDenialOrStorageFailureWithholdsTheCompleteSearch() {
        var a=admit();when(remote.call(anyString(),anyString(),anyString(),any())).thenReturn(Json.read("{\"items\":["+source(customer)+"],\"hasMore\":false}"));
        assertThatThrownBy(()->access.search(new SupportCustomerAccess.Input("Private"),"support","c")).hasMessage("SUPPORT_ACCESS_UNAVAILABLE");
        when(grants.finishCustomerSearch(a,"c","DISCLOSED",1)).thenThrow(new DataAccessResourceFailureException("private-db"));
        assertThatThrownBy(()->access.search(new SupportCustomerAccess.Input("Private"),"support","c")).isInstanceOf(DataAccessResourceFailureException.class);
    }
    @Test void ownerFailureRecordsOnlyAnUnavailableOutcome() {
        var a=admit();when(remote.call(anyString(),anyString(),anyString(),any())).thenThrow(new ApiFailure(503,"PRIVATE_SOURCE"));when(grants.finishCustomerSearch(a,"c","SOURCE_UNAVAILABLE",0)).thenReturn(true);
        assertThatThrownBy(()->access.search(new SupportCustomerAccess.Input("Private"),"support","c")).hasMessage("SUPPORT_CUSTOMER_UNAVAILABLE");verify(grants).finishCustomerSearch(a,"c","SOURCE_UNAVAILABLE",0);
    }
}
