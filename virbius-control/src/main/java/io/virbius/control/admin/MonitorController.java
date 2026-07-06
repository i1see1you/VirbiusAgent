package io.virbius.control.admin;

import io.virbius.control.common.response.ApiResult;
import io.virbius.control.config.SqlDialectConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/tenants/{tenantId}/monitor")
public class MonitorController {

    private final JdbcTemplate jdbc;
    private final SqlDialectConfig dialect;
    private final RolloutAdminController rolloutAdmin;

    public MonitorController(JdbcTemplate jdbc, SqlDialectConfig dialect, RolloutAdminController rolloutAdmin) {
        this.jdbc = jdbc;
        this.dialect = dialect;
        this.rolloutAdmin = rolloutAdmin;
    }

    @GetMapping("/rule-ranking")
    public ApiResult<Map<String, Object>> ruleRanking(
            @PathVariable("tenantId") String tenantId,
            @RequestParam(name = "hours", defaultValue = "24") int hours,
            @RequestParam(name = "limit", defaultValue = "10") int limit) {
        String timeExpr = dialect.isMysql()
                ? " minute_bucket >= NOW() - INTERVAL ? HOUR"
                : " minute_bucket >= datetime('now', ?)";
        List<Map<String, Object>> ranking = jdbc.query(
                """
                SELECT rule_id,
                       SUM(cnt_review + cnt_block + cnt_captcha) AS total_hits,
                       SUM(cnt_block) AS cnt_block,
                       SUM(cnt_review) AS cnt_review,
                       SUM(cnt_captcha) AS cnt_captcha,
                       SUM(cnt_allow) AS cnt_allow,
                       SUM(cnt_total_requests) AS cnt_total_requests,
                       SUM(cnt_degraded) AS cnt_degraded
                FROM tb_rule_metrics_1m
                WHERE tenant_id = ? AND """ + timeExpr + """
                GROUP BY rule_id
                ORDER BY total_hits DESC
                LIMIT ?
                """,
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("rule_id", rs.getString("rule_id"));
                    row.put("total_hits", rs.getInt("total_hits"));
                    row.put("block", rs.getInt("cnt_block"));
                    row.put("review", rs.getInt("cnt_review"));
                    row.put("captcha", rs.getInt("cnt_captcha"));
                    row.put("allow", rs.getInt("cnt_allow"));
                    int total = rs.getInt("cnt_total_requests");
                    row.put("total_requests", total);
                    if (total > 0) {
                        int hits = rs.getInt("total_hits");
                        row.put("hit_rate", hits / (double) total);
                        row.put("block_rate", rs.getInt("cnt_block") / (double) total);
                    }
                    row.put("cnt_degraded", rs.getInt("cnt_degraded"));
                    return row;
                },
                tenantId,
                dialect.isMysql() ? hours : "-" + hours + " hours",
                limit);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ranking", ranking);
        return ApiResult.ok(out);
    }

    @GetMapping("/scene-traffic")
    public ApiResult<Map<String, Object>> sceneTraffic(
            @PathVariable("tenantId") String tenantId,
            @RequestParam(name = "hours", defaultValue = "24") int hours) {
        String timeExpr = dialect.isMysql()
                ? " hour_bucket >= NOW() - INTERVAL ? HOUR"
                : " hour_bucket >= datetime('now', ?)";
        List<Map<String, Object>> scenes = jdbc.query(
                """
                SELECT scene, layer, SUM(cnt_total) AS total_requests
                FROM tb_tenant_request_stats_1h
                WHERE tenant_id = ? AND """ + timeExpr + """
                GROUP BY scene, layer
                ORDER BY total_requests DESC
                """,
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("scene", rs.getString("scene"));
                    row.put("layer", rs.getString("layer"));
                    row.put("total_requests", rs.getLong("total_requests"));
                    return row;
                },
                tenantId,
                dialect.isMysql() ? hours : "-" + hours + " hours");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scenes", scenes);
        return ApiResult.ok(out);
    }

    @GetMapping("/degradation")
    public ApiResult<Map<String, Object>> degradation(
            @PathVariable("tenantId") String tenantId,
            @RequestParam(name = "hours", defaultValue = "24") int hours) {
        String timeExpr = dialect.isMysql()
                ? " minute_bucket >= NOW() - INTERVAL ? HOUR"
                : " minute_bucket >= datetime('now', ?)";
        List<Map<String, Object>> series = jdbc.query(
                """
                SELECT minute_bucket,
                       SUM(cnt_degraded) AS cnt_degraded,
                       SUM(cnt_total_requests) AS cnt_total_requests
                FROM tb_rule_metrics_1m
                WHERE tenant_id = ? AND """ + timeExpr + """
                GROUP BY minute_bucket
                ORDER BY minute_bucket
                """,
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("bucket", rs.getString("minute_bucket"));
                    int degraded = rs.getInt("cnt_degraded");
                    int total = rs.getInt("cnt_total_requests");
                    row.put("degraded", degraded);
                    row.put("total_requests", total);
                    row.put("degraded_rate", total > 0 ? degraded / (double) total : 0.0);
                    return row;
                },
                tenantId,
                dialect.isMysql() ? hours : "-" + hours + " hours");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("series", series);
        return ApiResult.ok(out);
    }

    @GetMapping("/event-timeline")
    public ApiResult<Map<String, Object>> eventTimeline(
            @PathVariable("tenantId") String tenantId,
            @RequestParam(name = "hours", defaultValue = "48") int hours,
            @RequestParam(name = "limit", defaultValue = "30") int limit) {
        String timeExpr = dialect.isMysql()
                ? " e.effective_at >= NOW() - INTERVAL ? HOUR"
                : " e.effective_at >= datetime('now', ?)";
        List<Map<String, Object>> events = jdbc.query(
                """
                SELECT e.rule_id, e.rule_revision, e.rollout_state, e.canary_percent,
                       e.trigger, e.operator, e.effective_at
                FROM tb_rule_rollout_event e
                WHERE e.tenant_id = ? AND """ + timeExpr + """
                ORDER BY e.effective_at DESC
                LIMIT ?
                """,
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("rule_id", rs.getString("rule_id"));
                    row.put("rule_revision", rs.getInt("rule_revision"));
                    row.put("rollout_state", rs.getString("rollout_state"));
                    row.put("canary_percent", rs.getObject("canary_percent"));
                    row.put("trigger", rs.getString("trigger"));
                    row.put("operator", rs.getString("operator"));
                    row.put("effective_at", rs.getString("effective_at"));
                    return row;
                },
                tenantId,
                dialect.isMysql() ? hours : "-" + hours + " hours",
                limit);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("events", events);
        return ApiResult.ok(out);
    }
}
