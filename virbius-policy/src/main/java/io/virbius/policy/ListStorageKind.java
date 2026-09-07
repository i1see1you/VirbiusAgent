package io.virbius.policy;

/** Where gateway/cloud list entries are materialized at runtime. */
public enum ListStorageKind {
    MEMORY,
    REDIS,
    /**
     * Image sample lists: entries are image fingerprints (sha256:phash)
     * consumed by the engine via dedicated {@code virbius:imgbl:*} Redis keys,
     * not via the engine snapshot JSON or the generic redis list index.
     */
    IMAGE;

    public static ListStorageKind fromDimension(String dimension) {
        if (dimension == null || dimension.isBlank()) {
            return MEMORY;
        }
        String d = dimension.toLowerCase();
        if ("keyword".equals(d) || "content".equals(d) || "ip_cidr".equals(d) || "ip".equals(d)) {
            return MEMORY;
        }
        if ("user_id".equals(d) || "device_id".equals(d) || d.startsWith("var:")) {
            return REDIS;
        }
        if ("image".equals(d)) {
            return IMAGE;
        }
        return MEMORY;
    }
}
