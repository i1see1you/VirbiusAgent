package io.virbius.control.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * Read-model merge for the decision-chain timeline: trace steps (edge, proxy)
 * + audit adjudications (cloud/edge, engine &amp; proxy) composed by trace_id
 * at query time. Guards the ordering contract (timestamp, steps before audits
 * on ties, RFC3339 +00:00 vs Z normalization) and the no-dedup layer tagging.
 */
class DecisionChainServiceTest {

    private static final String TENANT = "t1";

    private SingleConnectionDataSource ds;
    private JdbcTemplate jdbc;
    private DecisionChainService service;

    @BeforeEach
    void setUp() throws Exception {
        Class.forName("org.sqlite.JDBC");
        Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        ds = new SingleConnectionDataSource(conn, true);
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE tb_agent_trace ("
                + "trace_id TEXT, session_id TEXT, tenant_id TEXT, step_id TEXT,"
                + "parent_step_id TEXT, step_seq INTEGER, step_type TEXT, layer TEXT,"
                + "user_id TEXT, device_id TEXT, input_role TEXT, input_content_hash TEXT,"
                + "tool_name TEXT, tool_args_hash TEXT, tool_args TEXT, tool_decision TEXT,"
                + "rule_id TEXT, reason_code TEXT, risk_score INTEGER, tool_status TEXT,"
                + "tool_duration_ms INTEGER, tool_result_preview TEXT, content_size INTEGER,"
                + "content_sampled INTEGER, dlp_masked INTEGER, upstream_name TEXT,"
                + "app_id TEXT, occurred_at TEXT)");
        jdbc.execute("CREATE TABLE tb_audit_events ("
                + "event_id VARCHAR(128), trace_id VARCHAR(128), trace_id_source VARCHAR(16),"
                + "tenant_id VARCHAR(64), session_id VARCHAR(128), scene VARCHAR(64),"
                + "layer VARCHAR(16), tool_name VARCHAR(128), rule_id VARCHAR(128),"
                + "reason_code VARCHAR(64), effective_action VARCHAR(16),"
                + "max_risk_score INTEGER, rollout_state VARCHAR(16), canary_percent INTEGER,"
                + "in_canary_bucket INTEGER, degraded INTEGER, sampled_allow INTEGER,"
                + "intercepted_at TIMESTAMP, user_id VARCHAR(256), device_id VARCHAR(256))");
        service = new DecisionChainService(new TraceQueryService(jdbc), jdbc);
    }

    @AfterEach
    void tearDown() {
        ds.destroy();
    }

    @Test
    void sessionTimelineMergesStepsAndAuditsSortedByTime() {
        // Edge tool_call at 00:00:01 (proxy RFC3339 with +00:00 offset)...
        insertStep("tr-1", "sess", "s1", 1, "tool_call", "read_file", "allow", 10,
                "2026-10-20T00:00:01.500000000+00:00");
        // ...cloud adjudication 0.5s EARLIER (engine Instant.toString, Z suffix)
        insertAudit("evt-1", "tr-1", "tool_call", "cloud", "allow", 10,
                "2026-10-20T00:00:01Z");
        // ...edge audit same instant as the step: step (cause) sorts first.
        insertAudit("evt-2", "tr-1", "tool_call", "edge", "allow", 10,
                "2026-10-20T00:00:01.500Z");
        insertStep("tr-1", "sess", "s2", 2, "tool_result", null, null, null,
                "2026-10-20T00:00:02+00:00");

        List<Map<String, Object>> out = service.sessionTimeline(TENANT, "sess");

        assertEquals(4, out.size());
        assertEquals("audit", out.get(0).get("kind"));
        assertEquals("2026-10-20T00:00:01Z", out.get(0).get("occurred_at"));
        // Offset-formatted step and Z-formatted audit at the same instant:
        // parse-equal, so the tie-break puts the step first.
        assertEquals("step", out.get(1).get("kind"));
        assertEquals("audit", out.get(2).get("kind"));
        assertEquals("step", out.get(3).get("kind"));
        assertEquals("tool_result", out.get(3).get("type"));
    }

