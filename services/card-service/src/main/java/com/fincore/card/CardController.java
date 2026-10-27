package com.fincore.card;

import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
public class CardController {
 private final CardCoordinator cards;private final CardStore store;
 public CardController(CardCoordinator cards,CardStore store){this.cards=cards;this.store=store;}
 public record Settle(String action){}
 @PostMapping("/api/v1/cards") public Object create(@RequestHeader("Idempotency-Key")String key,@RequestBody CardCoordinator.Create body,@AuthenticationPrincipal Jwt jwt){return cards.create(jwt.getSubject(),jwt.getTokenValue(),key,body);}
 @GetMapping("/api/v1/cards") public Object cards(@RequestParam(required=false)UUID after,@AuthenticationPrincipal Jwt jwt){return cards.cards(jwt.getSubject(),after);}
 @GetMapping("/api/v1/cards/{id}") public Object card(@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt){return cards.card(id,jwt.getSubject());}
 @PutMapping("/api/v1/cards/{id}") public Object configure(@PathVariable UUID id,@RequestHeader("Idempotency-Key")String key,@RequestBody CardCoordinator.Configure body,@AuthenticationPrincipal Jwt jwt){return cards.configure(id,jwt.getSubject(),jwt.getTokenValue(),key,body);}
 @GetMapping("/api/v1/card-merchants") public Object merchants(){return cards.ledger("GET","/card-merchants",null);}
 @PostMapping("/api/v1/cards/{id}/authorizations") public Object authorize(@PathVariable UUID id,@RequestHeader("Idempotency-Key")String key,@RequestBody CardCoordinator.Authorize body,@AuthenticationPrincipal Jwt jwt){return ResponseEntity.accepted().header("Retry-After","2").body(cards.authorize(id,jwt.getSubject(),jwt.getTokenValue(),key,body));}
 @GetMapping("/api/v1/cards/{id}/authorizations") public Object authorizations(@PathVariable UUID id,@RequestParam(required=false)UUID after,@AuthenticationPrincipal Jwt jwt){return cards.authorizations(id,jwt.getSubject(),after);}
 @GetMapping("/api/v1/card-authorizations/{id}") public Object authorization(@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt){return cards.authorization(id,jwt.getSubject());}
 @PostMapping("/api/v1/card-authorizations/{id}/settle") public Object settle(@PathVariable UUID id,@RequestHeader("Idempotency-Key")String key,@RequestBody Settle body,@AuthenticationPrincipal Jwt jwt){return cards.settle(id,jwt.getSubject(),jwt.getTokenValue(),key,body.action());}
 @GetMapping("/internal/v1/cards/{id}/risk-context") public Object risk(@PathVariable UUID id){return store.risk(id);}
}
