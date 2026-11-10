package com.fincore.audit;

import com.fincore.messaging.KafkaSettings;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.kafka.annotation.*;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.*;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@EnableKafka
@ConditionalOnProperty(name="fincore.audit.messaging-enabled",havingValue="true")
public class AuditMessaging {
    @Bean ConcurrentKafkaListenerContainerFactory<String,String> auditKafkaFactory(Environment env,AuditStore store) {
        var properties=KafkaSettings.common(env);
        properties.put("key.deserializer",StringDeserializer.class);properties.put("value.deserializer",StringDeserializer.class);
        properties.put("enable.auto.commit",false);properties.put("auto.offset.reset","earliest");
        properties.put("max.poll.records",10);properties.put("max.poll.interval.ms",180000);
        var factory=new ConcurrentKafkaListenerContainerFactory<String,String>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(properties));
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.getContainerProperties().setSyncCommits(true);
        // Explicitly keep keys, values and headers out of framework record diagnostics.
        org.springframework.kafka.support.KafkaUtils.setConsumerRecordFormatter(
            record -> record.topic()+"-"+record.partition()+"@"+record.offset());
        var handler=new DefaultErrorHandler((record,error)->{
            AuditEvent.Poison poison=null;
            for(Throwable cause=error;cause!=null;cause=cause.getCause())if(cause instanceof AuditEvent.Poison rejected){poison=rejected;break;}
            if(poison==null)throw new IllegalStateException("AUDIT_DELIVERY_RETRY");
            store.quarantine(record.topic(),record.partition(),record.offset(),record.value()==null?null:record.value().toString(),poison.getMessage());
        },new FixedBackOff(1000,FixedBackOff.UNLIMITED_ATTEMPTS));
        handler.addNotRetryableExceptions(AuditEvent.Poison.class);
        factory.setCommonErrorHandler(handler);
        return factory;
    }
    @Bean AuditListener auditListener(AuditStore store,com.fincore.observability.Tracing tracing) { return new AuditListener(store,tracing); }
    public static class AuditListener {
        private final AuditStore store;
        private final com.fincore.observability.Tracing tracing;
        AuditListener(AuditStore store) { this(store,com.fincore.observability.Tracing.disabled()); }
        AuditListener(AuditStore store,com.fincore.observability.Tracing tracing) { this.store=store;this.tracing=tracing; }
        @KafkaListener(topicPattern="fincore\\.(customer|account|ledger|payment|fraud|card|reporting)\\..*\\.v1",groupId="fincore-audit-v1",containerFactory="auditKafkaFactory")
        public void accept(ConsumerRecord<String,String> record) {
            var headers=record.headers().headers("traceparent").iterator();String parent=null;
            if(headers.hasNext()) { var value=headers.next().value();if(!headers.hasNext() && value!=null && value.length==55)parent=new String(value,java.nio.charset.StandardCharsets.US_ASCII); }
            try(var operation=tracing.consume(parent);var scope=operation.makeCurrent()) {
                try { store.accept(record.topic(),record.key(),record.value(),record.partition(),record.offset()); }
                catch(RuntimeException error) { operation.failed();throw error; }
            }
        }
    }
}
