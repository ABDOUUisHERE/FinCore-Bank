package com.fincore.support;

import com.fincore.observability.Tracing;
import io.opentelemetry.sdk.trace.*;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import static org.assertj.core.api.Assertions.*;

class ServletTracingTest {
    final InMemorySpanExporter exporter=InMemorySpanExporter.create();
    final SdkTracerProvider provider=SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
    final Tracing tracing=new Tracing(provider.get("servlet-test"));
    @AfterEach void close() { provider.close(); }
    @Test void signedOrUnsignedHttpInputCannotEnterSpanNamesTagsOrEvents() throws Exception {
        var request=new MockHttpServletRequest("GET","/api/v1/statements/PRIVATE_RESOURCE/pdf");
        request.setQueryString("month=PRIVATE_MONTH");request.addHeader("Authorization","Bearer PRIVATE_TOKEN");request.addHeader("baggage","name=PRIVATE_NAME");
        request.addHeader("traceparent","00-"+"a".repeat(32)+"-"+"b".repeat(16)+"-01");
        var response=new MockHttpServletResponse();
        new ServletTracingConfiguration.ServletTraceFilter(tracing).doFilter(request,response,(q,r)->{
            assertThat(Tracing.currentParent()).startsWith("00-"+"a".repeat(32));((jakarta.servlet.http.HttpServletResponse)r).setStatus(404);
        });
        assertThat(response.getHeader("X-Trace-ID")).isEqualTo("a".repeat(32));
        var span=exporter.getFinishedSpanItems().getFirst();assertThat(span.getParentSpanId()).isEqualTo("b".repeat(16));
        assertThat(span.getName()).isEqualTo("http.server");assertThat(span.getAttributes().asMap()).hasSize(2);
        assertThat(span.getAttributes().toString()).doesNotContain("PRIVATE");assertThat(span.getEvents()).isEmpty();
        assertThat(Tracing.currentParent()).isNull();
    }
    @Test void duplicateParentsStartAnIndependentTraceAndDisabledTracingPreservesAmbientContext() throws Exception {
        var request=new MockHttpServletRequest("GET","/api/v1/statements");String parent="00-"+"a".repeat(32)+"-"+"b".repeat(16)+"-01";
        request.addHeader("traceparent",parent);request.addHeader("traceparent",parent);var response=new MockHttpServletResponse();
        new ServletTracingConfiguration.ServletTraceFilter(tracing).doFilter(request,response,(q,r)->{});
        assertThat(response.getHeader("X-Trace-ID")).isNotEqualTo("a".repeat(32));
        try(var ambient=tracing.server(null,"GET");var scope=ambient.makeCurrent()) {
            var disabledResponse=new MockHttpServletResponse();
            new ServletTracingConfiguration.ServletTraceFilter(Tracing.disabled()).doFilter(new MockHttpServletRequest("GET","/api/v1/statements"),disabledResponse,(q,r)->assertThat(Tracing.currentParent()).isEqualTo(ambient.parent()));
            assertThat(disabledResponse.getHeader("X-Trace-ID")).isNull();
        }
    }
}
