package com.fincore.gateway.configuration;

import com.fincore.observability.*;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;

/** Explicit exchange state; no thread-local context crosses Reactor threads. */
@Component
@Import(TracingConfiguration.class)
@Order(Ordered.HIGHEST_PRECEDENCE+2)
public final class TraceFilter implements WebFilter {
    private final Tracing tracing;
    public TraceFilter(Tracing tracing) { this.tracing=tracing; }
    @Override public Mono<Void> filter(ServerWebExchange exchange,WebFilterChain chain) {
        if(!tracing.enabled() || !exchange.getRequest().getPath().value().startsWith("/api/"))return chain.filter(exchange);
        return Mono.defer(()->{
            var parents=exchange.getRequest().getHeaders().get("traceparent");
            var operation=tracing.server(parents!=null && parents.size()==1?parents.getFirst():null,exchange.getRequest().getMethod().name());
            var forwarded=exchange.mutate().request(request->request.headers(headers->{
                headers.remove("traceparent");headers.remove("tracestate");headers.remove("baggage");
                if(operation.parent()!=null)headers.set("traceparent",operation.parent());
            })).build();
            exchange.getResponse().beforeCommit(()->{
                if(operation.traceId()!=null)exchange.getResponse().getHeaders().set("X-Trace-ID",operation.traceId());
                return Mono.empty();
            });
            return chain.filter(forwarded).doOnError(error->operation.failed()).doFinally(signal->{
                var code=exchange.getResponse().getStatusCode();if(code!=null)operation.status(code.value());
                if(signal==reactor.core.publisher.SignalType.CANCEL)operation.failed();operation.close();
            });
        });
    }
}
