package io.virbius.control.audit;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Read-model composition for the console's decision-chain timeline.
 *
 * <p>Trace steps ({@code tb_agent_trace}, produced by the MCP proxy: tool_call
 * / tool_result) and audit adjudications ({@code tb_audit_events}, produced by
 * the engine and proxy: input / output / tool_call decisions) are merged at
 * query time by {@code trace_id}. No new write path exists: audit stays the
 * source of truth for adjudication, trace stays the execution view — the
 * engine does NOT double-write trace events.
 *
 * <p>Entries are NOT deduplicated: an edge tool_call and the corresponding
 * cloud adjudication are both shown, tagged by {@code layer}, so layer-level
 * disagreements stay visible. Ordering is by timestamp; ties prefer steps
 * (cause) over audits (effect).
 */
@Service
public class DecisionChainService {

    private final TraceQueryService traceQuery;
    private final JdbcTemplate jdbc;

    public DecisionChainService(TraceQueryService traceQuery, JdbcTemplate jdbc) {
        this.traceQuery = traceQuery;
        this.jdbc = jdbc;
    }

    /** Session timeline: trace steps + audit adjudications of every trace in the session. */
    public List<Map<String, Object>> sessionTimeline(String tenantId, String sessionId) {
        List<Map<String, Object>> steps = traceQuery.sessionTimeline(tenantId, sessionId);
        return merge(steps, auditEvents(tenantId, traceIds(steps)));
    }

    /** Single-trace chain: trace steps + audit adjudications of that trace. */
    public List<Map<String, Object>> traceChain(String tenantId, String traceId) {
        List<Map<String, Object>> steps = traceQuery.traceChain(tenantId, traceId);
        return merge(steps, auditEvents(tenantId, List.of(traceId)));
    }

    // ── merge ────────────────────────────────────────────────────────────────

    private List<Map<String, Object>> merge(
            List<Map<String, Object>> steps, List<Map<String, Object>> audits) {
        List<Map<String, Object>> out = new ArrayList<>(steps.size() + audits.size());
        for (Map<String, Object> step : steps) {
            Map<String, Object> e = new LinkedHashMap<>(step);
            e.put("kind", "step");
            e.put("type", step.get("step_type"));
            e.put("decision", step.get("tool_decision"));
            out.add(e);
        }
        for (Map<String, Object> audit : audits) {
            Map<String, Object> e = new LinkedHashMap<>(audit);
            e.put("kind", "audit");
            e.put("occurred_at", audit.get("intercepted_at"));
            e.put("type", audit.get("scene"));
            e.put("decision", audit.get("effective_action"));
            e.put("risk_score", audit.get("max_risk_score"));
            out.add(e);
        }
        out.sort(Comparator
                .comparing((Map<String, Object> e) -> toInstant(str(e.get("occurred_at"))),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(e -> "audit".equals(str(e.get("kind"))) ? 1 : 0)
                .thenComparing(e -> intVal(e.get("step_seq"))));
        return out;
    }

    private List<Map<String, Object>> auditEvents(String tenantId, Collection<String> traceIds) {
        Collection<String> ids = new LinkedHashSet<>();
        for (String id : traceIds) {
            if (id != null && !id.isBlank()) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        String sql = """
                SELECT event_id, trace_id, trace_id_source, tenant_id, session_id, scene, layer,
                       tool_name, rule_id, reason_code, effective_action, max_risk_score,
                       rollout_state, canary_percent, in_canary_bucket, degraded, sampled_allow,
                       intercepted_at, user_id, device_id
                FROM tb_audit_events
                WHERE tenant_id = ? AND trace_id IN (%s)
                ORDER BY intercepted_at
                """.formatted(placeholders);
        Object[] args = new Object[ids.size() + 1];
        args[0] = tenantId;
        int i = 1;
        for (String id : ids) {
            args[i++] = id;
        }
        return jdbc.query(sql, (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("event_id", rs.getString("event_id"));
            row.put("trace_id", rs.getString("trace_id"));
            row.put("trace_id_source", rs.getString("trace_id_source"));
            row.put("tenant_id", rs.getString("tenant_id"));
            row.put("session_id", rs.getString("session_id"));
            row.put("scene", rs.getString("scene"));
            row.put("layer", rs.getString("layer"));
            row.put("tool_name", rs.getString("tool_name"));
            row.put("rule_id", rs.getString("rule_id"));
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
        }, args);
    }

    private static Collection<String> traceIds(List<Map<String, Object>> steps) {
        Collection<String> ids = new LinkedHashSet<>();
        for (Map<String, Object> step : steps) {
            ids.add(str(step.get("trace_id")));
        }
        return ids;
    }

    /**
     * Proxy timestamps are RFC3339 with a {@code +00:00} offset, engine audit
     * timestamps are {@link Instant#toString()} ({@code Z}); plain string
     * comparison would order by offset suffix, so parse before comparing.
     */
    private static Instant toInstant(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(s);
        } catch (Exception ignored) {
            try {
                return OffsetDateTime.parse(s).toInstant();
            } catch (Exception also) {
                return null;
            }
        }
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static int intVal(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }
}
