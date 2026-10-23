package com.fincore.beneficiary;

import com.fincore.support.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

class BeneficiaryDatabaseIT {
    static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:17.11-alpine").withDatabaseName("beneficiary");
    static final String PASSWORD=UUID.randomUUID().toString();
    static JdbcTemplate runtime;
    static BeneficiaryStore store;
    @BeforeAll static void start() {
        PG.start();
        var admin=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()));
        admin.execute("create role beneficiary_migrator login password '"+PASSWORD+"'");
        admin.execute("create role beneficiary_runtime login password '"+PASSWORD+"'");
        admin.execute("alter database beneficiary owner to beneficiary_migrator");
        admin.execute("revoke all on database beneficiary from public");
        admin.execute("grant connect on database beneficiary to beneficiary_runtime");
        Flyway.configure().dataSource(PG.getJdbcUrl(),"beneficiary_migrator",PASSWORD).load().migrate();
        runtime=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),"beneficiary_runtime",PASSWORD));store=new BeneficiaryStore(runtime);
    }
    @AfterAll static void stop() { PG.stop(); }
    @Test void preservesIdentityAcrossReplayRenameAndDisable() {
        var actor=UUID.randomUUID().toString();var destination=UUID.randomUUID();
        var first=store.create(actor,"stable",destination,"MAD","Recipient");
        var id=UUID.fromString(first.path("id").asText());
        assertThat(store.create(actor,"stable",destination,"MAD","Recipient").path("id")).isEqualTo(first.path("id"));
        store.update(id,actor,"Updated","DISABLED",0);
        assertThat(store.replay(actor,"stable",destination,"Recipient").path("status").asText()).isEqualTo("DISABLED");
        assertThat(store.history(id,actor)).hasSize(2);
        assertThatThrownBy(()->store.replay(actor,"stable",UUID.randomUUID(),"Recipient")).isInstanceOf(ApiFailure.class).hasMessage("IDEMPOTENCY_CONFLICT");
        assertThatThrownBy(()->store.create(actor,"another",destination,"MAD","Recipient")).isInstanceOf(ApiFailure.class).hasMessage("BENEFICIARY_EXISTS");
    }
    @Test void wrongOwnersAndStaleVersionsCannotModifyOrReadEntries() {
        var actor=UUID.randomUUID().toString();var row=store.create(actor,"key",UUID.randomUUID(),"EUR","Private");
        var id=UUID.fromString(row.path("id").asText());
        assertThatThrownBy(()->store.own(id,"other")).isInstanceOf(ApiFailure.class).hasMessage("BENEFICIARY_NOT_FOUND");
        assertThatThrownBy(()->store.update(id,"other","Stolen","ACTIVE",0)).isInstanceOf(ApiFailure.class).hasMessage("BENEFICIARY_NOT_FOUND");
        store.update(id,actor,"Renamed","ACTIVE",0);
        assertThatThrownBy(()->store.update(id,actor,"Stale","DISABLED",0)).isInstanceOf(ApiFailure.class).hasMessage("BENEFICIARY_VERSION_CONFLICT");
        assertThat(store.list("other",null,"")).isEmpty();
    }
    @Test void restrictedRoleCannotForgeHistoryOrIdentityAndRollbackKeepsFactsAtomic() {
        var actor=UUID.randomUUID().toString();var row=store.create(actor,"key",UUID.randomUUID(),"USD","Atomic");
        var id=UUID.fromString(row.path("id").asText());
        for(var sql:List.of("delete from beneficiary_history","truncate beneficiary_history","update beneficiary set owner_subject='forged'","create table forbidden(id int)"))
            assertThatThrownBy(()->runtime.execute(sql)).isInstanceOf(DataAccessException.class);
        var tx=new TransactionTemplate(new DataSourceTransactionManager(runtime.getDataSource()));
        assertThatThrownBy(()->tx.execute(status->{store.update(id,actor,"Rollback","DISABLED",0);throw new IllegalStateException("Injected failure");})).hasMessage("Injected failure");
        assertThat(store.own(id,actor).path("version").asInt()).isZero();assertThat(store.history(id,actor)).hasSize(1);
    }
    @Test void paginatesOnlyOwnedRowsAndSearchesBeyondTheFirstPage() {
        var actor=UUID.randomUUID().toString();
        for(int n=0;n<55;n++)store.create(actor,"key-"+n,UUID.randomUUID(),"MAD",n==54?"Distinct recipient":"Recipient "+n);
        var first=store.list(actor,null,"");assertThat(first).hasSize(50);
        var second=store.list(actor,UUID.fromString(first.getLast().path("id").asText()),"");assertThat(second).hasSize(5);
        var ids=new HashSet<String>();first.forEach(r->ids.add(r.path("id").asText()));second.forEach(r->ids.add(r.path("id").asText()));assertThat(ids).hasSize(55);
        assertThat(store.list(actor,null,"DISTINCT")).hasSize(1);
    }
    @Test void concurrentCreationReplaysOneIdentityAndOneAuditFact()throws Exception{
        var actor=UUID.randomUUID().toString();
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            for(int n=0;n<8;n++){
                var destination=UUID.randomUUID();var key="concurrent-"+n;var start=new java.util.concurrent.CountDownLatch(1);
                var one=pool.submit(()->{start.await();return store.create(actor,key,destination,"MAD","Concurrent");});
                var two=pool.submit(()->{start.await();return store.create(actor,key,destination,"MAD","Concurrent");});start.countDown();
                var first=one.get(15,java.util.concurrent.TimeUnit.SECONDS);var second=two.get(15,java.util.concurrent.TimeUnit.SECONDS);
                assertThat(first.path("id")).isEqualTo(second.path("id"));assertThat(store.history(UUID.fromString(first.path("id").asText()),actor)).hasSize(1);
            }
        }
    }

}
