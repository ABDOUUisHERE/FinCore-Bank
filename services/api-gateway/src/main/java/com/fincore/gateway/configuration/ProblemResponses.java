package com.fincore.gateway.configuration;

import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

final class ProblemResponses {
    private ProblemResponses() {}

    static Mono<Void> write(ServerWebExchange exchange, int status) {
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.valueOf(status));
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (status == 401) response.getHeaders().set("WWW-Authenticate", "Bearer");
        String id = response.getHeaders().getFirst("X-Correlation-ID");
        String title = HttpStatus.valueOf(status).getReasonPhrase();
        String body = "{\"type\":\"about:blank\",\"title\":\"" + title + "\",\"status\":" + status
            + ",\"detail\":\"The request could not be accepted.\",\"code\":\"HTTP_" + status
            + "\",\"correlationId\":\"" + (id == null ? "unavailable" : id) + "\"}";
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
    }
}
