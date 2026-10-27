package com.fincore.ledger;

import java.util.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1/ledger/reports")
public class StatementController {
 private final StatementStore statements;private final LedgerStore ledger;
 public StatementController(StatementStore statements,LedgerStore ledger){this.statements=statements;this.ledger=ledger;}
 @GetMapping("/accounts/{id}/statement") public Object statement(@PathVariable UUID id,@RequestParam String month,@AuthenticationPrincipal Jwt jwt){issuer(jwt);var realm=jwt.getClaimAsMap("realm_access");if(realm==null || !(realm.get("roles") instanceof List<?> roles) || !roles.contains("CUSTOMER"))throw new AccessDeniedException("Signed customer required");return statements.statement(id,jwt.getSubject(),month);}
 @GetMapping("/reconciliation") public Object reconciliation(@AuthenticationPrincipal Jwt jwt){issuer(jwt);return ledger.reconcile();}
 private static void issuer(Jwt jwt){if(!"fincore-reporting".equals(jwt.getClaimAsString("azp")))throw new AccessDeniedException("Reporting issuer required");}
}
