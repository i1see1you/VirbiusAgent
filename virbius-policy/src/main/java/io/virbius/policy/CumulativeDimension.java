package io.virbius.policy;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Validates tb_cumulative.dimension. See docs/DESIGN.md §8.5.0.1 */
public final class CumulativeDimension {

    private static final Pattern BUILTIN =
            Pattern.compile("^(user_id|device_id|ip|session_id|keyword)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern VAR = Pattern.compile("^var:([a-zA-Z_][a-zA-Z0-9_]*)$");

    private CumulativeDimension() {}

    public static String validate(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("cumulative dimension required");
        }
        String d = raw.trim();
        if (BUILTIN.matcher(d).matches()) {
            return d.toLowerCase(Locale.ROOT);
        }
        var m = VAR.matcher(d);
        if (m.matches()) {
            return "var:" + m.group(1);
        }
        if ("var".equalsIgnoreCase(d)) {
            throw new IllegalArgumentException("var dimension requires logical name, e.g. var:app_id");
        }
        if ("app_id".equalsIgnoreCase(d)) {
            throw new IllegalArgumentException("use var:app_id with context_bindings instead of app_id");
        }
        throw new IllegalArgumentException(
                "invalid cumulative dimension: " + d + " (expected user_id|device_id|ip|session_id|keyword|var:logical)");
    }

    public static boolean isVar(String dimension) {
        return dimension != null && dimension.startsWith("var:");
    }

    public static Optional<String> varLogical(String dimension) {
        if (dimension == null || !dimension.startsWith("var:")) {
            return Optional.empty();
        }
        String logical = dimension.substring(4).trim();
        return logical.isEmpty() ? Optional.empty() : Optional.of(logical);
    }
}
