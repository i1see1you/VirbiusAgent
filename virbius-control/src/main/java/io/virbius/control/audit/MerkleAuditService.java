package io.virbius.control.audit;

import io.virbius.control.audit.MerkleUtil.ProofStep;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * A4: Merkle inclusion proofs over {@code tb_audit_events}.
 *
 * <p>Leaves are the per-event {@code curr_hash} values in {@code audit_seq}
 * order (over the whole tenant chain, or a bounded time range). Proofs let an
 * auditor show that a single event is part of the committed chain without
 * re-hashing every event, and let a tampered event be pinpointed instantly.
 */
@Service
public class MerkleAuditService {

    private final JdbcTemplate jdbc;

    public MerkleAuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private List<Map<String, Object>> loadRows(String tenantId, Instant from, Instant to) {
        StringBuilder sql = new StringBuilder("""
                SELECT event_id, audit_seq, curr_hash
                FROM tb_audit_events
                WHERE tenant_id = ?
                """);
        var args = new java.util.ArrayList<Object>();
        args.add(tenantId);
        if (from != null) {
            sql.append(" AND intercepted_at >= ?");
            args.add(from.toString());
        }
        if (to != null) {
            sql.append(" AND intercepted_at <= ?");
            args.add(to.toString());
        }
        sql.append(" ORDER BY audit_seq ASC");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    /**
     * Build an inclusion proof for one event. {@code verified} is the service's
     * own self-check (the caller can re-verify with the returned root + proof).
     */
    public Map<String, Object> proof(String tenantId, String eventId, Instant from, Instant to) {
        List<Map<String, Object>> rows = loadRows(tenantId, from, to);
        int index = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (eventId.equals(rows.get(i).get("event_id"))) {
                index = i;
                break;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenant_id", tenantId);
        out.put("event_id", eventId);
        out.put("range_total", rows.size());
        if (index < 0) {
            out.put("found", false);
            return out;
        }
        List<String> leaves = rows.stream().map(r -> String.valueOf(r.get("curr_hash"))).toList();
        List<List<String>> levels = MerkleUtil.buildLevels(leaves);
        String root = MerkleUtil.root(levels);
        List<ProofStep> steps = MerkleUtil.proof(levels, index);
        String leafHash = leaves.get(index);

        out.put("found", true);
        out.put("audit_seq", rows.get(index).get("audit_seq"));
        out.put("leaf_hash", leafHash);
        out.put("root_hash", root);
        out.put("proof", steps.stream().map(s -> Map.of("hash", s.hash(), "position", s.position())).toList());
        out.put("verified", MerkleUtil.verify(leafHash, steps, root));
        return out;
    }

    /**
     * Independently verify a proof: re-load the leaf hash for the event and fold
     * the supplied steps up to the expected root.
     */
    public boolean verifyProof(
            String tenantId, String eventId, String expectedRoot, List<ProofStep> steps) {
        List<String> hashes = jdbc.queryForList(
                "SELECT curr_hash FROM tb_audit_events WHERE tenant_id = ? AND event_id = ?",
                String.class, tenantId, eventId);
        if (hashes.isEmpty()) {
            return false;
        }
        return MerkleUtil.verify(hashes.get(0), steps, expectedRoot);
    }
}
