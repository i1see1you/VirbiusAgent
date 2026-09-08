package io.virbius.control.service.deploy;

/**
 * Redis key conventions for deploy rollout (machine-bucket canary deployment).
 *
 * <pre>
 *   active pointer: virbius:deploy:active:{tenant}            HASH
 *   notify stream:  virbius:deploy:notify                      STREAM
 *   changed channel: virbius:deploy:changed                    PUB/SUB (broadcast; kernel nodes)
 *   node registry:  virbius:nodes:{layer}:{tenant}:{instance}  HASH (TTL ~60s)
 *   canary blobs reuse existing engine/gateway artifact key conventions:
 *     engine canary blob:  virbius:engine:{tenant}:r{rev}:snapshot
 *     gateway canary blob: virbius:artifacts:gateway:{tenant}:r{rev}:access-lists
 * </pre>
 */
public final class DeployRolloutKeys {

    public static final String STREAM_KEY = "virbius:deploy:notify";

    /**
     * Pub/Sub broadcast channel mirroring {@link #STREAM_KEY} events. Kernel nodes (and any other
     * pool-resolving agent that cannot afford per-node consumer groups) subscribe here and
     * re-resolve their pool from the pointer on every message.
     */
    public static final String CHANGED_CHANNEL = "virbius:deploy:changed";

    private DeployRolloutKeys() {}

    public static String activePointerKey(String tenantId) {
        return "virbius:deploy:active:" + tenantId;
    }

    public static String nodeKey(String layer, String tenantId, String instanceId) {
        return "virbius:nodes:" + layer + ":" + tenantId + ":" + instanceId;
    }

    public static String nodeKeyPrefix(String layer, String tenantId) {
        return "virbius:nodes:" + layer + ":" + tenantId + ":";
    }
}
