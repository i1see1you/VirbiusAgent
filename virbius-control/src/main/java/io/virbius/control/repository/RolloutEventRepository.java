package io.virbius.control.repository;

public interface RolloutEventRepository {

    void recordEvent(
            String tenantId,
            String ruleId,
            int ruleRevision,
            String rolloutState,
            Integer canaryPercent,
            String trigger,
            String operator);
}
