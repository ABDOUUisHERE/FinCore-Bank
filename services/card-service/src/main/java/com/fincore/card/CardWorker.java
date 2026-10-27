package com.fincore.card;

import com.fasterxml.jackson.databind.JsonNode;
import com.fincore.support.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class CardWorker {
 private final CardStore store;private final CardCoordinator coordinator;private final boolean enabled;private UUID controlsAfter,authorizationAfter;
 public CardWorker(CardStore store,CardCoordinator coordinator,@Value("${CARD_WORKER_ENABLED:true}")boolean enabled){this.store=store;this.coordinator=coordinator;this.enabled=enabled;}
 @Scheduled(fixedDelayString="${CARD_RECOVERY_DELAY_MS:5000}")public void tick(){if(enabled)cycle();}
 public void cycle(){try{
  try{var page=coordinator.ledger("GET","/card-controls"+(controlsAfter==null?"":"?after="+controlsAfter),null);for(var item:page.path("items"))store.importCard(item.path("receipt"));controlsAfter=cursor(page);}catch(ApiFailure ignored){/* Existing work can recover while discovery is unavailable. */}
  try{var page=coordinator.ledger("GET","/card-authorizations"+(authorizationAfter==null?"":"?after="+authorizationAfter),null);for(var receipt:page.path("items"))if(!store.hasAuthorization(CardStore.id(receipt,"authorizationId"))){var card=coordinator.ledger("GET","/cards/"+receipt.path("cardId").asText(),null);store.discoverCard(card);store.importAuthorization(receipt);}authorizationAfter=cursor(page);}catch(ApiFailure ignored){/* The next full scan recovers an interrupted page. */}
  for(int n=0;n<5;n++){var leased=store.claim();if(leased==null)break;try{process(leased);}catch(ApiFailure e){store.retry(leased,e.code);}catch(org.springframework.dao.DataAccessException ignored){/* Fenced lease expires; no new financial identity is generated. */}}
 }catch(org.springframework.dao.DataAccessException ignored){/* Readiness reports storage failures. */}}
 static UUID cursor(JsonNode page){return page.path("nextCursor").isNull()?null:UUID.fromString(page.path("nextCursor").asText());}
 public void process(JsonNode leased){
  var id=CardStore.id(leased,"id");var receipt=coordinator.ledger("GET","/card-authorizations/"+id,null);
  if(!receipt.path("status").asText().equals("AUTHORIZING")){store.result(leased,"RESOLVED",receipt.path("code").asText(),null,receipt);return;}
  // Close the previous attempt under Ledger's admission lock before a later BLOCK/REVIEW can resolve it.
  if(leased.hasNonNull("last_grant_expires")){
   var recovered=coordinator.ledger("POST","/card-authorizations/"+id+"/recover",Map.of("lastGrantExpiresAt",leased.path("last_grant_expires").asText()));
   if(!recovered.path("status").asText().equals("AUTHORIZING")){store.result(leased,"RESOLVED",recovered.path("code").asText(),null,recovered);return;}
  }
  var assessment=coordinator.assess(id);if(!assessment.path("paymentId").asText().equals(id.toString()))throw new ApiFailure(503,"INVALID_RISK_RESPONSE");var assessmentId=CardStore.id(assessment,"assessmentId");
  switch(assessment.path("verdict").asText()){
   case "REVIEW"->store.result(leased,"PENDING_RISK_REVIEW","RISK_REVIEW",assessmentId,null);
   case "BLOCK"->store.result(leased,"REJECTED","RISK_BLOCKED",assessmentId,null);
   case "ALLOW"->{java.time.Instant expires;try{expires=java.time.Instant.parse(assessment.path("expiresAt").asText());}catch(java.time.DateTimeException e){throw new ApiFailure(503,"INVALID_RISK_RESPONSE");}if(!expires.isAfter(java.time.Instant.now()) || expires.isAfter(java.time.Instant.now().plusSeconds(60)))throw new ApiFailure(503,"INVALID_RISK_RESPONSE");store.attempting(leased,expires);var held=coordinator.ledger("POST","/card-authorizations/"+id+"/reserve",Map.of("assessmentId",assessmentId));store.result(leased,"RESOLVED",held.path("code").asText(),assessmentId,held);}
   default->throw new ApiFailure(503,"INVALID_RISK_RESPONSE");
  }
 }
}
