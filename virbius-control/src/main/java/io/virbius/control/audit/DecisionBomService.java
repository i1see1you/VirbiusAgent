package io.virbius.control.audit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * A6: Decision Bill of Materials — reconstructs all inputs/context/outputs of a
 * decision from a single {@code trace_id}, by four phases (mirrors the AGT
 * Decision BOM design):
 *
 * <ol>
 *   <li><b>Audit</b> — audit events for the trace (decision, reason, risk, rule).</li>
 *   <li><b>Session</b> — the owning session, its tool counts and peak risk.</li>
 *   <li><b>Policy</b> — the rule revisions that fired (from rule history).</li>
 *   <li><b>Trace</b> — the ordered behaviour steps.</li>
 * </ol>
 *
 * <p>Also drives A3.2/X: from one alert you get behaviour trajectory + data
 * sources + judgement basis + disposal, and can export it.
 */
@Service
public class DecisionBomService {

    private final JdbcTemplate jdbc;

    public DecisionBomService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> reconstruct(String tenantId, String traceId) {
        Map<String, Object> bom = new LinkedHashMap<>();
        bom.put("tenant_id", tenantId);
        bom.put("trace_id", traceId);
        bom.put("reconstructed_at", java.time.Instant.now().toString());
        List<String> sourcesQueried = new ArrayList<>();

        // Phase 1 — audit events.
        List<Map<String, Object>> audit = jdbc.queryForList(
                """
                SELECT event_id, scene, layer, rule_id, rule_revision, reason_code,
                       effective_action, max_risk_score, rollout_state, intercepted_at,
                       user_id, device_id
                FROM tb_audit_events
                WHERE tenant_id = ? AND trace_id = ?
                ORDER BY audit_seq ASC
                """,
                tenantId, traceId);
        sourcesQueried.add("tb_audit_events");
        bom.put("audit_events", audit);

        // Phase 4 (loaded early to derive the session) — trace steps.
        List<Map<String, Object>> trace = jdbc.queryForList(
                """
                SELECT step_id, parent_step_id, step_seq, step_type, layer, tool_name,
                       tool_args_hash, tool_args, tool_decision, rule_id, reason_code,
                       risk_score, tool_status, tool_result_preview, chain_seq, occurred_at,
                       session_id
                FROM tb_agent_trace
                WHERE tenant_id = ? AND trace_id = ?
                ORDER BY step_seq ASC, chain_seq ASC
                """,
                tenantId, traceId);
        sourcesQueried.add("tb_agent_trace");
        bom.put("trace_steps", trace);

        String sessionId = trace.stream()
                .map(r -> r.get("session_id"))
                .filter(java.util.Objects::nonNull)
                .map(String::valueOf)
                .findFirst()
                .orElseGet(() -> deriveSession(tenantId, traceId));
        bom.put("session_id", sessionId);

        // Phase 2 — session context (tool counts + peak risk).
        if (sessionId != null && !sessionId.isBlank()) {
            List<Map<String, Object>> toolCounts = jdbc.queryForList(
                    """
                    SELECT tool_name, COUNT(*) AS calls, MAX(risk_score) AS max_risk
                    FROM tb_agent_trace
                    WHERE tenant_id = ? AND session_id = ? AND tool_name IS NOT NULL
                    GROUP BY tool_name ORDER BY calls DESC
                    """,
                    tenantId, sessionId);
            sourcesQueried.add("tb_agent_trace(session)");
            bom.put("session_tool_counts", toolCounts);
            Integer peak = jdbc.queryForObject(
                    "SELECT COALESCE(MAX(risk_score), 0) FROM tb_agent_trace WHERE tenant_id = ? AND session_id = ?",
                    Integer.class, tenantId, sessionId);
            bom.put("session_peak_risk", peak);
        }

        // Phase 3 — policy revisions that fired.
        List<Map<String, Object>> policies = new ArrayList<>();
        for (Map<String, Object> a : audit) {
            Object ruleId = a.get("rule_id");
            Object rev = a.get("rule_revision");
            if (ruleId == null || String.valueOf(ruleId).isBlank()) {
                continue;
            }
            List<Map<String, Object>> revRows = jdbc.queryForList(
                    """
                    SELECT rule_id, rule_revision, layer, runtime, reason_code, risk_score,
                           intent_action, rollout_state, effective_from
                    FROM tb_rule_history
                    WHERE tenant_id = ? AND rule_id = ? AND rule_revision = ?
                    """,
                    tenantId, String.valueOf(ruleId),
                    rev instanceof Number n ? n.intValue() : parseInt(String.valueOf(rev)));
            if (!revRows.isEmpty()) {
                policies.add(revRows.get(0));
            }
        }
        sourcesQueried.add("tb_rule_history");
        bom.put("policy_revisions", policies);

        bom.put("sources_queried", sourcesQueried);
        bom.put("completeness_score", completeness(audit, trace, policies));
        return bom;
    }

    private String deriveSession(String tenantId, String traceId) {
        List<String> s = jdbc.queryForList(
                "SELECT DISTINCT session_id FROM tb_agent_trace WHERE tenant_id = ? AND trace_id = ? LIMIT 1",
                String.class, tenantId, traceId);
        return s.isEmpty() ? null : s.get(0);
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return -1;
        }
    }

    /** Fraction of required BOM fields that are populated (0..1). */
    private static double completeness(
            List<Map<String, Object>> audit, List<Map<String, Object>> trace, List<Map<String, Object>> policies) {
        int required = 4;
        int present = 0;
        if (!audit.isEmpty()) {
            Map<String, Object> primary = audit.get(0);
            if (primary.get("user_id") != null) present++;                       // agent/user identity
            if (primary.get("effective_action") != null) present++;              // decision outcome
        }
        if (!policies.isEmpty()) present++;                                      // policy rules evaluated
        if (!trace.isEmpty()) present++;                                         // action/behaviour evidence
        return present / (double) required;
    }
}
