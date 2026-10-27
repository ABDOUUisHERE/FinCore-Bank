package com.fincore.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fincore.support.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

class ReportingDatabaseIT {
 static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:17.11-alpine");static JdbcTemplate db;static StatementStore store;static TransactionTemplate tx;
 @BeforeAll static void setup(){PG.start();String password=UUID.randomUUID().toString();var admin=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()));for(String role:List.of("reporting_migrator","reporting_runtime"))admin.execute("create role "+role+" login password '"+password+"'");admin.execute("alter database test owner to reporting_migrator");Flyway.configure().dataSource(PG.getJdbcUrl(),"reporting_migrator",password).load().migrate();db=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),"reporting_runtime",password));store=new StatementStore(db);tx=new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource()));}
 @AfterAll static void stop(){PG.stop();}
 @Test void immutableSnapshotReplaysByContentAndRemainsIsolatedBySignedOwner(){
  var snapshot=(com.fasterxml.jackson.databind.node.ObjectNode)StatementExportsTest.statement(3).path("snapshot");var account=UUID.fromString(snapshot.path("accountId").asText());JsonNode first=tx.execute(s->store.save("owner",account,snapshot));snapshot.put("observedAt","2026-02-01T00:00:00Z");JsonNode replay=tx.execute(s->store.save("owner",account,snapshot));assertThat(replay).isEqualTo(first);snapshot.put("currentReserved","25.00");snapshot.put("currentAvailable","113.75");JsonNode changed=tx.execute(s->store.save("owner",account,snapshot));assertThat(changed.path("statementId")).isNotEqualTo(first.path("statementId"));assertThat(store.own(UUID.fromString(first.path("statementId").asText()),"owner")).isEqualTo(first);assertThatThrownBy(()->store.own(UUID.fromString(first.path("statementId").asText()),"other-owner")).isInstanceOf(ApiFailure.class).extracting("status").isEqualTo(404);
  for(String sql:List.of("update statement_snapshot set payload='{}'","delete from statement_snapshot","truncate statement_snapshot"))assertThatThrownBy(()->db.execute(sql)).isInstanceOf(org.springframework.dao.DataAccessException.class);
 }
}
