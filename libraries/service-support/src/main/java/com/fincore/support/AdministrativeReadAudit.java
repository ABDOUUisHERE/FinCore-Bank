package com.fincore.support;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** A committed receipt is also the immutable owner outbox source. Policies are closed, never caller-selected SQL. */
public class AdministrativeReadAudit {
    public enum Owner {
        CUSTOMER("customer","customer_id","/api/v1/admin/customers/","CUSTOMER_INSPECT"),
        ACCOUNT("account","account_id","/api/v1/admin/accounts/","ACCOUNT_INSPECT"),
        PAYMENT("payment","payment_id","/api/v1/admin/transactions/","PAYMENT_INSPECT");
        private final String table,referenceColumn,prefix,action;
        Owner(String domain,String referenceColumn,String prefix,String action) {
            this.table=domain+"_access_audit";this.referenceColumn=referenceColumn;this.prefix=prefix;this.action=action;
        }
        public Target target(String path) {
            if (!path.startsWith(prefix)) return null;
            String reference=path.substring(prefix.length());
            if (this==CUSTOMER && reference.equals("search")) return new Target("CUSTOMER_SEARCH",null);
            if (reference.isEmpty() || reference.contains("/")) return null;
            UUID id=reference.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") ? UUID.fromString(reference) : null;
            return new Target(action,id);
        }
    }
    public record Target(String action,UUID resourceId) {}
    public record Actor(String subject) {}
    private final JdbcTemplate db;
    private final TransactionTemplate receipt;
    private final Owner owner;
    public AdministrativeReadAudit(JdbcTemplate db,PlatformTransactionManager transactions,Owner owner) {
        this.db=db;this.owner=owner;receipt=new TransactionTemplate(transactions);
        receipt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        receipt.setTimeout(3);
    }
    public void retain(Target target,String method,int status,Actor actor,String correlation) {
        receipt.executeWithoutResult(ignored->db.update("insert into "+owner.table+"(id,action,"+owner.referenceColumn+",http_method,http_status,outcome,actor_subject,actor_provenance,correlation_id) values(?,?,?,?,?,?,?,?,?)",
            UUID.randomUUID(),target.action(),target.resourceId(),method.equals("GET") || method.equals("POST") ? method : "OTHER",
            status,outcome(status),actor==null?null:actor.subject(),actor==null?"UNAVAILABLE":"SIGNED_JWT",correlation));
    }
    private static String outcome(int status) {
        return switch(status) {
            case 400,405,409,415,422 -> "INVALID_REQUEST";
            case 401 -> "UNAUTHENTICATED"; case 403 -> "FORBIDDEN"; case 404 -> "NOT_FOUND";
            default -> status>=200 && status<300 ? "RESPONSE_READY" : "SERVER_ERROR";
        };
    }
}
