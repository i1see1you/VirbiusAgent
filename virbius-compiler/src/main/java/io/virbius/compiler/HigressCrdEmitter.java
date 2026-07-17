package io.virbius.compiler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Emits Higress CRD YAML for MCP routing and WASM plugin configuration.
 *
 * <p>Generates three CRD types:
 * <ul>
 *   <li>{@code McpBridge} — registers upstream MCP Servers</li>
 *   <li>{@code McpServer} — defines MCP routes with WASM plugin attachment</li>
 *   <li>{@code WasmPlugin} — configures the virbius-gateway WASM plugin</li>
 * </ul>
 *
 * <p>These CRDs are consumed by the Higress controller, which translates them
 * to Envoy xDS configuration for connection-less hot updates.
 */
public final class HigressCrdEmitter {

    private HigressCrdEmitter() {}

    /**
     * Emit all Higress CRD files to the given directory.
     *
     * @param root       the rule bundle JSON root
     * @param outputDir  target directory (usually {@code gateway/higress/})
     * @param json       Jackson ObjectMapper (for JSON parsing)
     * @return number of CRD documents emitted
     */
    public static int emit(JsonNode root, Path outputDir, ObjectMapper json) throws IOException {
        Files.createDirectories(outputDir);
        int count = 0;

        // Collect MCP routes from bundle
        List<McpRoute> routes = collectMcpRoutes(root);

        // 1. Emit McpBridge CRDs (one per upstream MCP Server)
        for (McpRoute route : routes) {
            Map<String, Object> bridge = buildMcpBridge(route);
            Path file = outputDir.resolve("mcp-bridge-" + route.name + ".yaml");
            writeYaml(file, bridge, json);
            count++;
        }

        // 2. Emit McpServer CRDs (one per route)
        for (McpRoute route : routes) {
            Map<String, Object> server = buildMcpServer(route);
            Path file = outputDir.resolve("mcp-server-" + route.name + ".yaml");
            writeYaml(file, server, json);
            count++;
        }

        // 3. Emit WasmPlugin CRD (single, tenant-scoped)
        Map<String, Object> wasmPlugin = buildWasmPlugin(root);
        Path wasmFile = outputDir.resolve("wasm-plugin-virbius.yaml");
        writeYaml(wasmFile, wasmPlugin, json);
        count++;

        return count;
    }

    /**
     * Build the effective WASM plugin configuration JSON from the bundle.
     *
     * <p>This is also used by the control plane to generate the {@code defaultConfig}
     * section of the WasmPlugin CRD.
     */
    public static Map<String, Object> buildPluginConfig(JsonNode root) {
        JsonNode gateway = root.path("gateway");
        JsonNode virbius = gateway.path("virbius");
        JsonNode cloudScan = gateway.path("cloud_scan");

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("tenant_id", root.path("tenant_id").asText("default"));
        config.put("evaluate", virbius.path("evaluate").asBoolean(true));
        config.put("engine_url", cloudScan.path("agent_url").asText("http://virbius-engine:8082"));
        config.put("engine_timeout_ms", virbius.path("timeout_ms").asInt(cloudScan.path("timeout_ms").asInt(3000)));
        config.put("tool_rate_limit", virbius.path("tool_rate_limit").asInt(50));
        config.put("fail_mode", virbius.path("fail_mode").asText("open"));

        // Fast path tools
        List<String> fastPath = new ArrayList<>();
        JsonNode fpNode = virbius.path("fast_path_tools");
        if (fpNode.isArray()) {
            fpNode.forEach(v -> fastPath.add(v.asText()));
        }
        config.put("fast_path_tools", fastPath);

        // Tool allowlist
        List<String> allowlist = new ArrayList<>();
        JsonNode alNode = virbius.path("tool_allowlist");
        if (alNode.isArray()) {
            alNode.forEach(v -> allowlist.add(v.asText()));
        }
        config.put("tool_allowlist", allowlist);

        // Expression rules (pre-compiled IR from control plane)
        // Supports two locations: gateway.virbius.expressions (rule bundle) or top-level expressions (gateway snapshot)
        JsonNode exprNode = virbius.path("expressions");
        if (exprNode.isMissingNode() || !exprNode.isArray() || exprNode.size() == 0) {
            exprNode = root.path("expressions");
        }
        if (exprNode.isArray() && exprNode.size() > 0) {
            List<Map<String, Object>> expressions = new ArrayList<>();
            for (JsonNode exprEntry : exprNode) {
                expressions.add(jsonNodeToMap(exprEntry));
            }
            config.put("expressions", expressions);
        }

        config.put("license_verify", virbius.path("license_verify").asBoolean(true));
        config.put("tls", virbius.path("tls").asBoolean(true));

        return config;
    }

    // --- CRD Builders ---

