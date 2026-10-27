package com.fincore.payment;

import java.math.BigDecimal;
import java.time.*;
import com.fasterxml.jackson.databind.JsonNode;

/** Synchronous risk port. The local implementation is an explicit isolated test fixture only. */
public interface RiskPolicy {
    enum Verdict {ALLOW, REVIEW, BLOCK}
    record Assessment(Verdict verdict,String reason,String ruleset,Instant expiresAt,java.util.UUID reference) {
        public Assessment(Verdict verdict,String reason,String ruleset,Instant expiresAt){this(verdict,reason,ruleset,expiresAt,null);}
    }
    Assessment assess(String kind,BigDecimal amount,String currency,boolean reviewed);
    default Assessment assess(JsonNode payment){return assess(payment.path("kind").asText(),new BigDecimal(payment.path("amount").asText()),payment.path("currency").asText(),payment.path("approved").asBoolean());}
    final class Local implements RiskPolicy {
        private final Clock clock;
        public Local(){this(Clock.systemUTC());}
        public Local(Clock clock){this.clock=clock;}
        public Assessment assess(String kind,BigDecimal amount,String currency,boolean reviewed){
            if(!java.util.Set.of("MAD","EUR","USD").contains(currency) || amount.signum()<=0)throw new IllegalArgumentException("Invalid risk input");
            var decision=kind.equals("REVERSAL")?Verdict.ALLOW:amount.compareTo(new BigDecimal("100000.00"))>0?Verdict.BLOCK
                :amount.compareTo(new BigDecimal("10000.00"))>0 && !reviewed?Verdict.REVIEW:Verdict.ALLOW;
            return new Assessment(decision,switch(decision){case ALLOW->"WITHIN_DEMO_POLICY";case REVIEW->"HIGH_VALUE_REVIEW";case BLOCK->"DEMO_HARD_LIMIT";},"phase3-local-v1",clock.instant().plusSeconds(30));
        }
    }
}
