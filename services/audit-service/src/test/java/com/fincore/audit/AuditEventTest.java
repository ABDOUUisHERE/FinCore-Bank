package com.fincore.audit;

import com.fincore.support.Json;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AuditEventTest {
    static String event(UUID id, UUID aggregate, Map<String,Object> payload) {
        return Json.text(Map.of("eventId",id,"eventType","account.created","schemaVersion",1,"aggregateId",aggregate,"aggregateVersion",0,"correlationId",UUID.randomUUID().toString(),"occurredAt","2026-11-02T10:00:00Z","payload",payload));
    }
    @Test void redactsUnknownFieldsAndEmailSubjectsBeforePersistence() {
        var id=UUID.randomUUID();var aggregate=UUID.randomUUID();
        var body=event(id,aggregate,Map.of("accountId",aggregate,"status","active","ownerSubject","person@example.invalid","displayName","PRIVATE_NAME","token","PRIVATE_TOKEN","reference","=PRIVATE_FORMULA"));
        var parsed=AuditEvent.parse("fincore.account.created.v1",aggregate.toString(),body);
        assertThat(parsed.actor()).isNull();
        assertThat(parsed.details()).isEqualTo(Json.read("{\"accountId\":\""+aggregate+"\",\"status\":\"active\"}"));
        assertThat(parsed.hash()).matches("[a-f0-9]{64}");
    }
    @Test void requiresMatchingTopicAndKeyAndSupportedDomain() {
        var aggregate=UUID.randomUUID();var body=event(UUID.randomUUID(),aggregate,Map.of());
        for(var input:List.of(List.of("fincore.payment.completed.v1",aggregate.toString(),body),List.of("fincore.account.created.v1",UUID.randomUUID().toString(),body),List.of("fincore.account.created.v1",aggregate.toString(),body.replace("account.created","unknown.created"))))
            assertThatThrownBy(()->AuditEvent.parse(input.get(0),input.get(1),input.get(2))).isInstanceOf(AuditEvent.Poison.class);
    }
    @Test void malformedSchemaTimestampAndMetadataCannotBlockTheConsumerAsStorageErrors() {
        var aggregate=UUID.randomUUID();var valid=event(UUID.randomUUID(),aggregate,Map.of());
        for(String body:List.of("{}",valid.replace("\"schemaVersion\":1","\"schemaVersion\":99"),valid.replace("\"aggregateVersion\":0","\"aggregateVersion\":0.5"),valid.replace("2026-11-02T10:00:00Z","not-a-timestamp"),event(UUID.randomUUID(),aggregate,Map.of("accountId","invalid"))))
            assertThatThrownBy(()->AuditEvent.parse("fincore.account.created.v1",aggregate.toString(),body)).isInstanceOf(AuditEvent.Poison.class).hasMessageNotContaining("not-a-timestamp");
    }
    static String access(UUID id, Map<String,Object> payload) {
        return Json.text(Map.of("eventId",id,"eventType","customer.access.recorded","schemaVersion",1,"aggregateId",id,"aggregateVersion",1,
            "correlationId","access-test","occurredAt","2026-11-09T12:00:00Z","payload",payload));
    }
    static Map<String,Object> accessPayload() {
        return new HashMap<>(Map.of("action","CUSTOMER_SEARCH","outcome","RESPONSE_READY","httpMethod","POST","httpStatus",200,
            "actorProvenance","SIGNED_JWT","actorSubject","signed-staff"));
    }
    @Test void accessProjectionDropsAllFreeTextAndUnrelatedReferences() {
        var id=UUID.randomUUID();var payload=accessPayload();payload.putAll(Map.of("namePrefix","PRIVATE_PREFIX","displayName","PRIVATE_NAME","token","PRIVATE_TOKEN",
            "ownerSubject","FORGED_FALLBACK","paymentId",UUID.randomUUID().toString(),"rawPath","PRIVATE_PATH"));
        var parsed=AuditEvent.parse("fincore.customer.access.recorded.v1",id.toString(),access(id,payload));
        assertThat(parsed.actor()).isEqualTo("signed-staff");
        assertThat(parsed.details()).isEqualTo(Json.read("{\"action\":\"CUSTOMER_SEARCH\",\"outcome\":\"RESPONSE_READY\",\"httpMethod\":\"POST\",\"httpStatus\":200,\"actorProvenance\":\"SIGNED_JWT\"}"));
        var reference=UUID.randomUUID();payload.put("action","CUSTOMER_INSPECT");payload.put("customerId",reference.toString());
        assertThat(AuditEvent.parse("fincore.customer.access.recorded.v1",id.toString(),access(id,payload)).details().path("customerId").asText()).isEqualTo(reference.toString());
    }
    @Test void unavailableIdentityDoesNotFallBackToProducerReportedOwner() {
        var id=UUID.randomUUID();var payload=accessPayload();payload.put("actorProvenance","UNAVAILABLE");payload.remove("actorSubject");payload.put("ownerSubject","FORGED_ACTOR");payload.put("httpStatus",401);payload.put("outcome","UNAUTHENTICATED");
        assertThat(AuditEvent.parse("fincore.customer.access.recorded.v1",id.toString(),access(id,payload)).actor()).isNull();
    }
    @Test void malformedAccessMetadataIsQuarantinedRatherThanCoercedOrTrusted() {
        var id=UUID.randomUUID();
        for(var entry:Map.<String,Object>of("action","ARBITRARY_ACTION","httpMethod","DELETE","httpStatus",200.5,"outcome","FORBIDDEN","actorProvenance","HEADER","actorSubject","email@example.invalid","customerId",UUID.randomUUID().toString()).entrySet()) {
            var payload=accessPayload();payload.put(entry.getKey(),entry.getValue());
            assertThatThrownBy(()->AuditEvent.parse("fincore.customer.access.recorded.v1",id.toString(),access(id,payload))).isInstanceOf(AuditEvent.Poison.class);
        }
        var valid=access(id,accessPayload());
        for(String body:List.of(valid.replace("\"aggregateVersion\":1","\"aggregateVersion\":0"),valid.replace("\"aggregateId\":\""+id,"\"aggregateId\":\""+UUID.randomUUID())))
            assertThatThrownBy(()->AuditEvent.parse("fincore.customer.access.recorded.v1",id.toString(),body)).isInstanceOf(AuditEvent.Poison.class);
    }
    @Test void accountAndPaymentAccessRetainCurrentStaffAndOnlyTheirOwnTypedReference() {
        for(String domain:List.of("account","payment")) {
            var id=UUID.randomUUID();var reference=UUID.randomUUID();var payload=accessPayload();payload.put("action",domain.toUpperCase(Locale.ROOT)+"_INSPECT");payload.put("httpMethod","GET");payload.put(domain+"Id",reference.toString());
            payload.putAll(Map.of("ownerSubject","PRIVATE_PAYER","amount","PRIVATE_AMOUNT","instruction","PRIVATE_INSTRUCTION","reference","PRIVATE_REFERENCE","beforeVersion","PRIVATE_CURSOR"));
            var body=access(id,payload).replace("customer.access.recorded",domain+".access.recorded");
            var parsed=AuditEvent.parse("fincore."+domain+".access.recorded.v1",id.toString(),body);
            assertThat(parsed.actor()).isEqualTo("signed-staff");
            assertThat(parsed.details()).isEqualTo(Json.read(Json.text(Map.of(domain+"Id",reference,"action",domain.toUpperCase(Locale.ROOT)+"_INSPECT","httpMethod","GET","httpStatus",200,"outcome","RESPONSE_READY","actorProvenance","SIGNED_JWT"))));
            assertThat(parsed.details().toString()).doesNotContain("PRIVATE_");
        }
    }
    @Test void reportingActionsBindOnlyTheirOwnReferenceAndRedactStatementContents() {
        for(String action:List.of("STATEMENT_CREATE_REQUEST","STATEMENT_LIST","STATEMENT_INSPECT","STATEMENT_EXPORT_CSV","STATEMENT_EXPORT_PDF","RECONCILIATION_INSPECT","JOURNAL_INSPECT")) {
            var id=UUID.randomUUID();var payload=accessPayload();payload.put("action",action);payload.put("httpMethod",action.equals("STATEMENT_CREATE_REQUEST")?"POST":"GET");
            payload.putAll(Map.of("snapshot","PRIVATE_BYTES","contentHash","PRIVATE_HASH","month","PRIVATE_MONTH","opening","PRIVATE_BALANCE","reference","PRIVATE_REFERENCE","rawPath","PRIVATE_URL"));
            String field=action.equals("JOURNAL_INSPECT")?"journalId":Set.of("STATEMENT_INSPECT","STATEMENT_EXPORT_CSV","STATEMENT_EXPORT_PDF").contains(action)?"statementId":null;
            var reference=UUID.randomUUID();if(field!=null)payload.put(field,reference.toString());
            var parsed=AuditEvent.parse("fincore.reporting.access.recorded.v1",id.toString(),access(id,payload).replace("customer.access.recorded","reporting.access.recorded"));
            assertThat(parsed.actor()).isEqualTo("signed-staff");assertThat(parsed.details().path("action").asText()).isEqualTo(action);assertThat(parsed.details().toString()).doesNotContain("PRIVATE_");
            assertThat(parsed.details().size()).isEqualTo(field==null?5:6);if(field!=null)assertThat(parsed.details().path(field).asText()).isEqualTo(reference.toString());
        }
    }
    @Test void reportingCannotForgeForeignActionsOrReferencesOrArbitraryDomainFacts() {
        var id=UUID.randomUUID();
        for(String action:List.of("STATEMENT_CREATE_REQUEST","STATEMENT_LIST","RECONCILIATION_INSPECT","JOURNAL_INSPECT","STATEMENT_EXPORT_PDF")) {
            for(String field:List.of("accountId","paymentId",action.equals("JOURNAL_INSPECT")?"statementId":"journalId")) {
                var payload=accessPayload();payload.put("action",action);payload.put(field,UUID.randomUUID().toString());
                assertThatThrownBy(()->AuditEvent.parse("fincore.reporting.access.recorded.v1",id.toString(),access(id,payload).replace("customer.access.recorded","reporting.access.recorded"))).isInstanceOf(AuditEvent.Poison.class);
            }
        }
        var payload=accessPayload();payload.put("action","ACCOUNT_INSPECT");
        assertThatThrownBy(()->AuditEvent.parse("fincore.reporting.access.recorded.v1",id.toString(),access(id,payload).replace("customer.access.recorded","reporting.access.recorded"))).isInstanceOf(AuditEvent.Poison.class);
        assertThatThrownBy(()->AuditEvent.parse("fincore.reporting.created.v1",id.toString(),access(id,accessPayload()).replace("customer.access.recorded","reporting.created"))).isInstanceOf(AuditEvent.Poison.class);
    }
    @Test void foreignActionsReferencesOrActorClaimsCannotMasqueradeAsOwnerReadReceipts() {
        for(String domain:List.of("account","payment")) {
            var id=UUID.randomUUID();var base=accessPayload();base.put("action",domain.toUpperCase(Locale.ROOT)+"_INSPECT");
            String foreign=domain.equals("account")?"payment":"account";
            for(var change:List.of(Map.<String,Object>of("action",foreign.toUpperCase(Locale.ROOT)+"_INSPECT"),Map.<String,Object>of(foreign+"Id",UUID.randomUUID().toString()),Map.<String,Object>of("actorProvenance","UNAVAILABLE"),Map.<String,Object>of("httpStatus","200"))) {
                var payload=new HashMap<>(base);payload.putAll(change);String body=access(id,payload).replace("customer.access.recorded",domain+".access.recorded");
                assertThatThrownBy(()->AuditEvent.parse("fincore."+domain+".access.recorded.v1",id.toString(),body)).isInstanceOf(AuditEvent.Poison.class);
            }
        }
    }
}
