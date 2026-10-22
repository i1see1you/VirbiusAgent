package io.virbius.control.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class HashChainVerifierTest {

    private static final String TENANT = "t1";
    private static final String GENESIS = "sha256:" + "0".repeat(64);

    private SingleConnectionDataSource ds;
    private JdbcTemplate jdbc;
    private HashChainVerifier verifier;

    @BeforeEach
    void setUp() throws Exception {
        Class.forName("org.sqlite.JDBC");
        Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        ds = new SingleConnectionDataSource(conn, true);
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE tb_audit_events ("
                + "tenant_id TEXT, trace_id TEXT, event_id TEXT, audit_seq INTEGER,"
                + " prev_hash TEXT, curr_hash TEXT, effective_action TEXT, layer TEXT,"
                + " reason_code TEXT, rule_id TEXT, scene TEXT, user_id TEXT,"
                + " device_id TEXT, session_id TEXT, agent_aid TEXT, intercepted_at TEXT)");
        verifier = new HashChainVerifier(jdbc);
    }

    @AfterEach
    void tearDown() {
        ds.destroy();
    }

    @Test
    void validChainWithAgentAidPasses() {
        insertEvent(1, GENESIS, "agent-a", null, "2026-10-14T00:00:00Z");

        HashChainVerifier.ChainVerificationResult r = verifier.verifyAll(TENANT);

        assertTrue(r.passed(), r.reason());
        assertEquals(1, r.totalEvents());
        assertEquals(1, r.verifiedEvents());
        assertNull(r.breakSeq());
    }

    @Test
    void validTwoEventChainWithAgentAidPasses() {
        String h1 = insertEvent(1, GENESIS, "agent-a", null, "2026-10-14T00:00:00Z");
        insertEvent(2, h1, "agent-a", null, "2026-10-14T00:00:01Z");

        HashChainVerifier.ChainVerificationResult r = verifier.verifyAll(TENANT);

        assertTrue(r.passed(), r.reason());
        assertEquals(2, r.verifiedEvents());
    }

    @Test
    void nullAgentAidStillPasses() {
        insertEvent(1, GENESIS, null, null, "2026-10-14T00:00:00Z");

        HashChainVerifier.ChainVerificationResult r = verifier.verifyAll(TENANT);

        assertTrue(r.passed(), r.reason());
        assertEquals(1, r.verifiedEvents());
    }

    @Test
    void tamperedAgentAidBreaksTheChain() {
        String storedHash = HashChainOrchestrator.computeHash(
                GENESIS, 1, content(1, "agent-a", "2026-10-14T00:00:00Z"));
        insertEvent(1, GENESIS, "agent-b", storedHash, "2026-10-14T00:00:00Z");

        HashChainVerifier.ChainVerificationResult r = verifier.verifyAll(TENANT);

        assertFalse(r.passed());
        assertEquals(1L, r.breakSeq());
        assertTrue(r.reason().contains("curr_hash mismatch"), r.reason());
    }

    private String insertEvent(long seq, String prev, String agentAid, String storedHash,
                               String interceptedAt) {
        String curr = storedHash != null
                ? storedHash
                : HashChainOrchestrator.computeHash(prev, seq, content(seq, agentAid, interceptedAt));
        jdbc.update(
                "INSERT INTO tb_audit_events (tenant_id,trace_id,event_id,audit_seq,prev_hash,"
                        + "curr_hash,effective_action,layer,reason_code,rule_id,scene,user_id,"
                        + "device_id,agent_aid,intercepted_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                TENANT, "tr", "evt-" + seq, seq, prev, curr, "allow", "cloud", "OK",
                "POLICY_ALLOW", "tool_call", "u", "d", agentAid, interceptedAt);
        return curr;
    }

    private static Map<String, Object> content(long seq, String agentAid, String interceptedAt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tenant_id", TENANT);
        m.put("trace_id", "tr");
        m.put("event_id", "evt-" + seq);
        m.put("effective_action", "allow");
        m.put("layer", "cloud");
        m.put("reason_code", "OK");
        m.put("rule_id", "POLICY_ALLOW");
        m.put("scene", "tool_call");
        m.put("user_id", "u");
        m.put("device_id", "d");
        m.put("agent_aid", agentAid);
        m.put("intercepted_at", interceptedAt);
        return m;
    }
}
