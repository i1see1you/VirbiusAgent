package io.virbius.control.gateway;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.virbius.policy.SceneRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GatewayUriCoverageTest {

    @Test
    void acceptsSceneUriUnderGatewayPrefix() {
        Map<String, Object> metadata = Map.of(
                "scene_registry",
                Map.of(
                        "version",
                        1,
                        "scenes",
                        Map.of(
                                "beta_chat",
                                Map.of(
                                        "app_id",
                                        "beta",
                                        "default",
                                        true,
                                        "uris",
                                        List.of("/v1/chat/completions")))));
        SceneRegistry reg = SceneRegistry.parse(metadata);
        assertDoesNotThrow(() -> GatewayUriCoverage.validateSceneRegistryUris(List.of("/v1/chat/*"), reg));
    }

    @Test
    void rejectsSceneUriOutsideGateway() {
        Map<String, Object> metadata = Map.of(
                "scene_registry",
                Map.of(
                        "version",
                        1,
                        "scenes",
                        Map.of(
                                "beta_chat",
                                Map.of(
                                        "app_id",
                                        "beta",
                                        "default",
                                        true,
                                        "uris",
                                        List.of("/v1/embeddings")))));
        SceneRegistry reg = SceneRegistry.parse(metadata);
        assertThrows(
                IllegalArgumentException.class,
                () -> GatewayUriCoverage.validateSceneRegistryUris(List.of("/v1/chat/*"), reg));
    }
}
