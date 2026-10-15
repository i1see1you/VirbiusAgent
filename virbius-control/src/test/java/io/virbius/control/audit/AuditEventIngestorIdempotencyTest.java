package io.virbius.control.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.virbius.control.config.ControlJedisPools;
import io.virbius.control.config.SqlDialectConfig;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * Redelivery idempotency of the audit ingest path. The chain head lives in
 * shared storage ({@code tb_audit_chain_state} here, Redis in production) and
 * cannot join the DB transaction, so the ingest order must be
 * insert -> advance head -> stamp the row. The old chain-first order turned
 * every duplicate insert (Kafka is at-least-once: redelivery after a rebalance
 * or a crash) into a permanent seq hole that {@link HashChainVerifier}
 * reported as "seq gap"; a crash between chain and insert advanced the head
 * with no row at all.
 */
class AuditEventIngestorIdempotencyTest {

    private static final String TENANT = "t1";

    private SingleConnectionDataSource ds;
    private JdbcTemplate jdbc;
    private AuditEventIngestor ingestor;
    private HashChainVerifier verifier;

    @BeforeEach
    void setUp() throws Exception {
        Class.forName("org.sqlite.JDBC");
        Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        ds = new SingleConnectionDataSource(conn, true);
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE tb_audit_events ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "event_id VARCHAR(128), trace_id VARCHAR(128) NOT NULL, trace_id_source VARCHAR(16),"
                + "tenant_id VARCHAR(64) NOT NULL, scene VARCHAR(64) NOT NULL, layer VARCHAR(16) NOT NULL,"
                + "tool_name VARCHAR(128),"
                + "rule_id VARCHAR(128) NOT NULL, rule_revision INTEGER NOT NULL,"
                + "reason_code VARCHAR(64) NOT NULL, effective_action VARCHAR(16) NOT NULL,"
                + "max_risk_score INTEGER NOT NULL, rollout_state VARCHAR(16), canary_percent INTEGER,"
                + "in_canary_bucket INTEGER, degraded INTEGER, sampled_allow INTEGER,"
                + "sample_rate_allow REAL, intercepted_at TIMESTAMP NOT NULL,"
                + "user_id VARCHAR(256), device_id VARCHAR(256), agent_aid VARCHAR(256),"
                + "app_id VARCHAR(128), subject_source VARCHAR(16), token_iss VARCHAR(64),"
                + "audit_seq BIGINT NOT NULL DEFAULT 0, prev_hash VARCHAR(128) NOT NULL DEFAULT '',"
                + "curr_hash VARCHAR(128) NOT NULL DEFAULT '',"
                + "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE UNIQUE INDEX uq_tb_audit_events_event_id ON tb_audit_events (event_id)");
        jdbc.execute("CREATE TABLE tb_audit_chain_state ("
                + "tenant_id VARCHAR(64) PRIMARY KEY, seq BIGINT NOT NULL DEFAULT 0,"
                + "last_hash VARCHAR(128) NOT NULL DEFAULT '', version INT NOT NULL DEFAULT 0,"
                + "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE tb_tenant_request_stats_1h ("
                + "tenant_id VARCHAR(64), scene VARCHAR(64), layer VARCHAR(16),"
                + "hour_bucket TIMESTAMP, cnt_total BIGINT NOT NULL DEFAULT 0,"
                + "PRIMARY KEY (tenant_id, scene, layer, hour_bucket))");
        // Blank Redis URL -> Optional.empty() pool -> the SQL chain-state fallback.
        HashChainOrchestrator chain = new HashChainOrchestrator(new ControlJedisPools(""), jdbc, true);
        ingestor = new AuditEventIngestor(jdbc, new SqlDialectConfig(ds), chain, new AlertService(jdbc, 80));
        verifier = new HashChainVerifier(jdbc);
    }

    @AfterEach
    void tearDown() {
        ds.destroy();
    }

    private Map<String, Object> event(String eventId, String traceId) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("event_id", eventId);
        e.put("tenant_id", TENANT);
        e.put("trace_id", traceId);
        e.put("scene", "input");
        e.put("layer", "cloud");
        e.put("rule_id", "rl-x");
        e.put("rule_revision", 1);
        e.put("reason_code", "TEST");
        e.put("effective_action", "allow");
        e.put("max_risk_score", 10); // below threshold: no alert side effects
        e.put("intercepted_at", "2026-10-15T00:00:00Z");
        return e;
    }

    private long rows() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM tb_audit_events", Long.class);
    }

    private long headSeq() {
        var rows = jdbc.queryForList(
                "SELECT seq FROM tb_audit_chain_state WHERE tenant_id = ?", Long.class, TENANT);
        return rows.isEmpty() ? 0L : rows.get(0);
    }

    /** A redelivery must not advance the chain head past the rows that exist. */
    @Test
    void redeliveryIsIdempotent() {
        Map<String, Object> e1 = event("e1", "tr-1");
        assertEquals("accepted", ingestor.ingestEvent(e1).status());
        // Redelivery of the identical payload.
        assertEquals("duplicated", ingestor.ingestEvent(event("e1", "tr-1")).status());

        assertEquals(1L, rows());
        assertEquals(1L, headSeq());
        assertEquals(1L, jdbc.queryForObject(
                "SELECT audit_seq FROM tb_audit_events WHERE event_id = 'e1'", Long.class));

        // The next NEW event must get the contiguous seq. With the old
        // chain-first order the redelivery had already advanced the head to 2,
        // so this row landed on seq 3 and verifyAll reported a seq gap.
        Map<String, Object> e2 = event("e2", "tr-2");
        e2.put("intercepted_at", "2026-10-15T00:00:01Z");
        assertEquals("accepted", ingestor.ingestEvent(e2).status());
        assertEquals(2L, headSeq());

        var result = verifier.verifyAll(TENANT);
        assertTrue(result.passed(), result.reason());
        assertEquals(2, result.verifiedEvents());

        // Stats count once per unique event, not once per delivery.
        assertEquals(2L, jdbc.queryForObject(
                "SELECT COALESCE(SUM(cnt_total), 0) FROM tb_tenant_request_stats_1h", Long.class));
    }

    /**
     * A predecessor that crashed between its insert and its chain step leaves an
     * unchained row (audit_seq=0); the redelivery repairs it instead of ignoring
     * it, so the event is neither lost nor permanently unchained.
     */
    @Test
    void crashBetweenInsertAndChainIsRepairedOnRedelivery() {
        Map<String, Object> e1 = event("e1", "tr-1");
        // Simulate the crash: hash chaining disabled -> row inserted, head untouched.
        AuditEventIngestor crashed = new AuditEventIngestor(
                jdbc, new SqlDialectConfig(ds), new HashChainOrchestrator(new ControlJedisPools(""), jdbc, false),
                new AlertService(jdbc, 80));
        assertEquals("accepted", crashed.ingestEvent(e1).status());
        assertEquals(1L, rows());
        assertEquals(0L, jdbc.queryForObject(
                "SELECT audit_seq FROM tb_audit_events WHERE event_id = 'e1'", Long.class));

        // Redelivery through the healthy ingestor finishes the interrupted chain step.
        assertEquals("accepted", ingestor.ingestEvent(event("e1", "tr-1")).status());
        assertEquals(1L, rows());
        assertEquals(1L, headSeq());
        assertEquals(1L, jdbc.queryForObject(
                "SELECT audit_seq FROM tb_audit_events WHERE event_id = 'e1'", Long.class));

        var result = verifier.verifyAll(TENANT);
        assertTrue(result.passed(), result.reason());
    }

    /** An unrepaired unchained row must surface as an explicit, named break. */
    @Test
    void unchainedRowIsAVisibleBreakNotASilentGap() {
        jdbc.update("INSERT INTO tb_audit_events ("
                + "event_id, trace_id, tenant_id, scene, layer, rule_id, rule_revision,"
                + "reason_code, effective_action, max_risk_score, intercepted_at)"
                + " VALUES ('stuck', 'tr-0', ?, 'input', 'cloud', 'rl-x', 1, 'TEST', 'allow', 10, '2026-10-15T00:00:00Z')",
                TENANT);

        Map<String, Object> e2 = event("e2", "tr-2");
        e2.put("intercepted_at", "2026-10-15T00:00:02Z");
        assertEquals("accepted", ingestor.ingestEvent(e2).status());

        var result = verifier.verifyAll(TENANT);
        assertFalse(result.passed());
        assertTrue(result.reason().contains("unchained row"), result.reason());
        assertTrue(result.reason().contains("stuck"), result.reason());
    }
}
