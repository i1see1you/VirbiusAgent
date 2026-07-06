package io.virbius.control.domain;

import java.time.Instant;
import java.util.Map;

public record BundleStaging(
        String tenantId,
        String bundleId,
        String layer,
        String baseVersion,
        String status,
        Map<String, String> ruleDiffs,
        int version,
        Instant updatedAt) {}
