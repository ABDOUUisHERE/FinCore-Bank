package com.fincore.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fincore.support.*;
import java.util.*;
import java.time.Instant;
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
    /** Fixed server policies; table, projection and fingerprint identifiers never come from a request. */
    public enum Resource {
        ACCOUNT("support_account_grant","accountId","ACCOUNT_STATUS_READ","SUPPORT_ACCOUNT_GRANT","support-grant","support.account.access"),
        PAYMENT("support_payment_grant","paymentId","PAYMENT_STATUS_READ","SUPPORT_PAYMENT_GRANT","support-payment-grant","support.payment.access"),
        CUSTOMER("support_customer_grant","customerId","CUSTOMER_PROFILE_READ","SUPPORT_CUSTOMER_GRANT","support-customer-grant","support.customer.access");
        final String table,idField,permission,fingerprint,identity,event;
        Resource(String table,String idField,String permission,String fingerprint,String identity,String event) {
            this.table=table;this.idField=idField;this.permission=permission;this.fingerprint=fingerprint;this.identity=identity;this.event=event;
        }
    }
    public record Admission(UUID incidentId, UUID resourceId, String actor, String grantVersion, UUID attemptId, Resource resource) {
        public Admission(UUID incidentId,UUID resourceId,String actor,String grantVersion,UUID attemptId) {
            this(incidentId,resourceId,actor,grantVersion,attemptId,Resource.ACCOUNT);
        }
    }

    public JsonNode current(UUID id,String actor,boolean admin) {
        return current(id,actor,admin,Resource.ACCOUNT);
    }
    public JsonNode current(UUID id,String actor,boolean admin,Resource resource) {
        requireKind(audit.own(id,actor,admin,false),resource);
        return view(id,resource);
    }
    private JsonNode view(UUID id,Resource resource) {
        return db.queryForObject("""
            select jsonb_build_object('incidentId',i.id,'%s',i.resource_id,'recipient',i.created_by,
              'permission','%s','version',coalesce(g.version,0)::text,
              'state',case when g.id is null then 'NONE' when g.revoked_at is not null then 'REVOKED'
                when i.status='RESOLVED' then 'RESOLVED' when g.expires_at<=clock_timestamp() then 'EXPIRED' else 'ACTIVE' end,
              'issuedBy',g.issued_by,'issuedAt',g.issued_at,'expiresAt',g.expires_at,'revokedAt',g.revoked_at,'reason',g.reason)::text
            from incident i left join %s g on g.incident_id=i.id where i.id=?
            """.formatted(resource.idField,resource.permission,resource.table),(rs,n)->Json.read(rs.getString(1)),id);
    }
    @Transactional public JsonNode command(UUID id,String actor,String key,String correlation,Command request) {
        return command(id,actor,key,correlation,request,Resource.ACCOUNT);
    }
    @Transactional public JsonNode command(UUID id,String actor,String key,String correlation,Command request,Resource resource) {
        long version=version(request);
        audit.commandLock(actor,key);
        var incident=audit.own(id,actor,true,true);
        var fingerprint=AuditEvent.hash(Json.text(Arrays.asList(resource.fingerprint,id,request.expectedVersion(),request.action(),request.minutes(),request.reason())));
        var replay=audit.receipt(actor,true,key,fingerprint); if(replay!=null)return replay;
        requireKind(incident,resource);
        if(!view(id,resource).path("version").asText().equals(Long.toString(version)))throw new ApiFailure(409,"GRANT_VERSION_CONFLICT");
        if(request.action().equals("ISSUE")) {
            if(incident.path("status").asText().equals("RESOLVED"))throw new ApiFailure(409,"INCIDENT_RESOLVED");
            db.update("""
                insert into %s(id,incident_id,version,issued_by,issued_at,expires_at,reason)
                  values(?,?,1,?,statement_timestamp(),statement_timestamp()+(? * interval '1 minute'),?)
                on conflict(incident_id) do update set version=%s.version+1,issued_by=excluded.issued_by,
                  issued_at=excluded.issued_at,expires_at=excluded.expires_at,revoked_at=null,reason=excluded.reason
                """.formatted(resource.table,resource.table),Identifiers.stable(resource.identity,id.toString()),id,actor,request.minutes(),request.reason());
        } else {
            if(db.update("update "+resource.table+" set version=version+1,revoked_at=clock_timestamp(),reason=? where incident_id=? and revoked_at is null",request.reason(),id)!=1)
                throw new ApiFailure(409,"GRANT_NOT_REVOCABLE");
        }
        var result=(com.fasterxml.jackson.databind.node.ObjectNode)view(id,resource);
        var record=UUID.randomUUID();
        audit.appendFact(record,request.action().equals("ISSUE")?"support.grant.issued":"support.grant.revoked",id,incident.path("version").asLong(),actor,correlation,
            Map.of(resource.idField,incident.path("resourceId").asText(),"recipient",incident.path("createdBy").asText(),"grantVersion",result.path("version").asText(),"reason",request.reason(),"permission",resource.permission,
                "issuedAt",result.path("issuedAt").asText(),"expiresAt",result.path("expiresAt").asText()));
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
        return admit(id,actor,correlation,Resource.ACCOUNT);
    }
    @Transactional public Admission admit(UUID id,String actor,String correlation,Resource resource) {
        JsonNode incident;
        try { incident=audit.own(id,actor,true,true); }
        catch(ApiFailure missing) {
            if(missing.status!=404)throw missing;
            audit.appendFact(UUID.randomUUID(),resource.event,id,0,actor,correlation,Map.of("outcome","INCIDENT_UNAVAILABLE"));
            return null;
        }
        audit.factLock();
        var grant=view(id,resource);var attempt=UUID.randomUUID();
        String denial=denial(incident,grant,actor,resource);
        fact(incident,grant,actor,correlation,attempt,denial==null?"ADMITTED":denial,resource);
        return denial==null?new Admission(id,UUID.fromString(incident.path("resourceId").asText()),actor,grant.path("version").asText(),attempt,resource):null;
    }
    @Transactional public boolean finish(Admission admission,String correlation,String outcome) {
        var incident=audit.own(admission.incidentId(),admission.actor(),true,true);
        audit.factLock();
        var resource=admission.resource();
        var grant=view(admission.incidentId(),resource);
        String denial=denial(incident,grant,admission.actor(),resource);
        if(denial==null && !incident.path("resourceId").asText().equals(admission.resourceId().toString()))denial="RESOURCE_MISMATCH";
        if(denial==null && !grant.path("version").asText().equals(admission.grantVersion()))denial="GRANT_CHANGED";
        fact(incident,grant,admission.actor(),correlation,admission.attemptId(),denial!=null?denial:outcome,resource);
        return denial==null;
    }
    private static String denial(JsonNode incident,JsonNode grant,String actor,Resource resource) {
        if(!incident.path("createdBy").asText().equals(actor))return "FOREIGN_INCIDENT";
        if(!incident.path("resourceKind").asText().equals(resource.name()))return resource.name()+"_INCIDENT_REQUIRED";
        if(incident.path("status").asText().equals("RESOLVED"))return "INCIDENT_RESOLVED";
        if(!grant.path("state").asText().equals("ACTIVE"))return "GRANT_"+grant.path("state").asText();
        return null;
    }
    public record SearchAdmission(UUID attemptId,String actor,List<Admission> grants,Instant expiresAt) {
        public SearchAdmission {grants=List.copyOf(grants);}
    }
    // Lock the entire cohort before the fact cursor: taking another incident lock after it can deadlock with commands.
    @Transactional public SearchAdmission admitCustomerSearch(String actor,String correlation) {
        var ids=db.query("""
            select i.id from incident i join support_customer_grant g on g.incident_id=i.id
            where i.created_by=? and i.resource_kind='CUSTOMER' and i.status<>'RESOLVED'
              and g.revoked_at is null and g.expires_at>clock_timestamp() order by i.id limit 101
            """,(rs,n)->rs.getObject(1,UUID.class),actor);
        UUID attempt=UUID.randomUUID();
        if(ids.isEmpty()||ids.size()>100) {searchFact(attempt,actor,correlation,ids.isEmpty()?"NO_ACTIVE_SCOPE":"SCOPE_TOO_LARGE",ids.size(),0);return null;}
        var incidents=ids.stream().map(id->audit.own(id,actor,false,true)).toList();
        audit.factLock();var admitted=new ArrayList<Admission>();Instant expiry=Instant.MAX;boolean valid=true;
        for(var incident:incidents) {
            UUID id=UUID.fromString(incident.path("incidentId").asText());var grant=view(id,Resource.CUSTOMER);
            String denial=denial(incident,grant,actor,Resource.CUSTOMER);
            fact(incident,grant,actor,correlation,attempt,denial==null?"SEARCH_ADMITTED":denial,Resource.CUSTOMER);
            if(denial!=null){valid=false;continue;}
            var until=Instant.parse(grant.path("expiresAt").asText());if(until.isBefore(expiry))expiry=until;
            admitted.add(new Admission(id,UUID.fromString(incident.path("resourceId").asText()),actor,grant.path("version").asText(),attempt,Resource.CUSTOMER));
        }
        valid=valid&&beforeExpiry(expiry);
        searchFact(attempt,actor,correlation,valid?"ADMITTED":"SCOPE_CHANGED",ids.size(),0);
        return valid?new SearchAdmission(attempt,actor,admitted,expiry):null;
    }
    @Transactional public boolean finishCustomerSearch(SearchAdmission admission,String correlation,String outcome,int matches) {
        var sorted=admission.grants().stream().sorted(Comparator.comparing(a->a.incidentId().toString())).toList();
        var incidents=sorted.stream().map(a->audit.own(a.incidentId(),admission.actor(),false,true)).toList();
        audit.factLock();boolean valid=true;
        for(int n=0;n<sorted.size();n++) {
            var a=sorted.get(n);var incident=incidents.get(n);var grant=view(a.incidentId(),Resource.CUSTOMER);
            String denial=denial(incident,grant,admission.actor(),Resource.CUSTOMER);
            if(denial==null&&(!a.grantVersion().equals(grant.path("version").asText())||!a.resourceId().toString().equals(incident.path("resourceId").asText())))denial="GRANT_CHANGED";
            fact(incident,grant,admission.actor(),correlation,admission.attemptId(),denial==null?"SEARCH_CHECKED":denial,Resource.CUSTOMER);
            valid&=denial==null;
        }
        // A check is not disclosure. Recheck the cohort deadline after retaining the individual checks.
        valid=valid&&beforeExpiry(admission.expiresAt());
        searchFact(admission.attemptId(),admission.actor(),correlation,valid?outcome:"SCOPE_CHANGED",sorted.size(),valid&&outcome.equals("DISCLOSED")?matches:0);
        return valid;
    }
    private boolean beforeExpiry(Instant expires) {return Boolean.TRUE.equals(db.queryForObject("select clock_timestamp()<?::timestamptz",Boolean.class,expires.toString()));}
    private void searchFact(UUID attempt,String actor,String correlation,String outcome,int scopes,int matches) {
        audit.appendFact(UUID.randomUUID(),"support.customer.search",attempt,0,actor,correlation,Map.of("attemptId",attempt.toString(),"outcome",outcome,"scopeCount",scopes,"matchCount",matches));
    }
    private static void requireKind(JsonNode incident,Resource resource) {
        if(!incident.path("resourceKind").asText().equals(resource.name()))throw new ApiFailure(409,resource.name()+"_INCIDENT_REQUIRED");
    }
    private void fact(JsonNode incident,JsonNode grant,String actor,String correlation,UUID attempt,String outcome,Resource resource) {
        audit.appendFact(UUID.randomUUID(),resource.event,UUID.fromString(incident.path("incidentId").asText()),incident.path("version").asLong(),actor,correlation,
            Map.of("attemptId",attempt.toString(),resource.idField,incident.path("resourceId").asText(),"grantVersion",grant.path("version").asText(),"outcome",outcome));
    }
}
