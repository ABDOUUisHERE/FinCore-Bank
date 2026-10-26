package com.fincore.gateway.configuration;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Runs inside the security chain after JWT authentication, before authorization. */
final class ApiRateLimitFilter implements WebFilter {
    private final LongSupplier clock;
    private final int capacity;
    private final Map<String, Integer> requests = new HashMap<>();
    private long minute = -1;

    ApiRateLimitFilter() { this(System::currentTimeMillis, 10_000); }
    ApiRateLimitFilter(LongSupplier clock, int capacity) { this.clock = clock; this.capacity = capacity; }

    private synchronized boolean accept(String key) {
        long now = clock.getAsLong() / 60_000;
        if (now != minute) { minute = now; requests.clear(); }
        int used = requests.getOrDefault(key, 0);
        if (used >= 300 || (used == 0 && requests.size() >= capacity)) return false;
        requests.put(key, used + 1);
        return true;
    }

    @Override public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!exchange.getRequest().getPath().value().startsWith("/api/")) return chain.filter(exchange);
        var remote = exchange.getRequest().getRemoteAddress();
        String anonymous = "anonymous:" + (remote == null || remote.getAddress() == null ? "unknown" : remote.getAddress().getHostAddress());
        return ReactiveSecurityContextHolder.getContext().flatMap(context -> Mono.justOrEmpty(context.getAuthentication()))
            .filter(Authentication::isAuthenticated).map(auth -> "subject:" + auth.getName())
            .defaultIfEmpty(anonymous).flatMap(key -> {
                if (accept(key)) return chain.filter(exchange);
                exchange.getResponse().getHeaders().set("Retry-After", "60");
                return ProblemResponses.write(exchange, 429);
            });
    }
}
