package io.virbius.control.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.control.config.SqlDialectConfig;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Ingests agent decision chain trace events from Redis Stream into {@code tb_agent_trace}.
 *
 * <p>Each event represents one step in the Agent decision chain:
 * input → reasoning → tool_call → tool_result → output.
 */
@Component
public class TraceEventIngestor {

    private static final Logger log = LoggerFactory.getLogger(TraceEventIngestor.class);

    private final JdbcTemplate jdbc;
    private final SqlDialectConfig dialect;
    private final SessionCheckpointService sessionCheckpoint;
    private final ObjectMapper mapper = new ObjectMapper();

    private final String insertIgnorePrefix;

    public TraceEventIngestor(JdbcTemplate jdbc, SqlDialectConfig dialect,
                              SessionCheckpointService sessionCheckpoint) {
        this.jdbc = jdbc;
        this.dialect = dialect;
        this.sessionCheckpoint = sessionCheckpoint;
        if (dialect.isMysql()) {
            this.insertIgnorePrefix = "INSERT IGNORE";
        } else if (dialect.isPostgresql()) {
            this.insertIgnorePrefix = "INSERT";
        } else {
            this.insertIgnorePrefix = "INSERT OR IGNORE";
        }
    }

    public IngestResult ingestEvent(Map<String, Object> event) {
        try {
            String traceId = str(event.get("trace_id"));
            String tenantId = str(event.get("tenant_id"));
            String stepId = str(event.get("step_id"));
            if (traceId.isBlank() || tenantId.isBlank() || stepId.isBlank()) {
                return IngestResult.rejected("missing trace_id, tenant_id, or step_id");
            }

            String sql = insertIgnorePrefix
                    + " INTO tb_agent_trace ("
                    + "  trace_id, session_id, tenant_id, step_id, parent_step_id, step_seq,"
                    + "  step_type, layer, user_id, device_id,"
                    + "  input_role, input_content_hash,"
                    + "  tool_name, tool_args_hash, tool_args, tool_decision, rule_id, reason_code, risk_score,"
                    + "  tool_status, tool_duration_ms, tool_result_preview,"
                    + "  content_size, content_sampled, dlp_masked,"
                    + "  upstream_name, app_id, occurred_at"
                    + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

            int updated = jdbc.update(sql,
                    traceId,
                    str(event.get("session_id")),
                    tenantId,
                    stepId,
                    nullIfBlank(str(event.get("parent_step_id"))),
                    intVal(event.get("step_seq")),
                    str(event.get("step_type")),
                    strOr(event.get("layer"), "edge"),
                    nullIfBlank(str(event.get("user_id"))),
                    nullIfBlank(str(event.get("device_id"))),
                    nullIfBlank(str(event.get("input_role"))),
                    nullIfBlank(str(event.get("input_content_hash"))),
                    nullIfBlank(str(event.get("tool_name"))),
                    nullIfBlank(str(event.get("tool_args_hash"))),
                    toolArgs(event.get("tool_args")),
                    nullIfBlank(str(event.get("tool_decision"))),
                    nullIfBlank(str(event.get("rule_id"))),
                    nullIfBlank(str(event.get("reason_code"))),
                    intValOrNull(event.get("risk_score")),
                    nullIfBlank(str(event.get("tool_status"))),
                    intValOrNull(event.get("tool_duration_ms")),
                    nullIfBlank(str(event.get("tool_result_preview"))),
                    intValOrNull(event.get("content_size")),
                    boolInt(event.get("content_sampled")),
                    boolInt(event.get("dlp_masked")),
                    nullIfBlank(str(event.get("upstream_name"))),
                    nullIfBlank(str(event.get("app_id"))),
                    str(event.get("occurred_at")));

            if (updated == 0) {
                return IngestResult.duplicated();
            }
            chainEvent(event);
            sessionCheckpoint.onTraceEvent(
                    tenantId,
                    str(event.get("session_id")),
                    longVal(event.get("chain_seq")),
                    str(event.get("occurred_at")),
                    str(event.get("tool_name")));
            return IngestResult.accepted();
        } catch (Exception e) {
            log.warn("trace ingest failed: {}", e.getMessage());
            return IngestResult.rejected(e.getMessage());
        }
    }

