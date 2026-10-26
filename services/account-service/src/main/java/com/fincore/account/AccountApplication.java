package com.fincore.account;

import org.flywaydb.core.Flyway;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@org.springframework.scheduling.annotation.EnableScheduling
@org.springframework.context.annotation.Import(com.fincore.messaging.OutboxConfiguration.class)
@SpringBootApplication
public class AccountApplication {
    public static void main(String[] args) {
        if (args.length == 1 && args[0].equals("--migrate-only")) {
            Flyway.configure().dataSource(required("ACCOUNT_DATABASE_URL"), "account_migrator", required("ACCOUNT_MIGRATION_PASSWORD")).load().migrate();
            return;
        }
        SpringApplication.run(AccountApplication.class, args);
    }
    private static String required(String key) {
        var value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing " + key);
        return value;
    }
}
