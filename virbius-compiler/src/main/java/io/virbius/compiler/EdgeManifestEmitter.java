package io.virbius.compiler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.policy.EdgeManifestFilter;
import io.virbius.policy.SceneRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Emits per-app edge SDK manifests ({@code rules[]}, {@code sdk_config}, legacy {@code lists}). */
public final class EdgeManifestEmitter {

    private EdgeManifestEmitter() {}

    public static Map<String, Object> buildManifest(JsonNode root) {
        return buildManifest(root, null);
    }

    public static Map<String, Object> buildManifest(JsonNode root, String appId) {
        String tenantId = root.path("tenant_id").asText("default");
        SceneRegistry registry = SceneRegistry.parse(metadataMap(root));
        List<Map<String, Object>> allRules = collectEdgeRules(root);
        List<Map<String, Object>> allDlpRules = collectDlpRules(root);
        List<Map<String, Object>> rules =
                appId == null ? allRules : filterRulesForApp(allRules, appId, registry);
        List<Map<String, Object>> dlpRules =
                appId == null ? allDlpRules : filterRulesForApp(allDlpRules, appId, registry);

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("tenant_id", tenantId);
        manifest.put("manifest_version", "1");
        manifest.put("bundle_id", root.path("bundle_id").asText("poc-default"));
        manifest.put("bundle_version", root.path("version").asText("0.1.0"));
        if (appId != null) {
            manifest.put("app_id", appId);
        }
        manifest.put("rules", rules);
        manifest.put("dlp_rules", dlpRules);
        manifest.put("sdk_config", defaultSdkConfig(root));
        return manifest;
    }

