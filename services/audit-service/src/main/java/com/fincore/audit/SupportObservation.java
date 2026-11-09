package com.fincore.audit;

import com.fincore.support.ApiFailure;
import java.util.UUID;
import java.util.function.Function;

/** Shared disclosure sequence; the owner read holds no database transaction or lock. */
final class SupportObservation {
    private final SupportAccessStore grants;
    SupportObservation(SupportAccessStore grants) {this.grants=grants;}
    <T> T read(UUID incident,String actor,String correlation,SupportAccessStore.Resource resource,Function<UUID,T> owner) {
        var admission=grants.admit(incident,actor,correlation,resource);
        if(admission==null)throw denied();
        T result;
        try {result=owner.apply(admission.resourceId());}
        catch(RuntimeException failure) {
            if(!grants.finish(admission,correlation,"SOURCE_UNAVAILABLE"))throw denied();
            throw new ApiFailure(503,"SUPPORT_"+resource.name()+"_UNAVAILABLE");
        }
        if(!grants.finish(admission,correlation,"DISCLOSED"))throw denied();
        return result;
    }
    private static ApiFailure denied() {return new ApiFailure(403,"SUPPORT_ACCESS_UNAVAILABLE");}
}
