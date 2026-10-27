package com.fincore.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fincore.support.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class FraudRiskPolicy implements RiskPolicy {
 private final Remote remote;private final String fraud;
 public FraudRiskPolicy(Remote remote,@Value("${FRAUD_URL:http://localhost:8087}")String fraud){this.remote=remote;this.fraud=fraud;}
 @Override public Assessment assess(String kind,BigDecimal amount,String currency,boolean reviewed){throw new IllegalStateException("Durable payment identity is required for Fraud authorization");}
 @Override public Assessment assess(JsonNode payment){
  var id=PaymentStore.uuid(payment,"id");var result=remote.call("POST",fraud+"/internal/v1/risk-assessments",remote.serviceToken(),Map.of("paymentId",id));
  try{
   if(!id.toString().equals(result.path("paymentId").asText()) || result.path("rulesVersion").asLong()<1)throw new IllegalArgumentException();
   var expires=Instant.parse(result.path("expiresAt").asText());
   if(!expires.isAfter(Instant.now()) || expires.isAfter(Instant.now().plusSeconds(60)))throw new ApiFailure(503,"ASSESSMENT_EXPIRED");
   var verdict=Verdict.valueOf(result.path("verdict").asText());var reference=UUID.fromString(result.path("assessmentId").asText());
   String reason="WITHIN_FRAUD_POLICY";
   if(result.path("reasons").isArray() && !result.path("reasons").isEmpty())reason=result.path("reasons").get(0).path("code").asText();
   if(!reason.matches("[A-Z_]{1,80}"))throw new IllegalArgumentException();
   return new Assessment(verdict,reason,"fraud-v"+result.path("rulesVersion").asLong(),expires,reference);
  }catch(IllegalArgumentException | java.time.DateTimeException e){throw new ApiFailure(503,"INVALID_RISK_RESPONSE");}
 }
}
