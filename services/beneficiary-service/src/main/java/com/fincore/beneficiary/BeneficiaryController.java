package com.fincore.beneficiary;

import com.fincore.support.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/beneficiaries")
public class BeneficiaryController {
    private final BeneficiaryStore store;
    private final Remote remote;
    private final String accounts;
    public BeneficiaryController(BeneficiaryStore store, Remote remote, @Value("${ACCOUNT_URL:http://localhost:8083}") String accounts) {
        this.store = store; this.remote = remote; this.accounts = accounts;
    }
    public record Create(String label, UUID destinationAccountId) {}
    public record Update(String label, String status, Long expectedVersion) {}
    @PostMapping public ResponseEntity<?> create(@RequestHeader("Idempotency-Key") String key, @RequestBody Create body, @AuthenticationPrincipal Jwt jwt) {
        var label = BeneficiaryStore.label(body.label());
        if (body.destinationAccountId() == null) throw new ApiFailure(400, "INVALID_DESTINATION");
        var row = store.replay(jwt.getSubject(), key, body.destinationAccountId(), label);
        if (row == null) {
            var account = remote.call("GET", accounts + "/internal/v1/accounts/" + body.destinationAccountId(), remote.serviceToken(), null);
            if (!account.path("status").asText().equals("ACTIVE")) throw new ApiFailure(422, "RECIPIENT_UNAVAILABLE");
            row = store.create(jwt.getSubject(), key, body.destinationAccountId(), account.path("currency").asText(), label);
        }
        return ResponseEntity.status(201).body(BeneficiaryStore.view(row));
    }
    @GetMapping public Map<String,Object> list(@RequestParam(required=false) UUID after, @RequestParam(defaultValue="") String search, @AuthenticationPrincipal Jwt jwt) {
        var rows = store.list(jwt.getSubject(), after, search);
        var result = new LinkedHashMap<String,Object>();
        result.put("items", rows.stream().map(BeneficiaryStore::view).toList());
        result.put("nextCursor", rows.size() == 50 ? rows.getLast().path("id").asText() : null);
        return result;
    }
    @GetMapping("/{id}") public Object get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) { return BeneficiaryStore.view(store.own(id, jwt.getSubject())); }
    @PutMapping("/{id}") public Object update(@PathVariable UUID id, @RequestBody Update body, @AuthenticationPrincipal Jwt jwt) {
        if (body.expectedVersion() == null || body.status() == null) throw new ApiFailure(400, "INVALID_BENEFICIARY_UPDATE");
        return BeneficiaryStore.view(store.update(id, jwt.getSubject(), BeneficiaryStore.label(body.label()), body.status(), body.expectedVersion()));
    }
    @GetMapping("/{id}/history") public Object history(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) { return Map.of("items", store.history(id, jwt.getSubject())); }
}
