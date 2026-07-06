package io.virbius.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VirbiusConfigMergerTest {

    private final ObjectMapper json = new ObjectMapper();
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    @Test
    void routeOverridesServiceAndGlobal() throws Exception {
        ObjectNode root = json.createObjectNode();
        root.put("tenant_id", "default");
        root.put("version", "0.1.0");
        ObjectNode gateway = root.putObject("gateway");
        gateway.put("evaluate", true);
        gateway.put("fail_mode", "open");
        ObjectNode global = gateway.putObject("global").putObject("virbius");
        global.put("sse_mode", "pass-through");
        ObjectNode service = gateway.putObject("service").putObject("virbius");
        service.put("evaluate", true);
        service.put("agent_url", "http://127.0.0.1:9070");
        ObjectNode route = json.createObjectNode();
        route.put("uri", "/v1/chat/completions");
        ObjectNode routeVirbius = route.putObject("virbius");
        routeVirbius.put("evaluate", false);

        VirbiusConfigMerger.MergeResult merged =
                VirbiusConfigMerger.mergeRoute(root, route, "/etc/virbius", "default", "0.1.0", VirbiusConfigMerger.DeployLayout.STAGED);

        assertFalse(merged.effective().path("evaluate").asBoolean());
        assertEquals("open", merged.effective().path("fail_mode").asText());
        assertEquals("pass-through", merged.effective().path("sse_mode").asText());
        assertEquals("http://127.0.0.1:9070", merged.effective().path("agent_url").asText());
    }

    @Test
    void controlDataLayoutPointsAtDataGateway() throws Exception {
        ObjectNode root = json.createObjectNode();
        root.put("tenant_id", "default");
        root.put("version", "0.1.0");
        root.putObject("gateway").put("evaluate", true);
        ObjectNode route = json.createObjectNode();
        route.put("uri", "/v1/chat/completions");

        VirbiusConfigMerger.MergeResult merged = VirbiusConfigMerger.mergeRoute(
                root,
                route,
                "/tmp/virbius-data",
                "default",
                "0.1.0",
                VirbiusConfigMerger.DeployLayout.CONTROL_DATA);

        assertEquals(
                "/tmp/virbius-data/gateway/default-access-lists.json",
                merged.effective().path("lists_file").asText());
        assertEquals(
                "/tmp/virbius-data/gateway/scene-registry-default.json",
                merged.effective().path("scene_registry_file").asText());
    }

    @Test
    void emitsOpenrestyArtifactsFromBundle(@TempDir Path tempDir) throws Exception {
        Path bundle =
                Path.of("examples/poc-default-bundle.yaml").toAbsolutePath().normalize();
        if (!Files.exists(bundle)) {
            bundle = Path.of("../examples/poc-default-bundle.yaml").toAbsolutePath().normalize();
        }
        JsonNode root = yaml.readTree(Files.readString(bundle));
        Path gw = tempDir.resolve("gateway");
        int count = GatewayOpenrestyEmitter.emit(
                root, gw, json, tempDir.toString(), VirbiusConfigMerger.DeployLayout.STAGED);
        assertEquals(1, count);
        assertTrue(Files.exists(gw.resolve("openresty/manifest.json")));
        assertTrue(Files.exists(gw.resolve("openresty/locations.conf")));
        assertTrue(Files.exists(gw.resolve("openresty/upstreams.conf")));
        assertTrue(Files.exists(gw.resolve("openresty/effective-v1-chat-completions.json")));
        JsonNode effective =
                json.readTree(Files.readString(gw.resolve("openresty/effective-v1-chat-completions.json")));
        assertTrue(effective.path("virbius").path("evaluate").asBoolean());
        assertTrue(Files.readString(gw.resolve("openresty/locations.conf")).contains("access_by_lua_file"));
    }

    @Test
    void prefixUriRendersCaretLocation() {
        assertEquals("location ^~ /v1/chat/", GatewayOpenrestyEmitter.renderLocationDirective("/v1/chat/*"));
        assertEquals("location = /v1/chat/completions", GatewayOpenrestyEmitter.renderLocationDirective("/v1/chat/completions"));
    }
}
