package io.virbius.control.audit;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * C5: per-session checkpoint maintained from trace ingest. Keeps the last
 * chain sequence, last event time, event count and observed tool names so a
 * session's context survives a Redis eviction and stays queryable as
 * interruption/resume evidence.
 */
@Service
public class SessionCheckpointService {

    private final JdbcTemplate jdbc;

    public SessionCheckpointService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void onTraceEvent(String tenantId, String sessionId, long chainSeq,
                             String occurredAt, String toolName) {
        if (tenantId == null || tenantId.isBlank() || sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT last_chain_seq, event_count, tool_names FROM tb_session_checkpoint "
                            + "WHERE tenant_id = ? AND session_id = ?",
                    tenantId, sessionId);
            long maxSeq = chainSeq;
            long count = 1;
            Set<String> tools = new LinkedHashSet<>();
            if (toolName != null && !toolName.isBlank()) {
                tools.add(toolName);
            }
            if (!rows.isEmpty()) {
                Map<String, Object> row = rows.get(0);
                maxSeq = Math.max(maxSeq, ((Number) row.get("last_chain_seq")).longValue());
                count = ((Number) row.get("event_count")).longValue() + 1;
                String existing = row.get("tool_names") != null ? row.get("tool_names").toString() : "";
                for (String t : existing.split(",")) {
                    if (!t.isBlank()) {
                        tools.add(t.trim());
                    }
                }
            }
            String toolList = String.join(",", tools);
            if (toolList.length() > 512) {
                toolList = toolList.substring(0, 512);
            }
            if (rows.isEmpty()) {
                jdbc.update(
                        "INSERT INTO tb_session_checkpoint (tenant_id, session_id, last_chain_seq, "
                                + "last_occurred_at, event_count, tool_names, saved_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                        tenantId, sessionId, maxSeq, occurredAt, count, toolList,
                        java.sql.Timestamp.from(java.time.Instant.now()));
            } else {
                jdbc.update(
                        "UPDATE tb_session_checkpoint SET last_chain_seq = ?, last_occurred_at = ?, "
                                + "event_count = ?, tool_names = ?, saved_at = ? "
                                + "WHERE tenant_id = ? AND session_id = ?",
                        maxSeq, occurredAt, count, toolList,
                        java.sql.Timestamp.from(java.time.Instant.now()), tenantId, sessionId);
            }
        } catch (Exception e) {
            // best-effort.
        }
    }

    public Map<String, Object> get(String tenantId, String sessionId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM tb_session_checkpoint WHERE tenant_id = ? AND session_id = ?",
                tenantId, sessionId);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
