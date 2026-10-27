package com.fincore.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fincore.support.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class StatementStore {
 private final JdbcTemplate db;
 public StatementStore(JdbcTemplate db){this.db=db;}
 static YearMonth month(String text){try{if(text==null || !text.matches("20[0-9]{2}-(0[1-9]|1[0-2])"))throw new IllegalArgumentException();var m=YearMonth.parse(text);if(m.isAfter(YearMonth.now(ZoneOffset.UTC)))throw new IllegalArgumentException();return m;}catch(java.time.DateTimeException e){throw new ApiFailure(400,"INVALID_MONTH");}}
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public JsonNode statement(UUID account,String actor,String period){
  var month=month(period);var from=month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();var to=month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
  db.execute("set local statement_timeout='5s'");
  var accounts=db.query("select jsonb_build_object('ledgerAccountId',a.id,'accountReference',a.external_reference,'currency',a.currency,'currentBooked',b.booked::text,'currentReserved',ledger.reserved(a.id)::text,'currentAvailable',(b.booked-ledger.reserved(a.id))::text,'ledgerVersion',b.version,'observedAt',statement_timestamp())::text from ledger.account a join ledger.balance b on b.account_id=a.id where a.id=? and a.owner_subject=? and a.type='CUSTOMER_LIABILITY'",(rs,n)->Json.read(rs.getString(1)),account,actor);
  if(accounts.isEmpty())throw new ApiFailure(404,"ACCOUNT_NOT_FOUND");
  var totals=db.queryForMap("select coalesce(sum(case p.side when 'CREDIT' then p.amount else -p.amount end) filter(where j.created_at<?::timestamptz),0)::text as opening,coalesce(sum(case p.side when 'CREDIT' then p.amount else -p.amount end) filter(where j.created_at<?::timestamptz),0)::text as closing from ledger.posting p join ledger.journal j on j.id=p.journal_id where p.account_id=?",from.toString(),to.toString(),account);
  var entries=db.query("select jsonb_build_object('postingId',p.id,'journalId',j.id,'at',j.created_at,'kind',o.kind,'reference',o.business_reference,'side',p.side,'amount',p.amount::text,'signedAmount',(case p.side when 'CREDIT' then p.amount else -p.amount end)::text,'reversalOf',j.reversal_of)::text from ledger.posting p join ledger.journal j on j.id=p.journal_id join ledger.operation o on o.id=j.operation_id where p.account_id=? and j.created_at>=?::timestamptz and j.created_at<?::timestamptz order by j.created_at,p.id limit 1001",(rs,n)->Json.read(rs.getString(1)),account,from.toString(),to.toString());
  if(entries.size()>1000)throw new ApiFailure(413,"REPORT_TOO_LARGE");
  var opening=new BigDecimal(totals.get("opening").toString());var closing=new BigDecimal(totals.get("closing").toString());var movement=entries.stream().map(e->new BigDecimal(e.path("signedAmount").asText())).reduce(BigDecimal.ZERO,BigDecimal::add);
  if(opening.add(movement).compareTo(closing)!=0)throw new ApiFailure(503,"STATEMENT_RECONCILIATION_FAILED");
  var result=(com.fasterxml.jackson.databind.node.ObjectNode)accounts.getFirst();result.put("source","ledger-postings");result.put("month",period);result.put("fromInclusive",from.toString());result.put("toExclusive",to.toString());result.put("opening",opening.setScale(2).toPlainString());result.put("movement",movement.setScale(2).toPlainString());result.put("closing",closing.setScale(2).toPlainString());result.put("reconciled",true);result.set("entries",Json.read(Json.text(entries)));return result;
 }
}
