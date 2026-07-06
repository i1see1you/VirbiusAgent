package io.virbius.control.service;

import java.util.LinkedHashMap;
import java.util.Map;

public record EngineArtifactPointer(
        String tenantId,
        long artifactRevision,
        String contentSha256,
        String policyVersion,
        String publishedAt,
        String trigger) {

    public Map<String, String> toRedisHash() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("artifact_revision", String.valueOf(artifactRevision));
        out.put("content_sha256", contentSha256 != null ? contentSha256 : "");
        out.put("policy_version", policyVersion != null ? policyVersion : "");
        out.put("published_at", publishedAt != null ? publishedAt : "");
        out.put("trigger", trigger != null ? trigger : "");
        return out;
    }

    public static EngineArtifactPointer fromRedisHash(String tenantId, Map<String, String> hash) {
        if (hash == null || hash.isEmpty()) return null;
        String rev = hash.get("artifact_revision");
        if (rev == null || rev.isBlank()) return null;
        return new EngineArtifactPointer(
                tenantId,
                Long.parseLong(rev),
                hash.getOrDefault("content_sha256", ""),
                hash.getOrDefault("policy_version", ""),
                hash.getOrDefault("published_at", ""),
                hash.getOrDefault("trigger", ""));
    }
}
