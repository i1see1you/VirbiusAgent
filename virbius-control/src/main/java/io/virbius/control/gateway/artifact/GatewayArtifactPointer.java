package io.virbius.control.gateway.artifact;

import java.util.LinkedHashMap;
import java.util.Map;

public record GatewayArtifactPointer(
        String tenantId,
        long artifactRevision,
        String publishedAt,
        String bundleId,
        String bundleVersion,
        String schemaVersion,
        String storage,
        String accessListsKey,
        String sceneRegistryKey,
        String accessListsUrl,
        String sceneRegistryUrl,
        String accessListsSha256,
        String sceneRegistrySha256,
        long accessListsBytes,
        long sceneRegistryBytes,
        String publishId,
        String trigger) {

    public Map<String, String> toRedisHash() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("artifact_revision", String.valueOf(artifactRevision));
        out.put("published_at", publishedAt);
        if (bundleId != null) {
            out.put("bundle_id", bundleId);
        }
        if (bundleVersion != null) {
            out.put("bundle_version", bundleVersion);
        }
        out.put("schema_version", schemaVersion != null ? schemaVersion : "2");
        out.put("storage", storage);
        if (accessListsKey != null) {
            out.put("access_lists_key", accessListsKey);
        }
        if (sceneRegistryKey != null) {
            out.put("scene_registry_key", sceneRegistryKey);
        }
        if (accessListsUrl != null) {
            out.put("access_lists_url", accessListsUrl);
        }
        if (sceneRegistryUrl != null) {
            out.put("scene_registry_url", sceneRegistryUrl);
        }
        out.put("access_lists_sha256", accessListsSha256);
        out.put("scene_registry_sha256", sceneRegistrySha256);
        out.put("access_lists_bytes", String.valueOf(accessListsBytes));
        out.put("scene_registry_bytes", String.valueOf(sceneRegistryBytes));
        if (publishId != null) {
            out.put("publish_id", publishId);
        }
        if (trigger != null) {
            out.put("trigger", trigger);
        }
        return out;
    }

    public static GatewayArtifactPointer fromRedisHash(String tenantId, Map<String, String> hash) {
        if (hash == null || hash.isEmpty()) {
            return null;
        }
        String rev = hash.get("artifact_revision");
        if (rev == null || rev.isBlank()) {
            return null;
        }
        return new GatewayArtifactPointer(
                tenantId,
                Long.parseLong(rev),
                hash.get("published_at"),
                hash.get("bundle_id"),
                hash.get("bundle_version"),
                hash.get("schema_version"),
                hash.get("storage"),
                hash.get("access_lists_key"),
                hash.get("scene_registry_key"),
                hash.get("access_lists_url"),
                hash.get("scene_registry_url"),
                hash.get("access_lists_sha256"),
                hash.get("scene_registry_sha256"),
                parseLong(hash.get("access_lists_bytes")),
                parseLong(hash.get("scene_registry_bytes")),
                hash.get("publish_id"),
                hash.get("trigger"));
    }

    private static long parseLong(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        return Long.parseLong(raw);
    }
}
