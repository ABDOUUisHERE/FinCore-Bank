package com.fincore.gateway.configuration;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import reactor.core.publisher.Mono;
import static org.assertj.core.api.Assertions.assertThat;

class ApiRateLimitFilterTest {
    private MockServerWebExchange request(ApiRateLimitFilter filter, String subject, String forged) {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/customers/me")
            .header("X-User-ID", forged).header("X-Forwarded-For", forged));
        Mono<Void> result = filter.filter(exchange, next -> { next.getResponse().setStatusCode(HttpStatus.OK); return next.getResponse().setComplete(); });
        if (subject != null) result = result.contextWrite(ReactiveSecurityContextHolder.withAuthentication(new TestingAuthenticationToken(subject, "unused", "ROLE_CUSTOMER")));
        result.block();
        return exchange;
    }

    @Test void oneVerifiedCustomerCannotConsumeAnotherCustomersBudget() {
        var filter = new ApiRateLimitFilter(() -> 1_000L, 10_000);
        for (int i = 0; i < 300; i++) assertThat(request(filter, "customer-a", "ignored").getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
        var limited = request(filter, "customer-a", "customer-b");
        assertThat(limited.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limited.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("60");
        assertThat(request(filter, "customer-b", "customer-a").getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test void unverifiedIdentityAndForwardingHeadersCannotCreateNewBudgets() {
        var filter = new ApiRateLimitFilter(() -> 1_000L, 10_000);
        for (int i = 0; i < 300; i++) assertThat(request(filter, null, "forged-" + i).getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(request(filter, null, "another-forgery").getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(request(filter, "verified", "ignored").getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test void bucketStorageIsBoundedAndBudgetsRenewAtTheNextMinute() {
        var clock = new AtomicLong(1_000L);
        var filter = new ApiRateLimitFilter(clock::get, 2);
        assertThat(request(filter, "a", "ignored").getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(request(filter, "b", "ignored").getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(request(filter, "c", "ignored").getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(request(filter, "a", "ignored").getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
        clock.set(60_000L);
        assertThat(request(filter, "c", "ignored").getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
