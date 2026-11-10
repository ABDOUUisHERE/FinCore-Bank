package com.fincore.observability;

import io.opentelemetry.api.trace.*;
import io.opentelemetry.sdk.trace.*;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

class TracingTest {
    final InMemorySpanExporter exporter=InMemorySpanExporter.create();
    final SdkTracerProvider provider=SdkTracerProvider.builder().setSampler(Sampler.parentBased(Sampler.alwaysOn())).addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
    final Tracing tracing=new Tracing(provider.get("closed-test"));
    @AfterEach void close() { provider.close(); }
    @Test void acceptsOnlyTheBoundedNonzeroVersion00ProfileAndNeverPrivateMethodText() {
        String valid="00-"+"1".repeat(32)+"-"+"2".repeat(16)+"-01";
        assertThat(Tracing.validParent(valid)).isTrue();
        for(String invalid:List.of("",valid+", "+valid,valid+"-private",valid.replace("00-","01-"),valid.replace("1","A"),valid.replace("1".repeat(32),"0".repeat(32)),valid.replace("2".repeat(16),"0".repeat(16)),valid.substring(0,53)+"ff")) {
            assertThat(Tracing.validParent(invalid)).isFalse();
            try(var request=tracing.server(invalid,"PRIVATE_METHOD")) { assertThat(request.traceId()).isNotEqualTo("1".repeat(32));request.status(400); }
        }
        assertThat(exporter.getFinishedSpanItems()).allSatisfy(span->{
            assertThat(span.getName()).isEqualTo("http.server");assertThat(span.getEvents()).isEmpty();
            assertThat(span.getAttributes().asMap()).hasSize(2);
            assertThat(span.getAttributes().toString()).doesNotContain("PRIVATE").contains("OTHER");
        });
    }
    @Test void delayedAndRetriedPublicationUsesEndedOriginAndConsumerUsesItsActualAttempt() {
        String origin,trace;
        try(var request=tracing.server(null,"GET");var scope=request.makeCurrent()) { origin=Tracing.currentParent();trace=request.traceId(); }
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
        String first,second;
        try(var unrelated=tracing.server(null,"POST");var scope=unrelated.makeCurrent()) {
            try(var publication=tracing.publish(origin)) { first=publication.parent();publication.failed(); }
            try(var retry=tracing.publish(origin)) { second=retry.parent(); }
            assertThat(Tracing.currentParent()).isEqualTo(unrelated.parent());
        }
        try(var consumer=tracing.consume(second);var scope=consumer.makeCurrent()) { assertThat(Tracing.currentParent()).isEqualTo(consumer.parent()); }
        var spans=exporter.getFinishedSpanItems();
        var publications=spans.stream().filter(s->s.getKind()==SpanKind.PRODUCER).toList();
        assertThat(publications).hasSize(2).allSatisfy(s->{assertThat(s.getTraceId()).isEqualTo(trace);assertThat(s.getParentSpanId()).isEqualTo(origin.substring(36,52));});
        assertThat(first).isNotEqualTo(second);
        var consumer=spans.stream().filter(s->s.getKind()==SpanKind.CONSUMER).findFirst().orElseThrow();
        assertThat(consumer.getTraceId()).isEqualTo(trace);assertThat(consumer.getParentSpanId()).isEqualTo(second.substring(36,52));
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
    }
    @Test void respectsUnsampledParentsAndDoesNotInventOriginsForLegacyOrInvalidDeliveries() {
        try(var request=tracing.server("00-"+"a".repeat(32)+"-"+"b".repeat(16)+"-00","GET");var scope=request.makeCurrent()) {
            assertThat(Tracing.currentParent()).endsWith("-00");
            try(var publish=tracing.publish(Tracing.currentParent())) { assertThat(publish.parent()).endsWith("-00"); }
        }
        try(var legacy=tracing.publish(null);var invalid=tracing.consume("PRIVATE_HEADER")) {
            assertThat(legacy.parent()).isNull();assertThat(invalid.traceId()).isNull();
        }
        assertThat(exporter.getFinishedSpanItems()).isEmpty();
    }
}
