package com.fincore.customer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@org.springframework.context.annotation.Import(com.fincore.messaging.OutboxConfiguration.class)
@SpringBootApplication
public class CustomerApplication {
    public static void main(String[] args) {
        if (args.length == 1 && "--migrate-only".equals(args[0])) {
            org.flywaydb.core.Flyway.configure()
                .dataSource(required("CUSTOMER_DATABASE_URL"), "customer_migrator", required("CUSTOMER_MIGRATION_PASSWORD"))
                .load().migrate();
            return;
        }
        SpringApplication.run(CustomerApplication.class, args);
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing environment variable: " + key);
        return value;
    }
}
