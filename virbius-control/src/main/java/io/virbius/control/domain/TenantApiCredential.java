package io.virbius.control.domain;

import io.virbius.control.security.ApiRole;
import java.time.Instant;

public record TenantApiCredential(
        String credentialId,
        String tenantId,
        ApiRole role,
        String keyHash,
        String keyPrefix,
        String label,
        String status,
        String createdBy,
        Instant createdAt,
        Instant revokedAt,
        Instant lastUsedAt) {

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_REVOKED = "revoked";
    public static final String PLATFORM_TENANT = "*";

    public boolean isActive() {
        return STATUS_ACTIVE.equals(status);
    }
}
