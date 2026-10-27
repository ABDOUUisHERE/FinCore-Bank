package com.fincore.payment;

import com.fincore.support.*;
import java.net.URI;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
public class PaymentController {
    private final PaymentCoordinator coordinator;private final PaymentStore store;
    public PaymentController(PaymentCoordinator coordinator,PaymentStore store){this.coordinator=coordinator;this.store=store;}
    public record Version(Long expectedVersion) {}
    public record Review(Long expectedVersion,Boolean approve,String reason) {}
    @PostMapping("/api/v1/transfers") public ResponseEntity<?> transfer(@RequestHeader("Idempotency-Key")String key,@RequestBody PaymentCoordinator.Transfer body,@AuthenticationPrincipal Jwt jwt){return accepted(coordinator.transfer(jwt.getSubject(),jwt.getTokenValue(),key,body));}
    @GetMapping("/api/v1/transfers/{id}") public Object get(@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt){return PaymentStore.view(store.own(id,jwt.getSubject()));}
    @GetMapping("/api/v1/transfers") public Map<String,Object> list(@RequestParam(required=false)UUID after,@RequestParam(defaultValue="")String status,@AuthenticationPrincipal Jwt jwt){var rows=store.list(jwt.getSubject(),after,status);var result=new LinkedHashMap<String,Object>();result.put("items",rows.stream().map(PaymentStore::view).toList());result.put("nextCursor",rows.size()==50?rows.getLast().path("id").asText():null);return result;}
    @GetMapping("/api/v1/transfers/{id}/history") public Object history(@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt){store.own(id,jwt.getSubject());return Map.of("items",store.history(id));}
    @PostMapping("/api/v1/transfers/{id}/cancel") public Object cancel(@PathVariable UUID id,@RequestBody Version body,@AuthenticationPrincipal Jwt jwt){if(body.expectedVersion()==null)throw new ApiFailure(400,"EXPECTED_VERSION_REQUIRED");return PaymentStore.view(store.cancel(id,jwt.getSubject(),body.expectedVersion()));}
    @PostMapping("/internal/v1/payments/funding") public ResponseEntity<?> funding(@RequestHeader("Idempotency-Key")String key,@RequestBody PaymentCoordinator.Funding body,@AuthenticationPrincipal Jwt jwt){return accepted(coordinator.funding(jwt.getSubject(),jwt.getTokenValue(),key,body));}
    @PostMapping("/internal/v1/payments/{id}/reversal") public ResponseEntity<?> reversal(@PathVariable UUID id,@RequestHeader("Idempotency-Key")String key,@RequestBody Map<String,Object> body,@AuthenticationPrincipal Jwt jwt){if(!body.isEmpty())throw new ApiFailure(400,"INVALID_REQUEST");return accepted(coordinator.reversal(id,jwt.getSubject(),jwt.getTokenValue(),key));}
    @PostMapping("/internal/v1/payments/{id}/decision") public Object review(@PathVariable UUID id,@RequestBody Review body,@AuthenticationPrincipal Jwt jwt){if(body.expectedVersion()==null || body.approve()==null)throw new ApiFailure(400,"INVALID_REVIEW");coordinator.reviewFraud(id,jwt.getTokenValue(),body);return PaymentStore.view(store.get(id));}
    @GetMapping("/internal/v1/payments/{id}/risk-context") public Object riskContext(@PathVariable UUID id){return store.riskContext(id);}
    @GetMapping("/internal/v1/payments/{id}") public Object inspect(@PathVariable UUID id){return PaymentStore.view(store.get(id));}
    private static ResponseEntity<?> accepted(com.fasterxml.jackson.databind.JsonNode payment){return ResponseEntity.accepted().location(URI.create("/api/v1/transfers/"+payment.path("id").asText())).header("Retry-After","1").body(payment.path("initial_response"));}
}
