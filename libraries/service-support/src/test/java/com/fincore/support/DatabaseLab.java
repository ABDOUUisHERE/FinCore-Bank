package com.fincore.support;

import java.sql.DriverManager;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/** Infrastructure fixture only: three separate actual PostgreSQL databases per test class. */
public final class DatabaseLab implements AutoCloseable {
    private PostgreSQLContainer<?> container;
    private final String adminUrl,adminUser,adminPassword;
    private final Map<String,String> databases=new HashMap<>(),passwords=new HashMap<>();
    public DatabaseLab(){
        try{
            String nativeUrl=System.getenv("FINCORE_TEST_ADMIN_URL");
            if(nativeUrl==null){container=new PostgreSQLContainer<>("postgres:17.11-alpine").withDatabaseName("bootstrap");container.start();adminUrl=container.getJdbcUrl();adminUser=container.getUsername();adminPassword=container.getPassword();}
            else{adminUrl=nativeUrl;adminUser=System.getenv("FINCORE_TEST_ADMIN_USER");adminPassword=System.getenv("FINCORE_TEST_ADMIN_PASSWORD");}
            try(var connection=DriverManager.getConnection(adminUrl,adminUser,adminPassword);var sql=connection.createStatement()){
                for(String service:List.of("ledger","account","payment")){
                    for(String role:List.of("runtime","migrator")){
                        var secret=System.getenv().getOrDefault("FINCORE_TEST_"+service.toUpperCase()+"_"+role.toUpperCase()+"_PASSWORD",UUID.randomUUID().toString());
                        passwords.put(service+"_"+role,secret);
                        try(var query=connection.prepareStatement("select 1 from pg_roles where rolname=?")){
                            query.setString(1,service+"_"+role);try(var rs=query.executeQuery()){if(!rs.next())sql.execute("create role "+service+"_"+role+" login password '"+secret.replace("'","''")+"'");}
                        }
                    }
                    var database=service+"_it_"+UUID.randomUUID().toString().replace("-","");databases.put(service,database);
                    sql.execute("create database "+database+" owner "+service+"_migrator");sql.execute("revoke all on database "+database+" from public");sql.execute("grant connect on database "+database+" to "+service+"_runtime");
                    Flyway.configure().dataSource(url(service),service+"_migrator",password(service,"migrator")).locations("filesystem:../"+service+"-service/src/main/resources/db/migration").load().migrate();
                }
            }
        }catch(Exception error){throw new IllegalStateException("Real PostgreSQL business test lab failed",error);}
    }
    public String url(String service){return adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+databases.get(service);}
    public String password(String service,String role){return passwords.get(service+"_"+role);}
    public JdbcTemplate runtime(String service){return template(service,"runtime");}
    public JdbcTemplate owner(String service){return template(service,"migrator");}
    private JdbcTemplate template(String service,String role){var jdbc=new JdbcTemplate(new DriverManagerDataSource(url(service),service+"_"+role,password(service,role)));jdbc.setQueryTimeout(15);return jdbc;}
    @Override public void close(){
        try(var connection=DriverManager.getConnection(adminUrl,adminUser,adminPassword);var sql=connection.createStatement()){
            for(var database:databases.values()){if(!database.matches("(account|payment|ledger)_it_[0-9a-f]{32}"))throw new IllegalStateException("Unsafe fixture target");sql.execute("drop database "+database+" with (force)");}
        }catch(java.sql.SQLException error){throw new IllegalStateException("Test database cleanup failed",error);}finally{if(container!=null)container.stop();}
    }
}
