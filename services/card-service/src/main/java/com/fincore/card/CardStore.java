package com.fincore.card;

import com.fasterxml.jackson.databind.JsonNode;
import com.fincore.support.*;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CardStore {
 private final JdbcTemplate db;private static final RowMapper<JsonNode> ROW=(rs,n)->Json.read(rs.getString(1));
 public CardStore(JdbcTemplate db){this.db=db;}
 public static UUID id(JsonNode node,String key){return UUID.fromString(node.path(key).asText());}
 private JsonNode one(String sql,Object...args){var rows=db.query(sql,ROW,args);if(rows.isEmpty())throw new ApiFailure(404,"CARD_NOT_FOUND");return rows.getFirst();}
 public JsonNode card(UUID id,String actor){return one("select to_jsonb(c)::text from card_projection c where id=? and actor=?",id,actor);}
 public List<JsonNode> cards(String actor,UUID after){return db.query("select to_jsonb(c)::text from card_projection c where actor=? and (?::uuid is null or id>?::uuid) order by id limit 50",ROW,actor,after,after);}
 public JsonNode authorization(UUID id){return one("select to_jsonb(a)::text from card_authorization a where id=?",id);}
 public boolean hasAuthorization(UUID id){return Boolean.TRUE.equals(db.queryForObject("select exists(select from card_authorization where id=?)",Boolean.class,id));}
 public JsonNode ownAuthorization(UUID id,String actor){return one("select to_jsonb(a)::text from card_authorization a where id=? and actor=?",id,actor);}
 public List<JsonNode> authorizations(String actor,UUID card,UUID after){return db.query("select to_jsonb(a)::text from card_authorization a where actor=? and card_id=? and (?::uuid is null or id>?::uuid) order by id limit 50",ROW,actor,card,after,after);}
 @Transactional public void discoverCard(JsonNode receipt){upsertCard(receipt);}
 private void upsertCard(JsonNode receipt){
  var id=id(receipt,"cardId");var ref=receipt.path("accountReference").asText();if(!ref.startsWith("account:"))throw new ApiFailure(503,"INVALID_CARD_RECEIPT");var account=UUID.fromString(ref.substring(8));var actor=receipt.path("actor").asText();var version=receipt.path("version").asLong();var status=receipt.path("status").asText();
  db.update("insert into card_projection(id,actor,account_id,first_observation,observed_version,observed_state) values(?,?,?,?::jsonb,?,?) on conflict do nothing",id,actor,account,receipt.toString(),version,status);
  db.update("update card_projection set observed_version=?,observed_state=? where id=? and actor=? and observed_version<?",version,status,id,actor,version);
 }
 @Transactional public void importCard(JsonNode receipt){
  upsertCard(receipt);var id=id(receipt,"cardId");var account=UUID.fromString(receipt.path("accountReference").asText().substring(8));var actor=receipt.path("actor").asText();var version=receipt.path("version").asLong();var status=receipt.path("status").asText();
  var event=Identifiers.stable("card-event",id.toString(),Long.toString(version));var kind=version==0?"card.created":status.equals("FROZEN")?"card.frozen":"card.state.changed";
  db.update("insert into card_outbox(id,aggregate_id,aggregate_version,event_type,payload) values(?,?,?,?,?::jsonb) on conflict do nothing",event,id,version,kind,Json.text(Map.of("cardId",id,"accountId",account,"actorSubject",actor,"status",status,"version",version)));
 }
 @Transactional public JsonNode importAuthorization(JsonNode receipt){var id=id(receipt,"authorizationId");db.update("insert into card_authorization(id,actor,card_id,receipt) values(?,?,?,?::jsonb) on conflict do nothing",id,receipt.path("actor").asText(),id(receipt,"cardId"),receipt.toString());return authorization(id);}
 @Transactional public JsonNode claim(){var rows=db.query("with candidate as(select id from card_authorization where status in ('AUTHORIZING','PENDING_RISK_REVIEW') and next_attempt<=clock_timestamp() and (lease_until is null or lease_until<clock_timestamp()) order by next_attempt,id for update skip locked limit 1) update card_authorization a set lease_token=gen_random_uuid(),lease_until=clock_timestamp()+interval '30 seconds',attempts=attempts+1 from candidate c where c.id=a.id returning to_jsonb(a)::text",ROW);return rows.isEmpty()?null:rows.getFirst();}
 @Transactional public void result(JsonNode leased,String status,String code,UUID assessment,JsonNode ledger){
  if(assessment==null && ledger!=null && ledger.hasNonNull("assessmentId"))assessment=id(ledger,"assessmentId");
  if(db.update("update card_authorization set status=?,code=?,version=version+case when status<>? then 1 else 0 end,assessment_id=?,ledger_outcome=?::jsonb,lease_token=null,lease_until=null,next_attempt=clock_timestamp()+interval '10 seconds' where id=? and lease_token=? and lease_until>clock_timestamp()",status,code,status,assessment,ledger==null?null:ledger.toString(),id(leased,"id"),id(leased,"lease_token"))!=1)throw new ApiFailure(409,"STALE_CARD_LEASE");
 }
 @Transactional public void retry(JsonNode leased,String code){db.update("update card_authorization set code=?,lease_token=null,lease_until=null,next_attempt=clock_timestamp()+(? * interval '1 second') where id=? and lease_token=?",code,Math.min(60,1<<Math.min(6,leased.path("attempts").asInt())),id(leased,"id"),id(leased,"lease_token"));}
 @Transactional public void attempting(JsonNode leased,java.time.Instant expires){if(db.update("update card_authorization set last_grant_expires=greatest(last_grant_expires,?::timestamptz) where id=? and lease_token=? and lease_until>clock_timestamp()",expires.toString(),id(leased,"id"),id(leased,"lease_token"))!=1)throw new ApiFailure(409,"STALE_CARD_LEASE");}
 @Transactional public void observe(UUID id,JsonNode ledger){db.update("update card_authorization set ledger_outcome=?::jsonb where id=?",ledger.toString(),id);}
 public JsonNode risk(UUID id){
  var row=authorization(id);var r=row.path("receipt");var result=Json.read("{}");var out=(com.fasterxml.jackson.databind.node.ObjectNode)result;
  for(String key:List.of("paymentId","actor","kind","amount","currency","destinationAccountId","fingerprint"))out.set(key,r.path(key));
  out.put("paymentVersion",row.path("version").asLong());out.put("paymentStatus",row.path("ledger_outcome").path("status").asText("AUTHORIZING").equals("AUTHORIZING")?row.path("status").asText():row.path("ledger_outcome").path("status").asText());out.set("journalId",row.path("ledger_outcome").path("journalId"));out.set("riskAssessmentId",row.path("assessment_id"));
  out.put("recentFailures",db.queryForObject("select count(*) from card_authorization where actor=? and created_at>=clock_timestamp()-interval '10 minutes' and (status='REJECTED' or ledger_outcome->>'status'='DECLINED')",Integer.class,row.path("actor").asText()));return out;
 }
}
