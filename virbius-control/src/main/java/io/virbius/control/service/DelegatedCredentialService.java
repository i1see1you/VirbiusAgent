package io.virbius.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.control.common.exception.BusinessException;
import io.virbius.control.config.ControlJedisPools;
import io.virbius.control.security.DelegatedCredEnvelope;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Delegated credential CRUD + snapshot publish (v2.5-min, B1 config-data
 * flow: no PublishOrchestrator / canary — change audit is a structured log
 * event; the tenant proxy picks the change up on its next snapshot poll).
 *
 * <p>Validation mirrors the proxy's canonical rules (delegated.rs) exactly:
 * host canonicalization, header deny-list, value >= 16 bytes, prefix+value
 * <= 4096 bytes, no CR/LF/NUL anywhere injected on the wire.
 */
@Service
public class DelegatedCredentialService {

    private static final Logger log = LoggerFactory.getLogger(DelegatedCredentialService.class);
    static final String SNAPSHOT_KEY_PREFIX = "virbius:delegated:snapshot:";

    /** Same deny-list as the proxy read side (ASCII case-insensitive). */
    static final List<String> HEADER_DENY_LIST = List.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade",
            "host", "content-length", "accept-encoding");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ControlJedisPools jedisPools;
    private final String masterKey;

    public DelegatedCredentialService(JdbcTemplate jdbc, ObjectMapper mapper,
            ControlJedisPools jedisPools,
            @Value("${virbius.delegated.master-key:}") String masterKey) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.jedisPools = jedisPools;
        this.masterKey = masterKey;
    }

    private DelegatedCredEnvelope envelope() {
        return new DelegatedCredEnvelope(masterKey);
    }

    // ------------------------------------------------------------------
    // Validation (mirror of the proxy rules — keep byte-compatible)
    // ------------------------------------------------------------------

    /**
     * Canonical tenant form: trim + lowercase. Applied at every entry point
     * (CRUD, KEK derivation, snapshot key) so the proxy's deploy-time tenant
     * (normalized identically) always matches — a case mismatch would make
     * the proxy drop the whole snapshot as foreign and stay cold forever.
     */
    static String normalizeTenant(String tenantId) {
        return tenantId == null ? "" : tenantId.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** Canonicalize a hostname; null when rejected. Mirrors delegated.rs. */
    static String canonicalHost(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().toLowerCase(java.util.Locale.ROOT);
        while (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.isEmpty() || s.length() > 253) {
            return null;
        }
        boolean allDigits = true;
        for (char c : s.toCharArray()) {
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '-';
            if (!ok) {
                return null;
            }
            if (!(c >= '0' && c <= '9') && c != '.') {
                allDigits = false;
            }
        }
        if (allDigits) {
            return null; // IP literal shapes (1.2.3.4 / 2130706433 / 127.1)
        }
        String[] labels = s.split("\\.", -1);
        for (String label : labels) {
            if (label.isEmpty() || label.length() > 63
                    || label.startsWith("-") || label.endsWith("-")
                    || label.startsWith("0x")) {
                return null;
            }
        }
        if (s.contains("..") || s.startsWith(".") || s.endsWith(".")) {
            return null;
        }
        return s;
    }

    static void validateHandle(String handle) {
        if (handle == null || handle.isBlank() || handle.length() > 64) {
            throw new BusinessException(400, "handle must be 1..64 chars");
        }
        for (char c : handle.toCharArray()) {
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.';
            if (!ok) {
                throw new BusinessException(400, "handle: only [A-Za-z0-9_.-] allowed");
            }
        }
    }

    static void validateHeaderName(String name) {
        if (name == null || name.isBlank() || name.length() > 64) {
            throw new BusinessException(400, "header_name must be 1..64 chars");
        }
        for (char c : name.toCharArray()) {
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-';
            if (!ok) {
                throw new BusinessException(400, "header_name: invalid token character");
            }
        }
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (HEADER_DENY_LIST.contains(lower) || lower.startsWith("content-")) {
            throw new BusinessException(400, "header_name is on the managed/deny list");
        }
    }

    static void validatePrefix(String prefix) {
        if (prefix == null) {
            prefix = "";
        }
        if (prefix.length() > 32) {
            throw new BusinessException(400, "header_prefix must be <= 32 chars");
        }
        for (char c : prefix.toCharArray()) {
            if (c < 0x20 || c == 0x7f) {
                throw new BusinessException(400, "header_prefix: control characters rejected");
            }
        }
    }

    static void validateValue(String value, String prefix) {
        if (value == null) {
            throw new BusinessException(400, "value is required");
        }
        byte[] vb = value.getBytes(StandardCharsets.UTF_8);
        if (vb.length < 16) {
            throw new BusinessException(400, "value must be >= 16 bytes (redaction safety)");
        }
        String combined = (prefix == null ? "" : prefix) + value;
        if (combined.getBytes(StandardCharsets.UTF_8).length > 4096) {
            throw new BusinessException(400, "header_prefix + value must be <= 4096 bytes");
        }
        for (char c : value.toCharArray()) {
            if (c == '\r' || c == '\n' || c == '\0') {
                throw new BusinessException(400, "value: CR/LF/NUL rejected");
            }
        }
    }

    // ------------------------------------------------------------------
    // CRUD (B1: change audit = structured log; no canary states)

    /**
     * Duplicate-key detection that works on SQLite (JdbcTemplate throws
     * {@code UncategorizedSQLException}, error code 19) as well as on
     * MySQL/Postgres ({@code DuplicateKeyException} / SQLState 23505).
     */
    static boolean isUniqueViolation(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String m = String.valueOf(t.getMessage());
            if (m.contains("UNIQUE constraint failed") || m.contains("Duplicate entry")
                    || m.contains("duplicate key value")) {
                return true;
            }
            if (t instanceof java.sql.SQLException sq
                    && (sq.getErrorCode() == 19 || "23505".equals(sq.getSQLState()))) {
                return true;
            }
        }
        return false;
    }
    // ------------------------------------------------------------------

    public Map<String, Object> create(String tenantId, String handle, String host,
            String headerName, String headerPrefix, String value) {
        tenantId = normalizeTenant(tenantId);
        validateHandle(handle);
        String canonical = canonicalHost(host);
        if (canonical == null) {
            throw new BusinessException(400, "host rejected (canonical rules; punycode for IDN)");
        }
        validateHeaderName(headerName);
        String prefix = headerPrefix == null ? "" : headerPrefix;
        validatePrefix(prefix);
        validateValue(value, prefix);

        String ciphertext = envelope().encrypt(
                tenantId, handle, canonical, headerName, prefix, value);
        try {
            jdbc.update(
                    "INSERT INTO tb_delegated_credentials"
                            + " (tenant_id, handle, host, header_name, header_prefix, ciphertext)"
                            + " VALUES (?, ?, ?, ?, ?, ?)",
                    tenantId, handle, canonical, headerName, prefix, ciphertext);
        } catch (org.springframework.dao.DataAccessException e) {
            if (isUniqueViolation(e)) {
                throw new BusinessException(409, "handle or host already exists for tenant"
                        + " (UNIQUE(tenant,host): revoke+delete the old row to re-issue)");
            }
            throw e;
        }
        audit("create", tenantId, handle, canonical);
        publish(tenantId);
        return view(tenantId, handle);
    }

    /** Full re-record: AAD binds every field, so any change re-encrypts. */
    public Map<String, Object> update(String tenantId, String handle, String host,
            String headerName, String headerPrefix, String value) {
        tenantId = normalizeTenant(tenantId);
        validateHandle(handle);
        String canonical = canonicalHost(host);
        if (canonical == null) {
            throw new BusinessException(400, "host rejected (canonical rules; punycode for IDN)");
        }
        validateHeaderName(headerName);
        String prefix = headerPrefix == null ? "" : headerPrefix;
        validatePrefix(prefix);
        validateValue(value, prefix);

        String ciphertext = envelope().encrypt(
                tenantId, handle, canonical, headerName, prefix, value);
        int n;
        try {
            n = jdbc.update(
                    "UPDATE tb_delegated_credentials SET host = ?, header_name = ?,"
                            + " header_prefix = ?, ciphertext = ?, status = 'active',"
                            + " updated_at = CURRENT_TIMESTAMP"
                            + " WHERE tenant_id = ? AND handle = ?",
                    canonical, headerName, prefix, ciphertext, tenantId, handle);
        } catch (org.springframework.dao.DataAccessException e) {
            if (isUniqueViolation(e)) {
                throw new BusinessException(409, "target host already in use for tenant"
                        + " (revoke+delete the old row to re-issue)");
            }
            throw e;
        }
        if (n == 0) {
            throw new BusinessException(404, "credential not found");
        }
        audit("update", tenantId, handle, canonical);
        publish(tenantId);
        return view(tenantId, handle);
    }

    public Map<String, Object> revoke(String tenantId, String handle) {
        tenantId = normalizeTenant(tenantId);
        int n = jdbc.update(
                "UPDATE tb_delegated_credentials SET status = 'revoked',"
                        + " updated_at = CURRENT_TIMESTAMP WHERE tenant_id = ? AND handle = ?",
                tenantId, handle);
        if (n == 0) {
            throw new BusinessException(404, "credential not found");
        }
        audit("revoke", tenantId, handle, null);
        publish(tenantId);
        return view(tenantId, handle);
    }

    /** Hard delete (frees the UNIQUE(tenant,host) slot for re-issue). */
    public void delete(String tenantId, String handle) {
        tenantId = normalizeTenant(tenantId);
        int n = jdbc.update(
                "DELETE FROM tb_delegated_credentials WHERE tenant_id = ? AND handle = ?",
                tenantId, handle);
        if (n == 0) {
            throw new BusinessException(404, "credential not found");
        }
        audit("delete", tenantId, handle, null);
        publish(tenantId);
    }

    public List<Map<String, Object>> list(String tenantId) {
        tenantId = normalizeTenant(tenantId);
        return jdbc.query(
                "SELECT handle, host, header_name, header_prefix, status, created_at, updated_at"
                        + " FROM tb_delegated_credentials WHERE tenant_id = ? ORDER BY handle",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("handle", rs.getString("handle"));
                    m.put("host", rs.getString("host"));
                    m.put("header_name", rs.getString("header_name"));
                    m.put("header_prefix", rs.getString("header_prefix"));
                    m.put("status", rs.getString("status"));
                    m.put("created_at", rs.getTimestamp("created_at").toInstant().toString());
                    m.put("updated_at", rs.getTimestamp("updated_at").toInstant().toString());
                    return m;
                },
                tenantId);
    }

    private Map<String, Object> view(String tenantId, String handle) {
        return jdbc.queryForObject(
                "SELECT handle, host, header_name, header_prefix, status FROM"
                        + " tb_delegated_credentials WHERE tenant_id = ? AND handle = ?",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("handle", rs.getString("handle"));
                    m.put("host", rs.getString("host"));
                    m.put("header_name", rs.getString("header_name"));
                    m.put("header_prefix", rs.getString("header_prefix"));
                    m.put("status", rs.getString("status"));
                    return m;
                },
                tenantId, handle);
    }

    /** One-time KEK reveal for proxy deployment (audited; value never logged). */
    public String revealKek(String tenantId) {
        tenantId = normalizeTenant(tenantId);
        audit("kek_reveal", tenantId, null, null);
        return envelope().revealTenantKekB64(tenantId);
    }

    // ------------------------------------------------------------------
    // Snapshot publish (Redis SET; one retry; scheduled self-heal)
    // ------------------------------------------------------------------

    public void publish(String tenantId) {
        tenantId = normalizeTenant(tenantId);
        try {
            publishOnce(tenantId);
        } catch (Exception first) {
            try {
                publishOnce(tenantId);
            } catch (Exception second) {
                // DB write already committed; scheduled re-publish self-heals.
                log.error("delegated_audit event=publish_failed tenant={} error={}",
                        tenantId, second.getMessage());
            }
        }
    }

    private void publishOnce(String tenantId) {
        tenantId = normalizeTenant(tenantId);
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT tenant_id, handle, host, header_name, header_prefix, ciphertext FROM"
                        + " tb_delegated_credentials WHERE tenant_id = ? AND status = 'active'",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("tenant_id", rs.getString("tenant_id"));
                    m.put("handle", rs.getString("handle"));
                    m.put("host", rs.getString("host"));
                    m.put("header_name", rs.getString("header_name"));
                    m.put("header_prefix", rs.getString("header_prefix"));
                    m.put("ciphertext", rs.getString("ciphertext"));
                    return m;
                },
                tenantId);
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("v", 1);
        doc.put("tenant_id", tenantId);
        doc.put("gen", System.currentTimeMillis());
        doc.put("rows", rows);
        String json;
        try {
            json = mapper.writeValueAsString(doc);
        } catch (Exception e) {
            throw new IllegalStateException("snapshot encode failed", e);
        }
        var pool = jedisPools.pool().orElseThrow(
                () -> new IllegalStateException("redis unavailable for delegated snapshot"));
        try (var jedis = pool.getResource()) {
            jedis.set(SNAPSHOT_KEY_PREFIX + tenantId, json);
        }
        log.info("delegated_audit event=publish tenant={} active_rows={}", tenantId, rows.size());
    }

    /** Fallback re-publish for every tenant with rows (self-heal, 60s). */
    @Scheduled(fixedDelay = 60_000, initialDelay = 90_000)
    public void republishAll() {
        List<String> tenants = jdbc.query(
                "SELECT DISTINCT tenant_id FROM tb_delegated_credentials",
                (rs, i) -> rs.getString(1));
        for (String tenant : tenants) {
            try {
                publishOnce(tenant);
            } catch (Exception e) {
                log.error("delegated_audit event=publish_failed tenant={} error={}",
                        tenant, e.getMessage());
            }
        }
    }

    private void audit(String event, String tenantId, String handle, String host) {
        log.info("delegated_audit event={} tenant={} handle={} host={}",
                event, tenantId, handle, host);
    }
}
