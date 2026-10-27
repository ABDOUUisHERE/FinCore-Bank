package com.fincore.reporting;

import org.flywaydb.core.Flyway;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@org.springframework.scheduling.annotation.EnableScheduling
@SpringBootApplication
public class ReportingApplication {
    public static void main(String[] args) {
        if (args.length == 1 && args[0].equals("--migrate-only")) {
            Flyway.configure().dataSource(required("REPORTING_DATABASE_URL"), "reporting_migrator", required("REPORTING_MIGRATION_PASSWORD")).load().migrate();
            return;
        }
        SpringApplication.run(ReportingApplication.class, args);
    }
    private static String required(String key) {
        var value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing " + key);
        return value;
    }
}
