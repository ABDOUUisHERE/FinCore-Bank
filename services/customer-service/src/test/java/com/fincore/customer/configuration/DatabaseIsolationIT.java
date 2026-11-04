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
import com.fincore.customer.adapter.in.web.CustomerInspectionController;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

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

    @Test void statusInspectionUsesRuntimePrivilegesAndDoesNotChangeProfileOrOutbox() {
        String url=POSTGRES.getJdbcUrl().replace("/bootstrap", "/customer");
        Flyway.configure().dataSource(url,"customer_migrator",PASSWORD).load().migrate();
        var db=new JdbcTemplate(new DriverManagerDataSource(url,"customer_runtime",PASSWORD));
        var id=UUID.randomUUID();
        db.update("insert into customer_profile(id,subject,display_name,country_code,version) values(?,?,'Private canary','MA',9007199254740993)",id,"private-"+id);
        var count=db.queryForObject("select count(*) from customer_outbox",Long.class);
        var result=new CustomerInspectionController.Inspection(db).get(id);
        assertThat(result.customerId()).isEqualTo(id);assertThat(result.status()).isEqualTo("ACTIVE");
        assertThat(result.profileVersion()).isEqualTo("9007199254740993");
        assertThat(result.toString()).doesNotContain("Private canary","private-","MA");
        assertThat(db.queryForObject("select count(*) from customer_outbox",Long.class)).isEqualTo(count);
        assertThatThrownBy(()->new CustomerInspectionController.Inspection(db).get(UUID.randomUUID()))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("CUSTOMER_NOT_FOUND");
    }

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
