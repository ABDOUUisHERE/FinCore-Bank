package com.fincore.card;

import com.fasterxml.jackson.databind.JsonNode;
import com.fincore.support.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class CardCoordinator {
 private final CardStore store;private final Remote remote;private final String ledger,account,fraud;
 public CardCoordinator(CardStore store,Remote remote,@Value("${LEDGER_URL:http://localhost:8082}")String ledger,@Value("${ACCOUNT_URL:http://localhost:8083}")String account,@Value("${FRAUD_URL:http://localhost:8087}")String fraud){this.store=store;this.remote=remote;this.ledger=ledger+"/internal/v1/ledger";this.account=account;this.fraud=fraud;}
 public JsonNode ledger(String method,String path,Object body){return remote.call(method,ledger+path,remote.serviceToken(),body);}
 public JsonNode assess(UUID id){return remote.call("POST",fraud+"/internal/v1/card-risk-assessments",remote.serviceToken(),Map.of("paymentId",id));}
 public record Create(UUID accountId,String transactionLimit,String dailyLimit){}
 public record Configure(Long expectedVersion,String status,String transactionLimit,String dailyLimit){}
 public record Authorize(UUID merchantId,String amount,String currency){}
 public JsonNode create(String actor,String bearer,String key,Create body){
  Identifiers.key(key);if(body.accountId()==null)throw new ApiFailure(400,"ACCOUNT_REQUIRED");String per=money(body.transactionLimit()),daily=money(body.dailyLimit());
  var owned=remote.call("GET",account+"/internal/v1/accounts/"+body.accountId(),remote.serviceToken(),null);if(!owned.path("ownerSubject").asText().equals(actor))throw new ApiFailure(404,"ACCOUNT_NOT_FOUND");if(owned.path("ledgerId").asText().isBlank())throw new ApiFailure(409,"ACCOUNT_NOT_READY");
  var receipt=remote.call("POST",ledger+"/cards",remote.exchange(bearer),Map.of("id",Identifiers.stable("card",actor,key),"key",key,"ledgerAccountId",owned.path("ledgerId").asText(),"transactionLimit",per,"dailyLimit",daily));
  signed(receipt,actor);store.importCard(receipt);return cardView(receipt);
 }
 public JsonNode configure(UUID id,String actor,String bearer,String key,Configure body){
  store.card(id,actor);Identifiers.key(key);if(body.expectedVersion()==null)throw new ApiFailure(400,"EXPECTED_VERSION_REQUIRED");if(body.status()==null || !Set.of("ACTIVE","FROZEN","TERMINATED").contains(body.status()))throw new ApiFailure(400,"INVALID_CARD_STATE");
  var receipt=remote.call("PUT",ledger+"/cards/"+id,remote.exchange(bearer),Map.of("key",key,"expectedVersion",body.expectedVersion(),"status",body.status(),"transactionLimit",money(body.transactionLimit()),"dailyLimit",money(body.dailyLimit())));signed(receipt,actor);store.importCard(receipt);return cardView(receipt);
 }
 public JsonNode card(UUID id,String actor){store.card(id,actor);var receipt=ledger("GET","/cards/"+id,null);signed(receipt,actor);return cardView(receipt);}
 public Object cards(String actor,UUID after){var rows=store.cards(actor,after);var views=rows.stream().map(r->card(CardStore.id(r,"id"),actor)).toList();var result=new LinkedHashMap<String,Object>();result.put("items",views);result.put("nextCursor",rows.size()==50?rows.getLast().path("id").asText():null);return result;}
 public JsonNode authorize(UUID card,String actor,String bearer,String key,Authorize body){
  store.card(card,actor);Identifiers.key(key);if(body.merchantId()==null || body.currency()==null || !Set.of("MAD","EUR","USD").contains(body.currency()))throw new ApiFailure(400,"INVALID_CARD_PAYMENT");var id=Identifiers.stable("card-authorization",actor,key);
  var receipt=remote.call("POST",ledger+"/card-authorizations",remote.exchange(bearer),Map.of("id",id,"cardId",card,"key",key,"merchantId",body.merchantId(),"amount",money(body.amount()),"currency",body.currency()));signed(receipt,actor);store.importAuthorization(receipt);
  var out=Json.read("{}");var initial=(com.fasterxml.jackson.databind.node.ObjectNode)out;initial.put("authorizationId",id.toString());initial.put("cardId",card.toString());initial.put("status","AUTHORIZING");initial.put("amount",receipt.path("amount").asText());initial.put("currency",receipt.path("currency").asText());return initial;
 }
 public JsonNode authorization(UUID id,String actor){var row=store.ownAuthorization(id,actor);var result=ledger("GET","/card-authorizations/"+id,null);signed(result,actor);store.observe(id,result);return authorizationView(row,result);}
 public Object authorizations(UUID card,String actor,UUID after){store.card(card,actor);var rows=store.authorizations(actor,card,after);var items=rows.stream().map(r->authorization(CardStore.id(r,"id"),actor)).toList();var result=new LinkedHashMap<String,Object>();result.put("items",items);result.put("nextCursor",rows.size()==50?rows.getLast().path("id").asText():null);return result;}
 public JsonNode settle(UUID id,String actor,String bearer,String key,String action){store.ownAuthorization(id,actor);Identifiers.key(key);if(action==null || !Set.of("CAPTURE","RELEASE","REFUND").contains(action))throw new ApiFailure(400,"INVALID_CARD_ACTION");var result=remote.call("POST",ledger+"/card-authorizations/"+id+"/settle",remote.exchange(bearer),Map.of("key",key,"action",action));signed(result,actor);store.observe(id,result);return authorizationView(store.ownAuthorization(id,actor),result);}
 public static String money(String value){if(value==null || !value.matches("(0|[1-9][0-9]{0,17})(\\.[0-9]{1,2})?"))throw new ApiFailure(400,"INVALID_MONEY");var amount=new BigDecimal(value);if(amount.signum()<=0)throw new ApiFailure(400,"INVALID_MONEY");return amount.setScale(2).toPlainString();}
 private static void signed(JsonNode receipt,String actor){if(!receipt.path("actor").asText().equals(actor))throw new ApiFailure(503,"INVALID_CARD_RECEIPT");}
 static JsonNode cardView(JsonNode receipt){var result=(com.fasterxml.jackson.databind.node.ObjectNode)Json.read("{}");for(String field:List.of("cardId","currency","status","transactionLimit","dailyLimit","version","expiresAt","createdAt"))result.set(field,receipt.path(field));result.put("accountId",receipt.path("accountReference").asText().substring(8));result.put("label","SIM •••• "+receipt.path("cardId").asText().replace("-","").substring(0,8).toUpperCase(Locale.ROOT));return result;}
 static JsonNode authorizationView(JsonNode row,JsonNode receipt){var result=(com.fasterxml.jackson.databind.node.ObjectNode)Json.read("{}");for(String field:List.of("authorizationId","cardId","merchantId","amount","currency","status","code","expiresAt","journalId","refundJournalId","refundOutcome","createdAt","operationOutcome"))if(receipt.has(field))result.set(field,receipt.path(field));if(receipt.path("status").asText().equals("AUTHORIZING")){result.put("status",row.path("status").asText());result.set("code",row.path("code"));}return result;}
}
