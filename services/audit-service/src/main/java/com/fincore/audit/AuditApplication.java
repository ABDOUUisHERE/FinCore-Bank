package com.fincore.audit;

import org.flywaydb.core.Flyway;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AuditApplication {
    public static void main(String[] args) {
        if (args.length == 1 && args[0].equals("--migrate-only")) {
            Flyway.configure().dataSource(required("AUDIT_DATABASE_URL"), "audit_migrator", required("AUDIT_MIGRATION_PASSWORD")).load().migrate();
            return;
        }
        SpringApplication.run(AuditApplication.class, args);
    }
    private static String required(String key) {
        var value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing " + key);
        return value;
    }
}
