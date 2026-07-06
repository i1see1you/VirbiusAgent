package io.virbius.control.domain;

import java.time.Instant;

/** Audit event entry for a deploy rollout (state transitions + rule demotions + gate results). */
public record DeployEvent(
        String eventId,
        String deployId,
        String tenantId,
        String eventType,
        String reason,
        String ruleId,
        String fromState,
        Integer fromPercent,
        String toState,
        Integer toPercent,
        String layer,
        String operator,
        String note,
        Instant createdAt) {}
