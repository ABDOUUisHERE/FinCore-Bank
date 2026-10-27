package com.fincore.card;

import com.fasterxml.jackson.databind.JsonNode;
import com.fincore.support.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CardWorkerTest {
 final CardStore store=mock(CardStore.class);final CardCoordinator coordinator=mock(CardCoordinator.class);final CardWorker worker=new CardWorker(store,coordinator,true);final UUID id=UUID.randomUUID(),assessment=UUID.randomUUID();
 JsonNode leased(){var values=new LinkedHashMap<String,Object>();values.put("id",id);values.put("lease_token",UUID.randomUUID());values.put("last_grant_expires",null);return Json.read(Json.text(values));}
 JsonNode risk(String verdict){return Json.read(Json.text(Map.of("paymentId",id,"assessmentId",assessment,"verdict",verdict,"expiresAt",Instant.now().plusSeconds(25).toString())));}
 void pending(){when(coordinator.ledger("GET","/card-authorizations/"+id,null)).thenReturn(Json.read("{\"status\":\"AUTHORIZING\"}"));}
 @Test void durableHoldWinsBeforeAnyNewFraudRead(){var leased=leased();var held=Json.read("{\"status\":\"HELD\",\"code\":\"RESERVED\"}");when(coordinator.ledger("GET","/card-authorizations/"+id,null)).thenReturn(held);worker.process(leased);verify(store).result(leased,"RESOLVED","RESERVED",null,held);verify(coordinator,never()).assess(any());}
 @Test void reviewAndBlockNeverCallFinancialAdmission(){for(String verdict:List.of("REVIEW","BLOCK")){reset(store,coordinator);pending();var leased=leased();when(coordinator.assess(id)).thenReturn(risk(verdict));worker.process(leased);verify(store).result(eq(leased),eq(verdict.equals("REVIEW")?"PENDING_RISK_REVIEW":"REJECTED"),anyString(),eq(assessment),isNull());verify(coordinator,never()).ledger(eq("POST"),anyString(),any());}}
 @Test void attemptedGrantIsPersistedBeforeTheRequestAndLostResponseDoesNotResolveFailure(){
  pending();var leased=leased();when(coordinator.assess(id)).thenReturn(risk("ALLOW"));when(coordinator.ledger(eq("POST"),contains("/reserve"),any())).thenThrow(new ApiFailure(503,"DEPENDENCY_UNAVAILABLE"));
  assertThatThrownBy(()->worker.process(leased)).isInstanceOf(ApiFailure.class);var ordered=inOrder(store,coordinator);ordered.verify(store).attempting(eq(leased),any());ordered.verify(coordinator).ledger(eq("POST"),contains("/reserve"),any());verify(store,never()).result(any(),anyString(),anyString(),any(),any());
 }
 @Test void laterBlockWaitsForLedgerBarrierAndRecoversAnAlreadyCommittedHold(){
  pending();var leased=(com.fasterxml.jackson.databind.node.ObjectNode)leased();leased.put("last_grant_expires",Instant.now().minusSeconds(1).toString());var held=Json.read("{\"status\":\"HELD\",\"code\":\"RESERVED\"}");when(coordinator.ledger(eq("POST"),contains("/recover"),any())).thenReturn(held);worker.process(leased);verify(store).result(leased,"RESOLVED","RESERVED",null,held);verify(coordinator,never()).assess(any());
 }
 @Test void unresolvedBarrierCannotBePresentedAsADecline(){pending();var leased=(com.fasterxml.jackson.databind.node.ObjectNode)leased();leased.put("last_grant_expires",Instant.now().plusSeconds(20).toString());when(coordinator.ledger(eq("POST"),contains("/recover"),any())).thenThrow(new ApiFailure(409,"ASSESSMENT_EXPIRED"));assertThatThrownBy(()->worker.process(leased)).isInstanceOf(ApiFailure.class);verifyNoInteractions(store);verify(coordinator,never()).assess(any());}
 @Test void staleWorkerCannotSendAnAdmissionAfterLosingItsLease(){pending();var leased=leased();when(coordinator.assess(id)).thenReturn(risk("ALLOW"));doThrow(new ApiFailure(409,"STALE_CARD_LEASE")).when(store).attempting(eq(leased),any());assertThatThrownBy(()->worker.process(leased)).isInstanceOf(ApiFailure.class);verify(coordinator,never()).ledger(eq("POST"),contains("/reserve"),any());}
}
