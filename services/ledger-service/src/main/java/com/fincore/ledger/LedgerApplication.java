package com.fincore.ledger;

import org.flywaydb.core.Flyway;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@org.springframework.context.annotation.Import(com.fincore.messaging.OutboxConfiguration.class)
@SpringBootApplication
public class LedgerApplication {
    public static void main(String[] args) {
        if (args.length == 1 && args[0].equals("--migrate-only")) {
            Flyway.configure().dataSource(required("LEDGER_DATABASE_URL"), "ledger_migrator", required("LEDGER_MIGRATION_PASSWORD")).load().migrate();
            return;
        }
        SpringApplication.run(LedgerApplication.class, args);
    }
    private static String required(String key) {
        var value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing " + key);
        return value;
    }
}
