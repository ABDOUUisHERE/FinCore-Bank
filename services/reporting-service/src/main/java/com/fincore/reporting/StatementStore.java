package com.fincore.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fincore.support.*;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StatementStore {
 private final JdbcTemplate db;private static final RowMapper<JsonNode> ROW=(rs,n)->Json.read(rs.getString(1));
 public StatementStore(JdbcTemplate db){this.db=db;}
 private static final String VIEW="jsonb_build_object('statementId',id,'accountId',account_id,'month',month,'contentHash',content_hash,'createdAt',created_at,'snapshot',payload)::text";
 public JsonNode own(UUID id,String actor){var rows=db.query("select "+VIEW+" from statement_snapshot where id=? and actor=?",ROW,id,actor);if(rows.isEmpty())throw new ApiFailure(404,"STATEMENT_NOT_FOUND");return rows.getFirst();}
 @Transactional public JsonNode save(String actor,UUID account,JsonNode snapshot){
  var content=(com.fasterxml.jackson.databind.node.ObjectNode)snapshot.deepCopy();content.remove("observedAt");
  String hash=db.queryForObject("select encode(sha256(convert_to(?::jsonb::text,'UTF8')),'hex')",String.class,content.toString());
  db.update("insert into statement_snapshot(actor,account_id,month,content_hash,payload) values(?,?,?,?,?::jsonb) on conflict(actor,content_hash) do nothing",actor,account,snapshot.path("month").asText(),hash,snapshot.toString());
  return db.queryForObject("select "+VIEW+" from statement_snapshot where actor=? and content_hash=?",ROW,actor,hash);
 }
 public Object list(String actor,UUID after){var rows=db.query("select jsonb_build_object('statementId',id,'accountId',account_id,'month',month,'contentHash',content_hash,'createdAt',created_at,'opening',payload->>'opening','closing',payload->>'closing','currency',payload->>'currency')::text from statement_snapshot where actor=? and (?::uuid is null or id>?::uuid) order by id limit 50",ROW,actor,after,after);var out=new LinkedHashMap<String,Object>();out.put("items",rows);out.put("nextCursor",rows.size()==50?rows.getLast().path("statementId").asText():null);return out;}
}
