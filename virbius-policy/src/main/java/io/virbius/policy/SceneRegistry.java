package io.virbius.policy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Parses bundle {@code scene_registry} and resolves {@code (app_id, uri, match) → scene_id}. */
public final class SceneRegistry {

    public record SceneEntry(
            String sceneId,
            String appId,
            boolean defaultScene,
            List<String> uris,
            int priority,
            Map<String, String> matchQuery,
            Map<String, String> matchHeaders) {}

    public record ResolvedScene(String sceneId, String source) {}

    private final boolean failOnUnknownApp;
    private final boolean failOnUnresolvedScene;
    private final List<SceneEntry> scenes;

    public SceneRegistry(boolean failOnUnknownApp, boolean failOnUnresolvedScene, List<SceneEntry> scenes) {
        this.failOnUnknownApp = failOnUnknownApp;
        this.failOnUnresolvedScene = failOnUnresolvedScene;
        this.scenes = List.copyOf(scenes);
    }

    public List<SceneEntry> scenes() {
        return scenes;
    }

    public boolean hasScene(String sceneId) {
        if (sceneId == null || sceneId.isBlank()) {
            return false;
        }
        return scenes.stream().anyMatch(e -> sceneId.equals(e.sceneId()));
    }

    public boolean failOnUnknownApp() {
        return failOnUnknownApp;
    }

    public boolean failOnUnresolvedScene() {
        return failOnUnresolvedScene;
    }

