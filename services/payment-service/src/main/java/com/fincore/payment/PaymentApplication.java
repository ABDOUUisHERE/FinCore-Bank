package com.fincore.payment;

import org.flywaydb.core.Flyway;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@org.springframework.scheduling.annotation.EnableScheduling
@SpringBootApplication
public class PaymentApplication {
    public static void main(String[] args) {
        if (args.length == 1 && args[0].equals("--migrate-only")) {
            Flyway.configure().dataSource(required("PAYMENT_DATABASE_URL"), "payment_migrator", required("PAYMENT_MIGRATION_PASSWORD")).load().migrate();
            return;
        }
        SpringApplication.run(PaymentApplication.class, args);
    }
    private static String required(String key) {
        var value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing " + key);
        return value;
    }
}
