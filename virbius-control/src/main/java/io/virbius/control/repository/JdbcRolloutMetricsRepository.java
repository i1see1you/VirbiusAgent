package io.virbius.control.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRolloutMetricsRepository implements RolloutMetricsRepository {

    private final JdbcTemplate jdbc;

    public JdbcRolloutMetricsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long countReview24h(String tenantId, String ruleId) {
        Long n = jdbc.queryForObject(
                """
                SELECT COALESCE(SUM(cnt_review), 0) FROM tb_rule_metrics_1m
                WHERE tenant_id = ? AND rule_id = ?
                  AND minute_bucket >= datetime('now', '-24 hours')
                """,
                Long.class,
                tenantId,
                ruleId);
        return n != null ? n : 0L;
    }

    @Override
    public long countTotalRequests24h(String tenantId) {
        Long n = jdbc.queryForObject(
                """
                SELECT COALESCE(SUM(cnt_total_requests), 0) FROM tb_rule_metrics_1m
                WHERE tenant_id = ? AND minute_bucket >= datetime('now', '-24 hours')
                """,
                Long.class,
                tenantId);
        return n != null ? n : 0L;
    }

    @Override
    public long countBlockInCanary24h(String tenantId, String ruleId) {
        Long n = jdbc.queryForObject(
                """
                SELECT COALESCE(SUM(cnt_block), 0) FROM tb_rule_metrics_1m
                WHERE tenant_id = ? AND rule_id = ? AND rollout_state = 'canary'
                  AND minute_bucket >= datetime('now', '-24 hours')
                """,
                Long.class,
                tenantId,
                ruleId);
        return n != null ? n : 0L;
    }

    @Override
    public double baseline7dDailyAvgReview(String tenantId, String ruleId) {
        Double avg = jdbc.queryForObject(
                """
                SELECT COALESCE(SUM(daily_review), 0) / 7.0
                FROM (
                  SELECT strftime('%Y-%m-%d', minute_bucket) AS day,
                         SUM(cnt_review) AS daily_review
                  FROM tb_rule_metrics_1m
                  WHERE tenant_id = ? AND rule_id = ?
                    AND rollout_state = 'dry_run'
                    AND minute_bucket >= datetime('now', '-8 days')
                    AND minute_bucket < datetime('now', '-1 day')
                  GROUP BY day
                )
                """,
                Double.class,
                tenantId,
                ruleId);
        return avg != null ? avg : 0.0;
    }

    @Override
    public int countBaselineDaysWithData(String tenantId, String ruleId) {
        Integer n = jdbc.queryForObject(
                """
                SELECT COUNT(DISTINCT strftime('%Y-%m-%d', minute_bucket))
                FROM tb_rule_metrics_1m
                WHERE tenant_id = ? AND rule_id = ?
                  AND rollout_state = 'dry_run'
                  AND minute_bucket >= datetime('now', '-8 days')
                  AND minute_bucket < datetime('now', '-1 day')
                """,
                Integer.class,
                tenantId,
                ruleId);
        return n != null ? n : 0;
    }
}
