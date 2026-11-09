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
        PAYMENT("payment","payment_id","/api/v1/admin/transactions/","PAYMENT_INSPECT"),
        REPORTING("reporting","resource_id","/api/v1/statements/","STATEMENT_INSPECT");
        private final String table,referenceColumn,prefix,action;
        Owner(String domain,String referenceColumn,String prefix,String action) {
            this.table=domain+"_access_audit";this.referenceColumn=referenceColumn;this.prefix=prefix;this.action=action;
        }
        public Target target(String path) { return target(path,"GET"); }
        public Target target(String path,String method) {
            if (this==REPORTING) return reportingTarget(path,method);
            if (!path.startsWith(prefix)) return null;
            String reference=path.substring(prefix.length());
            if (this==CUSTOMER && reference.equals("search")) return new Target("CUSTOMER_SEARCH",null);
            if (reference.isEmpty() || reference.contains("/")) return null;
            return new Target(action,reference(reference));
        }
        private static UUID reference(String value) {
            return value.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") ? UUID.fromString(value) : null;
        }
        private static Target reportingTarget(String path,String method) {
            if (path.equals("/api/v1/statements")) return new Target(method.equals("POST")?"STATEMENT_CREATE_REQUEST":"STATEMENT_LIST",null);
            if (path.equals("/api/v1/admin/reports/reconciliation")) return new Target("RECONCILIATION_INSPECT",null);
            String journal="/api/v1/admin/ledger/journals/",statement="/api/v1/statements/";
            if (path.startsWith(journal)) {
                String id=path.substring(journal.length());
                return id.isEmpty() || id.contains("/") ? null : new Target("JOURNAL_INSPECT",reference(id));
            }
            if (!path.startsWith(statement)) return null;
            String[] parts=path.substring(statement.length()).split("/",-1);
            if (parts[0].isEmpty()) return null;
            if (parts.length==1) return new Target("STATEMENT_INSPECT",reference(parts[0]));
            if (parts.length==2 && (parts[1].equals("csv") || parts[1].equals("pdf")))
                return new Target(parts[1].equals("csv")?"STATEMENT_EXPORT_CSV":"STATEMENT_EXPORT_PDF",reference(parts[0]));
            return null;
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
