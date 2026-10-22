package com.fincore.customer.configuration;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class DatabaseIsolationIT {
    static final String PASSWORD = UUID.randomUUID().toString();
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.11-alpine")
        .withDatabaseName("bootstrap")
        .withEnv("CUSTOMER_DB_PASSWORD", PASSWORD)
        .withEnv("CUSTOMER_MIGRATION_PASSWORD", PASSWORD)
        .withEnv("KEYCLOAK_DB_PASSWORD", UUID.randomUUID().toString())
        .withCopyFileToContainer(MountableFile.forHostPath(Path.of("../../infrastructure/docker/postgres/init-databases.sh").toAbsolutePath().normalize().toString(), 0744), "/docker-entrypoint-initdb.d/10-databases.sh");

    @Test void migrationAndRuntimePrivilegesAreSeparated() throws Exception {
        String url = POSTGRES.getJdbcUrl().replace("/bootstrap", "/customer");
        Flyway.configure().dataSource(url, "customer_migrator", PASSWORD).load().migrate();
        try (var runtime = DriverManager.getConnection(url, "customer_runtime", PASSWORD); var s = runtime.createStatement()) {
            try (var result = s.executeQuery("SELECT component FROM foundation_metadata")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("customer-service");
            }
            assertThatThrownBy(() -> s.execute("CREATE TABLE forbidden(id int)"))
                .isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
            assertThatThrownBy(() -> s.execute("DELETE FROM foundation_metadata"))
                .isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
        }
        assertThatThrownBy(() -> DriverManager.getConnection(POSTGRES.getJdbcUrl().replace("/bootstrap", "/keycloak"), "customer_runtime", PASSWORD))
            .isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
    }
}
