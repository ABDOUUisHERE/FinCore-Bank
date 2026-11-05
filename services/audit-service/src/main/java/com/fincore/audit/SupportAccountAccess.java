package com.fincore.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fincore.support.*;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Network observation is deliberately outside both database decision transactions. */
@Service
public class SupportAccountAccess {
    private final SupportAccessStore grants;
    private final Remote account;
    private final String base;
    public SupportAccountAccess(SupportAccessStore grants,Remote account,@Value("${ACCOUNT_SERVICE_URL:http://localhost:8083}")String base) {
        this.grants=grants;this.account=account;this.base=base;
    }
    public record Ledger(String status,String guardRevision,Instant observedAt) {}
    public record Status(UUID accountId,String currency,String recordedStatus,String accountVersion,String pendingStatus,
                         String lastGuardError,String recordedGuardRevision,Instant accountObservedAt,String ledgerState,Ledger ledger) {}
    public Status inspect(UUID incident,String actor,String correlation) {
        var admission=grants.admit(incident,actor,correlation);
        if(admission==null)throw denied();
        Status status;
        try { status=validate(admission.accountId(),account.call("GET",base+"/internal/v1/accounts/"+admission.accountId()+"/support-status",account.serviceToken(),null)); }
        catch(RuntimeException failure) {
            if(!grants.finish(admission,correlation,"SOURCE_UNAVAILABLE"))throw denied();
            throw new ApiFailure(503,"SUPPORT_ACCOUNT_UNAVAILABLE");
        }
        if(!grants.finish(admission,correlation,"DISCLOSED"))throw denied();
        return status;
    }
    private static ApiFailure denied() {return new ApiFailure(403,"SUPPORT_ACCESS_UNAVAILABLE");}
    static Status validate(UUID expected,JsonNode source) {
        try {
            if(source==null || !source.isObject() || !text(source,"accountId").equals(expected.toString()))throw new IllegalArgumentException();
            String currency=text(source,"currency"),recorded=text(source,"recordedStatus"),state=text(source,"ledgerState");
            if(!currency.matches("[A-Z]{3}") || !Set.of("PROVISIONING","ACTIVE","FROZEN","CLOSED","GUARD_PENDING").contains(recorded)
                || !Set.of("OBSERVED","UNAVAILABLE","NOT_LINKED").contains(state))throw new IllegalArgumentException();
            String pending=nullable(source,"pendingStatus"),error=nullable(source,"lastGuardError");
            if(pending!=null && !Set.of("ACTIVE","FROZEN","CLOSED").contains(pending)
                || error!=null && !Set.of("ACCOUNT_NOT_EMPTY","GUARD_CONFLICT").contains(error))throw new IllegalArgumentException();
            Ledger ledger=null;
            if(state.equals("OBSERVED")) {
                var l=source.path("ledger");var status=text(l,"status");
                if(!Set.of("ACTIVE","FROZEN","CLOSED").contains(status))throw new IllegalArgumentException();
                ledger=new Ledger(status,revision(l,"guardRevision"),Instant.parse(text(l,"observedAt")));
            } else if(!source.path("ledger").isNull() || state.equals("NOT_LINKED") && !recorded.equals("PROVISIONING"))throw new IllegalArgumentException();
            return new Status(expected,currency,recorded,revision(source,"accountVersion"),pending,error,revision(source,"recordedGuardRevision"),
                Instant.parse(text(source,"accountObservedAt")),state,ledger);
        }catch(IllegalArgumentException | java.time.DateTimeException invalid){throw new ApiFailure(503,"INVALID_SUPPORT_ACCOUNT_SOURCE");}
    }
    private static String text(JsonNode source,String key) {var value=source.path(key);if(!value.isTextual())throw new IllegalArgumentException();return value.asText();}
    private static String nullable(JsonNode source,String key) {return source.path(key).isNull()?null:text(source,key);}
    private static String revision(JsonNode source,String key) {var value=text(source,key);if(!value.matches("0|[1-9][0-9]{0,18}"))throw new IllegalArgumentException();Long.parseLong(value);return value;}
}
