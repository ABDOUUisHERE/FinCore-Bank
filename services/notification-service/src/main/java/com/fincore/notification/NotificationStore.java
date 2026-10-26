package com.fincore.notification;
import com.fasterxml.jackson.databind.JsonNode;
import com.fincore.messaging.EventEnvelope;
import com.fincore.support.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationStore {
    private final JdbcTemplate jdbc;
    private static final org.springframework.jdbc.core.RowMapper<JsonNode> ROW=(rs,n)->Json.read(rs.getString(1));
    public record Notice(String subject,String category,String title,String body,String semanticKey){}
    public NotificationStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public boolean consumed(UUID event){return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select from consumed_event where event_id=?)",Boolean.class,event));}
    @Transactional public boolean accept(EventEnvelope event,String raw,List<Notice> notices) {
        var hash=hash(raw);
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,event.eventType().split("\\.")[0]+":"+event.aggregateId());
        int inserted=jdbc.update("insert into consumed_event(event_id,payload_hash) values(?,?) on conflict do nothing",event.eventId(),hash);
        if(inserted==0){
            if(!hash.equals(jdbc.queryForObject("select payload_hash from consumed_event where event_id=?",String.class,event.eventId())))throw new IllegalArgumentException("EVENT_ID_CONFLICT");
            return false;
        }
        if(event.eventType().equals("ledger.journal.posted")) {
            jdbc.update("insert into ledger_snapshot(account_id,version,payload) values(?,?,?::jsonb) on conflict(account_id) do update set version=excluded.version,payload=excluded.payload where ledger_snapshot.version<excluded.version",event.aggregateId(),event.aggregateVersion(),event.payload().toString());
            return true;
        }
        var old=jdbc.query("select version from aggregate_watermark where aggregate_id=?",(rs,n)->rs.getLong(1),event.aggregateId());
        if(!old.isEmpty() && old.getFirst()>=event.aggregateVersion())return false;
        jdbc.update("insert into aggregate_watermark(aggregate_id,version,event_type) values(?,?,?) on conflict(aggregate_id) do update set version=excluded.version,event_type=excluded.event_type",event.aggregateId(),event.aggregateVersion(),event.eventType());
        for(var notice:notices){
            var pref=preferences(notice.subject());
            if(!pref.path(notice.category().equals("PAYMENTS")?"payments":"security").asBoolean())continue;
            var id=UUID.nameUUIDFromBytes((notice.subject()+":"+notice.semanticKey()).getBytes(StandardCharsets.UTF_8));
            if(jdbc.update("insert into notification(id,event_id,subject,aggregate_id,category,title,body,in_app,semantic_key) values(?,?,?,?,?,?,?,?,?) on conflict(subject,semantic_key) do nothing",id,event.eventId(),notice.subject(),event.aggregateId(),notice.category(),notice.title(),notice.body(),pref.path("inApp").asBoolean(),notice.semanticKey())==1 && pref.path("email").asBoolean())
                jdbc.update("insert into email_delivery(id,subject) values(?,?)",id,notice.subject());
        }
        return true;
    }
    public static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException(error);}}
    public JsonNode preferences(String subject){
        var rows=jdbc.query("select jsonb_build_object('inApp',in_app,'email',email,'payments',payments,'security',security,'version',version)::text from notification_preferences where subject=?",ROW,subject);
        return rows.isEmpty()?Json.read("{\"inApp\":true,\"email\":false,\"payments\":true,\"security\":true,\"version\":0}"):rows.getFirst();
    }
    @Transactional public JsonNode preferences(String subject,boolean inApp,boolean email,boolean payments,boolean security,long version){
        jdbc.update("insert into notification_preferences(subject) values(?) on conflict do nothing",subject);
        if(version<0 || jdbc.update("update notification_preferences set in_app=?,email=?,payments=?,security=?,version=version+1 where subject=? and version=?",inApp,email,payments,security,subject,version)!=1)throw new ApiFailure(409,"PREFERENCE_VERSION_CONFLICT");
        return preferences(subject);
    }
    public Map<String,Object> list(String subject,Long before,boolean unread){
        if(before!=null && before<=0)throw new ApiFailure(400,"INVALID_CURSOR");
        var rows=jdbc.query("select jsonb_build_object('notificationId',id,'sequence',sequence::text,'aggregateId',aggregate_id,'category',category,'title',title,'body',body,'readAt',read_at,'createdAt',created_at)::text from notification where subject=? and in_app and (?::bigint is null or sequence<?) and (not ? or read_at is null) order by sequence desc limit 50",ROW,subject,before,before,unread);
        var result=new LinkedHashMap<String,Object>();result.put("items",rows);result.put("nextCursor",rows.size()==50?rows.getLast().path("sequence").asText():null);result.put("unreadCount",jdbc.queryForObject("select count(*) from notification where subject=? and in_app and read_at is null",Long.class,subject));return result;
    }
    @Transactional public void read(String subject,UUID id){
        if(jdbc.update("update notification set read_at=coalesce(read_at,clock_timestamp()) where id=? and subject=? and in_app",id,subject)!=1)throw new ApiFailure(404,"NOTIFICATION_NOT_FOUND");
    }
    public long cursor(String subject){return jdbc.queryForObject("select coalesce(max(sequence),0) from notification where subject=? and in_app",Long.class,subject);}
    @Transactional public void deadLetter(String topic,String key,String raw,int partition,long offset){
        if(raw==null || raw.length()>131072)raw="{\"quarantined\":\"INVALID_SIZE\"}";
        var id=UUID.nameUUIDFromBytes((partition+":"+offset).getBytes(StandardCharsets.UTF_8));
        jdbc.update("insert into dead_letter(id,source_topic,record_key,body,source_partition,source_offset) values(?,?,?,?,?,?) on conflict do nothing",id,topic,key,raw,partition,offset);
    }
    public List<JsonNode> deadLetters(){return jdbc.query("select jsonb_build_object('deadLetterId',id,'sourceTopic',source_topic,'version',version,'createdAt',created_at)::text from dead_letter order by created_at desc limit 100",ROW);}
    @Transactional public UUID replay(UUID id,long version,String actor,String reason){
        if(version<0 || reason==null || !reason.matches("[A-Z_]{3,100}"))throw new ApiFailure(400,"INVALID_REPLAY_REASON");
        var rows=jdbc.query("select to_jsonb(d)::text from dead_letter d where id=? for update",ROW,id);
        if(rows.isEmpty())throw new ApiFailure(404,"DEAD_LETTER_NOT_FOUND");var d=rows.getFirst();
        if(d.path("version").asLong()!=version)throw new ApiFailure(409,"REPLAY_VERSION_CONFLICT");
        var replayId=Identifiers.stable("notification-replay",id.toString(),String.valueOf(version+1));
        jdbc.update("update dead_letter set version=version+1 where id=?",id);
        jdbc.update("insert into replay_audit(id,dead_letter_id,version,actor,reason) values(?,?,?,?,?)",replayId,id,version+1,actor,reason);
        jdbc.update("insert into replay_outbox(id,body) values(?,?::jsonb)",replayId,Json.text(Map.of("originalTopic",d.path("source_topic").asText(),"key",d.path("record_key").asText(),"value",d.path("body").asText())));
        return replayId;
    }
    public List<JsonNode> emails(String subject){return jdbc.query("select jsonb_build_object('deliveryId',delivery_id,'title',title,'body',body,'createdAt',created_at)::text from synthetic_email where subject=? order by created_at desc limit 50",ROW,subject);}
}
