package com.fincore.gateway.configuration;

import com.fincore.observability.Tracing;
import io.opentelemetry.sdk.trace.*;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class TraceFilterTest {
    final InMemorySpanExporter exporter=InMemorySpanExporter.create();
    final SdkTracerProvider provider=SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
    final Tracing tracing=new Tracing(provider.get("gateway-test"));
    @AfterEach void close() { provider.close(); }
    @Test void propagatesExplicitExchangeContextAcrossReactorThreadsAndReturnsOneSafeTraceHeader() {
        String origin="00-"+"a".repeat(32)+"-"+"b".repeat(16)+"-01";
        var exchange=MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/statements/PRIVATE_ID?month=PRIVATE_MONTH").header("traceparent",origin).header("baggage","name=PRIVATE_NAME").header("tracestate","PRIVATE_STATE").build());
        var forwarded=new AtomicReference<String>();
        new TraceFilter(tracing).filter(exchange,downstream->Mono.defer(()->{
            forwarded.set(downstream.getRequest().getHeaders().getFirst("traceparent"));
            assertThat(downstream.getRequest().getHeaders()).doesNotContainKeys("baggage","tracestate");
            downstream.getResponse().getHeaders().add("X-Trace-ID","PRIVATE_OWNER_VALUE");
            downstream.getResponse().setStatusCode(org.springframework.http.HttpStatus.OK);return downstream.getResponse().setComplete();
        }).subscribeOn(Schedulers.parallel())).block();
        await().untilAsserted(()->assertThat(exporter.getFinishedSpanItems()).hasSize(1));
        var span=exporter.getFinishedSpanItems().getFirst();
        assertThat(forwarded.get()).isEqualTo("00-"+span.getTraceId()+"-"+span.getSpanId()+"-01");
        assertThat(span.getParentSpanId()).isEqualTo("b".repeat(16));assertThat(span.getAttributes().toString()).doesNotContain("PRIVATE");
        assertThat(exchange.getResponse().getHeaders().get("X-Trace-ID")).containsExactly("a".repeat(32));
    }
    @Test void duplicateParentCannotSelectTraceAndErrorsRetainNoExceptionText() {
        String origin="00-"+"a".repeat(32)+"-"+"b".repeat(16)+"-01";
        var exchange=MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/statements").header("traceparent",origin,origin).build());
        assertThatThrownBy(()->new TraceFilter(tracing).filter(exchange,q->Mono.error(new IllegalStateException("PRIVATE_ERROR"))).block()).isInstanceOf(IllegalStateException.class);
        var span=exporter.getFinishedSpanItems().getFirst();
        assertThat(span.getTraceId()).isNotEqualTo("a".repeat(32));assertThat(span.getStatus().getStatusCode()).isEqualTo(io.opentelemetry.api.trace.StatusCode.ERROR);
        assertThat(span.getEvents()).isEmpty();assertThat(span.getStatus().getDescription()).isEmpty();
    }
}
