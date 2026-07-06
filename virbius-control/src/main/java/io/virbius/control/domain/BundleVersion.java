package io.virbius.control.domain;

import java.util.Map;

public record BundleVersion(
        String tenantId,
        String bundleId,
        String version,
        String status,
        String publishId,
        Map<String, Object> syncAck,
        Map<String, Object> metadata,
        int metadataVersion) {

    public BundleVersion(
            String tenantId,
            String bundleId,
            String version,
            String status,
            String publishId,
            Map<String, Object> syncAck,
            Map<String, Object> metadata) {
        this(tenantId, bundleId, version, status, publishId, syncAck, metadata, 0);
    }
}
