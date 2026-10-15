package io.virbius.control.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * A3: covers the per-session trace-chain verifier branches that the E2E only
 * exercises on the happy path — seq gap, prev/curr hash mismatch, unchained
 * rows, the tenant-level roll-up — plus the risk_score normalization contract
 * shared with {@link TraceEventIngestor} (a wire float must hash the same as the
 * stored integer the verifier recomputes from).
 */
class TraceChainVerifierTest {

    private static final String TENANT = "t1";
    private static final String GENESIS = "sha256:" + "0".repeat(64);

    private SingleConnectionDataSource ds;
    private JdbcTemplate jdbc;
    private TraceChainVerifier verifier;

    @BeforeEach
    void setUp() throws Exception {
        Class.forName("org.sqlite.JDBC");
        Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        ds = new SingleConnectionDataSource(conn, true);
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE tb_agent_trace ("
                + "tenant_id TEXT, trace_id TEXT, session_id TEXT, step_id TEXT, step_seq INTEGER,"
                + " step_type TEXT, tool_name TEXT, tool_args_hash TEXT, tool_decision TEXT,"
                + " rule_id TEXT, reason_code TEXT, risk_score INTEGER, chain_seq INTEGER,"
                + " prev_hash TEXT, curr_hash TEXT, occurred_at TEXT)");
        verifier = new TraceChainVerifier(jdbc);
    }

    @AfterEach
    void tearDown() {
        ds.destroy();
    }

    // ── hashing / storage normalization contract (BUG-1 regression) ──────────

    @Test
    void hashNormalizesRiskScoreToTheStoredInteger() {
        // A float wire value truncates to the same int the row stores, so the
        // verifier's recompute-from-row must produce the identical hash.
        assertEquals(hashOf(20), hashOf(20.7));
        // ... regardless of whether the driver reads the int back as Integer or Long.
        assertEquals(hashOf(20), hashOf(20L));
    }

    @Test
    void hashTreatsNonNumericAndNullRiskTheSame() {
        assertEquals(hashOf("abc"), hashOf(null));
    }

    // ── valid chain ──────────────────────────────────────────────────────────

    @Test
    void validTwoStepChainPasses() {
        String h1 = insertStep("sess", "tr", "s1", 1, "read_file", "aa", "allow", 10, 1,
                GENESIS, null, "2026-10-14T00:00:00Z");
        insertStep("sess", "tr", "s2", 2, "curl", "bb", "deny", 90, 2,
                h1, null, "2026-10-14T00:00:01Z");

        TraceChainVerifier.ChainVerificationResult r = verifier.verifySession(TENANT, "sess");

        assertTrue(r.passed());
        assertEquals(2, r.verifiedEvents());
        assertEquals(2, r.totalEvents());
        assertNull(r.breakSeq());
        assertNull(r.reason());
    }

    // ── seq gap ──────────────────────────────────────────────────────────────

    @Test
    void sequenceGapBreaksAtTheMissingSeq() {
        insertStep("sess", "tr", "s1", 1, "read_file", "aa", "allow", 10, 1,
                GENESIS, null, "2026-10-14T00:00:00Z");
        // seq jumps 1 -> 3
        insertStep("sess", "tr", "s3", 3, "curl", "bb", "allow", 20, 3,
                null, null, "2026-10-14T00:00:02Z");

        TraceChainVerifier.ChainVerificationResult r = verifier.verifySession(TENANT, "sess");

        assertFalse(r.passed());
        assertEquals(3L, r.breakSeq());
        assertTrue(r.reason().contains("seq gap"), r.reason());
    }

    // ── prev_hash mismatch ───────────────────────────────────────────────────

    @Test
    void prevHashMismatchBreaksAtThatSeq() {
        insertStep("sess", "tr", "s1", 1, "read_file", "aa", "allow", 10, 1,
                GENESIS, null, "2026-10-14T00:00:00Z");
        insertStep("sess", "tr", "s2", 2, "curl", "bb", "allow", 20, 2,
                "sha256:not-the-previous-hash", null, "2026-10-14T00:00:01Z");

        TraceChainVerifier.ChainVerificationResult r = verifier.verifySession(TENANT, "sess");

        assertFalse(r.passed());
        assertEquals(2L, r.breakSeq());
        assertTrue(r.reason().contains("prev_hash mismatch"), r.reason());
    }

    // ── curr_hash mismatch (tamper) ──────────────────────────────────────────

