package io.virbius.policy;

import com.fasterxml.jackson.databind.JsonNode;

public record ValueSource(ValueSourceKind kind, String ref, String literalValue) {

    public static ValueSource fromJson(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return null;
        }
        ValueSourceKind kind = ValueSourceKind.parse(text(node, "kind"));
        String ref = text(node, "ref");
        String value = text(node, "value");
        if (kind == ValueSourceKind.LITERAL && value != null) {
            return new ValueSource(kind, null, value);
        }
        if (kind == ValueSourceKind.DEFAULT) {
            return null;
        }
        return new ValueSource(kind, ref, value);
    }

    private static String text(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return n != null && !n.isNull() ? n.asText() : null;
    }
}
