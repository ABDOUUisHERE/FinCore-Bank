package com.fincore.payment;

import java.math.BigDecimal;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RiskPolicyTest {
    @Test void distinguishesAllowReviewBlockAndRevalidatesAnApproval(){
        var now=Instant.parse("2026-10-23T10:00:00Z");var risk=new RiskPolicy.Local(Clock.fixed(now,ZoneOffset.UTC));
        assertThat(risk.assess("TRANSFER",new BigDecimal("10000"),"MAD",false).verdict()).isEqualTo(RiskPolicy.Verdict.ALLOW);
        assertThat(risk.assess("TRANSFER",new BigDecimal("10000.01"),"MAD",false).verdict()).isEqualTo(RiskPolicy.Verdict.REVIEW);
        assertThat(risk.assess("TRANSFER",new BigDecimal("10000.01"),"MAD",true).verdict()).isEqualTo(RiskPolicy.Verdict.ALLOW);
        assertThat(risk.assess("TRANSFER",new BigDecimal("100000.01"),"MAD",true).verdict()).isEqualTo(RiskPolicy.Verdict.BLOCK);
        assertThat(risk.assess("TRANSFER",BigDecimal.ONE,"EUR",false).expiresAt()).isEqualTo(now.plusSeconds(30));
    }
    @Test void terminalStatesCannotRestartAndProcessingCannotBeCancelled(){
        for(var state:java.util.List.of(PaymentState.FAILED,PaymentState.REJECTED,PaymentState.REVERSED))for(var target:PaymentState.values())assertThat(state.canMoveTo(target)).isFalse();
        assertThat(PaymentState.PROCESSING.cancellable()).isFalse();assertThat(PaymentState.PROCESSING.canMoveTo(PaymentState.CREATED)).isFalse();
        assertThat(PaymentState.REVERSAL_PENDING.canMoveTo(PaymentState.COMPLETED)).isTrue();
    }
    @Test void invalidMoneyCannotReachAuthorization(){
        for(String amount:java.util.List.of("0","-1","1.001","1e2","1000000000000000000","NaN"))assertThatThrownBy(()->PaymentCoordinator.amount(amount,"MAD")).isInstanceOf(com.fincore.support.ApiFailure.class);
        assertThat(PaymentCoordinator.amount("100","MAD")).isEqualTo("100.00");
    }
}
