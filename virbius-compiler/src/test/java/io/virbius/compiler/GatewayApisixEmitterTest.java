package io.virbius.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatewayApisixEmitterTest {

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void emitsSingleEntryRoute() throws Exception {
        Path bundle =
                Path.of("examples/poc-default-bundle.yaml").toAbsolutePath().normalize();
        if (!Files.exists(bundle)) {
            bundle = Path.of("../examples/poc-default-bundle.yaml").toAbsolutePath().normalize();
        }
        JsonNode root = yaml.readTree(Files.readString(bundle));
        Path gw = tempDir.resolve("gateway");
        int count = GatewayApisixEmitter.emitRoutes(root, gw, json);
        assertEquals(1, count);
        JsonNode route = json.readTree(Files.readString(gw.resolve("apisix-routes-v1-chat-completions.json")));
        assertEquals("/v1/chat/completions", route.path("uri").asText());
        assertTrue(route.path("plugins").path("virbius-guard").path("scene_registry_file").isTextual());
        assertTrue(route.path("plugins").path("virbius-guard").path("dynamic_scene").isMissingNode());
        assertTrue(route.path("vars").isMissingNode());
    }
}
