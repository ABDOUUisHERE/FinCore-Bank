package com.fincore.gateway.configuration;

import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestFilter implements WebFilter {
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst("X-Correlation-ID");
        String id = incoming != null && incoming.matches("[A-Za-z0-9._-]{1,64}") ? incoming : UUID.randomUUID().toString();
        exchange.getResponse().getHeaders().set("X-Correlation-ID", id);
        if (exchange.getRequest().getPath().value().startsWith("/api/")) {
            long size = exchange.getRequest().getHeaders().getContentLength();
            if (size > 16_384) return ProblemResponses.write(exchange, 413);
            if (exchange.getRequest().getHeaders().containsKey("Transfer-Encoding")) return ProblemResponses.write(exchange, 413);
        }
        return chain.filter(exchange.mutate().request(request -> request.headers(headers -> {
            headers.set("X-Correlation-ID", id);
            headers.remove("X-User-ID");
        })).build());
    }
}
