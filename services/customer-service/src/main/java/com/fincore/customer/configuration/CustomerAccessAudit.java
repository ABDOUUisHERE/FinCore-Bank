package com.fincore.customer.configuration;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** One committed row is both the local receipt and the immutable outbox source. */
@Service
public class CustomerAccessAudit {
    private final JdbcTemplate db;
    public CustomerAccessAudit(JdbcTemplate db) { this.db = db; }
    public record Target(String action, UUID customerId) {}
    public record Actor(String subject) {}
    public void retain(Target target, String method, int status, Actor actor, String correlation) {
        String outcome = switch (status) {
            case 400,405,409,415,422 -> "INVALID_REQUEST";
            case 401 -> "UNAUTHENTICATED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            default -> status >= 200 && status < 300 ? "RESPONSE_READY" : "SERVER_ERROR";
        };
        db.update("insert into customer_access_audit(id,action,customer_id,http_method,http_status,outcome,actor_subject,actor_provenance,correlation_id) values(?,?,?,?,?,?,?,?,?)",
            UUID.randomUUID(), target.action(), target.customerId(), method.equals("GET") || method.equals("POST") ? method : "OTHER",
            status, outcome, actor == null ? null : actor.subject(), actor == null ? "UNAVAILABLE" : "SIGNED_JWT", correlation);
    }
    static Target target(String path) {
        String prefix = "/api/v1/admin/customers/";
        if (!path.startsWith(prefix)) return null;
        String reference = path.substring(prefix.length());
        if (reference.equals("search")) return new Target("CUSTOMER_SEARCH", null);
        if (reference.isEmpty() || reference.contains("/")) return null;
        UUID id = reference.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") ? UUID.fromString(reference) : null;
        return new Target("CUSTOMER_INSPECT", id);
    }
}
