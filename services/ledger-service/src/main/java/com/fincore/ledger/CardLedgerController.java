package com.fincore.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fincore.support.*;
import java.util.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1/ledger")
public class CardLedgerController {
 private final CardLedgerStore store;private final LedgerRiskVerifier risk;
 public CardLedgerController(CardLedgerStore store,LedgerRiskVerifier risk){this.store=store;this.risk=risk;}
 public record Create(UUID id,String key,UUID ledgerAccountId,String transactionLimit,String dailyLimit){}
 public record Configure(String key,Long expectedVersion,String status,String transactionLimit,String dailyLimit){}
 public record Prepare(UUID id,UUID cardId,String key,UUID merchantId,String amount,String currency){}
 public record Reserve(UUID assessmentId){}
 public record Settle(String key,String action){}
 @GetMapping("/cards") public Object cards(@RequestParam(required=false)UUID after,@AuthenticationPrincipal Jwt jwt){issuer(jwt);return store.scan("cards",after);}
 @GetMapping("/card-controls") public Object controls(@RequestParam(required=false)UUID after,@AuthenticationPrincipal Jwt jwt){issuer(jwt);return store.controls(after);}
 @GetMapping("/cards/{id}") public Object card(@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt){issuer(jwt);return store.card(id);}
 @GetMapping("/card-merchants") public Object merchants(@AuthenticationPrincipal Jwt jwt){issuer(jwt);return Map.of("items",store.merchants());}
 @GetMapping("/card-authorizations") public Object authorizations(@RequestParam(required=false)UUID after,@AuthenticationPrincipal Jwt jwt){issuer(jwt);return store.scan("authorizations",after);}
 @GetMapping("/card-authorizations/{id}") public JsonNode authorization(@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt){issuer(jwt);return store.authorization(id);}
 @PostMapping("/cards") public Object create(@RequestBody Create body,@AuthenticationPrincipal Jwt jwt){
  owner(jwt);Identifiers.key(body.key());if(!Identifiers.stable("card",jwt.getSubject(),body.key()).equals(body.id()) || body.ledgerAccountId()==null)throw new ApiFailure(400,"INVALID_CARD_IDENTITY");
  return store.create(body.id(),jwt.getSubject(),body.key(),body.ledgerAccountId(),money(body.transactionLimit()),money(body.dailyLimit()));
 }
 @PutMapping("/cards/{id}") public Object configure(@PathVariable UUID id,@RequestBody Configure body,@AuthenticationPrincipal Jwt jwt){owner(jwt);Identifiers.key(body.key());if(body.expectedVersion()==null)throw new ApiFailure(400,"EXPECTED_VERSION_REQUIRED");return store.configure(id,jwt.getSubject(),body.key(),body.expectedVersion(),body.status(),money(body.transactionLimit()),money(body.dailyLimit()));}
 @PostMapping("/card-authorizations") public Object prepare(@RequestBody Prepare body,@AuthenticationPrincipal Jwt jwt){owner(jwt);Identifiers.key(body.key());if(!Identifiers.stable("card-authorization",jwt.getSubject(),body.key()).equals(body.id()) || body.cardId()==null || body.merchantId()==null || body.currency()==null || !Set.of("MAD","EUR","USD").contains(body.currency()))throw new ApiFailure(400,"INVALID_CARD_PAYMENT");return store.prepare(body.id(),body.cardId(),jwt.getSubject(),body.key(),body.merchantId(),money(body.amount()),body.currency());}
 @PostMapping("/card-authorizations/{id}/reserve") public Object reserve(@PathVariable UUID id,@RequestBody Reserve body,@AuthenticationPrincipal Jwt jwt){
  issuer(jwt);var receipt=store.authorization(id);if(!receipt.path("status").asText().equals("AUTHORIZING"))return receipt;
  var expires=risk.verify(body.assessmentId(),id,receipt.path("fingerprint").asText());return store.reserve(id,body.assessmentId(),expires);
 }
 @PostMapping("/card-authorizations/{id}/settle") public Object settle(@PathVariable UUID id,@RequestBody Settle body,@AuthenticationPrincipal Jwt jwt){owner(jwt);Identifiers.key(body.key());return store.settle(id,jwt.getSubject(),body.key(),body.action());}
 static String money(String value){if(value==null || !value.matches("(0|[1-9][0-9]{0,17})(\\.[0-9]{1,2})?") || new java.math.BigDecimal(value).signum()<=0)throw new ApiFailure(400,"INVALID_MONEY");return new java.math.BigDecimal(value).setScale(2).toPlainString();}
 private static void issuer(Jwt jwt){if(!"fincore-card".equals(jwt.getClaimAsString("azp")))throw new AccessDeniedException("Card issuer required");}
 private static void owner(Jwt jwt){issuer(jwt);var realm=jwt.getClaimAsMap("realm_access");if(realm==null || !(realm.get("roles") instanceof List<?> roles) || !roles.contains("CUSTOMER"))throw new AccessDeniedException("Signed customer required");}
}
