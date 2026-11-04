package com.fincore.ledger;

import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
public class AccountStatusController {
    private final LedgerStore store;
    public AccountStatusController(LedgerStore store) { this.store = store; }
    @GetMapping("/internal/v1/ledger/accounts/{id}/operational-status")
    public Object status(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        if (!"fincore-account".equals(jwt.getClaimAsString("azp"))) throw new AccessDeniedException("Account issuer required");
        return store.operationalStatus(id);
    }
}
