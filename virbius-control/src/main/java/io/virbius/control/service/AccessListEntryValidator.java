package io.virbius.control.service;

import io.virbius.control.domain.AccessListMetaDimension;
import java.util.Map;

public final class AccessListEntryValidator {

    private AccessListEntryValidator() {}

    public static String normalizeAndValidate(String dimension, String raw, Map<String, Object> bundleMetadata) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("value required");
        }
        String v = raw.trim();
        if (v.contains("=")) {
            throw new IllegalArgumentException(
                    "list entry must be a plain value; use script listMatch(name, ctx.var('logical'))");
        }
        AccessListMetaDimension.validate(dimension);
        return v;
    }
}
