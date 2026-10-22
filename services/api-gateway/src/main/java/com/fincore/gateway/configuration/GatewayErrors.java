package com.fincore.gateway.configuration;

import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
@Order(-2)
public class GatewayErrors implements ErrorWebExceptionHandler {
    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable error) {
        if (exchange.getResponse().isCommitted()) return Mono.error(error);
        Throwable root = error;
        for (int depth = 0; root.getCause() != null && depth < 10; depth++) root = root.getCause();
        int status = error instanceof ResponseStatusException response ? response.getStatusCode().value()
            : root instanceof java.util.concurrent.TimeoutException ? 504
            : root instanceof java.io.IOException ? 503 : 500;
        return ProblemResponses.write(exchange, status);
    }
}
