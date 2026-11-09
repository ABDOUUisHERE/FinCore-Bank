package com.fincore.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fincore.support.*;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class SupportPaymentAccess {
    private final SupportObservation observation;
    private final Remote payment;
    private final String base;
    public SupportPaymentAccess(SupportAccessStore grants,Remote payment,@Value("${PAYMENT_SERVICE_URL:http://localhost:8084}")String base) {
        this.observation=new SupportObservation(grants);this.payment=payment;this.base=base;
    }
    public record Status(UUID paymentId,String kind,String status,String version,Instant createdAt,Instant observedAt) {}
    public Status inspect(UUID incident,String actor,String correlation) {
        return observation.read(incident,actor,correlation,SupportAccessStore.Resource.PAYMENT,
            id->validate(id,payment.call("GET",base+"/internal/v1/payments/"+id+"/support-status",payment.serviceToken(),null)));
    }
    static Status validate(UUID expected,JsonNode source) {
        try {
            if(source==null || !source.isObject() || !text(source,"paymentId").equals(expected.toString()))throw new IllegalArgumentException();
            String kind=text(source,"kind"),status=text(source,"status"),version=text(source,"version");
            if(!Set.of("TRANSFER","DEPOSIT","REVERSAL").contains(kind)
                || !Set.of("CREATED","VALIDATING","PENDING_RISK_REVIEW","PROCESSING","COMPLETED","FAILED","REJECTED","REVERSAL_PENDING","REVERSED").contains(status)
                || !version.matches("0|[1-9][0-9]{0,18}"))throw new IllegalArgumentException();
            Long.parseLong(version);
            Instant created=Instant.parse(text(source,"createdAt")),observed=Instant.parse(text(source,"observedAt"));
            return new Status(expected,kind,status,version,created,observed);
        }catch(IllegalArgumentException | java.time.DateTimeException invalid){throw new ApiFailure(503,"INVALID_SUPPORT_PAYMENT_SOURCE");}
    }
    private static String text(JsonNode source,String key) {
        var value=source.path(key);if(!value.isTextual())throw new IllegalArgumentException();return value.asText();
    }
}
