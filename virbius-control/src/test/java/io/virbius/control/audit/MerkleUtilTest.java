package io.virbius.control.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.virbius.control.audit.MerkleUtil.ProofStep;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MerkleUtilTest {

    private static List<String> leaves(int n) {
        return java.util.stream.IntStream.range(0, n)
                .mapToObj(i -> MerkleUtil.leafHash("leaf-" + i))
                .toList();
    }

    @Test
    void proofVerifiesForEveryLeafEvenAndOdd() {
        for (int n = 1; n <= 9; n++) {
            List<List<String>> levels = MerkleUtil.buildLevels(leaves(n));
            String root = MerkleUtil.root(levels);
            for (int i = 0; i < n; i++) {
                List<ProofStep> proof = MerkleUtil.proof(levels, i);
                assertTrue(MerkleUtil.verify(leaves(n).get(i), proof, root),
                        "leaf " + i + " of " + n + " should verify");
            }
        }
    }

    @Test
    void tamperedLeafFailsVerification() {
        List<String> leaves = leaves(5);
        List<List<String>> levels = MerkleUtil.buildLevels(leaves);
        String root = MerkleUtil.root(levels);
        List<ProofStep> proof = MerkleUtil.proof(levels, 2);
        String tampered = MerkleUtil.leafHash("leaf-2-altered");
        assertFalse(MerkleUtil.verify(tampered, proof, root));
    }

    @Test
    void emptyTreeHasEmptyRoot() {
        assertEquals("", MerkleUtil.root(MerkleUtil.buildLevels(List.of())));
    }

    @Test
    void canonicalTsIsDialectProof() {
        // ISO instant, epoch, and DB timestamp forms of the same moment fold together.
        long epoch = java.time.Instant.parse("2026-10-13T07:00:00Z").getEpochSecond();
        assertEquals(String.valueOf(epoch), MerkleUtil.canonicalTs("2026-10-13T07:00:00Z"));
        assertEquals(String.valueOf(epoch), MerkleUtil.canonicalTs("2026-10-13 07:00:00"));
        assertEquals(String.valueOf(epoch), MerkleUtil.canonicalTs("2026-10-13 07:00:00.000"));
        assertEquals(String.valueOf(epoch), MerkleUtil.canonicalTs(java.sql.Timestamp.valueOf("2026-10-13 07:00:00")));
        assertEquals(String.valueOf(epoch), MerkleUtil.canonicalTs(epoch));
    }

    @Test
    void traceHashIsStableAcrossTimestampFormats() {
        Map<String, Object> iso = new java.util.HashMap<>();
        iso.put("tenant_id", "t1");
        iso.put("session_id", "s1");
        iso.put("trace_id", "tr1");
        iso.put("step_id", "st1");
        iso.put("step_type", "tool_call");
        iso.put("occurred_at", "2026-10-13T07:00:00Z");

        Map<String, Object> dbForm = new java.util.HashMap<>(iso);
        dbForm.put("occurred_at", java.sql.Timestamp.valueOf("2026-10-13 07:00:00"));

        assertEquals(
                TraceEventIngestor.computeTraceHash("sha256:0", 1, iso),
                TraceEventIngestor.computeTraceHash("sha256:0", 1, dbForm));
    }
}
