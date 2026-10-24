package io.virbius.control.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.control.config.SqlDialectConfig;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class AuditEventIngestor {

    private final JdbcTemplate jdbc;
    private final SqlDialectConfig dialect;
    private final HashChainOrchestrator hashChain;
    private final AlertService alertService;
    private final ObjectMapper mapper = new ObjectMapper();

    private final String insertIgnorePrefix;
    private final String hourExpr;

    public AuditEventIngestor(
            JdbcTemplate jdbc,
            SqlDialectConfig dialect,
            HashChainOrchestrator hashChain,
            AlertService alertService) {
        this.jdbc = jdbc;
        this.dialect = dialect;
        this.hashChain = hashChain;
        this.alertService = alertService;
        if (dialect.isMysql()) {
            this.insertIgnorePrefix = "INSERT IGNORE";
            this.hourExpr = "DATE_FORMAT(NOW(), '%Y-%m-%d %H:00:00')";
        } else if (dialect.isPostgresql()) {
            this.insertIgnorePrefix = "INSERT";
            this.hourExpr = "DATE_TRUNC('hour', NOW())";
        } else {
            this.insertIgnorePrefix = "INSERT OR IGNORE";
            this.hourExpr = "strftime('%Y-%m-%d %H:00:00', 'now')";
        }
    }

    public IngestResult ingestEvent(Map<String, Object> event) {
        try {
            String traceId = str(event.get("trace_id"));
            String tenantId = str(event.get("tenant_id"));
            if (traceId.isBlank() || tenantId.isBlank()) {
                return IngestResult.rejected("missing trace_id or tenant_id");
            }
            String eventId = str(event.get("event_id"));
            if (eventId.isBlank()) {
                return IngestResult.rejected("missing event_id");
            }

            // Insert FIRST, chain SECOND. The chain head lives in shared Redis and
            // cannot join the DB transaction, so the only safe order is
            // insert -> advance head -> stamp the row: a redelivery (Kafka is
            // at-least-once) or a crash can then no longer advance the head
            // without a row — the old chain-first order turned every duplicate
            // insert into a permanent seq hole that HashChainVerifier reported
            // as "seq gap". Mirrors TraceEventIngestor's order. The row starts
            // with the schema defaults (audit_seq=0, empty hashes); audit_seq=0
            // marks "not chained yet" and is repaired by the duplicate path
            // below on redelivery.
            String insertSql = insertIgnorePrefix
                    + " INTO tb_audit_events ("
                    + "  event_id, trace_id, trace_id_source, tenant_id, scene, layer, tool_name,"
                    + "  rule_id, rule_revision, reason_code, effective_action, max_risk_score,"
                    + "  rollout_state, canary_percent, in_canary_bucket, degraded, sampled_allow,"
                    + "  sample_rate_allow, intercepted_at, user_id, device_id, session_id,"
                    + "  agent_aid, app_id, subject_source, token_iss,"
                    + "  audit_seq, prev_hash, curr_hash"
                    + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, '', '')";
            int updated = jdbc.update(insertSql,
                    eventId,
                    traceId,
                    str(event.get("trace_id_source")),
                    tenantId,
                    sceneOf(event),
                    str(event.get("layer")),
                    str(event.get("tool_name")),
                    str(event.get("rule_id")),
                    intVal(event.get("rule_revision")),
                    str(event.get("reason_code")),
                    str(event.get("effective_action")),
                    intVal(event.get("max_risk_score")),
                    str(event.get("rollout_state")),
                    event.get("canary_percent") != null ? intVal(event.get("canary_percent")) : null,
                    boolInt(event.get("in_canary_bucket")),
                    boolInt(event.get("degraded")),
                    boolInt(event.get("sampled_allow")),
                    doubleOrNull(event.get("sample_rate_allow")),
                    str(event.get("intercepted_at")),
                    str(event.get("user_id")),
                    str(event.get("device_id")),
                    nullIfBlank(str(event.get("session_id"))),
                    nullIfBlank(str(event.get("agent_aid"))),
                    nullIfBlank(str(event.get("app_id"))),
                    nullIfBlank(str(event.get("subject_source"))),
                    nullIfBlank(str(event.get("token_iss"))));
            if (updated == 0) {
                // Duplicate insert: either a benign redelivery of an already
                // chained row, or a predecessor that crashed between its insert
                // and its chain step. Only the latter still needs chaining.
                Long chained = jdbc.queryForObject(
                        "SELECT audit_seq FROM tb_audit_events WHERE tenant_id = ? AND event_id = ?",
                        Long.class, tenantId, eventId);
                if (chained == null || chained != 0) {
                    return IngestResult.duplicated();
                }
            }

            hashChain.chain(tenantId, event);
            if (event.get("audit_seq") instanceof Number n && n.longValue() > 0) {
                jdbc.update(
                        "UPDATE tb_audit_events SET audit_seq = ?, prev_hash = ?, curr_hash = ? "
                        + "WHERE tenant_id = ? AND event_id = ?",
                        n.longValue(),
                        str(event.get("prev_hash")),
                        str(event.get("curr_hash")),
                        tenantId,
                        eventId);
            }
            bumpRequestStats(tenantId, sceneOf(event), str(event.get("layer")), event);
            alertService.onAuditEvent(tenantId, event);
            return IngestResult.accepted();
        } catch (Exception e) {
            return IngestResult.rejected(e.getMessage());
        }
    }

    public Map<String, Object> ingestPayloadJson(String payload) throws Exception {
        IngestResult r = ingestPayload(payload);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", r.status());
        if (r.message() != null) {
            out.put("message", r.message());
        }
        return out;
    }

    public IngestResult ingestPayload(String payload) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> event = mapper.readValue(payload, Map.class);
        return ingestEvent(unwrapIfWrapped(event));
    }

    /**
     * Engine {@code AuditWriter} publishes {@code {"payload": "<event-json>"}}.
     * Ingest the inner event so tenant_id / trace_id are visible.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> unwrapIfWrapped(Map<String, Object> event) throws Exception {
        if (event.containsKey("tenant_id") || event.containsKey("trace_id")) {
            return event;
        }
        Object inner = event.get("payload");
        if (inner instanceof String s && !s.isBlank()) {
            return mapper.readValue(s, Map.class);
        }
        if (inner instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return event;
    }

    public Long countForStatus(String sql, Object... args) {
        Long val = jdbc.queryForObject(sql, Long.class, args);
        return val != null ? val : 0L;
    }

    /**
     * Edge events use `event_type` (tool_call/memory_write/output_review/...)
     * and have no `scene`; engine events use `scene`. Prefer scene, fall back
     * to event_type so edge audit rows are not stored with an empty scene.
     */
    private static String sceneOf(Map<String, Object> event) {
        String scene = str(event.get("scene"));
        return scene.isBlank() ? str(event.get("event_type")) : scene;
    }

    private void bumpRequestStats(String tenantId, String scene, String layer, Map<String, Object> event) {
        String effective = str(event.get("effective_action"));
        double weight = 1.0;
        if (Boolean.TRUE.equals(event.get("sampled_allow")) && "allow".equals(effective)) {
            Object rate = event.get("sample_rate_allow");
            if (rate instanceof Number n && n.doubleValue() > 0 && n.doubleValue() < 1) {
                weight = 1.0 / n.doubleValue();
            }
        }
        int increment = (int) Math.max(1, Math.round(weight));
        String onConflict;
        if (dialect.isMysql()) {
            onConflict = "ON DUPLICATE KEY UPDATE cnt_total = cnt_total + VALUES(cnt_total)";
        } else {
            onConflict = "ON CONFLICT(tenant_id, scene, layer, hour_bucket) DO UPDATE SET cnt_total = cnt_total + excluded.cnt_total";
        }
        String sql = "INSERT INTO tb_tenant_request_stats_1h (tenant_id, scene, layer, hour_bucket, cnt_total) "
                + "VALUES (?, ?, ?, " + hourExpr + ", ?) " + onConflict;
        jdbc.update(sql,
                tenantId,
                scene.isBlank() ? "default" : scene,
                layer.isBlank() ? "edge" : layer,
                increment);
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static String nullIfBlank(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static int intVal(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(str(o));
        } catch (Exception e) {
            return 0;
        }
    }

    private static Double doubleOrNull(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(str(o));
        } catch (Exception e) {
            return null;
        }
    }

    private static Integer boolInt(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Boolean b) {
            return b ? 1 : 0;
        }
        return "true".equalsIgnoreCase(str(o)) ? 1 : 0;
    }

    public record IngestResult(String status, String message) {
        static IngestResult accepted() {
            return new IngestResult("accepted", null);
        }

        static IngestResult duplicated() {
            return new IngestResult("duplicated", null);
        }

        static IngestResult rejected(String message) {
            return new IngestResult("rejected", message);
        }

    }
}
