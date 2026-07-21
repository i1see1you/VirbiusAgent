package io.virbius.control.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.control.config.SqlDialectConfig;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Ingests agent decision chain trace events from Redis Stream into {@code tb_agent_trace}.
 *
 * <p>Each event represents one step in the Agent decision chain:
 * input → reasoning → tool_call → tool_result → output.
 */
@Component
public class TraceEventIngestor {

    private static final Logger log = LoggerFactory.getLogger(TraceEventIngestor.class);

    private final JdbcTemplate jdbc;
    private final SqlDialectConfig dialect;
    private final ObjectMapper mapper = new ObjectMapper();

    private final String insertIgnorePrefix;

    public TraceEventIngestor(JdbcTemplate jdbc, SqlDialectConfig dialect) {
        this.jdbc = jdbc;
        this.dialect = dialect;
        if (dialect.isMysql()) {
            this.insertIgnorePrefix = "INSERT IGNORE";
        } else if (dialect.isPostgresql()) {
            this.insertIgnorePrefix = "INSERT";
        } else {
            this.insertIgnorePrefix = "INSERT OR IGNORE";
        }
    }

    public IngestResult ingestEvent(Map<String, Object> event) {
        try {
            String traceId = str(event.get("trace_id"));
            String tenantId = str(event.get("tenant_id"));
            String stepId = str(event.get("step_id"));
            if (traceId.isBlank() || tenantId.isBlank() || stepId.isBlank()) {
                return IngestResult.rejected("missing trace_id, tenant_id, or step_id");
            }

            String sql = insertIgnorePrefix
                    + " INTO tb_agent_trace ("
                    + "  trace_id, session_id, tenant_id, step_id, parent_step_id, step_seq,"
                    + "  step_type, layer, user_id, device_id,"
                    + "  input_role, input_content_hash,"
                    + "  tool_name, tool_args_hash, tool_args, tool_decision, rule_id, reason_code, risk_score,"
                    + "  tool_status, tool_duration_ms,"
                    + "  content_size, content_sampled, dlp_masked, occurred_at"
                    + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

            int updated = jdbc.update(sql,
                    traceId,
                    str(event.get("session_id")),
                    tenantId,
                    stepId,
                    nullIfBlank(str(event.get("parent_step_id"))),
                    intVal(event.get("step_seq")),
                    str(event.get("step_type")),
                    strOr(event.get("layer"), "edge"),
                    nullIfBlank(str(event.get("user_id"))),
                    nullIfBlank(str(event.get("device_id"))),
                    nullIfBlank(str(event.get("input_role"))),
                    nullIfBlank(str(event.get("input_content_hash"))),
                    nullIfBlank(str(event.get("tool_name"))),
                    nullIfBlank(str(event.get("tool_args_hash"))),
                    toolArgs(event.get("tool_args")),
                    nullIfBlank(str(event.get("tool_decision"))),
                    nullIfBlank(str(event.get("rule_id"))),
                    nullIfBlank(str(event.get("reason_code"))),
                    intValOrNull(event.get("risk_score")),
                    nullIfBlank(str(event.get("tool_status"))),
                    intValOrNull(event.get("tool_duration_ms")),
                    intValOrNull(event.get("content_size")),
                    boolInt(event.get("content_sampled")),
                    boolInt(event.get("dlp_masked")),
                    str(event.get("occurred_at")));

            if (updated == 0) {
                return IngestResult.duplicated();
            }
            return IngestResult.accepted();
        } catch (Exception e) {
            log.warn("trace ingest failed: {}", e.getMessage());
            return IngestResult.rejected(e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    public IngestResult ingestPayload(String payload) {
        try {
            Map<String, Object> event = mapper.readValue(payload, Map.class);
            return ingestEvent(event);
        } catch (Exception e) {
            return IngestResult.rejected(e.getMessage());
        }
    }

    public Long countForStatus(String sql, Object... args) {
        try {
            Long val = jdbc.queryForObject(sql, Long.class, args);
            return val != null ? val : 0L;
        } catch (Exception e) {
            return null;
        }
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static String strOr(Object o, String def) {
        String s = str(o);
        return s.isBlank() ? def : s;
    }

    private static String nullIfBlank(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static int intVal(Object o) {
        if (o instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(str(o));
        } catch (Exception e) {
            return 0;
        }
    }

    private static Integer intValOrNull(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(str(o));
        } catch (Exception e) {
            return null;
        }
    }

    private static int boolInt(Object o) {
        if (o == null) return 0;
        if (o instanceof Boolean b) return b ? 1 : 0;
        return "true".equalsIgnoreCase(str(o)) ? 1 : 0;
    }

    private String toolArgs(Object o) {
        if (o == null) return null;
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            log.debug("failed to serialize tool_args: {}", e.getMessage());
            return null;
        }
    }

    public record IngestResult(String status, String message) {
        static IngestResult accepted() { return new IngestResult("accepted", null); }
        static IngestResult duplicated() { return new IngestResult("duplicated", null); }
        static IngestResult rejected(String message) { return new IngestResult("rejected", message); }
    }
}
