package com.fincore.reporting;

import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
public class ReportingController {
 private final StatementStore store;private final ReportingCoordinator coordinator;
 public ReportingController(StatementStore store,ReportingCoordinator coordinator){this.store=store;this.coordinator=coordinator;}
 public record Create(UUID accountId,String month){}
 @PostMapping("/api/v1/statements")public Object create(@RequestBody Create body,@AuthenticationPrincipal Jwt jwt){return coordinator.create(body.accountId(),body.month(),jwt.getSubject(),jwt.getTokenValue());}
 @GetMapping("/api/v1/statements")public Object list(@RequestParam(required=false)UUID after,@AuthenticationPrincipal Jwt jwt){return store.list(jwt.getSubject(),after);}
 @GetMapping("/api/v1/statements/{id}")public Object get(@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt){return store.own(id,jwt.getSubject());}
 @GetMapping("/api/v1/statements/{id}/csv")public ResponseEntity<byte[]> csv(@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt){return download(id,"csv","text/csv; charset=utf-8",StatementExports.csv(store.own(id,jwt.getSubject())));}
 @GetMapping("/api/v1/statements/{id}/pdf")public ResponseEntity<byte[]> pdf(@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt){return download(id,"pdf","application/pdf",StatementExports.pdf(store.own(id,jwt.getSubject())));}
 @GetMapping("/api/v1/admin/reports/reconciliation")public Object reconcile(){return coordinator.reconcile();}
 private static ResponseEntity<byte[]> download(UUID id,String extension,String type,byte[] body){return ResponseEntity.ok().header("Cache-Control","no-store").header("X-Content-Type-Options","nosniff").header("Content-Disposition","attachment; filename=\"fincore-"+id+"."+extension+"\"").contentType(MediaType.parseMediaType(type)).body(body);}
}
