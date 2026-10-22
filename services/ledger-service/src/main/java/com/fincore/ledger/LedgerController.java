package com.fincore.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fincore.ledger.domain.Money;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1/ledger")
public class LedgerController {
    private final LedgerStore store;
    public LedgerController(LedgerStore store) { this.store = store; }
    public record Account(String externalReference, String currency, String type, String ownerSubject) {}
    public record Guard(String status, Long expectedRevision) {}
    public record Entry(UUID accountId, String side, String amount) {}
    public record Command(String kind, String businessReference, String currency, List<Entry> entries) {}
    public record Reversal(String businessReference) {}
    @PostMapping("/accounts") public JsonNode provision(@RequestBody Account body, @AuthenticationPrincipal Jwt jwt) {
        return store.provision(body.externalReference(), body.currency(), body.type(), body.ownerSubject() == null ? jwt.getSubject() : body.ownerSubject());
    }
    @PutMapping("/accounts/{id}/guard") public JsonNode guard(@PathVariable UUID id, @RequestBody Guard body) {
        if (body.expectedRevision() == null) throw new IllegalArgumentException("Expected guard revision required");
        return store.guard(id, body.status(), body.expectedRevision());
    }
    @PostMapping("/operations") public ResponseEntity<JsonNode> post(@RequestHeader("Idempotency-Key") String key, @RequestBody Command body,
                                                                   @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        if (body.kind() == null || !Set.of("DEPOSIT", "TRANSFER", "WITHDRAWAL", "MERCHANT_PAYMENT").contains(body.kind())) throw new IllegalArgumentException("Invalid operation kind");
        if (body.kind().equals("DEPOSIT")) require(auth, "ledger.fund");
        if (body.entries() == null || body.entries().size() != 2) throw new IllegalArgumentException("Two postings required");
        var normalized = body.entries().stream().map(e -> new Entry(e.accountId(), e.side(), Money.parse(e.amount(), body.currency()).decimal())).toList();
        return outcome(store.post(jwt.getClaimAsString("azp"), jwt.getSubject(), key, new Command(body.kind(), body.businessReference(), body.currency(), normalized)));
    }
    @PostMapping("/journals/{id}/reversal") public ResponseEntity<JsonNode> reverse(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                                                                  @RequestBody Reversal body, @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        require(auth, "ledger.reverse");
        if (body.businessReference() == null || body.businessReference().isBlank()) throw new IllegalArgumentException("Business reference required");
        return outcome(store.reverse(jwt.getClaimAsString("azp"), jwt.getSubject(), key, id, body.businessReference()));
    }
    @GetMapping("/accounts/{id}/balance") public JsonNode balance(@PathVariable UUID id) { return store.balance(id); }
    @GetMapping("/journals/{id}") public JsonNode journal(@PathVariable UUID id) { return store.journal(id); }
    @GetMapping("/operations/{reference}") public JsonNode operation(@PathVariable String reference, @AuthenticationPrincipal Jwt jwt) {
        return store.operation(jwt.getClaimAsString("azp"), jwt.getSubject(), reference);
    }
    @GetMapping("/reconciliation") public JsonNode reconcile() { return store.reconcile(); }
    private static void require(Authentication auth, String role) {
        if (auth.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals(role))) throw new AccessDeniedException("Missing technical role");
    }
    private static ResponseEntity<JsonNode> outcome(JsonNode result) {
        return ResponseEntity.status(result.path("status").asText().equals("POSTED") ? 200 : 422).body(result);
    }
}
