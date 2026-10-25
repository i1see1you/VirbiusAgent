package io.virbius.control.audit;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * C4: alert entity. Alerts are generated from ingested high-risk audit events
 * and act as the entry point for traceability: each alert carries a
 * {@code trace_id}, which {@link DecisionBomService} expands into the full
 * trajectory, judgement basis and disposal process.
 *
 * <p>Alerts aggregate <b>per answer</b> ({@code trace_id}): a blocked
 * streaming answer emits one deny audit event per review window, and one
 * alert per window would flood the console — the Decision BOM already lists
 * every window event of the trace. Blank {@code trace_id} (uncorrelated
 * event) falls back to one alert per event.
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    private final JdbcTemplate jdbc;
    private final int riskThreshold;

    public AlertService(
            JdbcTemplate jdbc,
            @Value("${virbius.alert.risk-threshold:80}") int riskThreshold) {
        this.jdbc = jdbc;
        this.riskThreshold = riskThreshold;
    }

    /** Called by the audit ingestor for every accepted event. */
    public void onAuditEvent(String tenantId, Map<String, Object> event) {
        try {
            int risk = intVal(event.get("max_risk_score"));
            String action = str(event.get("effective_action"));
            boolean enforcing = "block".equalsIgnoreCase(action)
                    || "deny".equalsIgnoreCase(action)
                    || "challenge".equalsIgnoreCase(action);
            if (risk < riskThreshold || !enforcing) {
                return;
            }
            String alertId = alertIdFor(tenantId, str(event.get("trace_id")), str(event.get("event_id")));
            String sessionId = str(event.get("session_id"));
            int inserted = jdbc.update(
                    "INSERT INTO tb_alert (alert_id, tenant_id, trace_id, session_id, scene, rule_id, "
                            + "reason_code, risk_score, effective_action, status) "
                            + "SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, 'open' "
                            + "WHERE NOT EXISTS (SELECT 1 FROM tb_alert WHERE alert_id = ?)",
                    alertId, tenantId, str(event.get("trace_id")),
                    sessionId.isBlank() ? null : sessionId,
                    str(event.get("scene")),
                    str(event.get("rule_id")), str(event.get("reason_code")), risk, action, alertId);
            if (inserted > 0) {
                log.info("alert raised: tenant={} trace={} rule={} risk={} action={}",
                        tenantId, event.get("trace_id"), event.get("rule_id"), risk, action);
            }
        } catch (Exception e) {
            log.warn("alert generation failed: {}", e.getMessage());
        }
    }

    /**
     * One alert per answer: streaming output review emits one deny event per
     * window, so the alert id keys on {@code (tenant_id, trace_id)} — the
     * WHERE NOT EXISTS insert then collapses the whole answer to a single
     * alert (first window wins; every window stays visible in the BOM). The
     * tenant is part of the id because trace ids are caller-provided and may
     * repeat across tenants. Blank trace falls back to the event id.
     */
    static String alertIdFor(String tenantId, String traceId, String eventId) {
        if (traceId != null && !traceId.isBlank()) {
            return "alert-" + tenantId + "-" + traceId;
        }
        return "alert-" + (eventId == null || eventId.isBlank()
                ? UUID.randomUUID().toString() : eventId);
    }

    public List<Map<String, Object>> list(String tenantId, String status, int limit) {
        if (status != null && !status.isBlank()) {
            return jdbc.queryForList(
                    "SELECT * FROM tb_alert WHERE tenant_id = ? AND status = ? ORDER BY created_at DESC LIMIT ?",
                    tenantId, status, Math.min(limit, 500));
        }
        return jdbc.queryForList(
                "SELECT * FROM tb_alert WHERE tenant_id = ? ORDER BY created_at DESC LIMIT ?",
                tenantId, Math.min(limit, 500));
    }

    public Map<String, Object> get(String tenantId, String alertId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM tb_alert WHERE tenant_id = ? AND alert_id = ?", tenantId, alertId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public boolean ack(String tenantId, String alertId, String operator) {
        return jdbc.update(
                "UPDATE tb_alert SET status = 'acked', acked_by = ?, acked_at = ? "
                        + "WHERE tenant_id = ? AND alert_id = ? AND status = 'open'",
                operator != null ? operator : "unknown", java.sql.Timestamp.from(Instant.now()), tenantId, alertId) > 0;
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static int intVal(Object o) {
        if (o instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(str(o));
        } catch (Exception e) {
            return 0;
        }
    }
}
