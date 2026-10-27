package com.fincore.gateway.configuration;

import java.time.Duration;
import org.springframework.cloud.gateway.config.HttpClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Container addresses can change while the public gateway remains running. */
@Configuration(proxyBeanMethods=false)
public class UpstreamResolution {
    @Bean
    HttpClientCustomizer boundedUpstreamDnsCache() {
        return client -> client.resolver(spec -> spec
            .cacheMinTimeToLive(Duration.ZERO)
            .cacheMaxTimeToLive(Duration.ofSeconds(5))
            .cacheNegativeTimeToLive(Duration.ZERO));
    }
}
