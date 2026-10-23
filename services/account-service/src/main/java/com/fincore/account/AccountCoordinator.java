package com.fincore.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fincore.support.*;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class AccountCoordinator {
    private final AccountStore store;private final Remote remote;private final String ledger;
    @Value("${ACCOUNT_RECOVERY_ENABLED:true}") private boolean recoveryEnabled=true;
    public AccountCoordinator(AccountStore store,Remote remote,@Value("${LEDGER_URL:http://localhost:8082}")String ledger){this.store=store;this.remote=remote;this.ledger=ledger+"/internal/v1/ledger";}
    public JsonNode ledger(String method,String path,Object body){return remote.call(method,ledger+path,remote.serviceToken(),body);}
    public void sync(AccountStore.Account account){
        if(account.status().equals("PROVISIONING")){
            var result=ledger("POST","/accounts",Map.of("externalReference","account:"+account.id(),"currency",account.currency(),"type","CUSTOMER_LIABILITY","ownerSubject",account.owner()));
            store.provisioned(account,UUID.fromString(result.path("id").asText()));
        }else if(account.status().equals("GUARD_PENDING")){
            var current=ledger("GET","/accounts/"+account.ledgerId(),null);
            long revision=current.path("guard_revision").asLong();String status=current.path("status").asText();
            if(revision==account.guardRevision() && status.equals(account.previous())){
                try{
                    current=ledger("PUT","/accounts/"+account.ledgerId()+"/guard",Map.of("status",account.target(),"expectedRevision",revision));
                    store.guarded(account,current.path("status").asText(),current.path("guard_revision").asLong(),null);
                }catch(ApiFailure failure){if(failure.status==409 && failure.code.equals("ACCOUNT_NOT_EMPTY"))store.guarded(account,status,revision,failure.code);else throw failure;}
            }else if(revision==account.guardRevision()+1 && status.equals(account.target()))store.guarded(account,status,revision,null);
            else store.guarded(account,status,revision,"GUARD_CONFLICT");
        }
    }
    @Scheduled(fixedDelayString="${ACCOUNT_RECOVERY_DELAY_MS:3000}") public void recover(){
        if(!recoveryEnabled)return;
        try{for(var account:store.pending())try{sync(account);}catch(ApiFailure ignored){/* Durable intent remains pending; next bounded cycle retries. */}}
        catch(org.springframework.dao.DataAccessException ignored){/* Readiness exposes storage failure; do not invent success. */}
    }
}
