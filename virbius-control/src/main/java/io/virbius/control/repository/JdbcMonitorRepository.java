package io.virbius.control.repository;

import io.virbius.control.config.SqlDialectConfig;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcMonitorRepository implements MonitorRepository {

    private final JdbcTemplate jdbc;
    private final SqlDialectConfig dialect;

    public JdbcMonitorRepository(JdbcTemplate jdbc, SqlDialectConfig dialect) {
        this.jdbc = jdbc;
        this.dialect = dialect;
    }

    @Override
    public List<RuleRankingRow> findRuleRanking(String tenantId, int hours, int limit) {
        String timeExpr = dialect.isMysql()
                ? " minute_bucket >= NOW() - INTERVAL ? HOUR"
                : " minute_bucket >= datetime('now', ?)";
        String sql = """
                SELECT rule_id,
                       SUM(cnt_review + cnt_block + cnt_captcha) AS total_hits,
                       SUM(cnt_block) AS cnt_block,
                       SUM(cnt_review) AS cnt_review,
                       SUM(cnt_captcha) AS cnt_captcha,
                       SUM(cnt_allow) AS cnt_allow,
                       SUM(cnt_total_requests) AS cnt_total_requests,
                       SUM(cnt_degraded) AS cnt_degraded
                FROM tb_rule_metrics_1m
                WHERE tenant_id = ? AND""" + timeExpr + """
                GROUP BY rule_id
                ORDER BY total_hits DESC
                LIMIT ?
                """;
        return jdbc.query(sql,
                (rs, i) -> new RuleRankingRow(
                        rs.getString("rule_id"),
                        rs.getInt("total_hits"),
                        rs.getInt("cnt_block"),
                        rs.getInt("cnt_review"),
                        rs.getInt("cnt_captcha"),
                        rs.getInt("cnt_allow"),
                        rs.getInt("cnt_total_requests"),
                        rs.getInt("cnt_degraded")),
                tenantId,
                dialect.isMysql() ? hours : "-" + hours + " hours",
                limit);
    }

    @Override
    public List<SceneTrafficRow> findSceneTraffic(String tenantId, int hours) {
        String timeExpr = dialect.isMysql()
                ? " hour_bucket >= NOW() - INTERVAL ? HOUR"
                : " hour_bucket >= datetime('now', ?)";
        String sql = """
                SELECT scene, layer, SUM(cnt_total) AS total_requests
                FROM tb_tenant_request_stats_1h
                WHERE tenant_id = ? AND""" + timeExpr + """
                GROUP BY scene, layer
                ORDER BY total_requests DESC
                """;
        return jdbc.query(sql,
                (rs, i) -> new SceneTrafficRow(
                        rs.getString("scene"),
                        rs.getString("layer"),
                        rs.getLong("total_requests")),
                tenantId,
                dialect.isMysql() ? hours : "-" + hours + " hours");
    }

    @Override
    public List<DegradationRow> findDegradation(String tenantId, int hours) {
        String timeExpr = dialect.isMysql()
                ? " minute_bucket >= NOW() - INTERVAL ? HOUR"
                : " minute_bucket >= datetime('now', ?)";
        String sql = """
                SELECT minute_bucket,
                       SUM(cnt_degraded) AS cnt_degraded,
                       SUM(cnt_total_requests) AS cnt_total_requests
                FROM tb_rule_metrics_1m
                WHERE tenant_id = ? AND""" + timeExpr + """
                GROUP BY minute_bucket
                ORDER BY minute_bucket
                """;
        return jdbc.query(sql,
                (rs, i) -> new DegradationRow(
                        rs.getString("minute_bucket"),
                        rs.getInt("cnt_degraded"),
                        rs.getInt("cnt_total_requests")),
                tenantId,
                dialect.isMysql() ? hours : "-" + hours + " hours");
    }

    @Override
    public List<EventTimelineRow> findEventTimeline(String tenantId, int hours, int limit) {
        String timeExpr = dialect.isMysql()
                ? " e.effective_at >= NOW() - INTERVAL ? HOUR"
                : " e.effective_at >= datetime('now', ?)";
        String sql = """
                SELECT e.rule_id, e.rule_revision, e.rollout_state, e.canary_percent,
                       e.`trigger`, e.operator, e.effective_at
                FROM tb_rule_rollout_event e
                WHERE e.tenant_id = ? AND""" + timeExpr + """
                ORDER BY e.effective_at DESC
                LIMIT ?
                """;
        return jdbc.query(sql,
                (rs, i) -> new EventTimelineRow(
                        rs.getString("rule_id"),
                        rs.getInt("rule_revision"),
                        rs.getString("rollout_state"),
                        rs.getObject("canary_percent") != null ? rs.getInt("canary_percent") : null,
                        rs.getString("trigger"),
                        rs.getString("operator"),
                        rs.getString("effective_at")),
                tenantId,
                dialect.isMysql() ? hours : "-" + hours + " hours",
                limit);
    }
}
