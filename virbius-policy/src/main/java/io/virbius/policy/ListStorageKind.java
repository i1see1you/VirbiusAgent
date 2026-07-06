package io.virbius.policy;

/** Where gateway/cloud list entries are materialized at runtime. */
public enum ListStorageKind {
    MEMORY,
    REDIS;

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
        return MEMORY;
    }
}
