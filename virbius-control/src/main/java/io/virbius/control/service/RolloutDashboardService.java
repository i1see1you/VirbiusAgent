package io.virbius.control.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class RolloutDashboardService {

    private final JdbcTemplate jdbc;

    public RolloutDashboardService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> metrics(String tenantId, String ruleId, int hours) {
        List<Map<String, Object>> series = jdbc.query(
                """
                SELECT minute_bucket, rollout_state, canary_percent,
                       cnt_review, cnt_block, cnt_captcha, cnt_allow, cnt_total_requests
                FROM tb_rule_metrics_1m
                WHERE tenant_id = ? AND rule_id = ?
                  AND minute_bucket >= datetime('now', ?)
                ORDER BY minute_bucket
                """,
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("bucket", rs.getString("minute_bucket"));
                    row.put("review", rs.getInt("cnt_review"));
                    row.put("block", rs.getInt("cnt_block"));
                    row.put("captcha", rs.getInt("cnt_captcha"));
                    row.put("allow", rs.getInt("cnt_allow"));
                    row.put("total_requests", rs.getInt("cnt_total_requests"));
                    return row;
                },
                tenantId,
                ruleId,
                "-" + hours + " hours");

        List<Map<String, Object>> series1m = jdbc.query(
                """
                SELECT strftime('%Y-%m-%d %H:%M:00', intercepted_at) AS bucket,
                       SUM(CASE WHEN effective_action = 'review'  THEN 1 ELSE 0 END) AS review,
                       SUM(CASE WHEN effective_action = 'block'   THEN 1 ELSE 0 END) AS block,
                       SUM(CASE WHEN effective_action = 'captcha' THEN 1 ELSE 0 END) AS captcha,
                       SUM(CASE WHEN effective_action = 'allow'   THEN 1 ELSE 0 END) AS allow,
                       COUNT(*) AS total_requests
                FROM tb_audit_events
                WHERE tenant_id = ? AND rule_id = ?
                  AND intercepted_at >= datetime('now', '-2 hours')
                GROUP BY bucket
                ORDER BY bucket
                """,
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("bucket", rs.getString("bucket"));
                    row.put("review", rs.getInt("review"));
                    row.put("block", rs.getInt("block"));
                    row.put("captcha", rs.getInt("captcha"));
                    row.put("allow", rs.getInt("allow"));
                    row.put("total_requests", rs.getInt("total_requests"));
                    return row;
                },
                tenantId,
                ruleId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rule_id", ruleId);
        out.put("series", series);
        out.put("series_1m", series1m);
        int review = 0;
        int block = 0;
        int captcha = 0;
        int allow = 0;
        int total = 0;
        for (Map<String, Object> row : series) {
            review += (int) row.get("review");
            block += (int) row.get("block");
            captcha += (int) row.get("captcha");
            allow += (int) row.get("allow");
            total += (int) row.get("total_requests");
        }
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("review", review);
        totals.put("block", block);
        totals.put("captcha", captcha);
        totals.put("allow", allow);
        totals.put("total_requests", total);
        if (total > 0) {
            totals.put("hit_rate", (review + block + captcha) / (double) total);
            totals.put("review_rate", review / (double) total);
            totals.put("block_rate", block / (double) total);
        }
        out.put("totals", totals);
        return out;
    }

    public List<Map<String, Object>> auditSamples(
            String tenantId, String ruleId, String effectiveAction, int limit) {
        String actionFilter = effectiveAction != null && !effectiveAction.isBlank() ? effectiveAction : null;
        return jdbc.query(
                """
                SELECT trace_id, effective_action, reason_code, max_risk_score, rollout_state,
                       canary_percent, in_canary_bucket, intercepted_at, user_id, device_id
                FROM tb_audit_events
                WHERE tenant_id = ? AND rule_id = ?
                  AND (? IS NULL OR effective_action = ?)
                ORDER BY intercepted_at DESC
                LIMIT ?
                """,
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("trace_id", rs.getString("trace_id"));
                    row.put("effective_action", rs.getString("effective_action"));
                    row.put("reason_code", rs.getString("reason_code"));
                    row.put("max_risk_score", rs.getInt("max_risk_score"));
                    row.put("rollout_state", rs.getString("rollout_state"));
                    row.put("canary_percent", rs.getObject("canary_percent"));
                    row.put("in_canary_bucket", rs.getObject("in_canary_bucket"));
                    row.put("intercepted_at", rs.getString("intercepted_at"));
                    row.put("user_id", rs.getString("user_id"));
                    row.put("device_id", rs.getString("device_id"));
                    return row;
                },
                tenantId,
                ruleId,
                actionFilter,
                actionFilter,
                limit);
    }

    public List<Map<String, Object>> gateLogs(String tenantId, String ruleId, int limit) {
        return jdbc.query(
                """
                SELECT from_state, to_state, pass, reasons_json, operator, comment, created_at
                FROM tb_rule_gate_log
                WHERE tenant_id = ? AND rule_id = ?
                ORDER BY created_at DESC
                LIMIT ?
                """,
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("from_state", rs.getString("from_state"));
                    row.put("to_state", rs.getString("to_state"));
                    row.put("pass", rs.getInt("pass") == 1);
                    row.put("reasons_json", rs.getString("reasons_json"));
                    row.put("operator", rs.getString("operator"));
                    row.put("comment", rs.getString("comment"));
                    row.put("created_at", rs.getString("created_at"));
                    return row;
                },
                tenantId,
                ruleId,
                limit);
    }

    public List<Map<String, Object>> timeline(String tenantId, String ruleId) {
        return jdbc.query(
                """
                SELECT rule_revision, rollout_state, canary_percent, trigger, operator, effective_at
                FROM tb_rule_rollout_event
                WHERE tenant_id = ? AND rule_id = ?
                ORDER BY effective_at DESC LIMIT 50
                """,
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("rule_revision", rs.getInt("rule_revision"));
                    row.put("rollout_state", rs.getString("rollout_state"));
                    row.put("canary_percent", rs.getObject("canary_percent"));
                    row.put("trigger", rs.getString("trigger"));
                    row.put("operator", rs.getString("operator"));
                    row.put("effective_at", rs.getString("effective_at"));
                    return row;
                },
                tenantId,
                ruleId);
    }

    public List<Map<String, Object>> trace(String tenantId, String traceId) {
        return jdbc.query(
                """
                SELECT trace_id, trace_id_source, tenant_id, scene, layer, rule_id, rule_revision,
                       reason_code, effective_action, max_risk_score, rollout_state, canary_percent,
                       in_canary_bucket, degraded, sampled_allow, intercepted_at, user_id, device_id
                FROM tb_audit_events
                WHERE tenant_id = ? AND trace_id = ?
                ORDER BY intercepted_at
                """,
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("trace_id", rs.getString("trace_id"));
                    row.put("trace_id_source", rs.getString("trace_id_source"));
                    row.put("tenant_id", rs.getString("tenant_id"));
                    row.put("scene", rs.getString("scene"));
                    row.put("layer", rs.getString("layer"));
                    row.put("rule_id", rs.getString("rule_id"));
                    row.put("rule_revision", rs.getInt("rule_revision"));
                    row.put("reason_code", rs.getString("reason_code"));
                    row.put("effective_action", rs.getString("effective_action"));
                    row.put("max_risk_score", rs.getInt("max_risk_score"));
                    row.put("rollout_state", rs.getString("rollout_state"));
                    row.put("canary_percent", rs.getObject("canary_percent"));
                    row.put("in_canary_bucket", rs.getObject("in_canary_bucket"));
                    row.put("degraded", rs.getObject("degraded"));
                    row.put("sampled_allow", rs.getObject("sampled_allow"));
                    row.put("intercepted_at", rs.getString("intercepted_at"));
                    row.put("user_id", rs.getString("user_id"));
                    row.put("device_id", rs.getString("device_id"));
                    return row;
                },
                tenantId,
                traceId);
    }

    public List<Map<String, Object>> recent(String tenantId, int limit) {
        return jdbc.query(
                """
                SELECT trace_id, trace_id_source, tenant_id, scene, layer, rule_id, rule_revision,
                       reason_code, effective_action, max_risk_score, rollout_state, canary_percent,
                       in_canary_bucket, degraded, sampled_allow, intercepted_at, user_id, device_id
                FROM tb_audit_events
                WHERE tenant_id = ?
                ORDER BY intercepted_at DESC
                LIMIT ?
                """,
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("trace_id", rs.getString("trace_id"));
                    row.put("trace_id_source", rs.getString("trace_id_source"));
                    row.put("tenant_id", rs.getString("tenant_id"));
                    row.put("scene", rs.getString("scene"));
                    row.put("layer", rs.getString("layer"));
                    row.put("rule_id", rs.getString("rule_id"));
                    row.put("rule_revision", rs.getInt("rule_revision"));
                    row.put("reason_code", rs.getString("reason_code"));
                    row.put("effective_action", rs.getString("effective_action"));
                    row.put("max_risk_score", rs.getInt("max_risk_score"));
                    row.put("rollout_state", rs.getString("rollout_state"));
                    row.put("canary_percent", rs.getObject("canary_percent"));
                    row.put("in_canary_bucket", rs.getObject("in_canary_bucket"));
                    row.put("degraded", rs.getObject("degraded"));
                    row.put("sampled_allow", rs.getObject("sampled_allow"));
                    row.put("intercepted_at", rs.getString("intercepted_at"));
                    row.put("user_id", rs.getString("user_id"));
                    row.put("device_id", rs.getString("device_id"));
                    return row;
                },
                tenantId,
                limit);
    }

    public Map<String, Object> aggregateMetrics(String tenantId, int hours) {
        List<Map<String, Object>> series = jdbc.query(
                """
                SELECT minute_bucket,
                       SUM(cnt_review) AS cnt_review,
                       SUM(cnt_block) AS cnt_block,
                       SUM(cnt_captcha) AS cnt_captcha,
                       SUM(cnt_allow) AS cnt_allow,
                       SUM(cnt_total_requests) AS cnt_total_requests
                FROM tb_rule_metrics_1m
                WHERE tenant_id = ?
                  AND minute_bucket >= datetime('now', ?)
                GROUP BY minute_bucket
                ORDER BY minute_bucket
                """,
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("bucket", rs.getString("minute_bucket"));
                    row.put("review", rs.getInt("cnt_review"));
                    row.put("block", rs.getInt("cnt_block"));
                    row.put("captcha", rs.getInt("cnt_captcha"));
                    row.put("allow", rs.getInt("cnt_allow"));
                    row.put("total_requests", rs.getInt("cnt_total_requests"));
                    return row;
                },
                tenantId,
                "-" + hours + " hours");

        List<Map<String, Object>> series1m = jdbc.query(
                """
                SELECT strftime('%Y-%m-%d %H:%M:00', intercepted_at) AS bucket,
                       SUM(CASE WHEN effective_action = 'review'  THEN 1 ELSE 0 END) AS review,
                       SUM(CASE WHEN effective_action = 'block'   THEN 1 ELSE 0 END) AS block,
                       SUM(CASE WHEN effective_action = 'captcha' THEN 1 ELSE 0 END) AS captcha,
                       SUM(CASE WHEN effective_action = 'allow'   THEN 1 ELSE 0 END) AS allow,
                       COUNT(*) AS total_requests
                FROM tb_audit_events
                WHERE tenant_id = ?
                  AND intercepted_at >= datetime('now', '-2 hours')
                GROUP BY bucket
                ORDER BY bucket
                """,
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("bucket", rs.getString("bucket"));
                    row.put("review", rs.getInt("review"));
                    row.put("block", rs.getInt("block"));
                    row.put("captcha", rs.getInt("captcha"));
                    row.put("allow", rs.getInt("allow"));
                    row.put("total_requests", rs.getInt("total_requests"));
                    return row;
                },
                tenantId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("series", series);
        out.put("series_1m", series1m);
        int review = 0, block = 0, captcha = 0, allow = 0, total = 0;
        for (Map<String, Object> row : series) {
            review += (int) row.get("review");
            block += (int) row.get("block");
            captcha += (int) row.get("captcha");
            allow += (int) row.get("allow");
            total += (int) row.get("total_requests");
        }
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("review", review);
        totals.put("block", block);
        totals.put("captcha", captcha);
        totals.put("allow", allow);
        totals.put("total_requests", total);
        if (total > 0) {
            totals.put("hit_rate", (review + block + captcha) / (double) total);
            totals.put("review_rate", review / (double) total);
            totals.put("block_rate", block / (double) total);
        }
        out.put("totals", totals);
        return out;
    }

    public Map<String, Object> ingestHealth(String tenantId, String layer, int hours) {
        Long events = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM tb_audit_events
                WHERE tenant_id = ? AND layer = ?
                  AND intercepted_at >= datetime('now', ?)
                """,
                Long.class,
                tenantId,
                layer,
                "-" + hours + " hours");
        Long total = jdbc.queryForObject(
                """
                SELECT COALESCE(SUM(cnt_total), 0) FROM tb_tenant_request_stats_1h
                WHERE tenant_id = ? AND layer = ?
                  AND hour_bucket >= datetime('now', ?)
                """,
                Long.class,
                tenantId,
                layer,
                "-" + hours + " hours");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("layer", layer);
        out.put("audit_events", events != null ? events : 0L);
        out.put("estimated_requests", total != null ? total : 0L);
        out.put("hours", hours);
        return out;
    }

    public void setLadderStatus(String tenantId, String ruleId, String status) {
        int updated = jdbc.update(
                """
                UPDATE tb_rule_ladder_state SET ladder_status = ?, updated_at = CURRENT_TIMESTAMP,
                  ladder_started_at = CASE WHEN ? = 'running' THEN CURRENT_TIMESTAMP ELSE ladder_started_at END
                WHERE tenant_id = ? AND rule_id = ?
                """,
                status,
                status,
                tenantId,
                ruleId);
        if (updated == 0) {
            jdbc.update(
                    """
                    INSERT INTO tb_rule_ladder_state (tenant_id, rule_id, ladder_status, ladder_started_at)
                    VALUES (?, ?, ?, CASE WHEN ? = 'running' THEN CURRENT_TIMESTAMP ELSE NULL END)
                    """,
                    tenantId,
                    ruleId,
                    status,
                    status);
        }
    }
}
