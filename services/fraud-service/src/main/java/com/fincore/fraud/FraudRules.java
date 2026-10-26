package com.fincore.fraud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fincore.support.ApiFailure;
import com.fincore.support.Json;
import java.math.BigDecimal;
import java.util.*;

public record FraudRules(Map<String,String> reviewAmounts, Map<String,String> blockAmounts,
        Map<String,String> newBeneficiaryAmounts, Integer frequencyReview, Integer frequencyBlock,
        Integer rapidCount, Integer velocityCount, Integer failedCount, Integer newBeneficiaryHours,
        List<String> flaggedCountries) {
    private static final Set<String> CURRENCIES=Set.of("MAD","EUR","USD");
    public FraudRules validated() {
        for(var amounts:Arrays.asList(reviewAmounts,blockAmounts,newBeneficiaryAmounts)) {
            if(amounts==null || !amounts.keySet().equals(CURRENCIES))invalid();
            for(var value:amounts.values())if(value==null || !value.matches("[0-9]{1,12}\\.[0-9]{2}") || new BigDecimal(value).signum()<=0)invalid();
        }
        for(var currency:CURRENCIES)if(new BigDecimal(newBeneficiaryAmounts.get(currency)).compareTo(new BigDecimal(reviewAmounts.get(currency)))>0
                || new BigDecimal(reviewAmounts.get(currency)).compareTo(new BigDecimal(blockAmounts.get(currency)))>=0)invalid();
        if(!bounded(frequencyReview,2,500) || !bounded(frequencyBlock,frequencyReview,1000)
                || !bounded(rapidCount,2,500) || !bounded(velocityCount,2,500) || !bounded(failedCount,1,100)
                || !bounded(newBeneficiaryHours,1,168) || flaggedCountries==null || flaggedCountries.size()>20
                || flaggedCountries.stream().anyMatch(c->c==null || !c.matches("[A-Z]{2}"))
                || new HashSet<>(flaggedCountries).size()!=flaggedCountries.size())invalid();
        return new FraudRules(Map.copyOf(reviewAmounts),Map.copyOf(blockAmounts),Map.copyOf(newBeneficiaryAmounts),frequencyReview,frequencyBlock,rapidCount,velocityCount,failedCount,newBeneficiaryHours,List.copyOf(flaggedCountries));
    }
    private static boolean bounded(Integer n,int min,int max){return n!=null && n>=min && n<=max;}
    private static void invalid(){throw new ApiFailure(400,"INVALID_FRAUD_RULES");}
    public static FraudRules read(JsonNode node){try{return new com.fasterxml.jackson.databind.ObjectMapper().treeToValue(node,FraudRules.class).validated();}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new ApiFailure(400,"INVALID_FRAUD_RULES");}}
}