    @Test
    void currHashMismatchBreaksAtThatSeq() {
        // chain_seq 1, but curr_hash is not what the row's content hashes to.
        insertStep("sess", "tr", "s1", 1, "read_file", "aa", "allow", 10, 1,
                GENESIS, "sha256:deadbeef", "2026-10-14T00:00:00Z");

        TraceChainVerifier.ChainVerificationResult r = verifier.verifySession(TENANT, "sess");

        assertFalse(r.passed());
        assertEquals(1L, r.breakSeq());
        assertTrue(r.reason().contains("curr_hash mismatch"), r.reason());
    }

    // ── unchained row (ingest chain failure) ─────────────────────────────────

    @Test
    void unchainedRowIsAGapNotASilentPass() {
        insertStep("sess", "tr", "s1", 1, "read_file", "aa", "allow", 10, 0,
                null, null, "2026-10-14T00:00:00Z");

        TraceChainVerifier.ChainVerificationResult r = verifier.verifySession(TENANT, "sess");

        assertFalse(r.passed());
        assertEquals(0L, r.breakSeq());
        assertTrue(r.reason().contains("unchained row"), r.reason());
    }

    // ── tenant-level roll-up ─────────────────────────────────────────────────

    @Test
    void tenantVerificationRollsUpValidAndBrokenSessions() {
        // sa: valid single-step chain.
        insertStep("sa", "tr-a", "s1", 1, "read_file", "aa", "allow", 10, 1,
                GENESIS, null, "2026-10-14T00:00:00Z");
        // sb: tampered curr_hash.
        insertStep("sb", "tr-b", "s1", 1, "curl", "bb", "deny", 90, 1,
                GENESIS, "sha256:deadbeef", "2026-10-14T00:00:00Z");

        TraceChainVerifier.TenantVerificationResult r = verifier.verifyTenant(TENANT);

        assertEquals(2, r.sessions());
        assertEquals(1, r.verifiedSessions());
        assertEquals(1, r.broken().size());
        assertEquals("sb", r.broken().get(0).get("session_id"));
        assertEquals(1L, r.broken().get(0).get("break_seq"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Hash of a step-1 row whose only variable is the risk_score wire value. */
    private static String hashOf(Object riskScore) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tenant_id", TENANT);
        m.put("trace_id", "tr");
        m.put("step_id", "s1");
        m.put("step_type", "tool_call");
        m.put("tool_name", "read_file");
        m.put("tool_args_hash", "aa");
        m.put("tool_decision", "allow");
        m.put("rule_id", "");
        m.put("reason_code", "");
        m.put("risk_score", riskScore);
        m.put("occurred_at", "2026-10-14T00:00:00Z");
        return TraceEventIngestor.computeTraceHash(GENESIS, 1, m);
    }

    /**
     * Insert a chained row and return its {@code curr_hash}. {@code curr} is
     * computed from the stored content when null, so callers can build a valid
     * chain (feeding the prior row's return value back as {@code prev}) or
     * inject a deliberate tamper. {@code prev} null means GENESIS.
     */
    private String insertStep(String session, String trace, String stepId, int stepSeq,
                              String tool, String argsHash, String decision, Object risk,
                              int chainSeq, String prev, String curr, String occurredAt) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("tenant_id", TENANT);
        content.put("trace_id", trace);
        content.put("step_id", stepId);
        content.put("step_type", "tool_call");
        content.put("tool_name", tool);
        content.put("tool_args_hash", argsHash);
        content.put("tool_decision", decision);
        content.put("rule_id", "");
        content.put("reason_code", "");
        content.put("risk_score", risk);
        content.put("occurred_at", occurredAt);
        String effectivePrev = prev != null ? prev : GENESIS;
        String effectiveCurr = curr != null
                ? curr
                : TraceEventIngestor.computeTraceHash(effectivePrev, chainSeq, content);
        jdbc.update(
                "INSERT INTO tb_agent_trace (tenant_id,trace_id,session_id,step_id,step_seq,step_type,"
                        + "tool_name,tool_args_hash,tool_decision,rule_id,reason_code,risk_score,"
                        + "chain_seq,prev_hash,curr_hash,occurred_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                TENANT, trace, session, stepId, stepSeq, "tool_call", tool, argsHash, decision,
                "", "", risk, chainSeq, effectivePrev, effectiveCurr, occurredAt);
        return effectiveCurr;
    }
}
