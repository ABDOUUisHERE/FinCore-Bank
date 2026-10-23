package com.fincore.support;

import com.fasterxml.jackson.databind.cfg.*;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.*;

@Configuration
@Import(WebSupport.class)
public class SupportConfiguration {
    @Bean Jackson2ObjectMapperBuilderCustomizer strictText(){return builder->builder.postConfigurer(mapper->{
        var config=mapper.coercionConfigFor(LogicalType.Textual);
        for(var shape:new CoercionInputShape[]{CoercionInputShape.Integer,CoercionInputShape.Float,CoercionInputShape.Boolean})config.setCoercion(shape,CoercionAction.Fail);
    });}
}
