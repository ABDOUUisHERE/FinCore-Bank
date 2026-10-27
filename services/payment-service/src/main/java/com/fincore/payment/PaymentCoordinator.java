package com.fincore.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fincore.support.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class PaymentCoordinator {
    private final Remote remote;private final PaymentStore store;private final String ledger,account;
    @Value("${FRAUD_URL:http://localhost:8087}") private String fraud;
    public PaymentCoordinator(Remote remote,PaymentStore store,@Value("${LEDGER_URL:http://localhost:8082}")String ledger,@Value("${ACCOUNT_URL:http://localhost:8083}")String account){this.remote=remote;this.store=store;this.ledger=ledger+"/internal/v1/ledger";this.account=account;}
    public JsonNode ledger(String method,String path,Object body){return remote.call(method,ledger+path,remote.serviceToken(),body);}
    public void reviewFraud(UUID id,String bearer,Object decision){remote.call("POST",fraud+"/internal/v1/fraud/payments/"+id+"/decision",bearer,decision);}
    public record Transfer(UUID sourceAccountId,UUID destinationAccountId,String amount,String currency,String reference) {}
    public record Funding(UUID destinationAccountId,String amount,String currency,String reference) {}
    public JsonNode transfer(String actor,String bearer,String key,Transfer body){
        Identifiers.key(key);String amount=amount(body.amount(),body.currency());String reference=reference(body.reference());
        if(body.sourceAccountId()==null || body.destinationAccountId()==null || body.sourceAccountId().equals(body.destinationAccountId()))throw new ApiFailure(400,"INVALID_ACCOUNTS");
        var metadata=metadata(body.sourceAccountId(),body.destinationAccountId(),amount,body.currency(),reference,null);
        var replay=replay(actor,key,"TRANSFER",metadata);if(replay!=null)return replay;
        var source=account(body.sourceAccountId());var destination=account(body.destinationAccountId());
        if(!source.path("ownerSubject").asText().equals(actor))throw new ApiFailure(404,"ACCOUNT_NOT_FOUND");
        ready(source,body.currency());ready(destination,body.currency());
        return authorize(actor,bearer,key,"TRANSFER",source.path("ledgerId").asText(),destination.path("ledgerId").asText(),amount,body.currency(),metadata,null);
    }
    public JsonNode funding(String actor,String bearer,String key,Funding body){
        Identifiers.key(key);String amount=amount(body.amount(),body.currency());var metadata=metadata(null,body.destinationAccountId(),amount,body.currency(),reference(body.reference()),null);
        var replay=replay(actor,key,"DEPOSIT",metadata);if(replay!=null)return replay;
        var destination=account(body.destinationAccountId());ready(destination,body.currency());
        var settlement=ledger("GET","/settlement/"+body.currency(),null);
        return authorize(actor,bearer,key,"DEPOSIT",settlement.path("id").asText(),destination.path("ledgerId").asText(),amount,body.currency(),metadata,null);
    }
    public JsonNode reversal(UUID original,String actor,String bearer,String key){
        Identifiers.key(key);var payment=store.get(original);var id=Identifiers.stable("payment",actor,"REVERSAL",key);
        if(store.exists(id)){var previous=store.get(id);if(!original.equals(PaymentStore.optionalUuid(previous,"original_payment_id")))throw new ApiFailure(409,"IDEMPOTENCY_CONFLICT");return previous;}
        if(!payment.path("status").asText().equals("COMPLETED") || payment.path("kind").asText().equals("REVERSAL"))throw new ApiFailure(409,"REVERSAL_STATE_CONFLICT");
        return authorize(actor,bearer,key,"REVERSAL",null,null,new BigDecimal(payment.path("amount").asText()).setScale(2).toPlainString(),payment.path("currency").asText(),metadata(PaymentStore.optionalUuid(payment,"destination_account_id"),PaymentStore.optionalUuid(payment,"source_account_id"),new BigDecimal(payment.path("amount").asText()).setScale(2).toPlainString(),payment.path("currency").asText(),"Full synthetic reversal",original),payment.path("journal_id").asText());
    }
    private JsonNode authorize(String actor,String bearer,String key,String kind,String debit,String credit,String amount,String currency,Map<String,Object> metadata,String reversal){
        var id=Identifiers.stable("payment",actor,kind,key);var command=new LinkedHashMap<String,Object>();command.put("id",id);command.put("key",key);command.put("kind",kind);command.put("debitAccountId",debit);command.put("creditAccountId",credit);command.put("amount",amount);command.put("currency",currency);command.put("metadata",metadata);command.put("reversalOf",reversal);
        var authorized=remote.call("POST",ledger+"/instructions",remote.exchange(bearer),command);
        if(!authorized.path("actor").asText().equals(actor) || !authorized.path("id").asText().equals(id.toString()))throw new ApiFailure(503,"INVALID_AUTHORIZATION_RECEIPT");
        return store.importInstruction(authorized);
    }
    private JsonNode replay(String actor,String key,String kind,Map<String,Object> metadata){
        var id=Identifiers.stable("payment",actor,kind,key);JsonNode receipt;
        if(store.exists(id))receipt=store.get(id).path("instruction");
        else{try{receipt=ledger("GET","/instructions/"+id,null);}catch(ApiFailure failure){if(failure.status==404)return null;throw failure;}}
        if(!receipt.path("actor").asText().equals(actor) || !receipt.path("kind").asText().equals(kind) || !receipt.path("metadata").equals(Json.read(Json.text(metadata))))throw new ApiFailure(409,"IDEMPOTENCY_CONFLICT");
        return store.importInstruction(receipt);
    }
    private JsonNode account(UUID id){if(id==null)throw new ApiFailure(400,"INVALID_ACCOUNTS");return remote.call("GET",account+"/internal/v1/accounts/"+id,remote.serviceToken(),null);}
    private static void ready(JsonNode account,String currency){if(!account.path("status").asText().equals("ACTIVE"))throw new ApiFailure(422,"ACCOUNT_RESTRICTED");if(!account.path("currency").asText().equals(currency))throw new ApiFailure(422,"CURRENCY_MISMATCH");}
    private static Map<String,Object> metadata(UUID source,UUID destination,String amount,String currency,String reference,UUID original){var result=new LinkedHashMap<String,Object>();result.put("sourceAccountId",source);result.put("destinationAccountId",destination);result.put("amount",amount);result.put("currency",currency);result.put("reference",reference);result.put("originalPaymentId",original);return result;}
    public static String amount(String amount,String currency){if(currency==null || !Set.of("MAD","EUR","USD").contains(currency) || amount==null || !amount.matches("(0|[1-9][0-9]{0,17})(\\.[0-9]{1,2})?"))throw new ApiFailure(400,"INVALID_MONEY");var value=new BigDecimal(amount);if(value.signum()<=0)throw new ApiFailure(400,"INVALID_MONEY");return value.setScale(2).toPlainString();}
    private static String reference(String reference){if(reference==null)return "";if(reference.length()>140 || reference.chars().anyMatch(Character::isISOControl))throw new ApiFailure(400,"INVALID_REFERENCE");return reference.strip();}
}
