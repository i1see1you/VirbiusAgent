package io.virbius.compiler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Merge Global / Service / Route virbius plugin config (Route > Service > Global). */
final class VirbiusConfigMerger {

    static final int SCHEMA_VERSION = 1;

    private VirbiusConfigMerger() {}

    record MergeResult(ObjectNode effective, ObjectNode mergeTrace) {}

    enum DeployLayout {
        /** {@code {deployRoot}/{bundleVersion}/gateway/...} */
        STAGED,
        /** {@code {deployRoot}/gateway/...} — virbius-control {@code VIRBIUS_DATA_DIR} */
        CONTROL_DATA
    }

    static DeployLayout parseLayout(String raw) {
        if (raw == null) {
            return DeployLayout.STAGED;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "control-data", "control", "data" -> DeployLayout.CONTROL_DATA;
            default -> DeployLayout.STAGED;
        };
    }

    static String materializeBase(String deployRoot, String bundleVersion) {
        String root = deployRoot.replaceAll("/+$", "");
        if (root.endsWith("/" + bundleVersion) || root.endsWith(bundleVersion)) {
            return root;
        }
        return root + "/" + bundleVersion;
    }

    static String gatewayDir(String deployRoot, String bundleVersion, DeployLayout layout) {
        String root = deployRoot.replaceAll("/+$", "");
        if (layout == DeployLayout.CONTROL_DATA) {
            return root + "/gateway";
        }
        return materializeBase(deployRoot, bundleVersion) + "/gateway";
    }

    static MergeResult mergeRoute(
            JsonNode root,
            JsonNode routeEntry,
            String deployRoot,
            String tenantId,
            String bundleVersion,
            DeployLayout layout) {
        JsonNode gateway = gateway(root);
        ObjectMapper json = new ObjectMapper();
        String gatewayDir = gatewayDir(deployRoot, bundleVersion, layout);

        Map<String, String> globalLayer = virbiusLayer(gateway.path("global").path("virbius"));
        applyGatewayTopDefaults(globalLayer, gateway);

        Map<String, String> serviceLayer = virbiusLayer(gateway.path("service").path("virbius"));
        applyServiceDefaults(serviceLayer, gateway, tenantId, gatewayDir, bundleVersion);

        Map<String, String> routeLayer = virbiusLayer(routeEntry.path("virbius"));
        if (routeEntry.has("evaluate")) {
            routeLayer.put("evaluate", String.valueOf(routeEntry.path("evaluate").asBoolean()));
        }

        Map<String, String> effective = new LinkedHashMap<>();
        effective.putAll(globalLayer);
        effective.putAll(serviceLayer);
        effective.putAll(routeLayer);

        ensureDefaults(effective, gateway, tenantId, gatewayDir, bundleVersion);

        ObjectNode mergeTrace = json.createObjectNode();
        for (Map.Entry<String, String> e : effective.entrySet()) {
            ObjectNode field = mergeTrace.putObject(e.getKey());
            field.put("global", globalLayer.getOrDefault(e.getKey(), ""));
            field.put("service", serviceLayer.getOrDefault(e.getKey(), ""));
            field.put("route", routeLayer.getOrDefault(e.getKey(), ""));
            field.put("effective", e.getValue());
        }

        ObjectNode out = json.createObjectNode();
        for (Map.Entry<String, String> e : effective.entrySet()) {
            putTyped(out, e.getKey(), e.getValue());
        }
        return new MergeResult(out, mergeTrace);
    }

    static JsonNode resolveUpstream(JsonNode root, JsonNode routeEntry) {
        JsonNode gateway = gateway(root);
        if (routeEntry.has("upstream") && !routeEntry.get("upstream").isNull()) {
            return routeEntry.get("upstream");
        }
        JsonNode service = gateway.path("service");
        if (service.has("upstream") && !service.get("upstream").isNull()) {
            return service.get("upstream");
        }
        if (gateway.has("upstream") && !gateway.get("upstream").isNull()) {
            return gateway.get("upstream");
        }
        return defaultUpstream();
    }

    private static JsonNode gateway(JsonNode root) {
        JsonNode gateway = root.get("gateway");
        if (gateway != null && !gateway.isMissingNode()) {
            return gateway;
        }
        return root.path("bundle").path("gateway");
    }

