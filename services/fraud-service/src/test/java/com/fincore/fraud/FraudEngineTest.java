package com.fincore.fraud;

import com.fincore.support.Json;
import java.math.BigDecimal;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FraudEngineTest {
 static final Instant NOW=Instant.parse("2026-10-26T12:00:00Z");
 static FraudRules rules(){return FraudRules.read(Json.read("""
 {"reviewAmounts":{"MAD":"10000.00","EUR":"10000.00","USD":"10000.00"},
 "blockAmounts":{"MAD":"100000.00","EUR":"100000.00","USD":"100000.00"},
 "newBeneficiaryAmounts":{"MAD":"5000.00","EUR":"5000.00","USD":"5000.00"},
 "frequencyReview":30,"frequencyBlock":60,"rapidCount":12,"velocityCount":15,
 "failedCount":5,"newBeneficiaryHours":24,"flaggedCountries":["ZZ"]}
 """));}
 static FraudEngine.Context transfer(String amount,String country,Instant saved,int failures){return new FraudEngine.Context("TRANSFER",new BigDecimal(amount),"MAD",country,saved,failures);}
 static FraudEngine.Velocity quiet(){return new FraudEngine.Velocity(1,0,1,false);}
 @Test void normalTransferAndExplicitlyTechnicalKindsRemainDistinct(){
  assertThat(FraudEngine.evaluate(transfer("100.00","MA",NOW.minusSeconds(90000),0),quiet(),rules(),NOW).verdict()).isEqualTo("ALLOW");
  for(var kind:java.util.List.of("DEPOSIT","REVERSAL"))assertThat(FraudEngine.evaluate(new FraudEngine.Context(kind,new BigDecimal("200000.00"),"MAD","ZZ",null,99),quiet(),rules(),NOW).verdict()).isEqualTo("ALLOW");
 }
 @Test void highValueNewBeneficiaryRemainsReviewableAndExplainsBothSignals(){
  var result=FraudEngine.evaluate(transfer("12000.00","MA",NOW,0),quiet(),rules(),NOW);
  assertThat(result.verdict()).isEqualTo("REVIEW");assertThat(result.score()).isEqualTo(100);
  assertThat(result.reasons()).extracting(FraudEngine.Reason::code).containsExactly("HIGH_VALUE","NEW_BENEFICIARY_HIGH_VALUE");
  assertThat(FraudEngine.evaluate(transfer("6000.00","MA",null,0),quiet(),rules(),NOW).verdict()).isEqualTo("REVIEW");
 }
 @Test void hardAmountAndSimulatedCountryAreNotReviewApprovals(){
  assertThat(FraudEngine.evaluate(transfer("100000.01","MA",null,0),quiet(),rules(),NOW).verdict()).isEqualTo("BLOCK");
  var flagged=FraudEngine.evaluate(transfer("1.00","ZZ",null,0),quiet(),rules(),NOW);
  assertThat(flagged.verdict()).isEqualTo("BLOCK");assertThat(flagged.reasons()).extracting(FraudEngine.Reason::code).containsExactly("FLAGGED_DECLARED_COUNTRY");
 }
 @Test void velocityRapidRepeatsCountryChangeAndFailedPaymentsHaveIndependentExplanations(){
  var result=FraudEngine.evaluate(transfer("1.00","FR",null,5),new FraudEngine.Velocity(30,4,12,true),rules(),NOW);
  assertThat(result.verdict()).isEqualTo("REVIEW");
  assertThat(result.reasons()).extracting(FraudEngine.Reason::code).containsExactly("FREQUENCY_REVIEW","RAPID_REPEAT","VELOCITY_CHANGE","DECLARED_COUNTRY_CHANGE","REPEATED_FAILED_PAYMENTS");
  assertThat(FraudEngine.evaluate(transfer("1.00","MA",null,0),new FraudEngine.Velocity(60,0,1,false),rules(),NOW).verdict()).isEqualTo("BLOCK");
 }
 @Test void exactThresholdsOldBeneficiaryAndStableBaselineDoNotInventRisk(){
  var result=FraudEngine.evaluate(transfer("10000.00","MA",NOW.minus(Duration.ofDays(2)),4),new FraudEngine.Velocity(15,100,1,false),rules(),NOW);
  assertThat(result.verdict()).isEqualTo("ALLOW");assertThat(result.reasons()).isEmpty();
  assertThatThrownBy(()->new FraudRules(rules().reviewAmounts(),rules().blockAmounts(),rules().newBeneficiaryAmounts(),null,60,12,15,5,24,java.util.List.of("ZZ")).validated()).hasMessage("INVALID_FRAUD_RULES");
 }
}
