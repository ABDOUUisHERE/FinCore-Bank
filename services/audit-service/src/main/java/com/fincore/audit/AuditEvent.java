package com.fincore.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fincore.messaging.EventEnvelope;
import com.fincore.support.Json;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Redaction happens before persistence. Unknown/free-text payload fields are never retained. */
public record AuditEvent(EventEnvelope envelope, JsonNode details, String actor, String hash) {
    public static final class Poison extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public Poison(String code) { super(code); }
    }
    public static String hash(String body) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((body == null ? "" : body).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException("SHA256_UNAVAILABLE"); }
    }
    public static AuditEvent parse(String topic, String key, String body) {
        try {
            var event = EventEnvelope.parse(body);
            var raw = Json.read(body);
            if (!raw.path("schemaVersion").isIntegralNumber() || !raw.path("aggregateVersion").isIntegralNumber()
                || event.occurredAt().isBefore(java.time.Instant.parse("2000-01-01T00:00:00Z"))
                || !event.occurredAt().isBefore(java.time.Instant.parse("2100-01-01T00:00:00Z")))
                throw new Poison("INVALID_EVENT_ENVELOPE");
            if (!event.topic().equals(topic) || !event.aggregateId().toString().equals(key)
                || !Set.of("customer","account","ledger","payment","fraud","card").contains(event.eventType().split("\\.")[0]))
                throw new Poison("EVENT_SOURCE_MISMATCH");
            if (event.eventType().equals("customer.access.recorded")) return customerAccess(event, body);
            var result = (com.fasterxml.jackson.databind.node.ObjectNode) Json.read("{}");
            for (String field : List.of("customerId","accountId","paymentId","journalId","cardId","caseId","alertId","assessmentId","reversalOf","refundJournalId")) {
                if (event.payload().hasNonNull(field)) result.put(field, UUID.fromString(event.payload().path(field).asText()).toString());
            }
            for (String field : List.of("status","kind","currency","verdict")) {
                if (event.payload().hasNonNull(field)) {
                    String value = event.payload().path(field).asText();
                    if (!value.matches("[A-Za-z_]{1,40}")) throw new Poison("INVALID_AUDIT_METADATA");
                    result.put(field, value);
                }
            }
            String actor = event.payload().path("actorSubject").asText("");
            if (actor.isEmpty()) actor = event.payload().path("ownerSubject").asText("");
            // Preserve opaque technical subjects; drop email-like or arbitrary text identifiers.
            if (!actor.matches("[A-Za-z0-9:_-]{1,200}")) actor = null;
            return new AuditEvent(event, result, actor, hash(body));
        } catch (Poison error) { throw error; }
        catch (IllegalArgumentException | java.time.DateTimeException error) { throw new Poison("INVALID_EVENT_ENVELOPE"); }
    }
    private static AuditEvent customerAccess(EventEnvelope event, String body) {
        var payload = event.payload();
        String action = text(payload,"action",Set.of("CUSTOMER_SEARCH","CUSTOMER_INSPECT"));
        String method = text(payload,"httpMethod",Set.of("GET","POST","OTHER"));
        String provenance = text(payload,"actorProvenance",Set.of("SIGNED_JWT","UNAVAILABLE"));
        if (!event.aggregateId().equals(event.eventId()) || event.aggregateVersion()!=1
            || !payload.path("httpStatus").isIntegralNumber() || !payload.path("httpStatus").canConvertToInt())
            throw new Poison("INVALID_ACCESS_AUDIT");
        int status = payload.path("httpStatus").asInt();
        if (status<100 || status>599) throw new Poison("INVALID_ACCESS_AUDIT");
        String outcome = switch(status) {
            case 400,405,409,415,422 -> "INVALID_REQUEST";
            case 401 -> "UNAUTHENTICATED"; case 403 -> "FORBIDDEN"; case 404 -> "NOT_FOUND";
            default -> status>=200 && status<300 ? "RESPONSE_READY" : "SERVER_ERROR";
        };
        if (!outcome.equals(text(payload,"outcome",Set.of(outcome)))) throw new Poison("INVALID_ACCESS_AUDIT");
        var details = (com.fasterxml.jackson.databind.node.ObjectNode) Json.read("{}");
        if (payload.hasNonNull("customerId")) {
            if (!action.equals("CUSTOMER_INSPECT") || !payload.path("customerId").isTextual()) throw new Poison("INVALID_ACCESS_AUDIT");
            String reference=payload.path("customerId").asText();
            if (!reference.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw new Poison("INVALID_ACCESS_AUDIT");
            details.put("customerId", UUID.fromString(reference).toString());
        }
        String actor = null;
        if (provenance.equals("SIGNED_JWT")) {
            if (!payload.path("actorSubject").isTextual() || !payload.path("actorSubject").asText().matches("[A-Za-z0-9:_-]{1,200}")) throw new Poison("INVALID_ACCESS_AUDIT");
            actor=payload.path("actorSubject").asText();
        } else if (payload.hasNonNull("actorSubject")) throw new Poison("INVALID_ACCESS_AUDIT");
        details.put("action",action).put("httpMethod",method).put("httpStatus",status).put("outcome",outcome).put("actorProvenance",provenance);
        return new AuditEvent(event,details,actor,hash(body));
    }
    private static String text(JsonNode payload,String field,Set<String> values) {
        if (!payload.path(field).isTextual() || !values.contains(payload.path(field).asText())) throw new Poison("INVALID_ACCESS_AUDIT");
        return payload.path(field).asText();
    }
}
