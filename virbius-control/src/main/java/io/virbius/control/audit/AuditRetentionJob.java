package io.virbius.control.audit;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * A5: retention archival. Default mode is {@code archive} — expired rows are
 * *copied* into the {@code *_archive} mirrors and the originals are kept.
 * Deletion is intentionally left as a manual ops action (see
 * deploy/scripts/audit-insert-only-grants.sql) so evaluation environments never
 * lose the "complete historical link" the outline's preconditions require.
 *
 * <p>Idempotent: rows already present in the archive are skipped by business key.
 */
@Component
public class AuditRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(AuditRetentionJob.class);

    private final JdbcTemplate jdbc;
    private final boolean enabled;

    public AuditRetentionJob(
            JdbcTemplate jdbc,
            @Value("${virbius.audit.retention.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.enabled = enabled;
    }

    /** Runs daily at 03:30 UTC. */
    @Scheduled(cron = "0 30 3 * * *", zone = "UTC")
    public void run() {
        if (!enabled) {
            return;
        }
        try {
            archiveAll();
        } catch (Exception e) {
            log.error("retention archive failed: {}", e.getMessage());
        }
    }

    public Map<String, Object> archiveAll() {
        Map<String, Object> summary = new LinkedHashMap<>();
        List<Map<String, Object>> policies = jdbc.queryForList(
                "SELECT tenant_id, table_name, retention_days, mode FROM tb_tenant_retention_policy");
        int archivedAudit = 0;
        int archivedTrace = 0;
        for (Map<String, Object> p : policies) {
            String tenantId = String.valueOf(p.get("tenant_id"));
            String table = String.valueOf(p.get("table_name"));
            int days = ((Number) p.get("retention_days")).intValue();
            String mode = String.valueOf(p.get("mode"));
            if (!"archive".equalsIgnoreCase(mode)) {
                continue;
            }
            Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);
            if ("audit_events".equals(table)) {
                archivedAudit += archiveAuditEvents(tenantId, cutoff);
            } else if ("agent_trace".equals(table)) {
                archivedTrace += archiveTrace(tenantId, cutoff);
            }
        }
        summary.put("archived_audit_events", archivedAudit);
        summary.put("archived_trace_rows", archivedTrace);
        return summary;
    }

    private int archiveAuditEvents(String tenantId, Instant cutoff) {
        return jdbc.update(
                """
                INSERT INTO tb_audit_events_archive
                    (event_id, trace_id, trace_id_source, tenant_id, scene, layer, rule_id,
                     rule_revision, reason_code, effective_action, max_risk_score, rollout_state,
                     canary_percent, in_canary_bucket, degraded, sampled_allow, sample_rate_allow,
                     intercepted_at, user_id, device_id, audit_seq, prev_hash, curr_hash, created_at)
                SELECT event_id, trace_id, trace_id_source, tenant_id, scene, layer, rule_id,
                       rule_revision, reason_code, effective_action, max_risk_score, rollout_state,
                       canary_percent, in_canary_bucket, degraded, sampled_allow, sample_rate_allow,
                       intercepted_at, user_id, device_id, audit_seq, prev_hash, curr_hash, created_at
                FROM tb_audit_events
                WHERE tenant_id = ? AND intercepted_at < ?
                  AND event_id NOT IN (SELECT event_id FROM tb_audit_events_archive)
                """,
                tenantId, cutoff.toString());
    }

    private int archiveTrace(String tenantId, Instant cutoff) {
        return jdbc.update(
                """
                INSERT INTO tb_agent_trace_archive
                    (trace_id, session_id, tenant_id, step_id, parent_step_id, step_seq, step_type,
                     layer, user_id, device_id, input_role, input_content_hash, tool_name,
                     tool_args_hash, tool_args, tool_decision, rule_id, reason_code, risk_score,
                     tool_status, tool_duration_ms, tool_result_preview, chain_seq, prev_hash,
                     curr_hash, content_ref, content_size, content_sampled, dlp_masked,
                     upstream_name, app_id, occurred_at, created_at)
                SELECT trace_id, session_id, tenant_id, step_id, parent_step_id, step_seq, step_type,
                       layer, user_id, device_id, input_role, input_content_hash, tool_name,
                       tool_args_hash, tool_args, tool_decision, rule_id, reason_code, risk_score,
                       tool_status, tool_duration_ms, tool_result_preview, chain_seq, prev_hash,
                       curr_hash, content_ref, content_size, content_sampled, dlp_masked,
                       upstream_name, app_id, occurred_at, created_at
                FROM tb_agent_trace
                WHERE tenant_id = ? AND occurred_at < ?
                  AND step_id NOT IN (SELECT step_id FROM tb_agent_trace_archive WHERE tenant_id = ?)
                """,
                tenantId, cutoff.toString(), tenantId);
    }
}
