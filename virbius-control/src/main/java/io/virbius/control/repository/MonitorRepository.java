package io.virbius.control.repository;

import java.util.List;

public interface MonitorRepository {

    record RuleRankingRow(
            String ruleId,
            int totalHits,
            int block,
            int review,
            int captcha,
            int allow,
            int totalRequests,
            int degraded) {}

    record SceneTrafficRow(
            String scene,
            String layer,
            long totalRequests) {}

    record DegradationRow(
            String bucket,
            int degraded,
            int totalRequests) {}

    record EventTimelineRow(
            String ruleId,
            int ruleRevision,
            String rolloutState,
            Integer canaryPercent,
            String trigger,
            String operator,
            String effectiveAt) {}

    List<RuleRankingRow> findRuleRanking(String tenantId, int hours, int limit);

    List<SceneTrafficRow> findSceneTraffic(String tenantId, int hours);

    List<DegradationRow> findDegradation(String tenantId, int hours);

    List<EventTimelineRow> findEventTimeline(String tenantId, int hours, int limit);
}
