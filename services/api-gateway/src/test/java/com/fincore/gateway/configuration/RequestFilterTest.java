package com.fincore.gateway.configuration;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import static org.assertj.core.api.Assertions.*;

class RequestFilterTest {
    @Test void generatedCorrelationIsForwardedAndReturnedOnceAfterOwnerResponseHeaders() {
        var exchange=MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/admin/customers/test").build());
        String[] forwarded=new String[1];
        new RequestFilter().filter(exchange, downstream->{
            forwarded[0]=downstream.getRequest().getHeaders().getFirst("X-Correlation-ID");
            downstream.getResponse().getHeaders().add("X-Correlation-ID",forwarded[0]);
            return downstream.getResponse().setComplete();
        }).block();
        assertThat(forwarded[0]).matches("[0-9a-f-]{36}");
        assertThat(exchange.getResponse().getHeaders().get("X-Correlation-ID")).containsExactly(forwarded[0]);
    }
    @Test void validCallerCorrelationSurvivesAndInvalidInputIsReplacedWithoutDuplicateValues() {
        for(String incoming:List.of("caller-correlation","PRIVATE INVALID HEADER")) {
            var exchange=MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/admin/customers/test").header("X-Correlation-ID",incoming).build());
            String[] forwarded=new String[1];
            new RequestFilter().filter(exchange,downstream->{
                forwarded[0]=downstream.getRequest().getHeaders().getFirst("X-Correlation-ID");
                downstream.getResponse().getHeaders().add("X-Correlation-ID",forwarded[0]);
                downstream.getResponse().getHeaders().add("X-Correlation-ID","unexpected-owner-value");
                return downstream.getResponse().setComplete();
            }).block();
            if(incoming.equals("caller-correlation"))assertThat(forwarded[0]).isEqualTo(incoming);
            else assertThat(forwarded[0]).matches("[0-9a-f-]{36}");
            assertThat(exchange.getResponse().getHeaders().get("X-Correlation-ID")).containsExactly(forwarded[0]);
        }
    }
}
