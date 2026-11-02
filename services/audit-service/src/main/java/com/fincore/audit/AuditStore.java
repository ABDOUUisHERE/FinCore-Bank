package com.fincore.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fincore.support.ApiFailure;
import com.fincore.support.Identifiers;
import com.fincore.support.Json;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditStore {
    private final JdbcTemplate db;
    private static final RowMapper<JsonNode> JSON = (rs, n) -> Json.read(rs.getString(1));
    private static final String FACT = "jsonb_build_object('recordId',id,'sequence',sequence::text,'source',source,'eventType',event_type,'aggregateId',aggregate_id,'aggregateVersion',aggregate_version,'actor',actor,'correlationId',correlation_id,'occurredAt',occurred_at,'observedAt',observed_at,'details',details,'bodyHash',body_hash,'sourceTopic',source_topic,'sourcePartition',source_partition,'sourceOffset',source_offset::text)::text";
    private static final String INCIDENT = "jsonb_build_object('incidentId',id,'createdBy',created_by,'resourceKind',resource_kind,'resourceId',resource_id,'severity',severity,'status',status,'version',version,'reason',reason,'createdAt',created_at,'updatedAt',updated_at)::text";
    public AuditStore(JdbcTemplate db) { this.db = db; }

    // One lock is taken before all fact/quarantine sequence allocation and held through commit.
    // This provides commit-order cursors, not a distributed transaction with Kafka.
    private void factLock() { db.execute("select pg_advisory_xact_lock(808801)"); }
    private void commandLock(String actor, String key) {
        Identifiers.key(key);
        db.queryForObject("select pg_advisory_xact_lock(hashtextextended(?,0))", String.class, Json.text(List.of(actor,key)));
    }
    @Transactional public void accept(String topic, String key, String body, int partition, long offset) {
        var parsed = AuditEvent.parse(topic,key,body);
        var event = parsed.envelope();
        factLock();
        var previous = db.query("select body_hash from audit_record where id=?", (rs,n) -> rs.getString(1), event.eventId());
        if (!previous.isEmpty()) {
            if (!previous.getFirst().equals(parsed.hash())) throw new AuditEvent.Poison("EVENT_IDENTITY_CONFLICT");
            return;
        }
        db.update("insert into audit_record(id,source,event_type,aggregate_id,aggregate_version,actor,correlation_id,occurred_at,details,body_hash,source_topic,source_partition,source_offset) values(?,'DOMAIN',?,?,?,?,?,?::timestamptz,?::jsonb,?,?,?,?)",
            event.eventId(),event.eventType(),event.aggregateId(),event.aggregateVersion(),parsed.actor(),event.correlationId(),event.occurredAt().toString(),parsed.details().toString(),parsed.hash(),topic,partition,offset);
    }
    @Transactional public void quarantine(String topic, int partition, long offset, String body, String code) {
        factLock();
        db.update("insert into audit_quarantine(source_topic,source_partition,source_offset,body_hash,code) values(?,?,?,?,?) on conflict do nothing",
            topic,partition,offset,AuditEvent.hash(body),code);
    }
    public Object facts(Long before, UUID aggregate, String eventType) {
        cursor(before);
        if (eventType != null && !eventType.matches("[a-z]+(\\.[a-z_]+){1,3}")) throw new ApiFailure(400,"INVALID_EVENT_FILTER");
        var rows = db.query("select " + FACT + " from audit_record where (?::bigint is null or sequence<?) and (?::uuid is null or aggregate_id=?) and (?::text is null or event_type=?) order by sequence desc limit 50", JSON,
            before,before,aggregate,aggregate,eventType,eventType);
        return page(rows, rows.size()==50 ? rows.getLast().path("sequence").asText() : null);
    }
    public Object quarantine(Long before) {
        cursor(before);
        var rows = db.query("select jsonb_build_object('sequence',sequence::text,'sourceTopic',source_topic,'sourcePartition',source_partition,'sourceOffset',source_offset::text,'bodyHash',body_hash,'code',code,'observedAt',observed_at)::text from audit_quarantine where (?::bigint is null or sequence<?) order by sequence desc limit 50",JSON,before,before);
        return page(rows, rows.size()==50 ? rows.getLast().path("sequence").asText() : null);
    }
    public Object summary() {
        return db.queryForObject("select jsonb_build_object('retainedDomainFacts',(select count(*) from audit_record where source='DOMAIN'),'recordedOperations',(select count(*) from audit_record where source='OPERATIONS'),'quarantinedRecords',(select count(*) from audit_quarantine),'openIncidents',(select count(*) from incident where status<>'RESOLVED'),'observedAt',clock_timestamp())::text",JSON);
    }
    public Object incidents(UUID after, String actor, boolean admin) {
        var rows = db.query("select " + INCIDENT + " from incident where (? or created_by=?) and (?::uuid is null or id>?) order by id limit 50",JSON,admin,actor,after,after);
        return page(rows, rows.size()==50 ? rows.getLast().path("incidentId").asText() : null);
    }
    public JsonNode incident(UUID id, String actor, boolean admin) { return own(id,actor,admin,false); }
    private JsonNode own(UUID id, String actor, boolean admin, boolean lock) {
        var rows = db.query("select " + INCIDENT + " from incident where id=? and (? or created_by=?)" + (lock ? " for update" : ""),JSON,id,admin,actor);
        if (rows.isEmpty()) throw new ApiFailure(404,"INCIDENT_NOT_FOUND");
        return rows.getFirst();
    }
    public Object history(UUID id, Long before, String actor, boolean admin) {
        own(id,actor,admin,false);cursor(before);
        // Only local incident facts are exposed to support, never unrelated domain events with a matching UUID.
        var rows=db.query("select " + FACT + " from audit_record where source='OPERATIONS' and aggregate_id=? and (?::bigint is null or sequence<?) order by sequence desc limit 50",JSON,id,before,before);
        return page(rows,rows.size()==50 ? rows.getLast().path("sequence").asText() : null);
    }
    public record Create(String resourceKind, UUID resourceId, String severity, String reason) { }
    public record Change(Long expectedVersion, String status, String reason) { }
    @Transactional public JsonNode create(String actor, boolean admin, String key, String correlation, Create request) {
        if (request == null || request.resourceId()==null || !Set.of("CUSTOMER","ACCOUNT","PAYMENT","JOURNAL","CARD","SERVICE").contains(Objects.toString(request.resourceKind(),""))
            || !Set.of("LOW","MEDIUM","HIGH").contains(Objects.toString(request.severity(),""))) throw new ApiFailure(400,"INVALID_INCIDENT");
        reason(request.reason());commandLock(actor,key);
        var fingerprint=AuditEvent.hash(Json.text(List.of("CREATE",request.resourceKind(),request.resourceId(),request.severity(),request.reason())));
        var replay=receipt(actor,admin,key,fingerprint);if(replay!=null)return replay;
        var id=Identifiers.stable("incident",actor,key);
        db.update("insert into incident(id,created_by,resource_kind,resource_id,severity,status,reason) values(?,?,?,?,?,'OPEN',?)",id,actor,request.resourceKind(),request.resourceId(),request.severity(),request.reason());
        return record(actor,key,fingerprint,correlation,id,"incident.opened",request.reason());
    }
    @Transactional public JsonNode change(UUID id, String actor, boolean admin, String key, String correlation, Change request) {
        if(request==null || request.expectedVersion()==null || request.expectedVersion()<0 || !Set.of("INVESTIGATING","RESOLVED").contains(Objects.toString(request.status(),"")))throw new ApiFailure(400,"INVALID_INCIDENT_CHANGE");
        reason(request.reason());commandLock(actor,key);var current=own(id,actor,admin,true);
        var fingerprint=AuditEvent.hash(Json.text(List.of("CHANGE",id,request.expectedVersion(),request.status(),request.reason())));
        var replay=receipt(actor,admin,key,fingerprint);if(replay!=null)return replay;
        if(current.path("version").asLong()!=request.expectedVersion())throw new ApiFailure(409,"INCIDENT_VERSION_CONFLICT");
        var status=current.path("status").asText();
        if(!(Set.of("OPEN","RESOLVED").contains(status) && request.status().equals("INVESTIGATING") || status.equals("INVESTIGATING") && request.status().equals("RESOLVED")))throw new ApiFailure(409,"INCIDENT_STATE_CONFLICT");
        db.update("update incident set status=?,version=version+1,reason=?,updated_at=clock_timestamp() where id=?",request.status(),request.reason(),id);
        return record(actor,key,fingerprint,correlation,id,"incident.status.changed",request.reason());
    }
    private JsonNode receipt(String actor, boolean admin, String key, String fingerprint) {
        var rows=db.query("select jsonb_build_object('fingerprint',fingerprint,'response',response)::text from incident_command where actor=? and key=?",JSON,actor,key);
        if(rows.isEmpty())return null;var row=rows.getFirst();
        var response=row.path("response");own(UUID.fromString(response.path("incidentId").asText()),actor,admin,false);
        if(!row.path("fingerprint").asText().equals(fingerprint))throw new ApiFailure(409,"IDEMPOTENCY_CONFLICT");
        return response;
    }
    private JsonNode record(String actor,String key,String fingerprint,String correlation,UUID id,String type,String reason) {
        var incident=own(id,actor,true,false);var recordId=UUID.randomUUID();
        var details=Map.of("resourceKind",incident.path("resourceKind").asText(),"resourceId",incident.path("resourceId").asText(),"severity",incident.path("severity").asText(),"status",incident.path("status").asText(),"reason",reason);
        factLock();
        db.update("insert into audit_record(id,source,event_type,aggregate_id,aggregate_version,actor,correlation_id,occurred_at,details,body_hash) values(?,'OPERATIONS',?,?,?,?,?,clock_timestamp(),?::jsonb,?)",
            recordId,type,id,incident.path("version").asLong(),actor,correlation,Json.text(details),AuditEvent.hash(Json.text(details)));
        var response=(com.fasterxml.jackson.databind.node.ObjectNode)incident.deepCopy();response.put("auditRecordId",recordId.toString());
        db.update("insert into incident_command(actor,key,fingerprint,incident_id,response) values(?,?,?,?,?::jsonb)",actor,key,fingerprint,id,response.toString());
        return response;
    }
    private static void reason(String value) { if(value==null || !value.matches("[A-Z][A-Z0-9_]{2,59}"))throw new ApiFailure(400,"INVALID_REASON_CODE"); }
    private static void cursor(Long value) { if(value!=null && value<=0)throw new ApiFailure(400,"INVALID_CURSOR"); }
    private static Map<String,Object> page(List<JsonNode> rows,String cursor) { var result=new LinkedHashMap<String,Object>();result.put("items",rows);result.put("nextCursor",cursor);return result; }
}
