package com.fincore.audit;

import java.util.*;
import com.fincore.support.ServiceSecurity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin")
public class AuditController {
    private final AuditStore store;
    public AuditController(AuditStore store) { this.store=store; }
    private static boolean admin(Jwt jwt) { return ServiceSecurity.authorities(jwt,"audit-service").stream().anyMatch(a->a.getAuthority().equals("ROLE_ADMIN")); }
    @GetMapping("/operations/me") public Object identity(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("subject",jwt.getSubject(),"scope",admin(jwt)?"ALL_INCIDENTS":"OWN_INCIDENTS");
    }
    @GetMapping("/audit/records") public Object facts(@RequestParam(required=false)Long before,@RequestParam(required=false)UUID aggregateId,@RequestParam(required=false)String eventType) { return store.facts(before,aggregateId,eventType); }
    @GetMapping("/audit/quarantine") public Object quarantine(@RequestParam(required=false)Long before) { return store.quarantine(before); }
    @GetMapping("/audit/summary") public Object summary() { return store.summary(); }
    @GetMapping("/incidents") public Object incidents(@RequestParam(required=false)UUID after,@AuthenticationPrincipal Jwt jwt) { return store.incidents(after,jwt.getSubject(),admin(jwt)); }
    @GetMapping("/incidents/{id}") public Object incident(@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt) { return store.incident(id,jwt.getSubject(),admin(jwt)); }
    @GetMapping("/incidents/{id}/history") public Object history(@PathVariable UUID id,@RequestParam(required=false)Long before,@AuthenticationPrincipal Jwt jwt) { return store.history(id,before,jwt.getSubject(),admin(jwt)); }
    @PostMapping("/incidents") public Object create(@RequestHeader("Idempotency-Key")String key,@RequestBody AuditStore.Create body,@AuthenticationPrincipal Jwt jwt,HttpServletRequest request) {
        return store.create(jwt.getSubject(),admin(jwt),key,Objects.toString(request.getAttribute("correlationId")),body);
    }
    @PutMapping("/incidents/{id}/status") public Object change(@PathVariable UUID id,@RequestHeader("Idempotency-Key")String key,@RequestBody AuditStore.Change body,@AuthenticationPrincipal Jwt jwt,HttpServletRequest request) {
        return store.change(id,jwt.getSubject(),admin(jwt),key,Objects.toString(request.getAttribute("correlationId")),body);
    }
}
