package io.virbius.control.audit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class HashChainVerifier {

    private static final String GENESIS_HASH = "sha256:" + "0".repeat(64);

    private final JdbcTemplate jdbc;

    public HashChainVerifier(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ChainVerificationResult verify(String tenantId, Instant from, Instant to) {
        String sql = """
                SELECT audit_seq, prev_hash, curr_hash, trace_id, event_id, tenant_id,
                       effective_action, layer, reason_code, rule_id, scene,
                       user_id, device_id, intercepted_at
                FROM tb_audit_events
                WHERE tenant_id = ? AND intercepted_at >= ? AND intercepted_at <= ?
                ORDER BY audit_seq ASC
                """;
        List<Map<String, Object>> rows = jdbc.queryForList(sql, tenantId, from.toString(), to.toString());
        return verifyRows(rows);
    }

    public ChainVerificationResult verifyAll(String tenantId) {
        String sql = """
                SELECT audit_seq, prev_hash, curr_hash, trace_id, event_id, tenant_id,
                       effective_action, layer, reason_code, rule_id, scene,
                       user_id, device_id, intercepted_at
                FROM tb_audit_events
                WHERE tenant_id = ?
                ORDER BY audit_seq ASC
                """;
        List<Map<String, Object>> rows = jdbc.queryForList(sql, tenantId);
        return verifyRows(rows);
    }

    private ChainVerificationResult verifyRows(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return new ChainVerificationResult(true, null, null, 0, 0);
        }

        int verified = 0;
        String expectedPrevHash = GENESIS_HASH;
        long expectedSeq = 1;

        for (Map<String, Object> row : rows) {
            long seq = ((Number) row.get("audit_seq")).longValue();
            String prevHash = (String) row.get("prev_hash");
            String currHash = (String) row.get("curr_hash");

            if (seq != expectedSeq) {
                return new ChainVerificationResult(false, seq,
                        "seq gap: expected " + expectedSeq + ", got " + seq,
                        rows.size(), verified);
            }

            if (!expectedPrevHash.equals(prevHash)) {
                return new ChainVerificationResult(false, seq,
                        "prev_hash mismatch: expected " + expectedPrevHash + ", got " + prevHash,
                        rows.size(), verified);
            }

            String recomputed = HashChainOrchestrator.computeHash(prevHash, seq, row);
            if (!recomputed.equals(currHash)) {
                return new ChainVerificationResult(false, seq,
                        "curr_hash mismatch: content may have been tampered",
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
}
