package io.virbius.control.job;

import io.virbius.control.config.SqlDialectConfig;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MetricsRollupJob {

    private final JdbcTemplate jdbc;
    private final SqlDialectConfig dialect;

    public MetricsRollupJob(JdbcTemplate jdbc, SqlDialectConfig dialect) {
        this.jdbc = jdbc;
        this.dialect = dialect;
    }

    @Scheduled(fixedDelayString = "${rollout.metrics.rollup-ms:60000}")
    public void rollup() {
        jdbc.update(buildSql());
    }

    private String buildSql() {
        String hourBucket;
        String sinceExpr;
        if (dialect.isMysql()) {
            hourBucket = "DATE_FORMAT(intercepted_at, '%Y-%m-%d %H:%i:00')";
            sinceExpr = "DATE_SUB(NOW(), INTERVAL 2 HOUR)";
        } else if (dialect.isPostgresql()) {
            hourBucket = "TO_CHAR(DATE_TRUNC('minute', intercepted_at), 'YYYY-MM-DD HH24:MI:00')";
            sinceExpr = "NOW() - INTERVAL '2 hours'";
        } else {
            hourBucket = "strftime('%Y-%m-%d %H:%M:00', intercepted_at)";
            sinceExpr = "datetime('now', '-2 hours')";
        }

        // sampled allow events represent 1 / sample_rate_allow real requests
        String allowWeight =
                "CASE WHEN sampled_allow = 1 AND sample_rate_allow > 0"
                        + " THEN ROUND(1.0 / sample_rate_allow) ELSE 1 END";

        String select = """
                SELECT tenant_id, rule_id,
                        %s AS minute_bucket,
                       rollout_state, canary_percent,
                       SUM(CASE WHEN effective_action = 'review' THEN 1 ELSE 0 END) AS cnt_review,
                       SUM(CASE WHEN effective_action = 'block' THEN 1 ELSE 0 END) AS cnt_block,
                       SUM(CASE WHEN effective_action = 'captcha' THEN 1 ELSE 0 END) AS cnt_captcha,
                       SUM(CASE WHEN effective_action = 'allow' THEN %s ELSE 0 END) AS cnt_allow,
                       SUM(CASE WHEN effective_action = 'allow' THEN %s ELSE 1 END) AS cnt_total_requests,
                       SUM(CASE WHEN degraded = 1 THEN 1 ELSE 0 END) AS cnt_degraded
                FROM tb_audit_events
                WHERE intercepted_at >= %s
                GROUP BY tenant_id, rule_id, %s, rollout_state, canary_percent
                """
                .formatted(hourBucket, allowWeight, allowWeight, sinceExpr, hourBucket);

        String columns =
                "tenant_id, rule_id, minute_bucket, rollout_state, canary_percent,"
                        + " cnt_review, cnt_block, cnt_captcha, cnt_allow, cnt_total_requests, cnt_degraded";

        if (dialect.isMysql()) {
            return "INSERT INTO tb_rule_metrics_1m (" + columns + ") " + select
                    + " ON DUPLICATE KEY UPDATE"
                    + "   rollout_state = VALUES(rollout_state),"
                    + "   canary_percent = VALUES(canary_percent),"
                    + "   cnt_review = VALUES(cnt_review),"
                    + "   cnt_block = VALUES(cnt_block),"
                    + "   cnt_captcha = VALUES(cnt_captcha),"
                    + "   cnt_allow = VALUES(cnt_allow),"
                    + "   cnt_total_requests = VALUES(cnt_total_requests),"
                    + "   cnt_degraded = VALUES(cnt_degraded)";
        } else if (dialect.isPostgresql()) {
            return "INSERT INTO tb_rule_metrics_1m (" + columns + ") " + select
                    + " ON CONFLICT (tenant_id, rule_id, minute_bucket) DO UPDATE SET"
                    + "   rollout_state = excluded.rollout_state,"
                    + "   canary_percent = excluded.canary_percent,"
                    + "   cnt_review = excluded.cnt_review,"
                    + "   cnt_block = excluded.cnt_block,"
                    + "   cnt_captcha = excluded.cnt_captcha,"
                    + "   cnt_allow = excluded.cnt_allow,"
                    + "   cnt_total_requests = excluded.cnt_total_requests,"
                    + "   cnt_degraded = excluded.cnt_degraded";
        }
        return "INSERT OR REPLACE INTO tb_rule_metrics_1m (" + columns + ") " + select;
    }
}