    private static Map<String, String> virbiusLayer(JsonNode node) {
        Map<String, String> out = new LinkedHashMap<>();
        if (node == null || node.isMissingNode() || !node.isObject()) {
            return out;
        }
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            if (e.getValue().isNull()) {
                continue;
            }
            out.put(e.getKey(), scalar(e.getValue()));
        }
        return out;
    }

    private static void applyGatewayTopDefaults(Map<String, String> globalLayer, JsonNode gateway) {
        if (gateway.has("evaluate")) {
            globalLayer.putIfAbsent("evaluate", String.valueOf(gateway.path("evaluate").asBoolean(true)));
        }
        if (gateway.has("fail_mode")) {
            globalLayer.putIfAbsent("fail_mode", gateway.path("fail_mode").asText());
        }
        if (gateway.has("sse_mode")) {
            globalLayer.putIfAbsent("sse_mode", gateway.path("sse_mode").asText());
        }
        if (gateway.has("auth_mode")) {
            globalLayer.putIfAbsent("auth_mode", gateway.path("auth_mode").asText());
        }
    }

    private static void applyServiceDefaults(
            Map<String, String> serviceLayer,
            JsonNode gateway,
            String tenantId,
            String gatewayDir,
            String bundleVersion) {
        serviceLayer.putIfAbsent("tenant_id", tenantId);
        serviceLayer.putIfAbsent("bundle_version", bundleVersion);
        serviceLayer.putIfAbsent("lists_file", gatewayDir + "/" + tenantId + "-access-lists.json");
        serviceLayer.putIfAbsent("scene_registry_file", gatewayDir + "/scene-registry-" + tenantId + ".json");
        JsonNode cloudScan = gateway.path("cloud_scan");
        if (cloudScan.has("agent_url")) {
            serviceLayer.putIfAbsent("agent_url", cloudScan.path("agent_url").asText());
        }
        if (cloudScan.has("timeout_ms")) {
            serviceLayer.putIfAbsent("timeout_ms", String.valueOf(cloudScan.path("timeout_ms").asInt()));
        }
        if (gateway.has("fail_mode")) {
            serviceLayer.putIfAbsent("fail_mode", gateway.path("fail_mode").asText());
        }
    }

    private static void ensureDefaults(
            Map<String, String> effective,
            JsonNode gateway,
            String tenantId,
            String gatewayDir,
            String bundleVersion) {
        effective.putIfAbsent("evaluate", "true");
        effective.putIfAbsent("fail_mode", "open");
        effective.putIfAbsent("sse_mode", "pass-through");
        effective.putIfAbsent("auth_mode", "optional");
        effective.putIfAbsent("tenant_id", tenantId);
        effective.putIfAbsent("bundle_version", bundleVersion);
        effective.putIfAbsent("lists_file", gatewayDir + "/" + tenantId + "-access-lists.json");
        effective.putIfAbsent("scene_registry_file", gatewayDir + "/scene-registry-" + tenantId + ".json");
        if (!effective.containsKey("agent_url")) {
            JsonNode cloudScan = gateway.path("cloud_scan");
            if (cloudScan.has("agent_url")) {
                effective.put("agent_url", cloudScan.path("agent_url").asText());
            } else {
                effective.put("agent_url", "http://127.0.0.1:9070");
            }
        }
        if (!effective.containsKey("timeout_ms")) {
            JsonNode cloudScan = gateway.path("cloud_scan");
            effective.put(
                    "timeout_ms",
                    String.valueOf(cloudScan.path("timeout_ms").asInt(3000)));
        }
    }

    private static void putTyped(ObjectNode out, String key, String value) {
        if ("evaluate".equals(key)) {
            out.put(key, Boolean.parseBoolean(value));
            return;
        }
        if ("timeout_ms".equals(key)) {
            out.put(key, Integer.parseInt(value));
            return;
        }
        if (value.isEmpty()) {
            out.putNull(key);
            return;
        }
        out.put(key, value);
    }

    private static String scalar(JsonNode node) {
        if (node.isBoolean()) {
            return String.valueOf(node.asBoolean());
        }
        if (node.isNumber()) {
            return node.asText();
        }
        return node.asText("");
    }

    private static JsonNode defaultUpstream() {
        ObjectMapper json = new ObjectMapper();
        ObjectNode upstream = json.createObjectNode();
        upstream.put("id", "virbius-upstream-default");
        ArrayNode nodes = upstream.putArray("nodes");
        ObjectNode node = nodes.addObject();
        node.put("host", "127.0.0.1");
        node.put("port", 11434);
        node.put("weight", 1);
        return upstream;
    }

    static String routeKeyFromUri(String uri) {
        String key = uri.replace('/', '-').replaceAll("^-", "");
        if (key.isBlank()) {
            return "root";
        }
        return key;
    }

    static String upstreamId(JsonNode upstream, String fallback) {
        if (upstream != null && upstream.has("id") && !upstream.get("id").asText("").isBlank()) {
            return upstream.get("id").asText();
        }
        return fallback;
    }
}
