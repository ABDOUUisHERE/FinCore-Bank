package com.fincore.account;

import com.fincore.support.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
public class AccountController {
    private final AccountStore store;private final AccountCoordinator coordinator;private final Remote remote;private final String customer;
    public AccountController(AccountStore store,AccountCoordinator coordinator,Remote remote,@Value("${CUSTOMER_URL:http://localhost:8081}")String customer){this.store=store;this.coordinator=coordinator;this.remote=remote;this.customer=customer;}
    public record Create(String currency) {}
    public record Guard(String status,Long expectedVersion) {}
    @PostMapping("/api/v1/accounts") public ResponseEntity<?> create(@RequestHeader("Idempotency-Key")String key,@RequestBody Create body,@AuthenticationPrincipal Jwt jwt){
        var profile=remote.call("GET",customer+"/api/v1/customers/me/profile",jwt.getTokenValue(),null);
        var account=store.create(jwt.getSubject(),UUID.fromString(profile.path("customerId").asText()),body.currency(),key);
        try{coordinator.sync(account);}catch(ApiFailure ignored){/* A committed provisioning intent remains retryable. */}
        account=store.own(account.id(),jwt.getSubject());return ResponseEntity.status(account.status().equals("PROVISIONING")?202:201).body(account.publicView());
    }
    @GetMapping("/api/v1/accounts") public Map<String,Object> list(@RequestParam(required=false)UUID after,@AuthenticationPrincipal Jwt jwt){
        var accounts=store.list(jwt.getSubject(),after);var result=new LinkedHashMap<String,Object>();result.put("items",accounts.stream().map(AccountStore.Account::publicView).toList());result.put("nextCursor",accounts.size()==50?accounts.getLast().id():null);return result;
    }
    @GetMapping("/api/v1/accounts/{id}") public Object account(@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt){return store.own(id,jwt.getSubject()).publicView();}
    @GetMapping("/api/v1/accounts/{id}/balance") public Object balance(@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt){var account=ready(id,jwt.getSubject());return coordinator.ledger("GET","/accounts/"+account.ledgerId()+"/balance",null);}
    @GetMapping("/api/v1/accounts/{id}/transactions") public Object history(@PathVariable UUID id,@RequestParam Map<String,String> filters,@AuthenticationPrincipal Jwt jwt){var account=ready(id,jwt.getSubject());return coordinator.ledger("GET","/accounts/"+account.ledgerId()+"/transactions"+query(filters,Set.of("before","from","to","minimum","maximum","side","kind","search","journal")),null);}
    @GetMapping("/api/v1/accounts/{id}/activity") public Object activity(@PathVariable UUID id,@RequestParam Map<String,String> filters,@AuthenticationPrincipal Jwt jwt){var account=ready(id,jwt.getSubject());return coordinator.ledger("GET","/accounts/"+account.ledgerId()+"/activity"+query(filters,Set.of("month")),null);}
    private static String query(Map<String,String> values,Set<String> allowed){return values.entrySet().stream().filter(e->allowed.contains(e.getKey())).map(e->e.getKey()+"="+java.net.URLEncoder.encode(e.getValue(),java.nio.charset.StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&","?",""));}
    @PutMapping("/api/v1/accounts/{id}/guard") public ResponseEntity<?> guard(@PathVariable UUID id,@RequestBody Guard body,@AuthenticationPrincipal Jwt jwt){
        if(body.expectedVersion()==null)throw new ApiFailure(400,"EXPECTED_VERSION_REQUIRED");var account=store.guard(id,jwt.getSubject(),body.status(),body.expectedVersion());
        try{coordinator.sync(account);}catch(ApiFailure ignored){}
        account=store.own(id,jwt.getSubject());if(account.guardError()!=null)throw new ApiFailure(409,account.guardError());
        return ResponseEntity.status(account.status().equals("GUARD_PENDING")?202:200).body(account.publicView());
    }
    @GetMapping("/internal/v1/accounts/{id}") public Object internal(@PathVariable UUID id){return store.get(id).internalView();}
    private AccountStore.Account ready(UUID id,String subject){var account=store.own(id,subject);if(account.ledgerId()==null)throw new ApiFailure(409,"ACCOUNT_NOT_READY");return account;}
}
