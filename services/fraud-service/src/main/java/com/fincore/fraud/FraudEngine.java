package com.fincore.fraud;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Explainable simulation rules; declared country is not physical geolocation. */
public final class FraudEngine {
    private FraudEngine(){}
    public record Context(String kind,BigDecimal amount,String currency,String country,Instant beneficiaryCreatedAt,int failures){}
    public record Velocity(int currentMinute,int priorFourMinutes,int rapidDestination,boolean countryChanged){}
    public record Reason(String code,int score,String observed,String threshold){}
    public record Result(String verdict,int score,List<Reason> reasons){}
    public static Result evaluate(Context c,Velocity v,FraudRules r,Instant now) {
        if(!Set.of("TRANSFER","MERCHANT_PAYMENT").contains(c.kind()))return new Result("ALLOW",0,List.of(new Reason(c.kind().equals("REVERSAL")?"CONFIRMED_COMPENSATION":"PRIVATE_SIMULATION_FUNDING",0,c.kind(),"technical authorization required")));
        var reasons=new ArrayList<Reason>();
        var amount=c.amount();var currency=c.currency();
        if(amount.compareTo(new BigDecimal(r.blockAmounts().get(currency)))>0)add(reasons,"HARD_AMOUNT_LIMIT",100,amount.toPlainString(),r.blockAmounts().get(currency));
        else if(amount.compareTo(new BigDecimal(r.reviewAmounts().get(currency)))>0)add(reasons,"HIGH_VALUE",50,amount.toPlainString(),r.reviewAmounts().get(currency));
        if((c.beneficiaryCreatedAt()==null || c.beneficiaryCreatedAt().isAfter(now.minus(Duration.ofHours(r.newBeneficiaryHours()))))
                && amount.compareTo(new BigDecimal(r.newBeneficiaryAmounts().get(currency)))>0)
            add(reasons,c.kind().equals("MERCHANT_PAYMENT")?"NEW_MERCHANT_HIGH_VALUE":"NEW_BENEFICIARY_HIGH_VALUE",50,amount.toPlainString(),r.newBeneficiaryAmounts().get(currency));
        if(v.currentMinute()>=r.frequencyBlock())add(reasons,"EXCESSIVE_FREQUENCY",100,""+v.currentMinute(),""+r.frequencyBlock());
        else if(v.currentMinute()>=r.frequencyReview())add(reasons,"FREQUENCY_REVIEW",50,""+v.currentMinute(),""+r.frequencyReview());
        if(v.rapidDestination()>=r.rapidCount())add(reasons,"RAPID_REPEAT",50,""+v.rapidDestination(),""+r.rapidCount());
        if(v.currentMinute()>=r.velocityCount() && (long)v.currentMinute()*4>Math.max(1,v.priorFourMinutes())*3L)
            add(reasons,"VELOCITY_CHANGE",50,""+v.currentMinute(),"minute count >= "+r.velocityCount()+" and > 3x prior four-minute average");
        if(r.flaggedCountries().contains(c.country()))add(reasons,"FLAGGED_DECLARED_COUNTRY",100,c.country(),"configured simulated country");
        if(v.countryChanged())add(reasons,"DECLARED_COUNTRY_CHANGE",50,c.country(),"changed within ten minutes");
        if(c.failures()>=r.failedCount())add(reasons,"REPEATED_FAILED_PAYMENTS",50,""+c.failures(),""+r.failedCount());
        int score=Math.min(100,reasons.stream().mapToInt(Reason::score).sum());
        // Independent review signals do not turn an otherwise reviewable transfer into an unreviewable hard block.
        boolean hard=reasons.stream().anyMatch(x->x.score()==100);
        return new Result(hard?"BLOCK":score>=50?"REVIEW":"ALLOW",score,List.copyOf(reasons));
    }
    private static void add(List<Reason> reasons,String code,int score,String observed,String threshold){reasons.add(new Reason(code,score,observed,threshold));}
}
