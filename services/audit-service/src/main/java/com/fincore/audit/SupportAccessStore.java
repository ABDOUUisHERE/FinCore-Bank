package com.fincore.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fincore.support.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** All access decisions lock incident before grant before the retained-fact cursor lock. */
@Service
public class SupportAccessStore {
    private final JdbcTemplate db;
    private final AuditStore audit;
    public SupportAccessStore(JdbcTemplate db, AuditStore audit) { this.db=db; this.audit=audit; }
    public record Command(String expectedVersion, String action, Integer minutes, String reason) {}
    public record Admission(UUID incidentId, UUID accountId, String actor, String grantVersion, UUID attemptId) {}

    public JsonNode current(UUID id,String actor,boolean admin) {
        audit.own(id,actor,admin,false);
        return view(id);
    }
    private JsonNode view(UUID id) {
        return db.queryForObject("""
            select jsonb_build_object('incidentId',i.id,'accountId',i.resource_id,'recipient',i.created_by,
              'permission','ACCOUNT_STATUS_READ','version',coalesce(g.version,0)::text,
              'state',case when g.id is null then 'NONE' when g.revoked_at is not null then 'REVOKED'
                when i.status='RESOLVED' then 'RESOLVED' when g.expires_at<=clock_timestamp() then 'EXPIRED' else 'ACTIVE' end,
              'issuedBy',g.issued_by,'issuedAt',g.issued_at,'expiresAt',g.expires_at,'revokedAt',g.revoked_at,'reason',g.reason)::text
            from incident i left join support_account_grant g on g.incident_id=i.id where i.id=?
            """,(rs,n)->Json.read(rs.getString(1)),id);
    }
    @Transactional public JsonNode command(UUID id,String actor,String key,String correlation,Command request) {
        long version=version(request);
        audit.commandLock(actor,key);
        var incident=audit.own(id,actor,true,true);
        var fingerprint=AuditEvent.hash(Json.text(Arrays.asList("SUPPORT_ACCOUNT_GRANT",id,request.expectedVersion(),request.action(),request.minutes(),request.reason())));
        var replay=audit.receipt(actor,true,key,fingerprint); if(replay!=null)return replay;
        if(!incident.path("resourceKind").asText().equals("ACCOUNT"))throw new ApiFailure(409,"ACCOUNT_INCIDENT_REQUIRED");
        if(!view(id).path("version").asText().equals(Long.toString(version)))throw new ApiFailure(409,"GRANT_VERSION_CONFLICT");
        if(request.action().equals("ISSUE")) {
            if(incident.path("status").asText().equals("RESOLVED"))throw new ApiFailure(409,"INCIDENT_RESOLVED");
            db.update("""
                insert into support_account_grant(id,incident_id,version,issued_by,issued_at,expires_at,reason)
                  values(?,?,1,?,statement_timestamp(),statement_timestamp()+(? * interval '1 minute'),?)
                on conflict(incident_id) do update set version=support_account_grant.version+1,issued_by=excluded.issued_by,
                  issued_at=excluded.issued_at,expires_at=excluded.expires_at,revoked_at=null,reason=excluded.reason
                """,Identifiers.stable("support-grant",id.toString()),id,actor,request.minutes(),request.reason());
        } else {
            if(db.update("update support_account_grant set version=version+1,revoked_at=clock_timestamp(),reason=? where incident_id=? and revoked_at is null",request.reason(),id)!=1)
                throw new ApiFailure(409,"GRANT_NOT_REVOCABLE");
        }
        var result=(com.fasterxml.jackson.databind.node.ObjectNode)view(id);
        var record=UUID.randomUUID();
        audit.appendFact(record,request.action().equals("ISSUE")?"support.grant.issued":"support.grant.revoked",id,incident.path("version").asLong(),actor,correlation,
            Map.of("accountId",incident.path("resourceId").asText(),"recipient",incident.path("createdBy").asText(),"grantVersion",result.path("version").asText(),"reason",request.reason(),"permission","ACCOUNT_STATUS_READ"));
        result.put("auditRecordId",record.toString());audit.saveReceipt(actor,key,fingerprint,id,result);return result;
    }
    private static long version(Command request) {
        if(request==null || request.expectedVersion()==null || !request.expectedVersion().matches("0|[1-9][0-9]{0,18}")
            || !Set.of("ISSUE","REVOKE").contains(Objects.toString(request.action(),""))
            || request.reason()==null || !request.reason().matches("[A-Z][A-Z0-9_]{2,59}")
            || (request.action().equals("ISSUE") ? !Set.of(5,15,30,60).contains(Objects.requireNonNullElse(request.minutes(),0)) : request.minutes()!=null))
            throw new ApiFailure(400,"INVALID_SUPPORT_GRANT");
        try {long v=Long.parseLong(request.expectedVersion());if(v==Long.MAX_VALUE)throw new NumberFormatException();return v;}
        catch(NumberFormatException invalid){throw new ApiFailure(400,"INVALID_SUPPORT_GRANT");}
    }
    // A denial is returned rather than thrown so its fact can commit before the HTTP failure.
    @Transactional public Admission admit(UUID id,String actor,String correlation) {
        JsonNode incident;
        try { incident=audit.own(id,actor,true,true); }
        catch(ApiFailure missing) {
            if(missing.status!=404)throw missing;
            audit.appendFact(UUID.randomUUID(),"support.account.access",id,0,actor,correlation,Map.of("outcome","INCIDENT_UNAVAILABLE"));
            return null;
        }
        audit.factLock();
        var grant=view(id);var attempt=UUID.randomUUID();
        String denial=denial(incident,grant,actor);
        fact(incident,grant,actor,correlation,attempt,denial==null?"ADMITTED":denial);
        return denial==null?new Admission(id,UUID.fromString(incident.path("resourceId").asText()),actor,grant.path("version").asText(),attempt):null;
    }
    @Transactional public boolean finish(Admission admission,String correlation,String outcome) {
        var incident=audit.own(admission.incidentId(),admission.actor(),true,true);
        audit.factLock();
        var grant=view(admission.incidentId());
        String denial=denial(incident,grant,admission.actor());
        if(denial==null && !grant.path("version").asText().equals(admission.grantVersion()))denial="GRANT_CHANGED";
        fact(incident,grant,admission.actor(),correlation,admission.attemptId(),denial!=null?denial:outcome);
        return denial==null;
    }
    private static String denial(JsonNode incident,JsonNode grant,String actor) {
        if(!incident.path("createdBy").asText().equals(actor))return "FOREIGN_INCIDENT";
        if(!incident.path("resourceKind").asText().equals("ACCOUNT"))return "ACCOUNT_INCIDENT_REQUIRED";
        if(incident.path("status").asText().equals("RESOLVED"))return "INCIDENT_RESOLVED";
        if(!grant.path("state").asText().equals("ACTIVE"))return "GRANT_"+grant.path("state").asText();
        return null;
    }
    private void fact(JsonNode incident,JsonNode grant,String actor,String correlation,UUID attempt,String outcome) {
        audit.appendFact(UUID.randomUUID(),"support.account.access",UUID.fromString(incident.path("incidentId").asText()),incident.path("version").asLong(),actor,correlation,
            Map.of("attemptId",attempt.toString(),"accountId",incident.path("resourceId").asText(),"grantVersion",grant.path("version").asText(),"outcome",outcome));
    }
}
