package io.virbius.policy;

/** Redis key helpers for gateway list ZSETs (score = expire_at unix sec; 0 = permanent). */
public final class GatewayListRedisKeys {

    public static final String DEFAULT_PREFIX = "virbius:lists";

    private GatewayListRedisKeys() {}

    public static String listKey(String prefix, String tenantId, String listName) {
        String p = prefix == null || prefix.isBlank() ? DEFAULT_PREFIX : prefix.trim();
        return p + ":" + tenantId + ":" + listName;
    }

    public static String stagingKey(String liveKey) {
        return liveKey + ":staging";
    }
}
