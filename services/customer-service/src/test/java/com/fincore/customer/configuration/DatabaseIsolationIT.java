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
import com.fincore.customer.adapter.in.web.CustomerSearchController;
import com.fincore.customer.adapter.in.web.CustomerSearchController.Input;
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

    private JdbcTemplate runtime() {
        String url=POSTGRES.getJdbcUrl().replace("/bootstrap","/customer");
        Flyway.configure().dataSource(url,"customer_migrator",PASSWORD).load().migrate();
        return new JdbcTemplate(new DriverManagerDataSource(url,"customer_runtime",PASSWORD));
    }

    @Test void nameSearchPagesRealProfilesWithoutChangingProfileOrOutbox() {
        var db=runtime(); var search=new CustomerSearchController.Search(db);
        String prefix="Paged-"+UUID.randomUUID();
        var ids=java.util.stream.IntStream.range(0,23).mapToObj(n->UUID.randomUUID()).sorted(java.util.Comparator.comparing(UUID::toString)).toList();
        for(int n=0;n<ids.size();n++)db.update("insert into customer_profile(id,subject,display_name,country_code,version) values(?,?,?,'MA',?)",ids.get(n),"private-"+ids.get(n),(n%2==0?prefix.toUpperCase(java.util.Locale.ROOT):prefix)+" "+n,9007199254740993L+n);
        var before=db.queryForObject("select jsonb_agg(to_jsonb(p) order by id)::text from customer_profile p",String.class);
        var outbox=db.queryForObject("select count(*) from customer_outbox",Long.class);
        var first=search.find(new Input("  "+prefix.toLowerCase(java.util.Locale.ROOT)+"  ",null));
        assertThat(first.items()).extracting(CustomerSearchController.Match::customerId).containsExactlyElementsOf(ids.subList(0,20));
        assertThat(first.nextAfterId()).isEqualTo(ids.get(19));
        assertThat(first.items().getFirst().profileVersion()).isEqualTo("9007199254740993");
        assertThat(first.toString()).doesNotContain("private-","countryCode","subject");
        var second=search.find(new Input(prefix,first.nextAfterId().toString()));
        assertThat(second.items()).extracting(CustomerSearchController.Match::customerId).containsExactlyElementsOf(ids.subList(20,23));
        assertThat(second.nextAfterId()).isNull();
        var end=search.find(new Input(prefix,ids.getLast().toString()));
        assertThat(end.items()).isEmpty();assertThat(end.nextAfterId()).isNull();assertThat(end.observedAt()).isNotNull();
        assertThat(db.queryForObject("select jsonb_agg(to_jsonb(p) order by id)::text from customer_profile p",String.class)).isEqualTo(before);
        assertThat(db.queryForObject("select count(*) from customer_outbox",Long.class)).isEqualTo(outbox);
    }

    @Test void nameSearchTreatsSqlAndLikeMetacharactersAsLiteralData() {
        var db=runtime();var search=new CustomerSearchController.Search(db);
        String suffix=UUID.randomUUID().toString();String prefix="Literal'_%!"+suffix;UUID id=UUID.randomUUID();
        db.update("insert into customer_profile(id,subject,display_name,country_code) values(?,?,?,'MA')",id,"private-"+id,prefix+" match");
        var other=UUID.randomUUID();db.update("insert into customer_profile(id,subject,display_name,country_code) values(?,?,?,'MA')",other,"private-"+other,"Literal'XY!"+suffix+" other");
        var page=search.find(new Input(prefix.toLowerCase(java.util.Locale.ROOT),null));
        assertThat(page.items()).extracting(CustomerSearchController.Match::customerId).containsExactly(id);
        assertThat(search.find(new Input("Absent' OR TRUE -- "+suffix,null)).items()).isEmpty();
    }

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
