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
}
