package com.fincore.payment;

import java.math.BigDecimal;
import java.time.*;
import org.springframework.stereotype.Component;

/** Explicit local policy port; no claim of production fraud or velocity analysis. */
public interface RiskPolicy {
    enum Verdict {ALLOW, REVIEW, BLOCK}
    record Assessment(Verdict verdict,String reason,String ruleset,Instant expiresAt) {}
    Assessment assess(String kind,BigDecimal amount,String currency,boolean reviewed);
    @Component final class Local implements RiskPolicy {
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