    /** Writes {@code {output}/{tenant}/{app_id}/edge-manifest.json} for each app; tenant-wide if no apps. */
    public static Map<String, Path> writeAll(Path outputDir, JsonNode root, ObjectMapper json) throws IOException {
        String tenantId = root.path("tenant_id").asText("default");
        SceneRegistry registry = SceneRegistry.parse(metadataMap(root));
        List<Map<String, Object>> allRules = collectEdgeRules(root);
        List<Map<String, Object>> scopes = scopesFromRules(root);
        List<String> appIds = EdgeManifestFilter.collectAppIds(registry, scopes);

        Map<String, Path> written = new LinkedHashMap<>();
        if (appIds.isEmpty()) {
            throw new IllegalStateException(
                    "scene_registry with at least one app_id required to emit edge manifests");
        }
        for (String appId : appIds) {
            Path dir = outputDir.resolve(tenantId).resolve(appId);
            Files.createDirectories(dir);
            Path file = dir.resolve("edge-manifest.json");
            json.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), buildManifest(root, appId));
            written.put(appId, file);
        }
        return written;
    }

    public static void write(Path outputDir, JsonNode root, ObjectMapper json) throws IOException {
        writeAll(outputDir, root, json);
    }

    public static List<Map<String, Object>> collectEdgeRules(JsonNode root) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        JsonNode rules = root.get("rules");
        if (rules == null || !rules.isArray()) {
            return blocks;
        }
        for (JsonNode rule : rules) {
            if (!"edge".equalsIgnoreCase(rule.path("layer").asText(""))) {
                continue;
            }
            if ("dlp-dsl".equalsIgnoreCase(rule.path("runtime").asText(""))) {
                continue;
            }
            String rolloutState = rule.path("rollout_state").asText("dry_run");
            if (!inExecutionPlane(rolloutState)) {
                continue;
            }
            blocks.add(toRuleBlock(rule, rolloutState));
        }
        blocks.sort((a, b) -> String.valueOf(a.get("rule_id")).compareTo(String.valueOf(b.get("rule_id"))));
        return blocks;
    }

    public static List<Map<String, Object>> collectDlpRules(JsonNode root) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        JsonNode rules = root.get("rules");
        if (rules == null || !rules.isArray()) {
            return blocks;
        }
        for (JsonNode rule : rules) {
            if (!"edge".equalsIgnoreCase(rule.path("layer").asText(""))) {
                continue;
            }
            if (!"dlp-dsl".equalsIgnoreCase(rule.path("runtime").asText(""))) {
                continue;
            }
            String rolloutState = rule.path("rollout_state").asText("dry_run");
            if (!inExecutionPlane(rolloutState)) {
                continue;
            }
            blocks.add(toDlpRuleBlock(rule, rolloutState));
        }
        blocks.sort((a, b) -> String.valueOf(a.get("rule_id")).compareTo(String.valueOf(b.get("rule_id"))));
        return blocks;
    }

    private static Map<String, Object> toDlpRuleBlock(JsonNode rule, String rolloutState) {
        Map<String, Object> block = toRuleBlock(rule, rolloutState);
        block.put("intent_action", "allow");
        block.put("risk_score", 0);
        return block;
    }

    static List<Map<String, Object>> filterRulesForApp(
            List<Map<String, Object>> rules, String appId, SceneRegistry registry) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> rule : rules) {
            @SuppressWarnings("unchecked")
            Map<String, Object> scope = rule.get("scope") instanceof Map<?, ?> m
                    ? (Map<String, Object>) m
                    : Map.of();
            if (EdgeManifestFilter.includesForApp(scope, appId, registry)) {
                out.add(rule);
            }
        }
        return out;
    }

    private static Map<String, Object> toRuleBlock(JsonNode rule, String rolloutState) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("rule_id", rule.path("rule_id").asText());
        block.put("rule_revision", rule.path("rule_revision").asInt(1));
        block.put("reason_code", rule.path("reason_code").asText(""));
        block.put("risk_score", rule.path("risk_score").asInt(100));
        block.put("intent_action", rule.path("intent_action").asText("deny"));
        block.put("enforce_mode", enforceMode(rolloutState));
        block.put("rollout_state", rolloutState);
        if ("canary".equalsIgnoreCase(rolloutState) && rule.has("canary_percent")) {
            block.put("canary_percent", rule.get("canary_percent").asInt());
        }
        if (rule.has("body")) {
            JsonNode body = rule.get("body");
            block.put("body", body.isTextual() ? body.asText() : jsonToMap(body));
        }
        if (rule.has("scope") && rule.get("scope").isObject()) {
            block.put("scope", jsonToMap(rule.get("scope")));
        }
        return block;
    }

    private static List<Map<String, Object>> scopesFromRules(JsonNode root) {
        List<Map<String, Object>> scopes = new ArrayList<>();
        JsonNode rules = root.get("rules");
        if (rules == null || !rules.isArray()) {
            return scopes;
        }
        for (JsonNode rule : rules) {
            if (!"edge".equalsIgnoreCase(rule.path("layer").asText(""))) {
                continue;
            }
            if (rule.has("scope") && rule.get("scope").isObject()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> scope = (Map<String, Object>) jsonToMap(rule.get("scope"));
                scopes.add(scope);
            } else {
                scopes.add(Map.of());
            }
        }
        return scopes;
    }

    private static Map<String, Object> defaultSdkConfig(JsonNode root) {
        Map<String, Object> sdk = new LinkedHashMap<>();
        JsonNode cfg = root.get("sdk_config");
        if (cfg != null && cfg.isObject()) {
            cfg.fields().forEachRemaining(e -> sdk.put(e.getKey(), jsonToMap(e.getValue())));
        }
        sdk.putIfAbsent("audit_sample_rate_allow", 0.1);
        sdk.putIfAbsent("audit_sample_rate_hit", 1.0);
        sdk.putIfAbsent("audit_flush_interval_ms", 30000);
        sdk.putIfAbsent("audit_queue_max", 500);
        sdk.putIfAbsent("canary_session_key", "device_id");
        sdk.putIfAbsent("dlp_vault_ttl_ms", 1_800_000L);
        return sdk;
    }

    private static boolean inExecutionPlane(String rolloutState) {
        if (rolloutState == null || rolloutState.isBlank()) {
            return false;
        }
        return switch (rolloutState.toLowerCase()) {
            case "dry_run", "canary", "full" -> true;
            default -> false;
        };
    }

    private static String enforceMode(String rolloutState) {
        return switch (rolloutState.toLowerCase()) {
            case "canary" -> "canary";
            case "full" -> "full";
            default -> "dry_run";
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> metadataMap(JsonNode root) {
        Object raw = jsonToMap(root);
        if (raw instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private static Object jsonToMap(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isValueNode()) {
            if (node.isInt()) {
                return node.asInt();
            }
            if (node.isDouble() || node.isFloat()) {
                return node.asDouble();
            }
            if (node.isBoolean()) {
                return node.asBoolean();
            }
            return node.asText();
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            node.forEach(n -> list.add(jsonToMap(n)));
            return list;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        node.fields().forEachRemaining(e -> map.put(e.getKey(), jsonToMap(e.getValue())));
        return map;
    }
}
