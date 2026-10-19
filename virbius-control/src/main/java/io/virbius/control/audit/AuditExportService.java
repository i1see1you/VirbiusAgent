package io.virbius.control.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * A7: audit export with permission-controlled access, an export ledger, and
 * AES-GCM encryption.
 *
 * <p>Every export writes a {@code tb_audit_export_job} row (requester, filters,
 * result hash) — the ledger <em>is</em> the export action's audit trail, so
 * exports never bypass the append-only audit path. When a key is configured the
 * payload is encrypted with AES-256-GCM; otherwise a plaintext export is written
 * by default ({@code virbius.audit.export.allow-plaintext=true}) and can be
 * turned back into a fail-closed mode with
 * {@code virbius.audit.export.allow-plaintext=false}.
 */
@Service
public class AuditExportService {

    private static final Logger log = LoggerFactory.getLogger(AuditExportService.class);
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path exportDir;
    private final byte[] aeadKey;
    private final boolean allowPlaintext;

    public AuditExportService(
            JdbcTemplate jdbc,
            @Value("${virbius.data-dir:./data}") String dataDir,
            @Value("${virbius.audit.export.key:}") String keyBase64,
            @Value("${virbius.audit.export.allow-plaintext:true}") boolean allowPlaintext) {
        this.jdbc = jdbc;
        this.exportDir = Path.of(dataDir, "exports");
        this.allowPlaintext = allowPlaintext;
        byte[] key = null;
        if (keyBase64 != null && !keyBase64.isBlank()) {
            try {
                key = java.util.Base64.getDecoder().decode(keyBase64.trim());
            } catch (Exception e) {
                throw new IllegalStateException("virbius.audit.export.key must be base64", e);
            }
            if (key.length != 32) {
                throw new IllegalStateException("virbius.audit.export.key must decode to 32 bytes (AES-256)");
            }
        }
        this.aeadKey = key;
        if (key != null) {
            if (allowPlaintext) {
                log.warn("audit export: mode=encrypted (allow-plaintext is ignored while "
                        + "virbius.audit.export.key is set)");
            } else {
                log.info("audit export: mode=encrypted");
            }
        } else if (allowPlaintext) {
            log.warn("audit export: mode=PLAINTEXT (no virbius.audit.export.key set) — "
                    + "exported audit JSONL is NOT encrypted");
        } else {
            log.info("audit export: mode=fail-closed (no key, allow-plaintext=false)");
        }
    }

    public record ExportFilters(
            String from, String to, String sessionId, String traceId,
            Integer minRisk, String effectiveAction) {}

