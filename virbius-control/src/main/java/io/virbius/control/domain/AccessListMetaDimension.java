package io.virbius.control.domain;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Validates tb_access_list_meta.dimension (script-rules model). */
public final class AccessListMetaDimension {

    private static final Pattern BUILTIN =
            Pattern.compile("^(keyword|user_id|device_id|ip_cidr)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern VAR = Pattern.compile("^var:([a-zA-Z_][a-zA-Z0-9_]*)$");

    private AccessListMetaDimension() {}

    public static String validate(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("dimension required");
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
            throw new IllegalArgumentException(
                    "dimension=var is deprecated; use var:logical (e.g. var:app_id) and plain entry values");
        }
        throw new IllegalArgumentException(
                "invalid list dimension: " + d + " (expected keyword|user_id|device_id|ip_cidr|var:logical)");
    }

    public static boolean isVar(String dimension) {
        return dimension != null && dimension.startsWith("var:");
    }

    public static Optional<String> varLogical(String dimension) {
        if (!isVar(dimension)) {
            return Optional.empty();
        }
        String logical = dimension.substring(4).trim();
        return logical.isEmpty() ? Optional.empty() : Optional.of(logical);
    }
}