    private static Map<String, Object> buildMcpBridge(McpRoute route) {
        Map<String, Object> bridge = new LinkedHashMap<>();
        bridge.put("apiVersion", "networking.higress.io/v1");
        bridge.put("kind", "McpBridge");

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", "mcp-bridge-" + route.name);
        Map<String, Object> labels = new LinkedHashMap<>();
        labels.put("app", "virbius");
        labels.put("mcp-server", route.name);
        metadata.put("labels", labels);
        bridge.put("metadata", metadata);

        Map<String, Object> spec = new LinkedHashMap<>();
        List<Map<String, Object>> registries = new ArrayList<>();
        Map<String, Object> registry = new LinkedHashMap<>();
        registry.put("name", route.name);
        registry.put("type", "static");
        registry.put("domain", route.host);
        registry.put("port", route.port);
        registries.add(registry);
        spec.put("registries", registries);
        bridge.put("spec", spec);

        return bridge;
    }

    private static Map<String, Object> buildMcpServer(McpRoute route) {
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("apiVersion", "networking.higress.io/v1");
        server.put("kind", "McpServer");

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", "mcp-server-" + route.name);
        Map<String, Object> labels = new LinkedHashMap<>();
        labels.put("app", "virbius");
        labels.put("mcp-server", route.name);
        metadata.put("labels", labels);
        server.put("metadata", metadata);

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("bridgeRef", "mcp-bridge-" + route.name);
        spec.put("pathPrefix", route.pathPrefix);

        List<Map<String, Object>> wasmPlugins = new ArrayList<>();
        Map<String, Object> plugin = new LinkedHashMap<>();
        plugin.put("name", "virbius-gateway");
        wasmPlugins.add(plugin);
        spec.put("wasmPlugins", wasmPlugins);

        server.put("spec", spec);

        return server;
    }

    private static Map<String, Object> buildWasmPlugin(JsonNode root) {
        String tenantId = root.path("tenant_id").asText("default");

        Map<String, Object> plugin = new LinkedHashMap<>();
        plugin.put("apiVersion", "networking.higress.io/v1");
        plugin.put("kind", "WasmPlugin");

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", "virbius-gateway");
        Map<String, Object> labels = new LinkedHashMap<>();
        labels.put("app", "virbius");
        labels.put("tenant", tenantId);
        metadata.put("labels", labels);
        plugin.put("metadata", metadata);

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("phase", "AUTHN");
        spec.put("url", "oci://registry.internal/virbius-gateway:latest");
        spec.put("defaultConfig", buildPluginConfig(root));
        plugin.put("spec", spec);

        return plugin;
    }

    // --- Route Collection ---

    /**
     * Collect MCP routes from the rule bundle's gateway configuration.
     */
    private static List<McpRoute> collectMcpRoutes(JsonNode root) {
        List<McpRoute> routes = new ArrayList<>();
        JsonNode gateway = root.path("gateway");
        JsonNode routesNode = gateway.path("routes");

        if (routesNode.isArray()) {
            for (JsonNode r : routesNode) {
                String name = r.path("name").asText("default");
                String uri = r.path("uri").asText("/");
                String upstream = r.path("upstream").asText("");

                McpRoute route = parseRoute(name, uri, upstream);
                if (route != null) {
                    routes.add(route);
                }
            }
        }

        // If no routes found, emit a default route
        if (routes.isEmpty()) {
            JsonNode upstream = gateway.path("upstream");
            String host = upstream.path("host").asText("mcp-server.default.svc.cluster.local");
            int port = upstream.path("port").asInt(8080);
            routes.add(new McpRoute("default", host, port, "/mcp"));
        }

        return routes;
    }

    private static McpRoute parseRoute(String name, String uri, String upstream) {
        // Parse upstream format: "host:port" or just "host"
        String host;
        int port;
        if (upstream.contains(":")) {
            String[] parts = upstream.split(":");
            host = parts[0];
            port = Integer.parseInt(parts[1]);
        } else if (!upstream.isEmpty()) {
            host = upstream;
            port = 8080;
        } else {
            return null;
        }

        String pathPrefix = uri.isEmpty() ? "/mcp/" + name : uri;
        return new McpRoute(name, host, port, pathPrefix);
    }

    // --- Helpers ---

    /**
     * Recursively convert a JsonNode tree to a plain Java Map/List structure
     * suitable for YAML serialization.
     */
    private static Object jsonNodeToValue(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isTextual()) return node.asText();
        if (node.isBoolean()) return node.asBoolean();
        if (node.isInt()) return node.asInt();
        if (node.isLong()) return node.asLong();
        if (node.isFloat() || node.isDouble()) return node.asDouble();
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonNode child : node) {
                list.add(jsonNodeToValue(child));
            }
            return list;
        }
        if (node.isObject()) {
            return jsonNodeToMap(node);
        }
        return node.asText();
    }

    private static Map<String, Object> jsonNodeToMap(JsonNode node) {
        Map<String, Object> map = new LinkedHashMap<>();
        Iterator<String> fields = node.fieldNames();
        while (fields.hasNext()) {
            String key = fields.next();
            map.put(key, jsonNodeToValue(node.get(key)));
        }
        return map;
    }

	// --- YAML Writer ---

	private static void writeYaml(Path file, Map<String, Object> doc, ObjectMapper json) throws IOException {
        ObjectMapper yaml = new ObjectMapper(
                new YAMLFactory()
                        .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
                        .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER));
        yaml.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), doc);
    }

    // --- DTO ---

    private record McpRoute(String name, String host, int port, String pathPrefix) {}
}
