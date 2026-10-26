package com.fincore.messaging;
import com.fasterxml.jackson.databind.JsonNode;
import com.fincore.support.Json;
import java.time.Instant;
import java.util.*;

/** Versioned facts only. Subject/account identifiers are opaque; no display names or email addresses. */
public record EventEnvelope(UUID eventId,String eventType,int schemaVersion,UUID aggregateId,long aggregateVersion,
                            String correlationId,Instant occurredAt,JsonNode payload) {
    public static EventEnvelope parse(String text) {
        if(text==null || text.length()>65536)throw new IllegalArgumentException("INVALID_EVENT_SIZE");
        var n=Json.read(text);
        var type=n.path("eventType").asText();
        if(n.path("schemaVersion").asInt()!=1 || !type.matches("[a-z]+(\\.[a-z_]+){1,3}") ||
           !n.path("aggregateVersion").canConvertToLong() || n.path("aggregateVersion").asLong()<0 ||
           !n.path("payload").isObject() || !n.path("correlationId").asText().matches("[A-Za-z0-9._:-]{1,120}"))
            throw new IllegalArgumentException("INVALID_EVENT_ENVELOPE");
        return new EventEnvelope(UUID.fromString(n.path("eventId").asText()),type,1,UUID.fromString(n.path("aggregateId").asText()),
            n.path("aggregateVersion").asLong(),n.path("correlationId").asText(),Instant.parse(n.path("occurredAt").asText()),n.path("payload"));
    }
    public String topic(){return "fincore."+eventType+".v1";}
}
