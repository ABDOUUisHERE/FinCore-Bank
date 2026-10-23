package com.fincore.account;

import com.fincore.support.*;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountStore {
    private final JdbcTemplate jdbc;
    public AccountStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public record Account(UUID id,UUID customerId,String owner,String currency,String status,UUID ledgerId,long version,long guardRevision,String target,String previous,String guardError) {
        public Map<String,Object> publicView(){var view=new LinkedHashMap<String,Object>();view.put("accountId",id);view.put("customerId",customerId);view.put("currency",currency);view.put("status",status);view.put("version",version);view.put("pendingStatus",target);view.put("guardError",guardError);return view;}
        public Map<String,Object> internalView(){var view=publicView();view.put("ownerSubject",owner);view.put("ledgerId",ledgerId);return view;}
    }
    private static final RowMapper<Account> ROW=(rs,n)->new Account(rs.getObject("id",UUID.class),rs.getObject("customer_id",UUID.class),rs.getString("owner_subject"),rs.getString("currency"),rs.getString("status"),rs.getObject("ledger_id",UUID.class),rs.getLong("version"),rs.getLong("ledger_guard_revision"),rs.getString("guard_target"),rs.getString("guard_previous"),rs.getString("guard_error"));
    public Account get(UUID id){var rows=jdbc.query("select * from account where id=?",ROW,id);if(rows.isEmpty())throw new ApiFailure(404,"ACCOUNT_NOT_FOUND");return rows.getFirst();}
    public Account own(UUID id,String subject){var account=get(id);if(!account.owner().equals(subject))throw new ApiFailure(404,"ACCOUNT_NOT_FOUND");return account;}
    public List<Account> list(String subject,UUID after){return jdbc.query("select * from account where owner_subject=? and (?::uuid is null or id>?::uuid) order by id limit 50",ROW,subject,after,after);}
    @Transactional public Account create(String subject,UUID customer,String currency,String key){
        Identifiers.key(key);if(currency==null || !Set.of("MAD","EUR","USD").contains(currency))throw new ApiFailure(400,"INVALID_CURRENCY");
        var id=Identifiers.stable("account",subject,key);
        jdbc.update("insert into account(id,customer_id,owner_subject,key,currency,status) values(?,?,?,?,?,'PROVISIONING') on conflict(owner_subject,key) do nothing",id,customer,subject,key,currency);
        var account=own(id,subject);if(!account.currency().equals(currency) || !account.customerId().equals(customer))throw new ApiFailure(409,"IDEMPOTENCY_CONFLICT");return account;
    }
    @Transactional public void provisioned(Account before,UUID ledger){
        if(jdbc.update("update account set ledger_id=?,status='ACTIVE',version=version+1 where id=? and version=? and status='PROVISIONING'",ledger,before.id(),before.version())==1)event(before.id(),before.version()+1,"account.created");
    }
    @Transactional public Account guard(UUID id,String subject,String target,long version){
        if(target==null || !Set.of("ACTIVE","FROZEN","CLOSED").contains(target))throw new ApiFailure(400,"INVALID_GUARD");
        var account=own(id,subject);
        if(!Set.of("ACTIVE","FROZEN").contains(account.status()) || account.version()!=version)throw new ApiFailure(409,"ACCOUNT_VERSION_CONFLICT");
        if(jdbc.update("update account set guard_previous=status,guard_target=?,guard_error=null,status='GUARD_PENDING',version=version+1 where id=? and version=? and status in ('ACTIVE','FROZEN')",target,id,version)!=1)throw new ApiFailure(409,"ACCOUNT_VERSION_CONFLICT");
        event(id,version+1,"account.restriction.requested");return get(id);
    }
    @Transactional public void guarded(Account before,String actual,long revision,String error){
        if(jdbc.update("update account set status=?,ledger_guard_revision=?,guard_target=null,guard_previous=null,guard_error=?,version=version+1 where id=? and version=? and status='GUARD_PENDING'",actual,revision,error,before.id(),before.version())==1)event(before.id(),before.version()+1,error==null?"account.restriction.applied":"account.restriction.rejected");
    }
    public List<Account> pending(){return jdbc.query("select * from account where status in ('PROVISIONING','GUARD_PENDING') order by created_at limit 20",ROW);}
    private void event(UUID id,long version,String type){jdbc.update("insert into account_outbox(id,account_id,aggregate_version,event_type) values(?,?,?,?)",UUID.randomUUID(),id,version,type);}
}
