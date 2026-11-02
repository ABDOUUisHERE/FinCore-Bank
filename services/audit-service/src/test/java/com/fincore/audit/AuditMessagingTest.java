package com.fincore.audit;

import org.apache.kafka.clients.consumer.*;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.*;
import org.springframework.kafka.support.KafkaUtils;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuditMessagingTest {
    @Test void poisonRecoveryAcknowledgesOnlyAfterDurableQuarantineAndLogsMetadataOnly() {
        var store=mock(AuditStore.class);
        var env=new MockEnvironment().withProperty("KAFKA_BOOTSTRAP_SERVERS","unused.invalid:9092").withProperty("KAFKA_SECURITY_PROTOCOL","PLAINTEXT");
        var factory=new AuditMessaging().auditKafkaFactory(env,store);
        var container=factory.createContainer("fincore.account.created.v1");
        var handler=(DefaultErrorHandler)container.getCommonErrorHandler();
        var record=new ConsumerRecord<String,String>("fincore.account.created.v1",0,42,"PRIVATE_KEY","PRIVATE_BODY");
        assertThat(KafkaUtils.format(record)).isEqualTo("fincore.account.created.v1-0@42").doesNotContain("PRIVATE");
        var error=new ListenerExecutionFailedException("Listener rejected record",new AuditEvent.Poison("INVALID_EVENT_ENVELOPE"));
        @SuppressWarnings("unchecked") Consumer<String,String> consumer=mock(Consumer.class);
        assertThat(handler.handleOne(error,record,consumer,container)).isTrue();
        verify(store).quarantine(record.topic(),0,42,"PRIVATE_BODY","INVALID_EVENT_ENVELOPE");
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("STORAGE_UNAVAILABLE")).when(store).quarantine(record.topic(),0,43,"PRIVATE_BODY","INVALID_EVENT_ENVELOPE");
        assertThat(handler.handleOne(error,new ConsumerRecord<String,String>(record.topic(),0,43,"PRIVATE_KEY","PRIVATE_BODY"),consumer,container)).isFalse();
        verifyNoInteractions(consumer);
    }
}
