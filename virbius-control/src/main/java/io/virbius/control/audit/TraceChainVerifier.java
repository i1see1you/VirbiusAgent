package io.virbius.control.audit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * A3: verifies a session's trace chain ({@code tb_agent_trace.chain_seq} /
 * {@code prev_hash} / {@code curr_hash}), mirroring {@link HashChainVerifier}
 * for the audit chain.
 */
@Component
public class TraceChainVerifier {

    private static final String GENESIS_HASH = "sha256:" + "0".repeat(64);

    private final JdbcTemplate jdbc;

    public TraceChainVerifier(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ChainVerificationResult verifySession(String tenantId, String sessionId) {
        String sql = """
                SELECT chain_seq, prev_hash, curr_hash, trace_id, step_id, step_type,
                       tool_name, tool_args_hash, tool_decision, rule_id, reason_code,
                       risk_score, occurred_at, tenant_id
                FROM tb_agent_trace
                WHERE tenant_id = ? AND session_id = ?
                ORDER BY chain_seq ASC
                """;
        List<Map<String, Object>> rows = jdbc.queryForList(sql, tenantId, sessionId);
        return verifyRows(rows, "session " + sessionId);
    }

    /** Verify a whole tenant's session chains (all sessions, all rows). */
    public TenantVerificationResult verifyTenant(String tenantId) {
        String sql = """
                SELECT DISTINCT session_id FROM tb_agent_trace
                WHERE tenant_id = ? AND chain_seq > 0
                ORDER BY session_id
                """;
        List<String> sessions = jdbc.queryForList(sql, String.class, tenantId);
        List<Map<String, Object>> broken = new ArrayList<>();
        int verified = 0;
        for (String sessionId : sessions) {
            ChainVerificationResult r = verifySession(tenantId, sessionId);
            if (r.passed()) {
                verified++;
            } else {
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("session_id", sessionId);
                b.put("break_seq", r.breakSeq());
                b.put("reason", r.reason());
                broken.add(b);
            }
        }
        return new TenantVerificationResult(sessions.size(), verified, broken);
    }

    private ChainVerificationResult verifyRows(List<Map<String, Object>> rows, String scope) {
        int verified = 0;
        String expectedPrevHash = GENESIS_HASH;
        long expectedSeq = 1;
        for (Map<String, Object> row : rows) {
            long seq = ((Number) row.get("chain_seq")).longValue();
            String prevHash = (String) row.get("prev_hash");
            String currHash = (String) row.get("curr_hash");
            if (seq == 0) {
                // Unchained row (chain failure at ingest) — a gap, not silent pass.
                return new ChainVerificationResult(false, 0L,
                        "unchained row: trace=" + row.get("trace_id") + " step=" + row.get("step_id")
                                + " (" + scope + ")",
                        rows.size(), verified);
            }
            if (seq != expectedSeq) {
                return new ChainVerificationResult(false, seq,
                        "seq gap: expected " + expectedSeq + ", got " + seq + " (" + scope + ")",
                        rows.size(), verified);
            }
            if (!expectedPrevHash.equals(prevHash)) {
                return new ChainVerificationResult(false, seq,
                        "prev_hash mismatch: expected " + expectedPrevHash + ", got " + prevHash
                                + " (" + scope + ")",
                        rows.size(), verified);
            }
            String recomputed = TraceEventIngestor.computeTraceHash(prevHash, seq, row);
            if (!recomputed.equals(currHash)) {
                return new ChainVerificationResult(false, seq,
                        "curr_hash mismatch: content may have been tampered (" + scope + ")",
                        rows.size(), verified);
            }
            expectedPrevHash = currHash;
            expectedSeq = seq + 1;
            verified++;
        }
        return new ChainVerificationResult(true, null, null, rows.size(), verified);
    }

    public record ChainVerificationResult(
            boolean passed,
            Long breakSeq,
            String reason,
            int totalEvents,
            int verifiedEvents) {}

    public record TenantVerificationResult(
            int sessions,
            int verifiedSessions,
            List<Map<String, Object>> broken) {}
}
