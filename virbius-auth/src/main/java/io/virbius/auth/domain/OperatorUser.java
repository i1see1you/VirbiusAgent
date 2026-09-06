package io.virbius.auth.domain;

import java.time.Instant;

public record OperatorUser(
        String userId,
        String username,
        String passwordHash,
        String role,
        String tenantId,
        String status,
        Instant createdAt) {

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_DISABLED = "disabled";
    public static final String PLATFORM_TENANT = "*";

    public boolean isActive() {
        return STATUS_ACTIVE.equals(status);
    }

    public OperatorUser withoutSecret() {
        return new OperatorUser(userId, username, null, role, tenantId, status, createdAt);
    }
}
