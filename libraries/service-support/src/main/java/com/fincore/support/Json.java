package com.fincore.support;

import com.fasterxml.jackson.databind.*;

public final class Json {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private Json() {}
    public static String text(Object value) {
        try { return MAPPER.writeValueAsString(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalArgumentException("Invalid JSON", error); }
    }
    public static JsonNode read(String value) {
        try { return MAPPER.readTree(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalArgumentException("Invalid JSON", error); }
    }
}
