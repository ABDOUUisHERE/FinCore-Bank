package com.fincore.ledger.configuration;

import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JsonConfiguration {
    @Bean Jackson2ObjectMapperBuilderCustomizer strictDecimals() {
        return builder -> builder.postConfigurer(mapper -> {
            var text = mapper.coercionConfigFor(LogicalType.Textual);
            for (var shape : new CoercionInputShape[]{CoercionInputShape.Integer, CoercionInputShape.Float, CoercionInputShape.Boolean}) {
                text.setCoercion(shape, CoercionAction.Fail);
            }
        });
    }
}
