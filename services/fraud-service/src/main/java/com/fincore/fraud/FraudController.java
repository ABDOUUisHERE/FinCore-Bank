package com.fincore.fraud;

import com.fincore.support.*;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
public class FraudController {
 private final FraudStore store;private final FraudContextClient context;
 public FraudController(FraudStore store,FraudContextClient context){this.store=store;this.context=context;}
 public record Assessment(UUID paymentId){}
 public record Decision(Long expectedVersion,Boolean approve,String reason){}
 public record RuleChange(Long expectedVersion,FraudRules rules,String reason){}
 @PostMapping("/internal/v1/risk-assessments") public Object assess(@RequestBody Assessment body){if(body.paymentId()==null)throw new ApiFailure(400,"PAYMENT_ID_REQUIRED");return store.assess(context.context(body.paymentId()));}
 @GetMapping({"/api/v1/admin/fraud-alerts","/api/v1/admin/fraud-cases"}) public Object cases(@RequestParam(required=false)Long before,@RequestParam(defaultValue="")String status){
  var items=store.cases(before,status);var result=new LinkedHashMap<String,Object>();result.put("items",items);result.put("nextCursor",items.size()==50?items.getLast().path("sequence").asText():null);return result;
 }
 @GetMapping("/api/v1/admin/fraud-cases/{id}") public Object get(@PathVariable UUID id){return store.caseDetail(id);}
 @GetMapping("/api/v1/admin/fraud-rules") public Object rules(){return store.rules();}
 @PutMapping("/api/v1/admin/fraud-rules") public Object rules(@RequestBody RuleChange body,@AuthenticationPrincipal Jwt jwt){if(body.expectedVersion()==null || body.rules()==null)throw new ApiFailure(400,"INVALID_FRAUD_RULES");return store.rules(body.expectedVersion(),body.rules(),jwt.getSubject(),body.reason());}
 @PostMapping("/api/v1/admin/fraud-cases/{id}/decisions") public ResponseEntity<?> decide(@PathVariable UUID id,@RequestHeader("Idempotency-Key")String key,@RequestBody Decision body,@AuthenticationPrincipal Jwt jwt){
  validate(body);var replay=store.decisionReplay(id,key,body.expectedVersion(),body.approve(),body.reason(),jwt.getSubject());if(replay!=null)return ResponseEntity.ok(replay);
  var detail=store.caseDetail(id);
  if(body.approve() && !context.payment(FraudStore.id(detail,"paymentId")).path("paymentStatus").asText().equals("PENDING_RISK_REVIEW"))throw new ApiFailure(409,"PAYMENT_STATE_CONFLICT");
  return ResponseEntity.ok(store.decide(id,body.expectedVersion(),body.approve(),body.reason(),jwt.getSubject(),key));
 }
 // Compatibility for the existing private operator; the worker has no fraud.review scope.
 @PostMapping("/internal/v1/fraud/payments/{id}/decision") public Object legacy(@PathVariable UUID id,@RequestBody Decision body,@AuthenticationPrincipal Jwt jwt){
  validate(body);UUID caseId=store.caseForPayment(id);String key="legacy:"+body.expectedVersion();
  var replay=store.decisionReplay(caseId,key,null,body.approve(),body.reason(),jwt.getSubject());if(replay!=null)return replay;
  var payment=context.payment(id);
  if(!payment.path("paymentStatus").asText().equals("PENDING_RISK_REVIEW") || payment.path("paymentVersion").asLong()!=body.expectedVersion())throw new ApiFailure(409,"PAYMENT_STATE_CONFLICT");
  var detail=store.caseDetail(caseId);return store.decide(caseId,detail.path("version").asLong(),body.approve(),body.reason(),jwt.getSubject(),key);
 }
 private static void validate(Decision body){if(body.expectedVersion()==null || body.approve()==null)throw new ApiFailure(400,"INVALID_INVESTIGATION_DECISION");}
}
