package io.virbius.control.service.deploy;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Active deploy rollout pointer published to Redis. Engine and gateway agent both read this to
 * decide whether they belong to the stable or canary pool, based on their {@code instance_bucket}.
 *
 * <p>An empty / missing pointer means there is no active rollout — runtime should keep using the
 * regular active artifact pointer (engine: {@code virbius:engine:{tenant}:pointer}; gateway:
 * {@code virbius:config:gateway:{tenant}}) as before.
 */
public record DeployRolloutPointer(
        String tenantId,
        String deployId,
        String state,
        int canaryPercent,
        long canaryEngineRevision,
        long stableEngineRevision,
        long canaryGatewayRevision,
        long stableGatewayRevision,
        long canaryEdgeRevision,
        long stableEdgeRevision,
        String targetVersion,
        String prevVersion,
        String updatedAt) {

    public Map<String, String> toRedisHash() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("deploy_id", nullSafe(deployId));
        out.put("state", nullSafe(state));
        out.put("canary_percent", String.valueOf(canaryPercent));
        out.put("canary_engine_revision", String.valueOf(canaryEngineRevision));
        out.put("stable_engine_revision", String.valueOf(stableEngineRevision));
        out.put("canary_gateway_revision", String.valueOf(canaryGatewayRevision));
        out.put("stable_gateway_revision", String.valueOf(stableGatewayRevision));
        out.put("canary_edge_revision", String.valueOf(canaryEdgeRevision));
        out.put("stable_edge_revision", String.valueOf(stableEdgeRevision));
        out.put("target_version", nullSafe(targetVersion));
        out.put("prev_version", nullSafe(prevVersion));
        out.put("updated_at", nullSafe(updatedAt));
        return out;
    }

    public static DeployRolloutPointer fromRedisHash(String tenantId, Map<String, String> hash) {
        if (hash == null || hash.isEmpty()) {
            return null;
        }
        String deployId = hash.get("deploy_id");
        if (deployId == null || deployId.isBlank()) {
            return null;
        }
        return new DeployRolloutPointer(
                tenantId,
                deployId,
                hash.getOrDefault("state", ""),
                parseInt(hash.get("canary_percent")),
                parseLong(hash.get("canary_engine_revision")),
                parseLong(hash.get("stable_engine_revision")),
                parseLong(hash.get("canary_gateway_revision")),
                parseLong(hash.get("stable_gateway_revision")),
                parseLong(hash.get("canary_edge_revision")),
                parseLong(hash.get("stable_edge_revision")),
                hash.getOrDefault("target_version", ""),
                hash.getOrDefault("prev_version", ""),
                hash.getOrDefault("updated_at", ""));
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private static int parseInt(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static long parseLong(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }
}
