package com.fincore.audit;

import com.fincore.observability.Tracing;
import io.opentelemetry.sdk.trace.*;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuditTraceTest {
    final InMemorySpanExporter exporter=InMemorySpanExporter.create();
    final SdkTracerProvider provider=SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
    final Tracing tracing=new Tracing(provider.get("audit-test"));
    @AfterEach void close() { provider.close(); }
    @Test void consumesUnderTheActualProducerAttemptWithoutCopyingKeyBodyOrFailureText() {
        var store=mock(AuditStore.class);var record=new ConsumerRecord<String,String>("fincore.reporting.access.recorded.v1",0,7,"PRIVATE_KEY","PRIVATE_BODY");
        record.headers().add("traceparent",("00-"+"a".repeat(32)+"-"+"b".repeat(16)+"-01").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        doAnswer(invocation->{assertThat(Tracing.currentParent()).startsWith("00-"+"a".repeat(32));throw new IllegalStateException("PRIVATE_STORAGE_MESSAGE");}).when(store).accept(record.topic(),record.key(),record.value(),0,7);
        assertThatThrownBy(()->new AuditMessaging.AuditListener(store,tracing).accept(record)).isInstanceOf(IllegalStateException.class);
        var span=exporter.getFinishedSpanItems().getFirst();assertThat(span.getName()).isEqualTo("audit.receive");assertThat(span.getParentSpanId()).isEqualTo("b".repeat(16));
        assertThat(span.getAttributes().isEmpty()).isTrue();assertThat(span.getEvents()).isEmpty();assertThat(span.getStatus().getDescription()).isEmpty();assertThat(Tracing.currentParent()).isNull();
    }
    @Test void malformedDuplicateOrMissingContextNeverRejectsAnOtherwiseConsumableRecord() {
        var store=mock(AuditStore.class);var listener=new AuditMessaging.AuditListener(store,tracing);
        for(int mode=0;mode<3;mode++) {
            var record=new ConsumerRecord<String,String>("fincore.reporting.access.recorded.v1",0,mode,"key","body");
            if(mode>0)record.headers().add("traceparent","PRIVATE".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            if(mode==2)record.headers().add("traceparent",new byte[55]);listener.accept(record);
            verify(store).accept(record.topic(),"key","body",0,mode);
        }
        assertThat(exporter.getFinishedSpanItems()).isEmpty();
    }
}
