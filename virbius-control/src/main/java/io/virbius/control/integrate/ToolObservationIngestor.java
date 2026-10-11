package io.virbius.control.integrate;

import io.virbius.control.config.SqlDialectConfig;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class ToolObservationIngestor {

    public static final String OBSERVATION_TABLE = "tb_tool_observation";
    public static final String INVENTORY_TABLE = "tb_tool_inventory";
    public static final String LIVENESS_TABLE = "tb_obs_app_liveness";

    private static final Set<String> VALID_PHASES = Set.of("started", "completed", "error");

    private static final String OBSERVATION_COLUMNS =
            " (event_id, call_id, phase, tenant_id, app_id, device_id, session_id, trace_id,"
                    + " tool_name, duration_ms, args_repr, result_repr, error, source, package_version, observed_at)";
    private static final String OBSERVATION_VALUES =
            " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String LIVENESS_COLUMNS =
            " (tenant_id, app_id, device_id, last_seen, last_event_ts, dropped_total,"
                    + " package_version, mode, updated_at)";
    private static final String LIVENESS_VALUES =
            " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String LIVENESS_EVENT_COLUMNS =
            " (tenant_id, app_id, device_id, last_event_ts, dropped_total, updated_at)";
    private static final String LIVENESS_EVENT_VALUES =
            " VALUES (?, ?, ?, ?, 0, ?)";
    private static final String INVENTORY_COLUMNS =
            " (tenant_id, app_id, tool_name, mcp_name, risk_class, matched_rules, confidence,"
                    + " recommended_mode, rule_table_version, package_version, bound_mode, first_seen, last_seen)";
    private static final String INVENTORY_VALUES =
            " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private final JdbcTemplate jdbc;
    private final SqlDialectConfig dialect;

    public ToolObservationIngestor(JdbcTemplate jdbc, SqlDialectConfig dialect) {
        this.jdbc = jdbc;
        this.dialect = dialect;
    }

    public int ingestEvents(String tenantId, String appId, List<Map<String, Object>> events) {
        if (events == null || events.isEmpty()) {
            return 0;
        }
        List<Object[]> batch = new ArrayList<>();
        Map<String, String> deviceLastEvent = new HashMap<>();
        for (Map<String, Object> event : events) {
            String eventId = str(event.get("event_id"));
            String phase = str(event.get("phase"));
            if (eventId.isBlank() || !VALID_PHASES.contains(phase)) {
                continue;
            }
            String device = str(event.get("device_id"));
            String observedAt = str(event.get("observed_at"));
            batch.add(new Object[] {
                    eventId,
                    str(event.get("call_id")),
                    phase,
                    tenantId,
                    appId,
                    device,
                    str(event.get("session_id")),
                    str(event.get("trace_id")),
                    str(event.get("tool_name")),
                    intOrNull(event.get("duration_ms")),
                    str(event.get("args_repr")),
                    str(event.get("result_repr")),
                    str(event.get("error")),
                    str(event.get("source")),
                    str(event.get("package_version")),
                    observedAt,
            });
            if (!device.isBlank() && !observedAt.isBlank()) {
                deviceLastEvent.merge(device, observedAt, (a, b) -> a.compareTo(b) >= 0 ? a : b);
            }
        }
        int stored = 0;
        if (!batch.isEmpty()) {
            int[] rows = jdbc.batchUpdate(observationInsertSql(), batch);
            for (int row : rows) {
                if (row > 0) {
                    stored += row;
                }
            }
        }
        for (Map.Entry<String, String> entry : deviceLastEvent.entrySet()) {
            jdbc.update(livenessEventUpsertSql(),
                    tenantId, appId, entry.getKey(), entry.getValue(), Instant.now().toString());
        }
        return stored;
    }

    public void upsertHeartbeat(String tenantId, String appId, Map<String, Object> body) {
        String device = str(body.get("device_id"));
        if (device.isBlank()) {
            device = "unknown-device";
        }
        String now = str(body.get("ts"));
        if (now.isBlank()) {
            now = Instant.now().toString();
        }
        Integer dropped = intOrNull(body.get("dropped_total"));
        jdbc.update(livenessUpsertSql(),
                tenantId, appId, device,
                now, null,
                dropped == null ? 0 : dropped,
                str(body.get("package_version")),
                str(body.get("mode")),
                now);
    }

    public int upsertInventory(String tenantId, String appId, List<Map<String, Object>> tools) {
        if (tools == null || tools.isEmpty()) {
            return 0;
        }
        String now = Instant.now().toString();
        int count = 0;
        for (Map<String, Object> tool : tools) {
            String toolName = str(tool.get("tool_name"));
            if (toolName.isBlank()) {
                continue;
            }
            jdbc.update(inventoryUpsertSql(),
                    tenantId,
                    appId,
                    toolName,
                    nullIfBlank(str(tool.get("mcp_name"))),
                    defaultLow(str(tool.get("risk_hint"))),
                    joinMatched(tool.get("matched_rules")),
                    str(tool.get("confidence")),
                    str(tool.get("recommended_mode")),
                    str(tool.get("rule_table_version")),
                    str(tool.get("package_version")),
                    str(tool.get("bound_mode")),
                    now,
                    now);
            count++;
        }
        return count;
    }

    String observationInsertSql() {
        String prefix = dialect.isMysql() ? "INSERT IGNORE INTO "
                : dialect.isPostgresql() ? "INSERT INTO "
                : "INSERT OR IGNORE INTO ";
        String conflict = dialect.isPostgresql() ? " ON CONFLICT (event_id) DO NOTHING" : "";
        return prefix + OBSERVATION_TABLE + OBSERVATION_COLUMNS + OBSERVATION_VALUES + conflict;
    }

    String livenessUpsertSql() {
        return upsertSql(
                LIVENESS_TABLE, LIVENESS_COLUMNS, LIVENESS_VALUES, "(tenant_id, app_id, device_id)",
                "last_seen=excluded.last_seen, dropped_total=excluded.dropped_total,"
                        + " package_version=excluded.package_version, mode=excluded.mode, updated_at=excluded.updated_at",
                "last_seen=VALUES(last_seen), dropped_total=VALUES(dropped_total),"
                        + " package_version=VALUES(package_version), mode=VALUES(mode), updated_at=VALUES(updated_at)");
    }

    String livenessEventUpsertSql() {
        return upsertSql(
                LIVENESS_TABLE, LIVENESS_EVENT_COLUMNS, LIVENESS_EVENT_VALUES, "(tenant_id, app_id, device_id)",
                "last_event_ts=excluded.last_event_ts, updated_at=excluded.updated_at",
                "last_event_ts=VALUES(last_event_ts), updated_at=VALUES(updated_at)");
    }

    String inventoryUpsertSql() {
        return upsertSql(
                INVENTORY_TABLE, INVENTORY_COLUMNS, INVENTORY_VALUES, "(tenant_id, app_id, tool_name)",
                "mcp_name=excluded.mcp_name, risk_class=excluded.risk_class, matched_rules=excluded.matched_rules,"
                        + " confidence=excluded.confidence, recommended_mode=excluded.recommended_mode,"
                        + " rule_table_version=excluded.rule_table_version, package_version=excluded.package_version,"
                        + " bound_mode=excluded.bound_mode, last_seen=excluded.last_seen",
                "mcp_name=VALUES(mcp_name), risk_class=VALUES(risk_class), matched_rules=VALUES(matched_rules),"
                        + " confidence=VALUES(confidence), recommended_mode=VALUES(recommended_mode),"
                        + " rule_table_version=VALUES(rule_table_version), package_version=VALUES(package_version),"
                        + " bound_mode=VALUES(bound_mode), last_seen=VALUES(last_seen)");
    }

    private String upsertSql(String table, String columns, String values, String conflictKey,
            String pgAssignments, String mysqlAssignments) {
        if (dialect.isMysql()) {
            return "INSERT INTO " + table + columns + values + " ON DUPLICATE KEY UPDATE " + mysqlAssignments;
        }
        return "INSERT INTO " + table + columns + values + " ON CONFLICT " + conflictKey + " DO UPDATE SET " + pgAssignments;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String nullIfBlank(String value) {
        return value.isBlank() ? null : value;
    }

    private static String defaultLow(String value) {
        if (value.equals("low") || value.equals("medium") || value.equals("high")) {
            return value;
        }
        return "low";
    }

    private static String joinMatched(Object value) {
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (Object item : list) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(item);
                if (sb.length() > 480) {
                    break;
                }
            }
            return sb.toString();
        }
        return str(value);
    }

    private static Integer intOrNull(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
