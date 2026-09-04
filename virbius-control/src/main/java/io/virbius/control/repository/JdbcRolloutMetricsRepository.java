package io.virbius.control.repository;

import io.virbius.control.config.SqlDialectConfig;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRolloutMetricsRepository implements RolloutMetricsRepository {

    private final JdbcTemplate jdbc;
    private final SqlDialectConfig dialect;

    public JdbcRolloutMetricsRepository(JdbcTemplate jdbc, SqlDialectConfig dialect) {
        this.jdbc = jdbc;
        this.dialect = dialect;
    }

    @Override
    public long countReview24h(String tenantId, String ruleId) {
        String timeExpr = dialect.isMysql()
                ? "minute_bucket >= NOW() - INTERVAL 24 HOUR"
                : "minute_bucket >= datetime('now', '-24 hours')";
        Long n = jdbc.queryForObject(
                "SELECT COALESCE(SUM(cnt_review), 0) FROM tb_rule_metrics_1m " +
                "WHERE tenant_id = ? AND rule_id = ? AND " + timeExpr,
                Long.class,
                tenantId,
                ruleId);
        return n != null ? n : 0L;
    }

    @Override
    public long countTotalRequests24h(String tenantId) {
        String timeExpr = dialect.isMysql()
                ? "minute_bucket >= NOW() - INTERVAL 24 HOUR"
                : "minute_bucket >= datetime('now', '-24 hours')";
        Long n = jdbc.queryForObject(
                "SELECT COALESCE(SUM(cnt_total_requests), 0) FROM tb_rule_metrics_1m " +
                "WHERE tenant_id = ? AND " + timeExpr,
                Long.class,
                tenantId);
        return n != null ? n : 0L;
    }

    @Override
    public long countBlockInCanary24h(String tenantId, String ruleId) {
        String timeExpr = dialect.isMysql()
                ? "minute_bucket >= NOW() - INTERVAL 24 HOUR"
                : "minute_bucket >= datetime('now', '-24 hours')";
        Long n = jdbc.queryForObject(
                "SELECT COALESCE(SUM(cnt_block), 0) FROM tb_rule_metrics_1m " +
                "WHERE tenant_id = ? AND rule_id = ? AND rollout_state = 'canary' AND " + timeExpr,
                Long.class,
                tenantId,
                ruleId);
        return n != null ? n : 0L;
    }

    @Override
    public double baseline7dDailyAvgReview(String tenantId, String ruleId) {
        String dateFunc = dialect.isMysql() ? "DATE_FORMAT(minute_bucket, '%Y-%m-%d')" : "strftime('%Y-%m-%d', minute_bucket)";
        String timeFrom = dialect.isMysql() ? "NOW() - INTERVAL 8 DAY" : "datetime('now', '-8 days')";
        String timeTo = dialect.isMysql() ? "NOW() - INTERVAL 1 DAY" : "datetime('now', '-1 day')";
        Double avg = jdbc.queryForObject(
                "SELECT COALESCE(SUM(daily_review), 0) / 7.0 FROM (" +
                "  SELECT " + dateFunc + " AS day, SUM(cnt_review) AS daily_review " +
                "  FROM tb_rule_metrics_1m " +
                "  WHERE tenant_id = ? AND rule_id = ? AND rollout_state = 'dry_run' " +
                "    AND minute_bucket >= " + timeFrom + " AND minute_bucket < " + timeTo +
                "  GROUP BY day" +
                ")",
                Double.class,
                tenantId,
                ruleId);
        return avg != null ? avg : 0.0;
    }

    @Override
    public int countBaselineDaysWithData(String tenantId, String ruleId) {
        String dateFunc = dialect.isMysql() ? "DATE_FORMAT(minute_bucket, '%Y-%m-%d')" : "strftime('%Y-%m-%d', minute_bucket)";
        String timeFrom = dialect.isMysql() ? "NOW() - INTERVAL 8 DAY" : "datetime('now', '-8 days')";
        String timeTo = dialect.isMysql() ? "NOW() - INTERVAL 1 DAY" : "datetime('now', '-1 day')";
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT " + dateFunc + ") FROM tb_rule_metrics_1m " +
                "WHERE tenant_id = ? AND rule_id = ? AND rollout_state = 'dry_run' " +
                "  AND minute_bucket >= " + timeFrom + " AND minute_bucket < " + timeTo,
                Integer.class,
                tenantId,
                ruleId);
        return n != null ? n : 0;
    }
}
