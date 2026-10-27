package com.fincore.ledger;

import com.fincore.support.*;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Component;

/** Owner-side verification occurs before opening the Ledger posting transaction. */
@Component
public class LedgerRiskVerifier {
 private final Remote remote;private final String fraud;
 @Autowired public LedgerRiskVerifier(@Value("${TOKEN_ENDPOINT:http://localhost:8180/realms/fincore/protocol/openid-connect/token}")String endpoint,
    @Value("${LEDGER_RISK_CLIENT_SECRET}")String secret,@Value("${FRAUD_URL:http://localhost:8087}")String fraud){this(new Remote(endpoint,"fincore-ledger-risk",secret),fraud);}
 LedgerRiskVerifier(Remote remote,String fraud){this.remote=remote;this.fraud=fraud;}
 public Instant verify(UUID assessment,UUID payment,String fingerprint){
  if(assessment==null)throw new ApiFailure(403,"RISK_AUTHORIZATION_REQUIRED");
  var grant=remote.call("POST",fraud+"/internal/v1/risk-assessments/"+assessment+"/validate",remote.serviceToken(),Map.of("paymentId",payment,"fingerprint",fingerprint));
  try{
   if(!assessment.toString().equals(grant.path("assessmentId").asText()) || !payment.toString().equals(grant.path("paymentId").asText()) || !fingerprint.equals(grant.path("fingerprint").asText()))throw new ApiFailure(403,"RISK_BINDING_MISMATCH");
   var expires=Instant.parse(grant.path("expiresAt").asText());if(!expires.isAfter(Instant.now()) || expires.isAfter(Instant.now().plusSeconds(60)))throw new ApiFailure(503,"ASSESSMENT_EXPIRED");return expires;
  }catch(java.time.DateTimeException e){throw new ApiFailure(503,"INVALID_RISK_RESPONSE");}
 }
}