    /** Run an export synchronously and return the job ledger row. */
    public Map<String, Object> startExport(String tenantId, String requester, ExportFilters filters) {
        String jobId = "exp-" + UUID.randomUUID();
        String filtersJson;
        try {
            filtersJson = mapper.writeValueAsString(filters);
        } catch (Exception e) {
            filtersJson = "{}";
        }
        jdbc.update(
                "INSERT INTO tb_audit_export_job (job_id, tenant_id, requester, filters_json, status) "
                        + "VALUES (?, ?, ?, ?, 'running')",
                jobId, tenantId, requester != null ? requester : "unknown", filtersJson);
        try {
            String payload = buildPayload(tenantId, filters);
            byte[] bytes = encrypt(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8), jobId);
            Files.createDirectories(exportDir);
            Path file = exportDir.resolve(jobId + ".bin");
            Files.write(file, bytes);
            String sha = MerkleUtil.sha256Hex(new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1));
            jdbc.update(
                    "UPDATE tb_audit_export_job SET status='done', file_path=?, file_sha256=?, file_bytes=?, "
                            + "completed_at=? WHERE job_id=?",
                    file.toString(), sha, (long) bytes.length,
                    java.time.Instant.now().toString(), jobId);
        } catch (Exception e) {
            log.warn("audit export failed job={}: {}", jobId, e.getMessage());
            jdbc.update(
                    "UPDATE tb_audit_export_job SET status='failed', error=?, completed_at=? WHERE job_id=?",
                    e.getMessage(), java.time.Instant.now().toString(), jobId);
        }
        return get(tenantId, jobId);
    }

    private String buildPayload(String tenantId, ExportFilters f) {
        StringBuilder sql = new StringBuilder("""
                SELECT event_id, trace_id, tenant_id, scene, layer, rule_id, rule_revision,
                       reason_code, effective_action, max_risk_score, rollout_state,
                       intercepted_at, user_id, device_id, audit_seq, prev_hash, curr_hash
                FROM tb_audit_events
                WHERE tenant_id = ?
                """);
        var args = new java.util.ArrayList<Object>();
        args.add(tenantId);
        if (f.from() != null && !f.from().isBlank()) {
            sql.append(" AND intercepted_at >= ?");
            args.add(f.from());
        }
        if (f.to() != null && !f.to().isBlank()) {
            sql.append(" AND intercepted_at <= ?");
            args.add(f.to());
        }
        if (f.traceId() != null && !f.traceId().isBlank()) {
            sql.append(" AND trace_id = ?");
            args.add(f.traceId());
        }
        if (f.effectiveAction() != null && !f.effectiveAction().isBlank()) {
            sql.append(" AND effective_action = ?");
            args.add(f.effectiveAction());
        }
        if (f.minRisk() != null) {
            sql.append(" AND max_risk_score >= ?");
            args.add(f.minRisk());
        }
        if (f.sessionId() != null && !f.sessionId().isBlank()) {
            sql.append(" AND trace_id IN (SELECT trace_id FROM tb_agent_trace WHERE tenant_id = ? AND session_id = ?)");
            args.add(tenantId);
            args.add(f.sessionId());
        }
        sql.append(" ORDER BY audit_seq ASC");

        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> row : rows) {
            try {
                sb.append(mapper.writeValueAsString(row)).append('\n');
            } catch (Exception e) {
                log.debug("skip unserializable audit row: {}", e.getMessage());
            }
        }
        return sb.toString();
    }

    /**
     * AES-256-GCM: output = 12-byte random IV || ciphertext(+tag).
     * Fails closed when no key is configured and plaintext is not opted in.
     */
    private byte[] encrypt(byte[] plaintext, String jobId) throws Exception {
        if (aeadKey == null) {
            if (allowPlaintext) {
                return plaintext;
            }
            throw new IllegalStateException(
                    "audit export disabled: set virbius.audit.export.key (or allow-plaintext=true)");
        }
        byte[] iv = new byte[GCM_IV_BYTES];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aeadKey, "AES"),
                new GCMParameterSpec(GCM_TAG_BITS, iv));
        cipher.updateAAD(jobId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] ct = cipher.doFinal(plaintext);
        byte[] out = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(ct, 0, out, iv.length, ct.length);
        return out;
    }

    public Map<String, Object> get(String tenantId, String jobId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM tb_audit_export_job WHERE tenant_id = ? AND job_id = ?",
                tenantId, jobId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Map<String, Object>> list(String tenantId, int limit) {
        return jdbc.queryForList(
                "SELECT job_id, requester, filters_json, status, file_sha256, file_bytes, created_at, completed_at "
                        + "FROM tb_audit_export_job WHERE tenant_id = ? ORDER BY created_at DESC LIMIT ?",
                tenantId, Math.min(limit, 200));
    }

    /** Read the encrypted export artifact for download (ops/admin scope). */
    public byte[] readFile(String tenantId, String jobId) throws Exception {
        Map<String, Object> job = get(tenantId, jobId);
        if (job == null || !"done".equals(job.get("status")) || job.get("file_path") == null) {
            return null;
        }
        return Files.readAllBytes(Path.of(String.valueOf(job.get("file_path"))));
    }
}
