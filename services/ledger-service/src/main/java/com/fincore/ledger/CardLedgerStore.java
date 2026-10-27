package com.fincore.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fincore.support.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Financial card guards and holds live exclusively in Ledger. */
@Service
public class CardLedgerStore {
 private final JdbcTemplate db;
 public CardLedgerStore(JdbcTemplate db){this.db=db;}
 private JsonNode one(String sql,Object...args){var rows=db.query(sql,(rs,n)->Json.read(rs.getString(1)),args);if(rows.isEmpty() || rows.getFirst()==null)throw new LedgerStore.MissingRecord();return rows.getFirst();}
 private void bounded(){db.execute("set local lock_timeout='3s'");db.execute("set local statement_timeout='4s'");}
 public JsonNode card(UUID id){return one("select ledger.card_view(?)",id);}
 public JsonNode authorization(UUID id){return one("select ledger.card_authorization(?)",id);}
 public Map<String,Object> scan(String collection,UUID after){
  String table=collection.equals("cards")?"card_control":"card_intent",function=collection.equals("cards")?"card_view":"card_authorization";
  var rows=db.query("select ledger."+function+"(id)::text from ledger."+table+" where (?::uuid is null or id>?::uuid) order by id limit 100",(rs,n)->Json.read(rs.getString(1)),after,after);
  var result=new LinkedHashMap<String,Object>();result.put("items",rows);result.put("nextCursor",rows.size()==100?rows.getLast().path(collection.equals("cards")?"cardId":"authorizationId").asText():null);return result;
 }
 public List<JsonNode> merchants(){return db.query("select jsonb_build_object('merchantId',m.id,'name',m.label,'currency',a.currency)::text from ledger.card_merchant m join ledger.account a on a.id=m.id order by a.currency",(rs,n)->Json.read(rs.getString(1)));}
 public Map<String,Object> controls(UUID after){
  var rows=db.query("select jsonb_build_object('eventId',event_id,'receipt',response)::text from (select md5(jsonb_build_array(actor,kind,request_key)::text)::uuid as event_id,response from ledger.card_action where kind in ('CREATE','CONFIGURE')) facts where (?::uuid is null or event_id>?::uuid) order by event_id limit 100",(rs,n)->Json.read(rs.getString(1)),after,after);var result=new LinkedHashMap<String,Object>();result.put("items",rows);result.put("nextCursor",rows.size()==100?rows.getLast().path("eventId").asText():null);return result;
 }
 @Transactional public JsonNode create(UUID id,String actor,String key,UUID account,String per,String daily){bounded();return one("select ledger.card_create(?,?,?,?,?::numeric,?::numeric)",id,actor,key,account,per,daily);}
 @Transactional public JsonNode configure(UUID id,String actor,String key,long version,String status,String per,String daily){bounded();return one("select ledger.card_configure(?,?,?,?,?,?::numeric,?::numeric)",id,actor,key,version,status,per,daily);}
 @Transactional public JsonNode prepare(UUID id,UUID card,String actor,String key,UUID merchant,String amount,String currency){bounded();return one("select ledger.card_prepare(?,?,?,?,?,?::numeric,?)",id,card,actor,key,merchant,amount,currency);}
 @Transactional public JsonNode reserve(UUID id,UUID assessment,Instant expiry){bounded();return one("select ledger.card_reserve(?,?,?::timestamptz)",id,assessment,expiry.toString());}
 @Transactional public JsonNode recover(UUID id,Instant expiry){bounded();return one("select ledger.card_recover(?,?::timestamptz)",id,expiry.toString());}
 @Transactional public JsonNode settle(UUID id,String actor,String key,String action){bounded();return one("select ledger.card_settle(?,?,?,?)",id,actor,key,action);}
}
