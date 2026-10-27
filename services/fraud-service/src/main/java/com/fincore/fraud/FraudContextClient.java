package com.fincore.fraud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fincore.support.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Required owner-service reads happen before entering Fraud's database transaction. */
@Service
public class FraudContextClient {
    private final Remote remote;private final String payments,customers,beneficiaries,cards;
    public FraudContextClient(Remote remote,@Value("${PAYMENT_URL:http://localhost:8084}")String payments,
            @Value("${CUSTOMER_URL:http://localhost:8081}")String customers,@Value("${BENEFICIARY_URL:http://localhost:8085}")String beneficiaries,@Value("${CARD_URL:http://localhost:8088}")String cards){
        this.remote=remote;this.payments=payments;this.customers=customers;this.beneficiaries=beneficiaries;this.cards=cards;
    }
    public JsonNode payment(UUID id){return remote.call("GET",payments+"/internal/v1/payments/"+id+"/risk-context",remote.serviceToken(),null);}
    public JsonNode card(UUID id){return remote.call("GET",cards+"/internal/v1/cards/"+id+"/risk-context",remote.serviceToken(),null);}
    public JsonNode context(UUID id){return enrich(id,payment(id),false);}
    public JsonNode cardContext(UUID id){return enrich(id,card(id),true);}
    private JsonNode enrich(UUID id,JsonNode canonical,boolean merchant){
        var p=canonical.deepCopy();
        if(!id.toString().equals(p.path("paymentId").asText()) || !p.path("fingerprint").asText().matches("[a-f0-9]{64}")
                || p.path("actor").asText().isBlank() || p.path("actor").asText().length()>200
                || !(merchant?Set.of("MERCHANT_PAYMENT"):Set.of("TRANSFER","DEPOSIT","REVERSAL")).contains(p.path("kind").asText())
                || !Set.of("MAD","EUR","USD").contains(p.path("currency").asText())
                || !p.path("amount").asText().matches("[0-9]{1,18}\\.[0-9]{2}") || new BigDecimal(p.path("amount").asText()).signum()<=0)
            throw new ApiFailure(503,"INVALID_RISK_CONTEXT");
        var result=(com.fasterxml.jackson.databind.node.ObjectNode)p;
        if(merchant || p.path("kind").asText().equals("TRANSFER")){
            var profile=remote.call("POST",customers+"/internal/v1/customers/risk-context",remote.serviceToken(),Map.of("subject",p.path("actor").asText()));
            var beneficiary=merchant?Json.read("{\"saved\":false,\"createdAt\":null}"):remote.call("POST",beneficiaries+"/internal/v1/beneficiaries/risk-context",remote.serviceToken(),Map.of("subject",p.path("actor").asText(),"destinationAccountId",UUID.fromString(p.path("destinationAccountId").asText())));
            if(!profile.path("countryCode").asText().matches("[A-Z]{2}") || !beneficiary.has("saved"))throw new ApiFailure(503,"INVALID_RISK_CONTEXT");
            result.put("countryCode",profile.path("countryCode").asText());
            result.set("beneficiary",beneficiary);
        }else{result.put("countryCode","");result.set("beneficiary",Json.read("{\"saved\":false,\"createdAt\":null}"));}
        return result;
    }
}