    /**
     * A3: per-session hash chain over trace rows. Sessions chain independently
     * (short chains, parallel verification); a daily Merkle anchor over the
     * day's session heads seals the whole day (see DailyAnchorJob).
     *
     * <p>CAS-first: the session chain state advances only on the optimistic-lock
     * update; the row is annotated after the state wins, so a lost race never
     * publishes a broken chain. Best-effort: failures leave the row unchained
     * and are visible to TraceChainVerifier as a gap.
     */
    private void chainEvent(Map<String, Object> event) {
        try {
            String tenantId = str(event.get("tenant_id"));
            String sessionId = str(event.get("session_id"));
            if (tenantId.isBlank() || sessionId.isBlank()) {
                return;
            }
            String traceId = str(event.get("trace_id"));
            String stepId = str(event.get("step_id"));

            for (int attempt = 0; attempt < 3; attempt++) {
                var rows = jdbc.queryForList(
                        "SELECT seq, last_hash, version FROM tb_trace_chain_state WHERE tenant_id = ? AND session_id = ?",
                        tenantId, sessionId);
                long seq;
                String prevHash;
                int version;
                if (rows.isEmpty()) {
                    jdbc.update(
                            "INSERT INTO tb_trace_chain_state (tenant_id, session_id, seq, last_hash, version) VALUES (?, ?, 0, ?, 0)",
                            tenantId, sessionId, GENESIS_HASH);
                    seq = 0;
                    prevHash = GENESIS_HASH;
                    version = 0;
                } else {
                    Map<String, Object> row = rows.get(0);
                    seq = ((Number) row.get("seq")).longValue();
                    prevHash = str(row.get("last_hash"));
                    if (prevHash.isBlank()) {
                        prevHash = GENESIS_HASH;
                    }
                    version = ((Number) row.get("version")).intValue();
                }
                seq++;
                String currHash = computeTraceHash(prevHash, seq, event);
                int updated = jdbc.update(
                        "UPDATE tb_trace_chain_state SET seq = ?, last_hash = ?, version = version + 1, updated_at = ? "
                                + "WHERE tenant_id = ? AND session_id = ? AND version = ?",
                        seq, currHash, java.time.Instant.now().toString(), tenantId, sessionId, version);
                if (updated == 0) {
                    continue; // lost the race, re-read state
                }
                jdbc.update(
                        "UPDATE tb_agent_trace SET chain_seq = ?, prev_hash = ?, curr_hash = ? "
                                + "WHERE tenant_id = ? AND trace_id = ? AND step_id = ?",
                        seq, prevHash, currHash, tenantId, traceId, stepId);
                event.put("chain_seq", seq);
                event.put("prev_hash", prevHash);
                event.put("curr_hash", currHash);
                return;
            }
            log.warn("trace chain CAS exhausted: tenant={} session={}", tenantId, sessionId);
        } catch (Exception e) {
            log.warn("trace chain failed: {}", e.getMessage());
        }
    }

    static String computeTraceHash(String prevHash, long seq, Map<String, Object> event) {
        String input = prevHash
                + "|" + seq
                + "|" + str(event.get("tenant_id"))
                + "|" + str(event.get("trace_id"))
                + "|" + str(event.get("step_id"))
                + "|" + str(event.get("step_type"))
                + "|" + str(event.get("tool_name"))
                + "|" + str(event.get("tool_args_hash"))
                + "|" + str(event.get("tool_decision"))
                + "|" + str(event.get("rule_id"))
                + "|" + str(event.get("reason_code"))
                + "|" + canonicalRiskScore(event.get("risk_score"))
                + "|" + MerkleUtil.canonicalTs(event.get("occurred_at"));
        return "sha256:" + sha256Hex(input);
    }

    private static final String GENESIS_HASH = "sha256:" + "0".repeat(64);

    private static String sha256Hex(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    @SuppressWarnings("unchecked")
    public IngestResult ingestPayload(String payload) {
        try {
            Map<String, Object> event = mapper.readValue(payload, Map.class);
            return ingestEvent(event);
        } catch (Exception e) {
            return IngestResult.rejected(e.getMessage());
        }
    }

    public Long countForStatus(String sql, Object... args) {
        try {
            Long val = jdbc.queryForObject(sql, Long.class, args);
            return val != null ? val : 0L;
        } catch (Exception e) {
            return null;
        }
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static String strOr(Object o, String def) {
        String s = str(o);
        return s.isBlank() ? def : s;
    }

    private static String nullIfBlank(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static int intVal(Object o) {
        if (o instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(str(o));
        } catch (Exception e) {
            return 0;
        }
    }

    private static long longVal(Object o) {
        if (o == null) return 0L;
        if (o instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(str(o));
        } catch (Exception e) {
            return 0L;
        }
    }

    private static Integer intValOrNull(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(str(o));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Canonical {@code risk_score} form shared by hashing and storage. The row
     * keeps {@link #intValOrNull} (an int, or NULL), so the chain hash must
     * commit the same normalized value; otherwise a wire value that is not a
     * plain int (e.g. {@code 20.7} or {@code "abc"}) hashes as "20.7"/"abc"
     * while the stored row reads back as 20 / NULL, and
     * {@link TraceChainVerifier}'s recompute falsely reports a curr_hash
     * mismatch on a legitimately ingested event.
     */
    static String canonicalRiskScore(Object o) {
        Integer v = intValOrNull(o);
        return v == null ? "" : v.toString();
    }

    private static int boolInt(Object o) {
        if (o == null) return 0;
        if (o instanceof Boolean b) return b ? 1 : 0;
        return "true".equalsIgnoreCase(str(o)) ? 1 : 0;
    }

    private String toolArgs(Object o) {
        if (o == null) return null;
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            log.debug("failed to serialize tool_args: {}", e.getMessage());
            return null;
        }
    }

    public record IngestResult(String status, String message) {
        static IngestResult accepted() { return new IngestResult("accepted", null); }
        static IngestResult duplicated() { return new IngestResult("duplicated", null); }
        static IngestResult rejected(String message) { return new IngestResult("rejected", message); }
    }
}
