package io.virbius.policy;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class ValueResolver {

    public static final int MAX_VALUE_LENGTH = 256;

    private ValueResolver() {}

    public static Optional<String> resolve(String dimension, ValueSource override, MatchContext ctx) {
        if (override != null && override.kind() != ValueSourceKind.DEFAULT) {
            return normalize(resolveSource(override, ctx));
        }
        if (dimension == null || dimension.isBlank()) {
            return Optional.empty();
        }
        return normalize(resolveDimension(dimension.trim(), ctx));
    }

    private static String resolveSource(ValueSource src, MatchContext ctx) {
        return switch (src.kind()) {
            case LITERAL -> src.literalValue();
            case REQUEST_FIELD -> resolveRequestField(src.ref(), ctx);
            case VAR -> ctx.varsOrEmpty().get(src.ref());
            case HEADER -> header(ctx.headers(), src.ref());
            case QUERY -> ctx.query().get(src.ref());
            case CONTENT -> ctx.content();
            default -> null;
        };
    }

    private static String resolveDimension(String dimension, MatchContext ctx) {
        if (dimension.startsWith("var:")) {
            return ctx.varsOrEmpty().get(dimension.substring(4));
        }
        return switch (dimension) {
            case "user_id" -> ctx.userId();
            case "device_id" -> ctx.deviceId();
            case "ip", "ip_cidr" -> ctx.clientIp();
            case "session_id" -> ctx.sessionId();
            case "keyword", "content" -> ctx.content();
            case "var" -> null;
            default -> resolveRequestField(dimension, ctx);
        };
    }

    private static String resolveRequestField(String field, MatchContext ctx) {
        if (field == null || field.isBlank()) {
            return null;
        }
        return switch (field) {
            case "user_id" -> ctx.userId();
            case "device_id" -> ctx.deviceId();
            case "client_ip", "ip" -> ctx.clientIp();
            case "session_id" -> ctx.sessionId();
            case "content" -> ctx.content();
            default -> null;
        };
    }

    private static String header(Map<String, String> headers, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (headers.containsKey(lower)) {
            return headers.get(lower);
        }
        return headers.get(name);
    }

    private static Optional<String> normalize(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String t = raw.trim();
        if (t.isEmpty() || t.length() > MAX_VALUE_LENGTH) {
            return Optional.empty();
        }
        return Optional.of(t);
    }

    public static String encodeRedisKeySegment(String value) {
        return value.replace(":", "%3A").replace(" ", "%20");
    }
}
