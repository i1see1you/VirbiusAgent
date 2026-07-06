package io.virbius.control.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record BundleRelease(
        String tenantId,
        String bundleId,
        String version,
        String status,
        List<Map<String, Object>> frozenSnapshot,
        Instant createdAt,
        Instant deployedAt) {}
