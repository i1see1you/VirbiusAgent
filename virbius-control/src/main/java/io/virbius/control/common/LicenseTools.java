package io.virbius.control.common;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Normalization for AgentLicense {@code allowed_tools}.
 *
 * <p>Semantics contract (shared by virbius-core, the engine and the MCP proxy):
 * an <b>empty</b> list means "all tools allowed" (License wildcard). The
 * control-plane persists the list as a comma-separated column, so an empty
 * list must round-trip as an empty list — {@code "".split(",")} yields
 * {@code [""]} in Java, which would flip the wildcard into "deny every tool".
 */
public final class LicenseTools {

    private LicenseTools() {}

    /** Trim, drop blanks and duplicates, preserving input order. Null-safe. */
    public static List<String> normalize(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String value : raw) {
            if (value == null) {
                continue;
            }
            String trimmed = value.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out.isEmpty() ? List.of() : List.copyOf(new ArrayList<>(out));
    }

    /** Parse the persisted comma-separated column. Blank/absent means empty (wildcard). */
    public static List<String> parseCsv(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return normalize(List.of(raw.split(",", -1)));
    }

    /** Serialize for persistence; empty list is stored as an empty string. */
    public static String toCsv(List<String> tools) {
        return String.join(",", normalize(tools));
    }
}
