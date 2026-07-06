package io.virbius.control.gateway;

import io.virbius.control.domain.dto.request.SceneEntryInput;
import io.virbius.control.domain.dto.request.SceneRegistryRequest;
import io.virbius.policy.SceneRegistry;
import io.virbius.policy.SceneRegistry.SceneEntry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SceneRegistryHelper {

    private SceneRegistryHelper() {}

    public static SceneRegistry parseRegistry(Map<String, Object> metadata) {
        return SceneRegistry.parse(metadata);
    }

    public static SceneRegistry fromRequest(SceneRegistryRequest body) {
        if (body == null || body.scenes() == null || body.scenes().isEmpty()) {
            throw new IllegalArgumentException("scenes required");
        }
        List<SceneEntry> entries = new ArrayList<>();
        for (SceneEntryInput row : body.scenes()) {
            if (row == null || row.sceneId() == null || row.sceneId().isBlank()) {
                continue;
            }
            if (row.appId() == null || row.appId().isBlank()) {
                throw new IllegalArgumentException("app_id required for scene " + row.sceneId());
            }
            entries.add(new SceneEntry(
                    row.sceneId().trim(),
                    row.appId().trim(),
                    Boolean.TRUE.equals(row.defaultScene()),
                    row.uris() != null ? row.uris() : List.of(),
                    row.priority() != null ? row.priority() : 0,
                    row.matchQuery() != null ? row.matchQuery() : Map.of(),
                    row.matchHeaders() != null ? row.matchHeaders() : Map.of()));
        }
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("scenes required");
        }
        boolean failUnknown = body.failOnUnknownApp() != null && body.failOnUnknownApp();
        boolean failUnresolved = body.failOnUnresolvedScene() != null && body.failOnUnresolvedScene();
        return new SceneRegistry(failUnknown, failUnresolved, entries);
    }

    public static void validate(SceneRegistry registry) {
        SceneRegistry.validate(registry);
    }

    public static Map<String, Object> registryBlock(Map<String, Object> metadata) {
        SceneRegistry reg = parseRegistry(metadata);
        if (reg.scenes().isEmpty()) {
            return Map.of();
        }
        return reg.toMetadataBlock();
    }

    public static List<Map<String, Object>> sceneRows(Map<String, Object> metadata) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SceneEntry e : parseRegistry(metadata).scenes()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("scene_id", e.sceneId());
            row.put("app_id", e.appId());
            row.put("default", e.defaultScene());
            row.put("uris", e.uris());
            row.put("priority", e.priority());
            if (!e.matchQuery().isEmpty() || !e.matchHeaders().isEmpty()) {
                Map<String, Object> match = new LinkedHashMap<>();
                if (!e.matchQuery().isEmpty()) {
                    match.put("query", e.matchQuery());
                }
                if (!e.matchHeaders().isEmpty()) {
                    match.put("headers", e.matchHeaders());
                }
                row.put("match", match);
            }
            out.add(row);
        }
        return out;
    }

    public static void putRegistryMetadata(Map<String, Object> metadata, SceneRegistry registry) {
        metadata.put("scene_registry", registry.toMetadataBlock());
    }

    public static List<String> mergeScopeScenes(SceneRegistry registry, Map<String, Object> metadata) {
        LinkedHashMap<String, String> ordered = new LinkedHashMap<>();
        for (String id : registry.sceneIds()) {
            ordered.put(id, id);
        }
        SceneRegistry existing = parseRegistry(metadata);
        for (String id : existing.sceneIds()) {
            ordered.putIfAbsent(id, id);
        }
        return List.copyOf(ordered.keySet());
    }
}
