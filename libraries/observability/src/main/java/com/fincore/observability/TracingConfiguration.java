package com.fincore.observability;

import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods=false)
public class TracingConfiguration {
    @Bean(destroyMethod="close") Tracing tracing(Environment env) {
        return env.getProperty("TRACING_ENABLED",Boolean.class,false)
            ? Tracing.otlp(env.getRequiredProperty("spring.application.name"),env.getProperty("TRACING_ENDPOINT","http://jaeger:4318/v1/traces"))
            : Tracing.disabled();
    }
}
