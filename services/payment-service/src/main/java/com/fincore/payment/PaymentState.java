package com.fincore.payment;

import java.util.Set;

public enum PaymentState {
    CREATED, VALIDATING, PENDING_RISK_REVIEW, PROCESSING, COMPLETED, FAILED, REJECTED, REVERSAL_PENDING, REVERSED;
    public boolean canMoveTo(PaymentState next){return switch(this){
        case CREATED -> Set.of(VALIDATING,REJECTED).contains(next);
        case VALIDATING -> Set.of(PENDING_RISK_REVIEW,PROCESSING,REJECTED,FAILED).contains(next);
        case PENDING_RISK_REVIEW -> Set.of(VALIDATING,REJECTED).contains(next);
        case PROCESSING -> Set.of(COMPLETED,FAILED).contains(next);
        case COMPLETED -> next==REVERSAL_PENDING;
        case REVERSAL_PENDING -> Set.of(REVERSED,COMPLETED).contains(next);
        default -> false;
    };}
    public boolean cancellable(){return Set.of(CREATED,VALIDATING,PENDING_RISK_REVIEW).contains(this);}
}
