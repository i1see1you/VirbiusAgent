package io.virbius.control.audit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Query service for agent decision chain traces stored in {@code tb_agent_trace}.
 *
 * <p>Provides session-level timeline, trace-level chain, and filtered search.
 */
@Service
public class TraceQueryService {

    private final JdbcTemplate jdbc;

    public TraceQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Get the full timeline of steps for a session, ordered by step_seq.
     */
    public List<Map<String, Object>> sessionTimeline(String tenantId, String sessionId) {
        return jdbc.query(
                """
                SELECT trace_id, session_id, tenant_id, step_id, parent_step_id, step_seq,
                       step_type, layer, user_id, device_id,
                       input_role, input_content_hash,
                       tool_name, tool_args_hash, tool_args, tool_decision, rule_id, reason_code, risk_score,
                       tool_status, tool_duration_ms,
                       content_size, content_sampled, dlp_masked, occurred_at
                FROM tb_agent_trace
                WHERE tenant_id = ? AND session_id = ?
                ORDER BY step_seq
                """,
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("trace_id", rs.getString("trace_id"));
                    row.put("session_id", rs.getString("session_id"));
                    row.put("tenant_id", rs.getString("tenant_id"));
                    row.put("step_id", rs.getString("step_id"));
                    row.put("parent_step_id", rs.getString("parent_step_id"));
                    row.put("step_seq", rs.getInt("step_seq"));
                    row.put("step_type", rs.getString("step_type"));
                    row.put("layer", rs.getString("layer"));
                    row.put("user_id", rs.getString("user_id"));
                    row.put("device_id", rs.getString("device_id"));
                    row.put("input_role", rs.getString("input_role"));
                    row.put("input_content_hash", rs.getString("input_content_hash"));
                    row.put("tool_name", rs.getString("tool_name"));
                    row.put("tool_args_hash", rs.getString("tool_args_hash"));
                    row.put("tool_args", rs.getString("tool_args"));
                    row.put("tool_decision", rs.getString("tool_decision"));
                    row.put("rule_id", rs.getString("rule_id"));
                    row.put("reason_code", rs.getString("reason_code"));
                    row.put("risk_score", rs.getInt("risk_score"));
                    row.put("tool_status", rs.getString("tool_status"));
                    row.put("tool_duration_ms", rs.getObject("tool_duration_ms"));
                    row.put("content_size", rs.getObject("content_size"));
                    row.put("content_sampled", rs.getInt("content_sampled"));
                    row.put("dlp_masked", rs.getInt("dlp_masked"));
                    row.put("occurred_at", rs.getString("occurred_at"));
                    return row;
                },
                tenantId,
                sessionId);
    }

    /**
     * Get all steps for a specific trace_id.
     */
    public List<Map<String, Object>> traceChain(String tenantId, String traceId) {
        return jdbc.query(
                """
                SELECT trace_id, session_id, tenant_id, step_id, parent_step_id, step_seq,
                       step_type, layer, user_id, device_id,
                       input_role, input_content_hash,
                       tool_name, tool_args_hash, tool_args, tool_decision, rule_id, reason_code, risk_score,
                       tool_status, tool_duration_ms,
                       content_size, content_sampled, dlp_masked, occurred_at
                FROM tb_agent_trace
                WHERE tenant_id = ? AND trace_id = ?
                ORDER BY step_seq
                """,
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("trace_id", rs.getString("trace_id"));
                    row.put("session_id", rs.getString("session_id"));
                    row.put("tenant_id", rs.getString("tenant_id"));
                    row.put("step_id", rs.getString("step_id"));
                    row.put("parent_step_id", rs.getString("parent_step_id"));
                    row.put("step_seq", rs.getInt("step_seq"));
                    row.put("step_type", rs.getString("step_type"));
                    row.put("layer", rs.getString("layer"));
                    row.put("user_id", rs.getString("user_id"));
                    row.put("device_id", rs.getString("device_id"));
                    row.put("input_role", rs.getString("input_role"));
                    row.put("input_content_hash", rs.getString("input_content_hash"));
                    row.put("tool_name", rs.getString("tool_name"));
                    row.put("tool_args_hash", rs.getString("tool_args_hash"));
                    row.put("tool_args", rs.getString("tool_args"));
                    row.put("tool_decision", rs.getString("tool_decision"));
                    row.put("rule_id", rs.getString("rule_id"));
                    row.put("reason_code", rs.getString("reason_code"));
                    row.put("risk_score", rs.getInt("risk_score"));
                    row.put("tool_status", rs.getString("tool_status"));
                    row.put("tool_duration_ms", rs.getObject("tool_duration_ms"));
                    row.put("content_size", rs.getObject("content_size"));
                    row.put("content_sampled", rs.getInt("content_sampled"));
                    row.put("dlp_masked", rs.getInt("dlp_masked"));
                    row.put("occurred_at", rs.getString("occurred_at"));
                    return row;
                },
                tenantId,
                traceId);
    }

    /**
     * Search trace steps with optional filters.
     */
    public List<Map<String, Object>> search(
            String tenantId,
            String toolName,
            String stepType,
            String toolDecision,
            int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT trace_id, session_id, step_id, step_seq, step_type, layer,
                       tool_name, tool_args_hash, tool_decision, rule_id, reason_code, risk_score,
                       tool_status, tool_duration_ms, occurred_at
                FROM tb_agent_trace
                WHERE tenant_id = ?
                """);
        if (toolName != null && !toolName.isBlank()) {
            sql.append(" AND tool_name = ?");
        }
        if (stepType != null && !stepType.isBlank()) {
            sql.append(" AND step_type = ?");
        }
        if (toolDecision != null && !toolDecision.isBlank()) {
            sql.append(" AND tool_decision = ?");
        }
        sql.append(" ORDER BY occurred_at DESC LIMIT ?");

        var args = new java.util.ArrayList<Object>();
        args.add(tenantId);
        if (toolName != null && !toolName.isBlank()) args.add(toolName);
        if (stepType != null && !stepType.isBlank()) args.add(stepType);
        if (toolDecision != null && !toolDecision.isBlank()) args.add(toolDecision);
        args.add(Math.min(limit, 500));

        return jdbc.query(sql.toString(), (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("trace_id", rs.getString("trace_id"));
            row.put("session_id", rs.getString("session_id"));
            row.put("step_id", rs.getString("step_id"));
            row.put("step_seq", rs.getInt("step_seq"));
            row.put("step_type", rs.getString("step_type"));
            row.put("layer", rs.getString("layer"));
            row.put("tool_name", rs.getString("tool_name"));
            row.put("tool_args_hash", rs.getString("tool_args_hash"));
            row.put("tool_decision", rs.getString("tool_decision"));
            row.put("rule_id", rs.getString("rule_id"));
            row.put("reason_code", rs.getString("reason_code"));
            row.put("risk_score", rs.getInt("risk_score"));
            row.put("tool_status", rs.getString("tool_status"));
            row.put("tool_duration_ms", rs.getObject("tool_duration_ms"));
            row.put("occurred_at", rs.getString("occurred_at"));
            return row;
        }, args.toArray());
    }
}
