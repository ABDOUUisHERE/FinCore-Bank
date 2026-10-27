package com.fincore.card;

import com.fasterxml.jackson.databind.JsonNode;
import com.fincore.support.*;
import java.time.Instant;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

class CardDatabaseIT {
 static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:17.11-alpine");static JdbcTemplate db,owner;static CardStore store;static TransactionTemplate tx;
 @BeforeAll static void setup(){PG.start();String password=UUID.randomUUID().toString();var admin=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()));for(String role:List.of("card_migrator","card_runtime"))admin.execute("create role "+role+" login password '"+password+"'");admin.execute("alter database test owner to card_migrator");Flyway.configure().dataSource(PG.getJdbcUrl(),"card_migrator",password).load().migrate();db=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),"card_runtime",password));owner=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),"card_migrator",password));store=new CardStore(db);tx=new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource()));}
 @AfterAll static void stop(){PG.stop();}
 static JsonNode card(UUID id,UUID account,int version,String status){return Json.read(Json.text(Map.of("cardId",id,"actor","owner","accountReference","account:"+account,"version",version,"status",status)));}
 static JsonNode receipt(UUID id,UUID card){return Json.read(Json.text(Map.of("authorizationId",id,"paymentId",id,"cardId",card,"actor","owner","kind","MERCHANT_PAYMENT","fingerprint","f".repeat(64),"amount","10.00","currency","MAD","destinationAccountId",UUID.randomUUID())));}
 @Test void outOfOrderControlRecoveryRetainsEveryCommittedFreezeWithoutRegressingProjection(){var id=UUID.randomUUID();var account=UUID.randomUUID();for(var r:List.of(card(id,account,2,"ACTIVE"),card(id,account,0,"ACTIVE"),card(id,account,1,"FROZEN"),card(id,account,1,"FROZEN")))tx.execute(s->{store.importCard(r);return null;});assertThat(store.card(id,"owner").path("observed_version").asInt()).isEqualTo(2);assertThat(store.card(id,"owner").path("observed_state").asText()).isEqualTo("ACTIVE");assertThat(db.queryForObject("select count(*) from card_outbox where aggregate_id=?",Integer.class,id)).isEqualTo(3);assertThat(db.queryForObject("select count(*) from card_outbox where aggregate_id=? and event_type='card.frozen'",Integer.class,id)).isEqualTo(1);}
 @Test void leasesFenceRecoveryAndNeverStoreBrowserTokensOrPermitCanonicalEdits(){var card=UUID.randomUUID();tx.execute(s->{store.importCard(card(card,UUID.randomUUID(),0,"ACTIVE"));return null;});var id=UUID.randomUUID();tx.execute(s->store.importAuthorization(receipt(id,card)));var leased=tx.execute(s->store.claim());assertThat(CardStore.id(leased,"id")).isEqualTo(id);tx.execute(s->{store.attempting(leased,Instant.now().plusSeconds(30));return null;});owner.update("update card_authorization set lease_until=clock_timestamp()-interval '1 second' where id=?",id);var newer=tx.execute(s->store.claim());assertThatThrownBy(()->tx.execute(s->{store.result(leased,"REJECTED","RISK_BLOCKED",UUID.randomUUID(),null);return null;})).isInstanceOf(ApiFailure.class);tx.execute(s->{store.result(newer,"PENDING_RISK_REVIEW","RISK_REVIEW",UUID.randomUUID(),null);return null;});assertThat(store.risk(id).path("paymentStatus").asText()).isEqualTo("PENDING_RISK_REVIEW");assertThat(store.authorization(id).hasNonNull("last_grant_expires")).isTrue();for(String sql:List.of("update card_authorization set receipt='{}'","delete from card_authorization","truncate card_outbox","update card_projection set actor='attacker'"))assertThatThrownBy(()->db.execute(sql)).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThatThrownBy(()->store.ownAuthorization(id,"other-owner")).isInstanceOf(ApiFailure.class).extracting("status").isEqualTo(404);}
}