    @SuppressWarnings("unchecked")
    public static SceneRegistry parse(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return empty();
        }
        Object block = metadata.get("scene_registry");
        if (!(block instanceof Map<?, ?> root)) {
            return empty();
        }
        boolean failUnknown = Boolean.TRUE.equals(root.get("fail_on_unknown_app"));
        boolean failUnresolved = Boolean.TRUE.equals(root.get("fail_on_unresolved_scene"));
        Object scenesObj = root.get("scenes");
        if (!(scenesObj instanceof Map<?, ?> scenesMap)) {
            return new SceneRegistry(failUnknown, failUnresolved, List.of());
        }
        List<SceneEntry> entries = new ArrayList<>();
        for (Map.Entry<?, ?> e : scenesMap.entrySet()) {
            if (e.getKey() == null || !(e.getValue() instanceof Map<?, ?> row)) {
                continue;
            }
            String sceneId = String.valueOf(e.getKey()).trim();
            Map<String, Object> m = (Map<String, Object>) row;
            String appId = str(m.get("app_id"));
            if (sceneId.isEmpty() || appId.isEmpty()) {
                continue;
            }
            boolean def = Boolean.TRUE.equals(m.get("default"));
            List<String> uris = stringList(m.get("uris"));
            int priority = intVal(m.get("priority"), 0);
            Map<String, String> matchQuery = matchMap(m.get("match"), "query");
            Map<String, String> matchHeaders = matchMap(m.get("match"), "headers");
            entries.add(new SceneEntry(sceneId, appId, def, uris, priority, matchQuery, matchHeaders));
        }
        return new SceneRegistry(failUnknown, failUnresolved, entries);
    }

    public static void validate(SceneRegistry registry) {
        if (registry == null || registry.scenes.isEmpty()) {
            throw new IllegalArgumentException("scene_registry.scenes required");
        }
        Set<String> sceneIds = new LinkedHashSet<>();
        Map<String, Integer> defaultCount = new LinkedHashMap<>();
        for (SceneEntry e : registry.scenes) {
            if (!e.sceneId().matches("[a-z][a-z0-9_-]*")) {
                throw new IllegalArgumentException("invalid scene_id: " + e.sceneId());
            }
            if (!e.appId().matches("[a-z][a-z0-9_-]*")) {
                throw new IllegalArgumentException("invalid app_id: " + e.appId());
            }
            if (sceneIds.contains(e.sceneId())) {
                throw new IllegalArgumentException("duplicate scene_id: " + e.sceneId());
            }
            sceneIds.add(e.sceneId());
            if (e.defaultScene()) {
                defaultCount.merge(e.appId(), 1, Integer::sum);
            }
            for (Map.Entry<String, String> h : e.matchHeaders().entrySet()) {
                if ("X-Virbius-Scene".equalsIgnoreCase(h.getKey())) {
                    throw new IllegalArgumentException("match.headers must not use X-Virbius-Scene");
                }
            }
        }
        for (Map.Entry<String, Integer> dc : defaultCount.entrySet()) {
            if (dc.getValue() > 1) {
                throw new IllegalArgumentException("multiple default scenes for app: " + dc.getKey());
            }
        }
        Set<String> apps = new LinkedHashSet<>();
        for (SceneEntry e : registry.scenes) {
            apps.add(e.appId());
        }
        for (String app : apps) {
            boolean hasDefault = registry.scenes.stream().anyMatch(s -> s.appId().equals(app) && s.defaultScene());
            if (!hasDefault) {
                throw new IllegalArgumentException("app missing default scene: " + app);
            }
        }
    }

    public Optional<ResolvedScene> resolve(
            String appId,
            String routeUri,
            Map<String, String> query,
            Map<String, String> headers) {
        if (appId == null || appId.isBlank()) {
            return Optional.empty();
        }
        String app = appId.trim();
        boolean knownApp = scenes.stream().anyMatch(s -> s.appId().equals(app));
        if (!knownApp) {
            return failOnUnknownApp ? Optional.empty() : defaultForApp(app);
        }
        String uri = BindScope.normalizeUri(routeUri);
        List<SceneEntry> candidates = new ArrayList<>();
        for (SceneEntry e : scenes) {
            if (!e.appId().equals(app)) {
                continue;
            }
            if (e.uris().isEmpty()) {
                continue;
            }
            if (uri == null) {
                continue;
            }
            boolean uriHit = false;
            for (String pattern : e.uris()) {
                if (BindScope.uriMatches(uri, pattern)) {
                    uriHit = true;
                    break;
                }
            }
            if (!uriHit) {
                continue;
            }
            if (!matchAll(e.matchQuery(), query) || !matchAll(e.matchHeaders(), headers)) {
                continue;
            }
            candidates.add(e);
        }
        if (!candidates.isEmpty()) {
            candidates.sort(Comparator.comparingInt(SceneEntry::priority).reversed());
            return Optional.of(new ResolvedScene(candidates.get(0).sceneId(), "rule"));
        }
        Optional<ResolvedScene> def = defaultForApp(app);
        if (def.isPresent()) {
            return def;
        }
        return failOnUnresolvedScene ? Optional.empty() : Optional.empty();
    }

    private Optional<ResolvedScene> defaultForApp(String app) {
        for (SceneEntry e : scenes) {
            if (e.appId().equals(app) && e.defaultScene()) {
                return Optional.of(new ResolvedScene(e.sceneId(), "default"));
            }
        }
        return Optional.empty();
    }

    public List<String> sceneIds() {
        return scenes.stream().map(SceneEntry::sceneId).toList();
    }

    public List<String> appIds() {
        return scenes.stream().map(SceneEntry::appId).distinct().toList();
    }

    public Map<String, Object> toMetadataBlock() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", 1);
        root.put("fail_on_unknown_app", failOnUnknownApp);
        root.put("fail_on_unresolved_scene", failOnUnresolvedScene);
        Map<String, Object> scenesMap = new LinkedHashMap<>();
        for (SceneEntry e : scenes) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("app_id", e.appId());
            if (e.defaultScene()) {
                row.put("default", true);
            }
            if (!e.uris().isEmpty()) {
                row.put("uris", e.uris());
            }
            if (e.priority() != 0) {
                row.put("priority", e.priority());
            }
            if (!e.matchQuery().isEmpty() || !e.matchHeaders().isEmpty()) {
                Map<String, Object> match = new LinkedHashMap<>();
                if (!e.matchQuery().isEmpty()) {
                    match.put("query", new LinkedHashMap<>(e.matchQuery()));
                }
                if (!e.matchHeaders().isEmpty()) {
                    match.put("headers", new LinkedHashMap<>(e.matchHeaders()));
                }
                row.put("match", match);
            }
            scenesMap.put(e.sceneId(), row);
        }
        root.put("scenes", scenesMap);
        return root;
    }

    public static SceneRegistry empty() {
        return new SceneRegistry(false, false, List.of());
    }

    private static boolean matchAll(Map<String, String> expected, Map<String, String> actual) {
        if (expected == null || expected.isEmpty()) {
            return true;
        }
        if (actual == null) {
            return false;
        }
        for (Map.Entry<String, String> e : expected.entrySet()) {
            String got = actual.get(e.getKey());
            if (got == null || !got.equals(e.getValue())) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> matchMap(Object match, String key) {
        if (!(match instanceof Map<?, ?> m)) {
            return Map.of();
        }
        Object inner = m.get(key);
        if (!(inner instanceof Map<?, ?> hm)) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        hm.forEach((k, v) -> {
            if (k != null && v != null) {
                out.put(String.valueOf(k), String.valueOf(v));
            }
        });
        return out;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static int intVal(Object o, int def) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o == null) {
            return def;
        }
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object o) {
        if (!(o instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                String s = String.valueOf(item).trim();
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        }
        return out;
    }
}
