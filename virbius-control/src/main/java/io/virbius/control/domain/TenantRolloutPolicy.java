package io.virbius.control.domain;

import java.util.List;

public record TenantRolloutPolicy(
        String tenantId,
        String autoMode,
        List<Integer> canaryLadder,
        int minDryRunHours,
        int minReviewCount,
        double maxReviewRate,
        double maxReviewSpikeRatio,
        int minHoursPerStep,
        int minBlockSamplesPerStep,
        boolean allowForce,
        double rollbackBlockSpikeRatio,
        double edgeAuditSampleRateAllow,
        int maxConcurrentRollouts) {

    public static TenantRolloutPolicy defaults(String tenantId) {
        return new TenantRolloutPolicy(
                tenantId,
                "assisted",
                List.of(5, 20, 50, 100),
                1,
                100,
                0.05,
                2.0,
                12,
                10,
                true,
                3.0,
                0.1,
                10);
    }
}
