package io.virbius.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SceneRegistryTest {

    @Test
    void resolvesClinicalSceneByQuery() {
        Map<String, Object> metadata = sampleMetadata();
        SceneRegistry reg = SceneRegistry.parse(metadata);
        var resolved = reg.resolve("medical-prod", "/v1/chat/completions", Map.of("mode", "clinical"), Map.of());
        assertTrue(resolved.isPresent());
        assertEquals("medical-prod_clinical", resolved.get().sceneId());
    }

    @Test
    void defaultsToChatScene() {
        Map<String, Object> metadata = sampleMetadata();
        SceneRegistry reg = SceneRegistry.parse(metadata);
        var resolved = reg.resolve("beta", "/v1/chat/completions", Map.of(), Map.of());
        assertEquals("beta_chat", resolved.get().sceneId());
    }

    @Test
    void rejectsSceneHeaderInMatch() {
        Map<String, Object> metadata = sampleMetadata();
        Map<String, Object> scenes = new LinkedHashMap<>(sampleScenes());
        scenes.put(
                "bad",
                Map.of(
                        "app_id",
                        "beta",
                        "uris",
                        List.of("/x"),
                        "match",
                        Map.of("headers", Map.of("X-Virbius-Scene", "x"))));
        metadata.put(
                "scene_registry",
                Map.of("version", 1, "scenes", scenes, "fail_on_unknown_app", false, "fail_on_unresolved_scene", false));
        SceneRegistry reg = SceneRegistry.parse(metadata);
        assertThrows(IllegalArgumentException.class, () -> SceneRegistry.validate(reg));
    }

    private static Map<String, Object> sampleMetadata() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("scene_registry", Map.of("version", 1, "scenes", sampleScenes()));
        return metadata;
    }

    private static Map<String, Object> sampleScenes() {
        Map<String, Object> scenes = new LinkedHashMap<>();
        scenes.put(
                "beta_chat",
                Map.of("app_id", "beta", "default", true, "uris", List.of("/v1/chat/completions"), "priority", 0));
        scenes.put(
                "medical-prod_clinical",
                Map.of(
                        "app_id",
                        "medical-prod",
                        "uris",
                        List.of("/v1/chat/completions"),
                        "priority",
                        10,
                        "match",
                        Map.of("query", Map.of("mode", "clinical"))));
        scenes.put(
                "medical-prod_chat",
                Map.of("app_id", "medical-prod", "default", true, "uris", List.of("/v1/chat/completions"), "priority", 0));
        return scenes;
    }
}
