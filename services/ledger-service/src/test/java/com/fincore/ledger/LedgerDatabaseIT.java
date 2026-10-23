package com.fincore.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

/** Actual PostgreSQL, restricted role, independent connections; never an in-memory substitute. */
class LedgerDatabaseIT {
    static PostgreSQLContainer<?> container;
    static String adminUrl, adminUser, adminPassword, url, database;
    static final String PASSWORD = UUID.randomUUID().toString();
    static final String RUNTIME = System.getenv().getOrDefault("LEDGER_TEST_RUNTIME_PASSWORD", PASSWORD);
    static final String MIGRATOR = System.getenv().getOrDefault("LEDGER_TEST_MIGRATION_PASSWORD", PASSWORD);
    static JdbcTemplate runtime, owner;
    static LedgerStore store;
    static final ObjectMapper JSON = new ObjectMapper();
    static final String ISSUER = "test-operator", ACTOR = "verified-actor";

    @BeforeAll static void database() throws Exception {
        adminUrl = System.getenv("LEDGER_TEST_ADMIN_URL");
        if (adminUrl == null) {
            container = new PostgreSQLContainer<>("postgres:17.11-alpine").withDatabaseName("bootstrap");
            container.start();
            adminUrl = container.getJdbcUrl(); adminUser = container.getUsername(); adminPassword = container.getPassword();
        } else {
            adminUser = System.getenv("LEDGER_TEST_ADMIN_USER"); adminPassword = System.getenv("LEDGER_TEST_ADMIN_PASSWORD");
        }
        database = "ledger_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(adminUrl, adminUser, adminPassword); var s = connection.createStatement()) {
            for (var role : Map.of("ledger_runtime", RUNTIME, "ledger_migrator", MIGRATOR).entrySet()) {
                try (var check = connection.prepareStatement("select 1 from pg_roles where rolname=?")) {
                    check.setString(1, role.getKey());
                    try (var rs = check.executeQuery()) { if (!rs.next()) s.execute("create role " + role.getKey() + " login password '" + role.getValue().replace("'", "''") + "'"); }
                }
            }
            s.execute("create database " + database + " owner ledger_migrator");
            s.execute("revoke all on database " + database + " from public");
            s.execute("grant connect on database " + database + " to ledger_runtime");
        }
        url = adminUrl.substring(0, adminUrl.lastIndexOf('/') + 1) + database;
        Flyway.configure().dataSource(url, "ledger_migrator", MIGRATOR).load().migrate();
        runtime = new JdbcTemplate(new DriverManagerDataSource(url, "ledger_runtime", RUNTIME));
        owner = new JdbcTemplate(new DriverManagerDataSource(url, "ledger_migrator", MIGRATOR));
        store = new LedgerStore(runtime, JSON);
    }
    @AfterAll static void cleanup() throws Exception {
        if (database != null && database.matches("ledger_test_[a-f0-9]{32}")) {
            try (var connection = DriverManager.getConnection(adminUrl, adminUser, adminPassword); var s = connection.createStatement()) { s.execute("drop database " + database + " with (force)"); }
        }
        if (container != null) container.stop();
    }
    static UUID account(String type, String currency, String actor) { return UUID.fromString(store.provision(UUID.randomUUID().toString(), currency, type, actor).get("id").asText()); }
    static UUID customer() { return account("CUSTOMER_LIABILITY", "MAD", ACTOR); }
    static String id() { return UUID.randomUUID().toString(); }
    static Map<String,Object> command(String kind, String ref, UUID debit, UUID credit, String amount) {
        return Map.of("kind",kind,"businessReference",ref,"currency","MAD","entries",List.of(Map.of("accountId",debit,"side","DEBIT","amount",amount),Map.of("accountId",credit,"side","CREDIT","amount",amount)));
    }
    static JsonNode post(String kind, UUID debit, UUID credit, String amount) { return store.post(ISSUER, ACTOR, id(), command(kind,id(),debit,credit,amount)); }
    static void fund(UUID customer, String amount) { assertThat(post("DEPOSIT",account("SETTLEMENT_ASSET","MAD",ACTOR),customer,amount).get("status").asText()).isEqualTo("POSTED"); }
    static String balance(UUID id) { return store.balance(id).get("booked").asText(); }
    static long count(String table) { return runtime.queryForObject("select count(*) from ledger." + table, Long.class); }
    static void cleanReconciliation() { assertThat(store.reconcile().get("balanceDiscrepancies")).isEmpty(); assertThat(store.reconcile().get("invalidJournals")).isEmpty(); }

    @Test void booksTransfersWithdrawalsMerchantPaymentsAndCompensation() {
        UUID a=customer(),b=customer(),merchant=account("MERCHANT_LIABILITY","MAD",ACTOR),settlement=account("SETTLEMENT_ASSET","MAD",ACTOR);
        assertThat(post("DEPOSIT",settlement,a,"100.00").get("status").asText()).isEqualTo("POSTED");
        var transfer=post("TRANSFER",a,b,"30");
        assertThat(post("MERCHANT_PAYMENT",a,merchant,"10").get("status").asText()).isEqualTo("POSTED");
        assertThat(post("WITHDRAWAL",a,settlement,"20").get("status").asText()).isEqualTo("POSTED");
        var jid=UUID.fromString(transfer.get("journalId").asText()); var original=store.journal(jid);
        var reverse=store.reverse(ISSUER,ACTOR,id(),jid,id());
        assertThat(reverse.get("status").asText()).isEqualTo("POSTED");
        assertThat(balance(a)).isEqualTo("70.00"); assertThat(balance(b)).isEqualTo("0.00");
        assertThat(balance(merchant)).isEqualTo("10.00"); assertThat(balance(settlement)).isEqualTo("80.00");
        assertThat(store.journal(jid)).isEqualTo(original);
        assertThat(store.reverse(ISSUER,ACTOR,id(),jid,id()).get("code").asText()).isEqualTo("ALREADY_REVERSED");
        cleanReconciliation();
    }
    @Test void rejectsMalformedUnbalancedMixedCurrencyAndUnauthorizedDebit() {
        UUID a=customer(),b=customer(); fund(a,"100"); long before=count("journal");
        for (String invalid:List.of("0","-1","1.001","1e2","1000000000000000000")) {
            assertThatThrownBy(()->post("TRANSFER",a,b,invalid)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        }
        var unbalanced=new HashMap<>(command("TRANSFER",id(),a,b,"1"));
        unbalanced.put("entries",List.of(Map.of("accountId",a,"side","DEBIT","amount","2"),Map.of("accountId",b,"side","CREDIT","amount","1")));
        assertThatThrownBy(()->store.post(ISSUER,ACTOR,id(),unbalanced)).hasMessageContaining("UNBALANCED_COMMAND");
        assertThatThrownBy(()->post("TRANSFER",a,account("CUSTOMER_LIABILITY","EUR",ACTOR),"1")).hasMessageContaining("CURRENCY_MISMATCH");
        assertThat(store.post(ISSUER,"forged-actor",id(),command("TRANSFER",id(),a,b,"1")).get("code").asText()).isEqualTo("DEBIT_NOT_AUTHORIZED");
        assertThat(count("journal")).isEqualTo(before); assertThat(balance(a)).isEqualTo("100.00"); cleanReconciliation();
    }
    @Test void idempotencySurvivesNewConnectionsAndTransportKeysAndNormalizesDecimals() {
        UUID a=customer(),b=customer(); fund(a,"100"); String key=id(),ref=id();
        var first=store.post(ISSUER,ACTOR,key,command("TRANSFER",ref,a,b,"10"));
        var restarted=new LedgerStore(new JdbcTemplate(new DriverManagerDataSource(url,"ledger_runtime",RUNTIME)),JSON);
        assertThat(restarted.post(ISSUER,ACTOR,key,command("TRANSFER",ref,a,b,"10.00"))).isEqualTo(first);
        assertThat(restarted.post(ISSUER,ACTOR,id(),command("TRANSFER",ref,a,b,"10.0"))).isEqualTo(first);
        assertThatThrownBy(()->store.post(ISSUER,ACTOR,key,command("TRANSFER",ref,a,b,"11"))).hasMessageContaining("IDEMPOTENCY_CONFLICT");
        assertThatThrownBy(()->store.post(ISSUER,ACTOR,id(),command("TRANSFER",ref,a,b,"11"))).hasMessageContaining("BUSINESS_REFERENCE_CONFLICT");
        assertThat(balance(a)).isEqualTo("90.00"); assertThat(balance(b)).isEqualTo("10.00"); cleanReconciliation();
    }
    @Test void definitiveRejectionRemainsRejectedAfterFunding() {
        UUID a=customer(),b=customer(); String key=id(),ref=id(); var cmd=command("TRANSFER",ref,a,b,"10");
        var first=store.post(ISSUER,ACTOR,key,cmd); assertThat(first.get("code").asText()).isEqualTo("INSUFFICIENT_FUNDS");
        fund(a,"20"); assertThat(store.post(ISSUER,ACTOR,key,cmd)).isEqualTo(first);
        assertThat(store.post(ISSUER,ACTOR,id(),cmd)).isEqualTo(first); assertThat(balance(a)).isEqualTo("20.00");
    }
    @Test void concurrentDebitsCannotOverspend() throws Exception {
        UUID a=customer(),b=customer(),c=customer(); fund(a,"100");
        var results=race(()->post("TRANSFER",a,b,"80"),()->post("TRANSFER",a,c,"80"));
        assertThat(results.stream().map(r->r.get("status").asText())).containsExactlyInAnyOrder("POSTED","REJECTED");
        assertThat(balance(a)).isEqualTo("20.00");
        assertThat(new java.math.BigDecimal(balance(b)).add(new java.math.BigDecimal(balance(c)))).isEqualByComparingTo("80"); cleanReconciliation();
    }
    @Test void concurrentDuplicateRequestsAndChangedKeysBookOnlyOnce() throws Exception {
        UUID a=customer(),b=customer(); fund(a,"100"); String ref=id(),key=id(); var cmd=command("TRANSFER",ref,a,b,"10");
        var results=race(()->store.post(ISSUER,ACTOR,key,cmd),()->store.post(ISSUER,ACTOR,key,cmd));
        assertThat(results.get(0)).isEqualTo(results.get(1));
        var aliases=race(()->store.post(ISSUER,ACTOR,id(),cmd),()->store.post(ISSUER,ACTOR,id(),cmd));
        assertThat(aliases).allMatch(results.getFirst()::equals); assertThat(balance(b)).isEqualTo("10.00");
    }
    @Test void opposingTransfersUseConsistentAccountLockOrder() throws Exception {
        UUID a=customer(),b=customer(); fund(a,"100"); fund(b,"100");
        assertThat(race(()->post("TRANSFER",a,b,"10"),()->post("TRANSFER",b,a,"20")).stream().map(r->r.get("status").asText())).containsOnly("POSTED");
        assertThat(balance(a)).isEqualTo("110.00"); assertThat(balance(b)).isEqualTo("90.00"); cleanReconciliation();
    }
    @Test void guardsAreVersionedAndSerializeWithPosting() throws Exception {
        UUID a=customer(),b=customer(); fund(a,"100");
        var results=race(()->store.guard(a,"FROZEN",0),()->post("TRANSFER",a,b,"80"));
        var transfer=results.get(1); var wasPosted=transfer.get("status").asText().equals("POSTED");
        assertThat(balance(a)).isEqualTo(wasPosted?"20.00":"100.00");
        assertThat(post("TRANSFER",a,b,"1").get("code").asText()).isEqualTo("ACCOUNT_RESTRICTED");
        assertThatThrownBy(()->store.guard(a,"ACTIVE",0)).hasMessageContaining("GUARD_CONFLICT");
        store.guard(a,"ACTIVE",1); assertThatThrownBy(()->store.guard(a,"CLOSED",2)).hasMessageContaining("ACCOUNT_NOT_EMPTY");
        cleanReconciliation();
    }
    @Test void reversalCannotOverdrawAndCannotBeForged() {
        UUID a=customer(),b=customer(),c=customer(); fund(a,"100");
        var transfer=post("TRANSFER",a,b,"100"); var jid=UUID.fromString(transfer.get("journalId").asText());
        post("TRANSFER",b,c,"100"); long before=count("journal");
        assertThat(store.reverse(ISSUER,ACTOR,id(),jid,id()).get("code").asText()).isEqualTo("INSUFFICIENT_FUNDS");
        var forged=new HashMap<>(command("REVERSAL",id(),b,a,"1")); forged.put("reversalOf",jid);
        assertThatThrownBy(()->store.post(ISSUER,ACTOR,id(),forged)).hasMessageContaining("INVALID_REVERSAL");
        assertThat(count("journal")).isEqualTo(before); cleanReconciliation();
    }
    @Test void simultaneousReversalsCompensateOnlyOnce() throws Exception {
        UUID a=customer(),b=customer(); fund(a,"100");
        UUID jid=UUID.fromString(post("TRANSFER",a,b,"40").get("journalId").asText());
        var results=race(()->store.reverse(ISSUER,ACTOR,id(),jid,id()),()->store.reverse(ISSUER,ACTOR,id(),jid,id()));
        assertThat(results.stream().map(r->r.get("code").asText())).containsExactlyInAnyOrder("POSTED","ALREADY_REVERSED");
        assertThat(balance(a)).isEqualTo("100.00"); assertThat(balance(b)).isEqualTo("0.00"); cleanReconciliation();
    }
    @Test void fundingHasSimulationLimitAndClosedAccountsNeverReopen() {
        UUID a=customer(),settlement=account("SETTLEMENT_ASSET","MAD",ACTOR);
        assertThat(post("DEPOSIT",settlement,a,"1000000.01").get("code").asText()).isEqualTo("FUNDING_LIMIT_EXCEEDED");
        assertThat(balance(a)).isEqualTo("0.00"); store.guard(a,"CLOSED",0);
        assertThatThrownBy(()->store.guard(a,"ACTIVE",1)).hasMessageContaining("GUARD_CONFLICT");
        assertThat(post("DEPOSIT",settlement,a,"1").get("code").asText()).isEqualTo("ACCOUNT_RESTRICTED");
    }
    @Test void runtimeCannotMutateTablesOrDisableInvariants() {
        for (String sql:List.of("update ledger.balance set booked=100", "delete from ledger.posting", "truncate ledger.journal", "alter table ledger.posting disable trigger all", "create table ledger.forged(id int)", "insert into ledger.account(id) values(gen_random_uuid())")) {
            assertThatThrownBy(()->runtime.execute(sql)).rootCause().isInstanceOfSatisfying(java.sql.SQLException.class,
                error -> assertThat(error.getSQLState()).isEqualTo("42501"));
        }
    }
    @Test void databaseRejectsEmptyUnbalancedAndLatePostingEvenForMigrationOwner() {
        UUID a=customer(),b=customer(); fund(a,"100"); var posted=post("TRANSFER",a,b,"10"); UUID jid=UUID.fromString(posted.get("journalId").asText());
        assertThatThrownBy(()->owner.update("insert into ledger.posting values(gen_random_uuid(),?,?,'MAD','DEBIT',1)",jid,a)).hasMessageContaining("JOURNAL_ALREADY_SEALED");
        assertThatThrownBy(()->owner.update("update ledger.journal set total=1 where id=?",jid)).hasMessageContaining("IMMUTABLE_FINANCIAL_RECORD");
        assertThatThrownBy(()->owner.execute("delete from ledger.posting")).hasMessageContaining("IMMUTABLE_FINANCIAL_RECORD");
        var tx=new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(owner.getDataSource())));
        assertThatThrownBy(()->tx.execute(status->{
            UUID op=UUID.randomUUID(),j=UUID.randomUUID();
            owner.update("insert into ledger.operation values(?,?,?,?,'TRANSFER',?,'POSTED',jsonb_build_object('operationId',?::text,'status','POSTED','journalId',?::text),now())",op,ISSUER,id(),ACTOR,"f".repeat(64),op,j);
            owner.update("insert into ledger.journal(id,operation_id,currency,total) values(?,?,'MAD',1)",j,op);
            return null;
        })).hasStackTraceContaining("UNBALANCED_JOURNAL");
        // Same creating transaction, real operation FK, but deliberately missing credit.
        assertThatThrownBy(()->tx.execute(status->{
            UUID op=UUID.randomUUID(),j=UUID.randomUUID();
            owner.update("insert into ledger.operation values(?,?,?,?,'TRANSFER',?,'POSTED',jsonb_build_object('operationId',?::text,'status','POSTED','journalId',?::text),now())",op,ISSUER,id(),ACTOR,"f".repeat(64),op,j);
            owner.update("insert into ledger.journal(id,operation_id,currency,total) values(?,?,'MAD',1)",j,op);
            owner.update("insert into ledger.posting values(gen_random_uuid(),?,?,'MAD','DEBIT',1)",j,a);
            owner.update("update ledger.journal set sealed=true where id=?",j); return null;
        })).hasStackTraceContaining("UNBALANCED_JOURNAL");
        cleanReconciliation();
    }
    @Test void rollbackRemovesJournalBalancesIdempotencyOutcomeAndOutboxTogether() {
        UUID a=customer(),b=customer(); fund(a,"100"); String key=id(),ref=id();
        long journals=count("journal"),operations=count("operation"),events=count("outbox_event"),keys=count("idempotency");
        var tx=new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(runtime.getDataSource())));
        tx.execute(status->{store.post(ISSUER,ACTOR,key,command("TRANSFER",ref,a,b,"10")); status.setRollbackOnly(); return null;});
        assertThat(count("journal")).isEqualTo(journals); assertThat(count("operation")).isEqualTo(operations);
        assertThat(count("outbox_event")).isEqualTo(events); assertThat(count("idempotency")).isEqualTo(keys);
        assertThat(balance(a)).isEqualTo("100.00"); assertThat(balance(b)).isEqualTo("0.00");
        assertThat(store.post(ISSUER,ACTOR,key,command("TRANSFER",ref,a,b,"10")).get("status").asText()).isEqualTo("POSTED"); cleanReconciliation();
    }
    @Test void reconciliationDetectsAggregateCorruptionWithoutChangingFinancialHistory() {
        UUID a=customer(); fund(a,"100");
        try {
            owner.update("update ledger.balance set booked=booked+1 where account_id=?",a);
            assertThat(store.reconcile().get("balanceDiscrepancies").toString()).contains(a.toString());
        } finally { owner.update("update ledger.balance set booked=booked-1 where account_id=?",a); }
        cleanReconciliation();
    }
    @Test void databaseFailureAfterBalanceChangesRollsBackAllFinancialEffects() {
        UUID a=customer(),b=customer(); fund(a,"100"); String key=id(),ref=id();
        long journals=count("journal"),operations=count("operation"),events=count("outbox_event"),keys=count("idempotency");
        owner.execute("create function ledger.test_outbox_failure() returns trigger language plpgsql as $$ begin raise exception 'SIMULATED_OUTBOX_FAILURE'; end $$");
        owner.execute("create trigger test_failure before insert on ledger.outbox_event for each row execute function ledger.test_outbox_failure()");
        try {
            assertThatThrownBy(()->store.post(ISSUER,ACTOR,key,command("TRANSFER",ref,a,b,"10"))).hasMessageContaining("SIMULATED_OUTBOX_FAILURE");
            assertThat(count("journal")).isEqualTo(journals); assertThat(count("operation")).isEqualTo(operations);
            assertThat(count("outbox_event")).isEqualTo(events); assertThat(count("idempotency")).isEqualTo(keys);
            assertThat(balance(a)).isEqualTo("100.00"); assertThat(balance(b)).isEqualTo("0.00");
        } finally {
            owner.execute("drop trigger test_failure on ledger.outbox_event"); owner.execute("drop function ledger.test_outbox_failure()");
        }
        assertThat(store.post(ISSUER,ACTOR,key,command("TRANSFER",ref,a,b,"10")).get("status").asText()).isEqualTo("POSTED"); cleanReconciliation();
    }
    static List<JsonNode> race(Callable<JsonNode> first, Callable<JsonNode> second) throws Exception {
        var gate=new CyclicBarrier(2);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var a=executor.submit(()->{gate.await(10,TimeUnit.SECONDS);return first.call();});
            var b=executor.submit(()->{gate.await(10,TimeUnit.SECONDS);return second.call();});
            return List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));
        }
    }
    JsonNode authorize(UUID id,String actor,String key,UUID debit,UUID credit,String amount){
        String value=runtime.queryForObject("select ledger.authorize_instruction(?,?,?,?,?,?,?,?,?,?::jsonb,null)::text",String.class,id,"fincore-payment",actor,key,"TRANSFER",debit,credit,amount,"MAD","{\"source\":\"synthetic-test\"}");
        try{return JSON.readTree(value);}catch(java.io.IOException error){throw new IllegalStateException(error);}
    }
    JsonNode execute(UUID id,java.time.Instant expires){
        String value=runtime.queryForObject("select ledger.execute_instruction(?,?,?::timestamptz)::text",String.class,"fincore-payment",id,expires.toString());
        try{return JSON.readTree(value);}catch(java.io.IOException error){throw new IllegalStateException(error);}
    }
    @Test void durableAuthorizationBindsActorAndWorkerExecutesExactlyOnce()throws Exception{
        UUID a=customer(),b=customer(),instruction=UUID.randomUUID();fund(a,"100");String key=id();
        assertThatThrownBy(()->authorize(instruction,"forged-owner",key,a,b,"10")).hasMessageContaining("INSTRUCTION_NOT_AUTHORIZED");
        var receipt=authorize(instruction,ACTOR,key,a,b,"10");
        assertThat(authorize(instruction,ACTOR,key,a,b,"10.00")).isEqualTo(receipt);
        assertThat(balance(a)).isEqualTo("100.00");
        assertThatThrownBy(()->authorize(instruction,ACTOR,key,a,b,"11")).hasMessageContaining("IDEMPOTENCY_CONFLICT");
        var results=race(()->execute(instruction,java.time.Instant.now().plusSeconds(30)),()->execute(instruction,java.time.Instant.now().plusSeconds(30)));
        assertThat(results.getFirst()).isEqualTo(results.getLast());assertThat(balance(a)).isEqualTo("90.00");assertThat(balance(b)).isEqualTo("10.00");
        assertThat(runtime.queryForObject("select actor from ledger.operation where issuer='fincore-payment' and business_reference=?",String.class,instruction.toString())).isEqualTo(ACTOR);
        assertThatThrownBy(()->runtime.queryForObject("select ledger.execute_instruction('another-service',?,now()+interval '30 seconds')",String.class,instruction)).hasMessageContaining("INSTRUCTION_NOT_FOUND");
        assertThatThrownBy(()->owner.execute("delete from ledger.instruction")).hasMessageContaining("IMMUTABLE_FINANCIAL_RECORD");cleanReconciliation();
    }
    @Test void expiredAssessmentDoesNotConsumeIdentityAndFreshAssessmentCanResume(){
        UUID a=customer(),b=customer(),instruction=UUID.randomUUID();fund(a,"100");authorize(instruction,ACTOR,id(),a,b,"10");
        long journals=count("journal");
        assertThatThrownBy(()->execute(instruction,java.time.Instant.now().minusSeconds(1))).hasMessageContaining("ASSESSMENT_EXPIRED");
        assertThat(count("journal")).isEqualTo(journals);assertThat(balance(a)).isEqualTo("100.00");
        assertThat(execute(instruction,java.time.Instant.now().plusSeconds(30)).path("status").asText()).isEqualTo("POSTED");cleanReconciliation();
    }
    @Test void assessmentExpiryDuringBalanceContentionRollsBackAtJournalInsertion()throws Exception{
        UUID a=customer(),b=customer(),instruction=UUID.randomUUID();fund(a,"100");authorize(instruction,ACTOR,id(),a,b,"10");
        try(var blocker=DriverManager.getConnection(url,"ledger_migrator",MIGRATOR);var executor=Executors.newVirtualThreadPerTaskExecutor()){
            blocker.setAutoCommit(false);try(var lock=blocker.prepareStatement("select 1 from ledger.account where id=? for update")){lock.setObject(1,a);lock.executeQuery().close();}
            var future=executor.submit(()->execute(instruction,java.time.Instant.now().plusMillis(150)));
            Thread.sleep(400);blocker.commit();assertThatThrownBy(()->future.get(10,TimeUnit.SECONDS)).hasStackTraceContaining("ASSESSMENT_EXPIRED");
        }
        assertThat(balance(a)).isEqualTo("100.00");assertThat(balance(b)).isEqualTo("0.00");
        assertThat(execute(instruction,java.time.Instant.now().plusSeconds(30)).path("status").asText()).isEqualTo("POSTED");cleanReconciliation();
    }
}
