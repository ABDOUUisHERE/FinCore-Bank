package com.fincore.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fincore.support.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ReportingCoordinator {
 private final Remote remote;private final StatementStore store;private final String account,ledger;
 public ReportingCoordinator(Remote remote,StatementStore store,@Value("${ACCOUNT_URL:http://localhost:8083}")String account,@Value("${LEDGER_URL:http://localhost:8082}")String ledger){this.remote=remote;this.store=store;this.account=account;this.ledger=ledger+"/internal/v1/ledger";}
 public JsonNode create(UUID id,String month,String actor,String bearer){
  if(id==null || month==null || !month.matches("20[0-9]{2}-(0[1-9]|1[0-2])") || YearMonth.parse(month).isAfter(YearMonth.now(ZoneOffset.UTC)))throw new ApiFailure(400,"INVALID_STATEMENT_REQUEST");
  var owned=remote.call("GET",account+"/internal/v1/accounts/"+id,remote.serviceToken(),null);if(!owned.path("ownerSubject").asText().equals(actor))throw new ApiFailure(404,"ACCOUNT_NOT_FOUND");if(owned.path("ledgerId").isNull())throw new ApiFailure(409,"ACCOUNT_NOT_READY");
  var source=remote.call("GET",ledger+"/reports/accounts/"+owned.path("ledgerId").asText()+"/statement?month="+month,remote.exchange(bearer),null);
  if(!source.path("ledgerAccountId").asText().equals(owned.path("ledgerId").asText()) || !source.path("accountReference").asText().equals("account:"+id) || !source.path("month").asText().equals(month) || !source.path("reconciled").asBoolean() || source.path("entries").size()>1000)throw new ApiFailure(503,"INVALID_STATEMENT_SOURCE");
  try{var movement=BigDecimal.ZERO;for(var item:source.path("entries"))movement=movement.add(new BigDecimal(item.path("signedAmount").asText()));if(new BigDecimal(source.path("opening").asText()).add(movement).compareTo(new BigDecimal(source.path("closing").asText()))!=0)throw new ApiFailure(503,"STATEMENT_RECONCILIATION_FAILED");}catch(NumberFormatException e){throw new ApiFailure(503,"INVALID_STATEMENT_SOURCE");}
  var snapshot=(com.fasterxml.jackson.databind.node.ObjectNode)source.deepCopy();snapshot.remove(List.of("ledgerAccountId","accountReference"));snapshot.put("accountId",id.toString());return store.save(actor,id,snapshot);
 }
 public JsonNode reconcile(){var source=remote.call("GET",ledger+"/reports/reconciliation",remote.serviceToken(),null);var result=(com.fasterxml.jackson.databind.node.ObjectNode)source.deepCopy();result.put("source","ledger-repeatable-read");result.put("observedAt",Instant.now().toString());result.put("reconciled",source.path("balanceDiscrepancies").isArray() && source.path("balanceDiscrepancies").isEmpty() && source.path("invalidJournals").isArray() && source.path("invalidJournals").isEmpty());return result;}
}
