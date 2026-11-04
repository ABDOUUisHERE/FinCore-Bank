package com.fincore.account;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fincore.support.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountInspectionTest {
    final AccountStore store=mock(AccountStore.class);
    final AccountCoordinator coordinator=mock(AccountCoordinator.class);
    final UUID id=UUID.randomUUID(), ledger=UUID.randomUUID(), customer=UUID.randomUUID();
    final AccountInspection inspection=new AccountInspection(store,coordinator);
    AccountStore.Account account(String state,UUID link){return new AccountStore.Account(id,customer,"private-owner", "MAD",state,link,9007199254740993L,0,state.equals("GUARD_PENDING")?"FROZEN":null,"ACTIVE","private arbitrary error");}
    ObjectNode source(){return (ObjectNode)Json.read(Json.text(Map.of("ledgerAccountId",ledger,"externalReference","account:"+id,"ownerSubject","private-owner","currency","MAD","type","CUSTOMER_LIABILITY","status","FROZEN","guardRevision","9007199254740993","observedAt","2026-11-04T12:00:00Z","privateCanary","must-not-cross")));}
    void remote(ObjectNode source){when(coordinator.ledger("GET","/accounts/"+ledger+"/operational-status",null)).thenReturn(source);}
    @Test void retainsSeparatePendingAndEffectiveStatesAndExactVersionsWithoutRecoveryOrPrivateFields(){
        when(store.get(id)).thenReturn(account("GUARD_PENDING",ledger));remote(source());
        var result=inspection.inspect(id);
        assertThat(result.recordedStatus()).isEqualTo("GUARD_PENDING");assertThat(result.pendingStatus()).isEqualTo("FROZEN");
        assertThat(result.accountVersion()).isEqualTo("9007199254740993");assertThat(result.recordedGuardRevision()).isEqualTo("0");
        assertThat(result.ledgerState()).isEqualTo("OBSERVED");assertThat(result.ledger().status()).isEqualTo("FROZEN");assertThat(result.ledger().guardRevision()).isEqualTo("9007199254740993");
        assertThat(Json.text(result)).doesNotContain("private-owner","private arbitrary error","must-not-cross",ledger.toString(),"booked","balance","externalReference");
        verify(store).get(id);verify(coordinator).ledger("GET","/accounts/"+ledger+"/operational-status",null);verifyNoMoreInteractions(store,coordinator);
    }
    @Test void missingLinkDoesNotClaimThatNoLedgerAccountExistsOrTryToProvision(){
        when(store.get(id)).thenReturn(account("PROVISIONING",null));var result=inspection.inspect(id);
        assertThat(result.ledgerState()).isEqualTo("NOT_LINKED");assertThat(result.ledger()).isNull();verifyNoInteractions(coordinator);
    }
    @Test void unavailableLedgerReturnsOnlyExplicitlyPartialRecordedState(){
        when(store.get(id)).thenReturn(account("ACTIVE",ledger));when(coordinator.ledger("GET","/accounts/"+ledger+"/operational-status",null)).thenThrow(new ApiFailure(503,"DEPENDENCY_UNAVAILABLE"));
        var result=inspection.inspect(id);assertThat(result.ledgerState()).isEqualTo("UNAVAILABLE");assertThat(result.ledger()).isNull();assertThat(result.recordedStatus()).isEqualTo("ACTIVE");
    }
    @Test void missingOrDeniedUpstreamIsNotPresentedAsObservedState(){
        when(store.get(id)).thenReturn(account("ACTIVE",ledger));
        for(int status:List.of(401,403,404)){when(coordinator.ledger("GET","/accounts/"+ledger+"/operational-status",null)).thenThrow(new ApiFailure(status,"NOT_AUTHORIZED"));assertInvalid();}
    }
    @Test void rejectsCrossAccountOwnerCurrencyTypeAndMalformedGuardMetadata(){
        when(store.get(id)).thenReturn(account("ACTIVE",ledger));
        var invalid=List.of(source().put("ledgerAccountId",UUID.randomUUID().toString()),source().put("externalReference","account:"+UUID.randomUUID()),source().put("ownerSubject","other"),source().put("currency","EUR"),source().put("type","SETTLEMENT_ASSET"),source().put("status","CREATED"),source().put("guardRevision",1),source().put("guardRevision","9223372036854775808"),source().put("guardRevision","-1"),source().put("observedAt","not-a-time"));
        for(var row:invalid){remote(row);assertInvalid();}
    }
    void assertInvalid(){assertThatThrownBy(()->inspection.inspect(id)).isInstanceOf(ApiFailure.class).hasMessage("INVALID_ACCOUNT_STATUS_SOURCE").extracting("status").isEqualTo(503);}
}
