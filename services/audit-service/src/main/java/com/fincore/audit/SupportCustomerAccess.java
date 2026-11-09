package com.fincore.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fincore.support.*;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class SupportCustomerAccess {
    private final SupportObservation observation;private final SupportAccessStore grants;private final Remote customer;private final String base;
    public SupportCustomerAccess(SupportAccessStore grants,Remote customer,@Value("${CUSTOMER_SERVICE_URL:http://localhost:8081}")String base) {
        observation=new SupportObservation(grants);this.grants=grants;this.customer=customer;this.base=base;
    }
    public record Profile(UUID customerId,String displayName,String status,String profileVersion,Instant createdAt,Instant observedAt) {}
    public record Input(String namePrefix) {}
    public record Match(UUID customerId,String displayName,String status,UUID incidentId) {}
    public record Results(List<Match> items,boolean hasMore,Instant expiresAt,Instant observedAt) {}
    public Profile inspect(UUID incident,String actor,String correlation) {
        return observation.read(incident,actor,correlation,SupportAccessStore.Resource.CUSTOMER,
            id->validate(id,customer.call("GET",base+"/internal/v1/customers/"+id+"/support-profile",customer.serviceToken(),null)));
    }
    public Results search(Input input,String actor,String correlation) {
        String prefix=prefix(input);var admission=grants.admitCustomerSearch(actor,correlation);if(admission==null)throw denied();
        Results result;
        try {
            var allowed=new LinkedHashMap<UUID,UUID>();for(var grant:admission.grants())allowed.putIfAbsent(grant.resourceId(),grant.incidentId());
            var source=customer.call("POST",base+"/internal/v1/customers/support-search",customer.serviceToken(),Map.of("namePrefix",prefix,"customerIds",allowed.keySet()));
            result=project(source,allowed,admission.expiresAt());
        }catch(RuntimeException failure) {
            if(!grants.finishCustomerSearch(admission,correlation,"SOURCE_UNAVAILABLE",0))throw denied();
            throw new ApiFailure(503,"SUPPORT_CUSTOMER_UNAVAILABLE");
        }
        if(!grants.finishCustomerSearch(admission,correlation,"DISCLOSED",result.items().size()))throw denied();return result;
    }
    static String prefix(Input input) {
        String value=input==null?null:input.namePrefix();
        if(value==null||value.chars().anyMatch(Character::isISOControl)||value.strip().length()<3||value.strip().length()>80)throw new ApiFailure(400,"INVALID_SEARCH_PREFIX");
        return value.strip();
    }
    static Results project(JsonNode source,Map<UUID,UUID> allowed,Instant expiry) {
        try {
            if(source==null||!source.isObject()||!source.path("items").isArray()||source.path("items").size()>20||!source.path("hasMore").isBoolean())throw new IllegalArgumentException();
            var seen=new HashSet<UUID>();var items=new ArrayList<Match>();
            for(var item:source.path("items")) {
                UUID id=UUID.fromString(text(item,"customerId"));if(!allowed.containsKey(id)||!seen.add(id))throw new IllegalArgumentException();
                var profile=validate(id,item);items.add(new Match(id,profile.displayName(),profile.status(),allowed.get(id)));
            }
            if(source.path("hasMore").asBoolean()&&items.size()!=20)throw new IllegalArgumentException();
            return new Results(List.copyOf(items),source.path("hasMore").asBoolean(),expiry,Instant.now());
        }catch(IllegalArgumentException invalid){throw new ApiFailure(503,"INVALID_SUPPORT_CUSTOMER_SOURCE");}
    }
    static Profile validate(UUID expected,JsonNode source) {
        try {
            if(source==null||!source.isObject()||!text(source,"customerId").equals(expected.toString()))throw new IllegalArgumentException();
            String name=text(source,"displayName"),status=text(source,"status"),version=text(source,"profileVersion");
            if(name.isBlank()||name.length()>80||name.chars().anyMatch(Character::isISOControl)||!status.equals("ACTIVE")||!version.matches("0|[1-9][0-9]{0,18}"))throw new IllegalArgumentException();
            Long.parseLong(version);return new Profile(expected,name,status,version,Instant.parse(text(source,"createdAt")),Instant.parse(text(source,"observedAt")));
        }catch(IllegalArgumentException|java.time.DateTimeException invalid){throw new ApiFailure(503,"INVALID_SUPPORT_CUSTOMER_SOURCE");}
    }
    private static String text(JsonNode node,String field) {var value=node.path(field);if(!value.isTextual())throw new IllegalArgumentException();return value.asText();}
    private static ApiFailure denied(){return new ApiFailure(403,"SUPPORT_ACCESS_UNAVAILABLE");}
}
