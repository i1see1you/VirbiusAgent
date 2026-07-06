package io.virbius.compiler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/** Emits APISIX route JSON from Bundle metadata {@code gateway.routes}. */
final class GatewayApisixEmitter {

    private GatewayApisixEmitter() {}

    static int emitRoutes(JsonNode root, Path gatewayDir, ObjectMapper json) throws IOException {
        JsonNode gateway = root.get("gateway");
        if (gateway == null || gateway.isMissingNode()) {
            gateway = root.path("bundle").path("gateway");
        }
        JsonNode routes = gateway.path("routes");
        if (!routes.isArray() || routes.isEmpty()) {
            return 0;
        }
        Files.createDirectories(gatewayDir);
        String tenantId = root.path("tenant_id").asText("default");
        String bundleVersion = root.path("version").asText("0.1.0");
        boolean evaluateDefault = gateway.path("evaluate").asBoolean(true);
        String serviceId = "virbius-svc-" + tenantId;

        int count = 0;
        Set<String> emittedUris = new LinkedHashSet<>();
        for (JsonNode route : routes) {
            String uri = route.path("uri").asText("/v1/chat/completions");
            if (!emittedUris.add(uri)) {
                continue;
            }
            String routeKey = uri.replace('/', '-').replaceAll("^-", "");
            if (routeKey.isBlank()) {
                routeKey = "root";
            }
            ObjectNode doc = json.createObjectNode();
            doc.put("id", "virbius-route-" + routeKey);
            doc.put("name", routeKey + "-entry");
            doc.put("desc", "generated from bundle gateway.routes uri=" + uri);
            doc.put("uri", uri);
            doc.put("service_id", serviceId);
            ArrayNode methods = doc.putArray("methods");
            JsonNode methodsNode = route.get("methods");
            if (methodsNode != null && methodsNode.isArray() && !methodsNode.isEmpty()) {
                methodsNode.forEach(m -> methods.add(m.asText()));
            } else {
                methods.add("POST");
            }
            ObjectNode plugins = doc.putObject("plugins");
            ObjectNode guard = plugins.putObject("virbius-guard");
            guard.put("evaluate", route.has("evaluate") ? route.path("evaluate").asBoolean() : evaluateDefault);
            guard.put("bundle_version", bundleVersion);
            guard.put("tenant_id", tenantId);
            guard.put(
                    "lists_file",
                    "/usr/local/apisix/conf/virbius/data/gateway/" + tenantId + "-access-lists.json");
            guard.put(
                    "scene_registry_file",
                    "/usr/local/apisix/conf/virbius/data/gateway/" + tenantId + "-scene-registry.json");

            Path out = gatewayDir.resolve("apisix-routes-" + routeKey + ".json");
            json.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), doc);
            count++;
        }
        return count;
    }

    static void emitService(JsonNode root, Path gatewayDir, ObjectMapper json) throws IOException {
        JsonNode gateway = root.get("gateway");
        if (gateway == null || gateway.isMissingNode()) {
            gateway = root.path("bundle").path("gateway");
        }
        JsonNode routes = gateway.path("routes");
        if (!routes.isArray() || routes.isEmpty()) {
            return;
        }
        String tenantId = root.path("tenant_id").asText("default");
        String bundleVersion = root.path("version").asText("0.1.0");
        Files.createDirectories(gatewayDir);

        ObjectNode doc = json.createObjectNode();
        doc.put("id", "virbius-svc-" + tenantId);
        doc.put("name", "virbius-" + tenantId + "-llm");
        doc.put("desc", "generated from bundle metadata tenant=" + tenantId);

        ObjectNode upstream = doc.putObject("upstream");
        upstream.put("type", "roundrobin");
        ObjectNode nodes = upstream.putObject("nodes");
        nodes.put("mock-llm.internal:443", 1);

        ObjectNode plugins = doc.putObject("plugins");
        ObjectNode guard = plugins.putObject("virbius-guard");
        guard.put("bundle_version", bundleVersion);
        guard.put("evaluate", gateway.path("evaluate").asBoolean(true));
        guard.put("tenant_id", tenantId);
        if (gateway.has("fail_mode")) {
            guard.put("fail_mode", gateway.path("fail_mode").asText());
        }
        JsonNode cloudScan = gateway.path("cloud_scan");
        if (cloudScan.has("agent_url")) {
            guard.put("agent_url", cloudScan.path("agent_url").asText());
        }
        guard.put(
                "lists_file",
                "/usr/local/apisix/conf/virbius/data/gateway/" + tenantId + "-access-lists.json");
        guard.put(
                "scene_registry_file",
                "/usr/local/apisix/conf/virbius/data/gateway/" + tenantId + "-scene-registry.json");

        Path out = gatewayDir.resolve("apisix-service-" + tenantId + ".json");
        json.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), doc);
    }

    static void emitSceneRegistry(JsonNode root, Path gatewayDir, ObjectMapper json) throws IOException {
        JsonNode reg = root.path("scene_registry");
        if (reg.isMissingNode() || reg.isEmpty()) {
            reg = root.path("bundle").path("scene_registry");
        }
        if (reg.isMissingNode() || reg.isEmpty()) {
            return;
        }
        Files.createDirectories(gatewayDir);
        String tenantId = root.path("tenant_id").asText("default");
        ObjectNode doc = json.createObjectNode();
        doc.put("tenant_id", tenantId);
        doc.set("scene_registry", reg);
        Path out = gatewayDir.resolve("scene-registry-" + tenantId + ".json");
        json.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), doc);
    }
}