    @Test
    void mergedEntriesCarryUnifiedFieldsAndLayerTag() {
        insertStep("tr-2", "sess", "s1", 1, "tool_call", "curl", "block", 90,
                "2026-10-20T00:00:01Z");
        insertAudit("evt-3", "tr-2", "output", "cloud", "deny", 80,
                "2026-10-20T00:00:02Z");

        List<Map<String, Object>> out = service.traceChain(TENANT, "tr-2");

        Map<String, Object> step = out.get(0);
        assertEquals("step", step.get("kind"));
        assertEquals("tool_call", step.get("type"));
        assertEquals("block", step.get("decision"));
        assertEquals("edge", step.get("layer"));

        Map<String, Object> audit = out.get(1);
        assertEquals("audit", audit.get("kind"));
        assertEquals("output", audit.get("type"));
        assertEquals("deny", audit.get("decision"));
        assertEquals("cloud", audit.get("layer"));
        assertEquals(80, audit.get("risk_score"));
        assertEquals("OUTPUT_XSS", audit.get("reason_code"));
    }

    @Test
    void auditOnlyTraceYieldsAuditEntriesWithoutSteps() {
        insertAudit("evt-4", "tr-3", "input", "cloud", "block", 70,
                "2026-10-20T00:00:01Z");

        List<Map<String, Object>> out = service.traceChain(TENANT, "tr-3");

        assertEquals(1, out.size());
        assertEquals("audit", out.get(0).get("kind"));
        assertEquals("input", out.get(0).get("type"));
    }

    @Test
    void otherTenantsAndTracesAreExcluded() {
        insertStep("tr-4", "sess", "s1", 1, "tool_call", "curl", "allow", 10,
                "2026-10-20T00:00:01Z");
        jdbc.update("INSERT INTO tb_agent_trace (trace_id, session_id, tenant_id, step_id,"
                + " step_seq, step_type, layer, occurred_at) VALUES (?,?,?,?,?,?,?,?)",
                "tr-other", "sess", "t2", "sx", 1, "tool_call", "edge",
                "2026-10-20T00:00:01Z");
        insertAudit("evt-other", "tr-other", "tool_call", "cloud", "allow", 10,
                "2026-10-20T00:00:01Z");

        List<Map<String, Object>> out = service.sessionTimeline(TENANT, "sess");

        assertEquals(1, out.size());
        assertEquals("tr-4", out.get(0).get("trace_id"));
    }

    @Test
    void unparseableTimestampSortsLastInsteadOfThrowing() {
        insertStep("tr-5", "sess", "s1", 1, "tool_call", "curl", "allow", 10, "not-a-time");
        insertStep("tr-5", "sess", "s2", 2, "tool_result", null, null, null,
                "2026-10-20T00:00:01Z");

        List<Map<String, Object>> out = service.sessionTimeline(TENANT, "sess");

        assertEquals(2, out.size());
        assertEquals("tool_result", out.get(0).get("type"));
        assertEquals("not-a-time", out.get(1).get("occurred_at"));
        assertTrue(true);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void insertStep(String trace, String session, String stepId, int seq, String type,
                            String tool, String decision, Integer risk, String occurredAt) {
        jdbc.update("INSERT INTO tb_agent_trace (trace_id, session_id, tenant_id, step_id,"
                        + " step_seq, step_type, layer, tool_name, tool_decision, rule_id,"
                        + " reason_code, risk_score, occurred_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                trace, session, TENANT, stepId, seq, type, "edge", tool, decision,
                decision != null ? "r-" + decision : null, null, risk, occurredAt);
    }

    private void insertAudit(String eventId, String trace, String scene, String layer,
                             String action, int risk, String interceptedAt) {
        jdbc.update("INSERT INTO tb_audit_events (event_id, trace_id, trace_id_source,"
                        + " tenant_id, session_id, scene, layer, rule_id, reason_code,"
                        + " effective_action, max_risk_score, intercepted_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                eventId, trace, "client", TENANT, "sess", scene, layer, "r-" + action,
                "OUTPUT_XSS", action, risk, interceptedAt);
    }
}
