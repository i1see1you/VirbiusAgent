package io.virbius.control.integrate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ObservationQueryService {

    private final JdbcTemplate jdbc;
    private final int heartbeatIntervalSecs;
    private final int eventWindowSecs;

    public ObservationQueryService(
            JdbcTemplate jdbc,
            @Value("${virbius.integrate.liveness.heartbeat-interval-secs:60}") int heartbeatIntervalSecs,
            @Value("${virbius.integrate.liveness.event-window-secs:300}") int eventWindowSecs) {
        this.jdbc = jdbc;
        this.heartbeatIntervalSecs = heartbeatIntervalSecs;
        this.eventWindowSecs = eventWindowSecs;
    }

    public List<Map<String, Object>> query(String tenantId, String appId, String callId,
            String since, String until, int limit) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT event_id, call_id, phase, device_id, session_id,"
                + " trace_id, tool_name, duration_ms, args_repr, result_repr, error, source,"
                + " package_version, observed_at FROM " + ToolObservationIngestor.OBSERVATION_TABLE
                + " WHERE tenant_id = ? AND app_id = ?");
        args.add(tenantId);
        args.add(appId);
        if (callId != null && !callId.isBlank()) {
            sql.append(" AND call_id = ?");
            args.add(callId);
        }
        if (since != null && !since.isBlank()) {
            sql.append(" AND observed_at >= ?");
            args.add(normalizeTsBound(since));
        }
        if (until != null && !until.isBlank()) {
            sql.append(" AND observed_at <= ?");
            args.add(normalizeTsBound(until));
        }
        sql.append(" ORDER BY observed_at ASC,"
                + " CASE phase WHEN 'started' THEN 0 WHEN 'completed' THEN 1 ELSE 2 END ASC"
                + " LIMIT ?");
        args.add(Math.max(1, Math.min(limit, 5000)));
        return jdbc.query(sql.toString(), (rs, rowNum) -> readObservation(rs), args.toArray());
    }

    public List<Map<String, Object>> coverage(String tenantId, String appId) {        String sql = "SELECT tool_name, mcp_name, risk_class, matched_rules, confidence,"
                + " recommended_mode, rule_table_version, package_version, bound_mode, first_seen, last_seen FROM "
                + ToolObservationIngestor.INVENTORY_TABLE
                + " WHERE tenant_id = ? AND app_id = ? ORDER BY recommended_mode DESC, tool_name ASC";
        return jdbc.query(sql, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tool_name", rs.getString("tool_name"));
            row.put("mcp_name", rs.getString("mcp_name"));
            row.put("risk_class", rs.getString("risk_class"));
            row.put("matched_rules", rs.getString("matched_rules"));
            row.put("confidence", rs.getString("confidence"));
            row.put("recommended_mode", rs.getString("recommended_mode"));
            row.put("rule_table_version", rs.getString("rule_table_version"));
            row.put("package_version", rs.getString("package_version"));
            row.put("bound_mode", rs.getString("bound_mode"));
            row.put("first_seen", rs.getString("first_seen"));
            row.put("last_seen", rs.getString("last_seen"));
            return row;
        }, tenantId, appId);
    }

    public List<Map<String, Object>> apps(String tenantId) {
        String sql = "SELECT app_id, MAX(last_seen) AS last_seen FROM ("
                + " SELECT app_id, observed_at AS last_seen FROM " + ToolObservationIngestor.OBSERVATION_TABLE
                + " WHERE tenant_id = ?"
                + " UNION ALL"
                + " SELECT app_id, last_seen FROM " + ToolObservationIngestor.INVENTORY_TABLE
                + " WHERE tenant_id = ?"
                + " UNION ALL"
                + " SELECT app_id, COALESCE(last_seen, last_event_ts) AS last_seen FROM "
                + ToolObservationIngestor.LIVENESS_TABLE
                + " WHERE tenant_id = ?"
                + ") t GROUP BY app_id ORDER BY last_seen IS NULL, last_seen DESC";
        return jdbc.query(sql, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("app_id", rs.getString("app_id"));
            row.put("last_seen", rs.getString("last_seen"));
            return row;
        }, tenantId, tenantId, tenantId);
    }

    public List<Map<String, Object>> liveness(String tenantId, String appId) {
        String sql = "SELECT device_id, last_seen, last_event_ts, dropped_total, package_version, mode,"
                + " updated_at FROM " + ToolObservationIngestor.LIVENESS_TABLE
                + " WHERE tenant_id = ? AND app_id = ? ORDER BY device_id ASC";
        return jdbc.query(sql, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            String device = rs.getString("device_id");
            String lastSeen = rs.getString("last_seen");
            String lastEvent = rs.getString("last_event_ts");
            row.put("device_id", device);
            row.put("last_seen", lastSeen);
            row.put("last_event_ts", lastEvent);
            row.put("dropped_total", rs.getInt("dropped_total"));
            row.put("package_version", rs.getString("package_version"));
            row.put("mode", rs.getString("mode"));
            row.put("state", livenessState(lastSeen, lastEvent));
            row.put("last_seen_age_seconds", ageSeconds(lastSeen));
            row.put("last_event_age_seconds", ageSeconds(lastEvent));
            return row;
        }, tenantId, appId);
    }

    private String livenessState(String lastSeen, String lastEvent) {
        Long heartbeatAge = ageSeconds(lastSeen);
        Long eventAge = ageSeconds(lastEvent);
        boolean heartbeatFresh = heartbeatAge != null && heartbeatAge <= (long) heartbeatIntervalSecs * 2;
        boolean eventFresh = eventAge != null && eventAge <= eventWindowSecs;
        if (heartbeatFresh || eventFresh) {
            return "live";
        }
        if (heartbeatAge != null || eventAge != null) {
            return "stale";
        }
        return "never-seen";
    }

    private Long ageSeconds(String rfc3339) {
        if (rfc3339 == null || rfc3339.isBlank()) {
            return null;
        }
        try {
            Instant then = Instant.parse(rfc3339);
            return Math.max(0, Duration.between(then, Instant.now()).getSeconds());
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> readObservation(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("event_id", rs.getString("event_id"));
        row.put("call_id", rs.getString("call_id"));
        row.put("phase", rs.getString("phase"));
        row.put("device_id", rs.getString("device_id"));
        row.put("session_id", rs.getString("session_id"));
        row.put("trace_id", rs.getString("trace_id"));
        row.put("tool_name", rs.getString("tool_name"));
        row.put("duration_ms", (Integer) rs.getObject("duration_ms"));
        row.put("args_repr", rs.getString("args_repr"));
        row.put("result_repr", rs.getString("result_repr"));
        row.put("error", rs.getString("error"));
        row.put("source", rs.getString("source"));
        row.put("package_version", rs.getString("package_version"));
        row.put("observed_at", rs.getString("observed_at"));
        return row;
    }

    /**
     * 归一窗口边界到 ISO-8601（与库内 observed_at 同形）：evaluation 回放侧发送
     * MySQL 'yyyy-MM-dd HH:mm:ss'（空格分隔）格式，与 'T' 分隔的 ISO 字符串直接比较
     * 会因 'T'（0x54）> 空格（0x20）将 <= 边界全部漏掉，统一转成 ISO 形式再比较。
     */
    static String normalizeTsBound(String ts) {
        if (ts == null || ts.length() < 19 || ts.charAt(10) != ' ' || ts.charAt(13) != ':') {
            return ts;
        }
        return ts.substring(0, 10) + "T" + ts.substring(11);
    }
}
