package io.virbius.control.domain;

import java.time.Instant;
import java.util.List;

/**
 * Persistent record for a deploy rollout (machine-bucket canary release of a bundle version
 * across cloud + gateway + edge). One non-terminal record per tenant at a time.
 */
public record DeployRollout(
        String deployId,
        String tenantId,
        String bundleId,
        String state,
        int canaryPercent,
        boolean edgeDeployed,
        String targetVersion,
        String prevVersion,
        Long canaryEngineRevision,
        Long stableEngineRevision,
        Long canaryGatewayRevision,
        Long stableGatewayRevision,
        Long canaryEdgeRevision,
        Long stableEdgeRevision,
        List<Integer> canaryLadder,
        Instant startedAt,
        Instant updatedAt,
        Instant finalizedAt,
        String operator,
        String note) {}
