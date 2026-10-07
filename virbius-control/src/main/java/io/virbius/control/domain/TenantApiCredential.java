package io.virbius.control.domain;

import io.virbius.control.security.ApiRole;
import io.virbius.control.security.TenantApiCredentialConstants;
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
        Instant lastUsedAt,
        String hashAlgo) {

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_REVOKED = "revoked";
    public static final String PLATFORM_TENANT = "*";

    /** Pre-pepper shape: rows without an explicit algorithm are legacy-sha256. */
    public TenantApiCredential(
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
        this(credentialId, tenantId, role, keyHash, keyPrefix, label, status, createdBy,
                createdAt, revokedAt, lastUsedAt, TenantApiCredentialConstants.HASH_ALGO_LEGACY);
    }

    public boolean isActive() {
        return STATUS_ACTIVE.equals(status);
    }

    public String hashAlgoOrLegacy() {
        return hashAlgo == null || hashAlgo.isBlank()
                ? TenantApiCredentialConstants.HASH_ALGO_LEGACY
                : hashAlgo;
    }
}
