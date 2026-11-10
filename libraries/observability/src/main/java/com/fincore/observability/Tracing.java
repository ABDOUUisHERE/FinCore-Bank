package com.fincore.observability;

import io.opentelemetry.api.common.*;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.*;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.time.Duration;
import java.util.Set;

/** Closed diagnostic vocabulary. No arbitrary tags, exception text, URLs or payloads. */
public final class Tracing implements AutoCloseable {
    public enum Dependency { LEDGER, ACCOUNT, IDENTITY_PROVIDER, OTHER }
    private final Tracer tracer;
    private final boolean enabled;
    private final Runnable shutdown;
    public Tracing(Tracer tracer) { this(tracer,true,()->{}); }
    private Tracing(Tracer tracer,boolean enabled,Runnable shutdown) { this.tracer=tracer;this.enabled=enabled;this.shutdown=shutdown; }
    public static Tracing disabled() { return new Tracing(TracerProvider.noop().get("fincore"),false,()->{}); }
    public boolean enabled() { return enabled; }
    public static Tracing otlp(String service,String endpoint) {
        if (!Set.of("api-gateway","reporting-service","ledger-service","audit-service").contains(service)) throw new IllegalArgumentException("UNSUPPORTED_TRACING_SERVICE");
        var exporter=OtlpHttpSpanExporter.builder().setEndpoint(endpoint).setTimeout(Duration.ofSeconds(1)).build();
        var processor=BatchSpanProcessor.builder(exporter).setMaxQueueSize(512).setMaxExportBatchSize(64)
            .setScheduleDelay(Duration.ofMillis(250)).setExporterTimeout(Duration.ofSeconds(1)).build();
        var provider=SdkTracerProvider.builder().setResource(Resource.create(Attributes.of(AttributeKey.stringKey("service.name"),service)))
            .setSampler(Sampler.parentBased(Sampler.alwaysOn())).addSpanProcessor(processor).build();
        return new Tracing(provider.get("fincore.closed-tracing", "1"),true,provider::close);
    }
    public Operation server(String parent,String method) {
        return start("http.server",SpanKind.SERVER,parentContext(parent)).method(method);
    }
    public Operation client(String method,Dependency dependency) {
        var operation=start("http.client",SpanKind.CLIENT,Context.current()).method(method);
        operation.span.setAttribute("fincore.dependency",dependency.name());return operation;
    }
    public Operation publish(String origin) {
        return validParent(origin) ? start("reporting.access.publish",SpanKind.PRODUCER,parentContext(origin)) : noop();
    }
    public Operation consume(String parent) {
        return validParent(parent) ? start("audit.receive",SpanKind.CONSUMER,parentContext(parent)) : noop();
    }
    private Operation start(String name,SpanKind kind,Context parent) {
        return enabled ? new Operation(tracer.spanBuilder(name).setSpanKind(kind).setParent(parent).startSpan()) : noop();
    }
    private static Operation noop() { return new Operation(Span.getInvalid()); }
    public static boolean validParent(String parent) {
        return parent!=null && parent.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-(00|01)")
            && !parent.substring(3,35).equals("0".repeat(32)) && !parent.substring(36,52).equals("0".repeat(16));
    }
    private static Context parentContext(String parent) {
        if (!validParent(parent)) return Context.root();
        var flags=parent.endsWith("01") ? TraceFlags.getSampled() : TraceFlags.getDefault();
        return Context.root().with(Span.wrap(SpanContext.createFromRemoteParent(parent.substring(3,35),parent.substring(36,52),flags,TraceState.getDefault())));
    }
    public static String currentParent() { return parent(Span.current().getSpanContext()); }
    private static String parent(SpanContext context) {
        return context.isValid() ? "00-"+context.getTraceId()+"-"+context.getSpanId()+"-"+context.getTraceFlags().asHex() : null;
    }
    @Override public void close() { shutdown.run(); }
    public static final class Operation implements AutoCloseable {
        private final Span span;
        private Operation(Span span) { this.span=span; }
        private Operation method(String method) {
            span.setAttribute("http.request.method",Set.of("GET","POST","PUT","PATCH","DELETE","HEAD","OPTIONS").contains(method)?method:"OTHER");return this;
        }
        public Scope makeCurrent() { return Context.root().with(span).makeCurrent(); }
        public String parent() { return Tracing.parent(span.getSpanContext()); }
        public String traceId() { return span.getSpanContext().isValid()?span.getSpanContext().getTraceId():null; }
        public void status(int code) { if(code>=100 && code<=599)span.setAttribute("http.response.status_code",code);if(code>=500)failed(); }
        public void failed() { span.setStatus(StatusCode.ERROR); }
        @Override public void close() { span.end(); }
    }
}
