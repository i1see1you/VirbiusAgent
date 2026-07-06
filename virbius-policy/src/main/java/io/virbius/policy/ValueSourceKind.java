package io.virbius.policy;

public enum ValueSourceKind {
    DEFAULT,
    REQUEST_FIELD,
    VAR,
    HEADER,
    QUERY,
    CONTENT,
    LITERAL;

    public static ValueSourceKind parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT;
        }
        return switch (raw.trim().toLowerCase()) {
            case "request_field" -> REQUEST_FIELD;
            case "var" -> VAR;
            case "header" -> HEADER;
            case "query" -> QUERY;
            case "content" -> CONTENT;
            case "literal" -> LITERAL;
            default -> DEFAULT;
        };
    }
}
