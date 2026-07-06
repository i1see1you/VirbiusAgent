package io.virbius.control.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

final class JsonHelper {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonHelper() {}

    static String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("json encode failed", e);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> mapFromJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of("_raw", json);
        }
    }

    static Object bodyFromJson(String json) {
        if (json == null) {
            return null;
        }
        String trimmed = json.trim();
        if (trimmed.isEmpty()) {
            return json;
        }
        try {
            if (trimmed.startsWith("\"") || trimmed.startsWith("{") || trimmed.startsWith("[")) {
                return MAPPER.readValue(trimmed, Object.class);
            }
            return json;
        } catch (Exception e) {
            return json;
        }
    }
}