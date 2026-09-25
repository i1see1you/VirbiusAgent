package io.virbius.control.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Save-time lint for tool-catalog arg transforms (restrict / redact / truncate). */
public final class ArgTransformValidator {

    private static final Set<String> OPS = Set.of("restrict", "redact", "truncate");
    private static final Set<String> DETECTORS = Set.of("idcard_cn", "phone_cn", "email", "bank_card_cn");
    // Same grammar as arg_transform::parse_path in virbius-core: each segment
    // is a `.key` carrying zero or more `[n]` index suffixes, or a bare `[n]`
    // (which may lead).  The lone wildcard `$..*string` stays the only `..` form.
    private static final Pattern PATH =
            Pattern.compile("^\\$(?:\\.[A-Za-z0-9_@ -]+(?:\\[(?:0|[1-9]\\d*)])*|\\[(?:0|[1-9]\\d*)])+$"
                    + "|^\\$\\.\\.\\*string$");
    private static final int MAX = 64;
    private static final int MAX_MATCH = 256;
    private static final ObjectMapper JSON = new ObjectMapper();

    // Mirrors MemoryInterceptor::is_memory_write_tool in virbius-core
    // (memory_interceptor.rs); that runtime list is authoritative and also
    // honors sdk_config.memory_tool_patterns, which control cannot see.
    private static final List<String> MEMORY_WRITE_PREFIXES = List.of(
            "memory_save", "memory_write", "memory_store", "mem_save", "mem_write",
            "vector_store", "vector_write", "vector_add", "embedding_store",
            "embedding_add", "recall_save", "long_term_memory", "save_memory",
            "store_memory");

    static boolean isMemoryWrite(String toolName) {
        if (toolName == null) {
            return false;
        }
        String t = toolName.toLowerCase();
        return MEMORY_WRITE_PREFIXES.stream().anyMatch(t::startsWith);
    }

    private ArgTransformValidator() {}

    public static String normalize(String raw) {
        return normalize(raw, null);
    }

    /** Lint and normalize; {@code toolName} gates memory-write tool restrictions (nullable). */
    public static String normalize(String raw, String toolName) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        JsonNode root;
        try {
            root = JSON.readTree(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException("arg_transforms is not valid JSON");
        }
        JsonNode mutations;
        if (root.isArray()) {
            mutations = root;
        } else if (root.isObject()) {
            String phase = root.path("phase").asText("pre_tool_call");
            if (!"pre_tool_call".equals(phase)) {
                throw new IllegalArgumentException("arg_transforms.phase must be pre_tool_call");
            }
            mutations = root.path("mutations");
        } else {
            throw new IllegalArgumentException("arg_transforms must be an object or array");
        }
        if (!mutations.isArray() || mutations.isEmpty() || mutations.size() > MAX) {
            throw new IllegalArgumentException("arg_transforms.mutations must be a 1.." + MAX + " array");
        }
        Set<String> paths = new HashSet<>();
        for (JsonNode m : mutations) {
            validateOne(m, toolName);
            String path = m.path("path").asText();
            if (!paths.add(path)) {
                throw new IllegalArgumentException("duplicate arg_transforms path '" + path + "'");
            }
        }
        try {
            if (root.isArray()) {
                return JSON.writeValueAsString(
                        JSON.createObjectNode().put("phase", "pre_tool_call").set("mutations", mutations));
            }
            return JSON.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalArgumentException("arg_transforms serialize failed");
        }
    }

    private static void validateOne(JsonNode m, String toolName) {
        String path = m.path("path").asText("");
        String op = m.path("op").asText("");
        if (!PATH.matcher(path).matches()) {
            throw new IllegalArgumentException("arg_transforms path '" + path + "' is invalid");
        }
        if (!OPS.contains(op)) {
            throw new IllegalArgumentException("unknown arg_transforms op '" + op + "'");
        }
        // Memory is a trusted store (default policy: no memory desensitization).
        // redact/truncate on write tools would deposit [REDACTED] artifacts or
        // silently cut entries into the store — restrict stays allowed because
        // it narrows write authority, not content fidelity.
        if (isMemoryWrite(toolName) && !op.equals("restrict")) {
            throw new IllegalArgumentException(
                    "memory-write tools must not carry redact/truncate transforms (trusted-store integrity): " + path);
        }
        if ("$..*string".equals(path) && "restrict".equals(op)) {
            throw new IllegalArgumentException("scan path is only valid for redact/truncate");
        }
        switch (op) {
            case "truncate" -> {
                if (!m.path("max_len").isInt() || m.path("max_len").asInt() <= 0) {
                    throw new IllegalArgumentException("truncate requires max_len > 0");
                }
            }
            case "restrict" -> {
                if (m.path("to").isMissingNode() || m.path("to").isNull()) {
                    throw new IllegalArgumentException("restrict requires to");
                }
                String ov = m.path("on_violation").asText("clamp");
                if (!ov.equals("clamp")) {
                    throw new IllegalArgumentException("restrict.on_violation must be clamp");
                }
                JsonNode to = m.path("to");
                if (to.isObject() && to.has("domains")) {
                    throw new IllegalArgumentException("restrict.to.domains is removed; use to.match");
                }
                if (to.isObject() && !to.has("values") && !to.has("prefixes") && !to.has("match")
                        && (to.has("min") || to.has("max"))) {
                    // Mirror of the Rust parse guard: an all-null range is a
                    // silent no-op and min>max crashes the edge clamp.
                    boolean numMin = to.path("min").isNumber();
                    boolean numMax = to.path("max").isNumber();
                    if (!numMin && !numMax) {
                        throw new IllegalArgumentException(
                                "restrict.to range needs a numeric min or max");
                    }
                    if (numMin && numMax && to.path("min").asDouble() > to.path("max").asDouble()) {
                        throw new IllegalArgumentException("restrict.to.min must be <= max");
                    }
                }
                if (to.isObject() && to.has("match")) {
                    if (!to.path("match").isTextual()) {
                        throw new IllegalArgumentException("restrict.to.match must be a string");
                    }
                    String pat = to.path("match").asText();
                    if (pat.isEmpty() || pat.length() > MAX_MATCH) {
                        throw new IllegalArgumentException("restrict.to.match length must be 1.." + MAX_MATCH);
                    }
                    try {
                        Pattern.compile(pat);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("restrict.to.match is not a valid regex");
                    }
                }
            }
            case "redact" -> {
                Set<String> dets = new HashSet<>();
                if (m.has("detector") && !m.path("detector").asText("").isEmpty()) {
                    dets.add(m.path("detector").asText());
                }
                if (m.path("detectors").isArray()) {
                    for (JsonNode d : m.path("detectors")) {
                        dets.add(d.asText());
                    }
                }
                if (dets.isEmpty() || dets.stream().anyMatch(d -> !DETECTORS.contains(d))) {
                    throw new IllegalArgumentException("redact detector must be one of " + DETECTORS);
                }
            }
            default -> throw new IllegalArgumentException("unreachable");
        }
        if (m.has("name") && !m.path("name").isNull()) {
            JsonNode name = m.path("name");
            if (!name.isTextual() || name.asText().length() > 64) {
                throw new IllegalArgumentException("arg_transforms name must be a string of at most 64 chars");
            }
        }
        Set<String> allowed = Set.of(
                "path", "op", "to", "on_violation", "detector", "detectors", "max_len", "name");
        Iterator<String> fields = m.fieldNames();
        while (fields.hasNext()) {
            String f = fields.next();
            if (!allowed.contains(f)) {
                throw new IllegalArgumentException("unknown arg_transforms field '" + f + "'");
            }
        }
    }
}
