package io.virbius.control.repository;

import io.virbius.control.domain.AgentLicense;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcLicenseRepository implements LicenseRepository {

    private static final RowMapper<AgentLicense> MAPPER = JdbcLicenseRepository::mapRow;

    private final JdbcTemplate jdbc;

    public JdbcLicenseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static AgentLicense mapRow(ResultSet rs, int rowNum) throws SQLException {
        AgentLicense lic = new AgentLicense();
        lic.setAppId(rs.getString("app_id"));
        lic.setTenantId(rs.getString("tenant_id"));
        String toolsRaw = rs.getString("allowed_tools");
        lic.setAllowedTools(toolsRaw != null ? Arrays.asList(toolsRaw.split(",")) : List.of());
        String scenesRaw = rs.getString("allowed_scenes");
        lic.setAllowedScenes(scenesRaw != null ? Arrays.asList(scenesRaw.split(",")) : List.of());
        lic.setRiskQuota(rs.getInt("risk_quota"));
        lic.setToolRateLimit(rs.getInt("tool_rate_limit"));
        Timestamp exp = rs.getTimestamp("expiry");
        lic.setExpiry(exp != null ? exp.toInstant() : null);
        Timestamp issued = rs.getTimestamp("issued_at");
        lic.setIssuedAt(issued != null ? issued.toInstant() : null);
        lic.setStatus(rs.getString("status"));
        lic.setSignature(rs.getString("signature"));
        return lic;
    }

    @Override
    public void save(AgentLicense license, String licenseId) {
        jdbc.update(
                """
                INSERT INTO tb_agent_licenses
                    (license_id, tenant_id, app_id, allowed_tools, allowed_scenes,
                     risk_quota, tool_rate_limit, expiry, issued_at, status, signature, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                licenseId,
                license.getTenantId(),
                license.getAppId(),
                String.join(",", license.getAllowedTools() != null ? license.getAllowedTools() : List.of()),
                String.join(",", license.getAllowedScenes() != null ? license.getAllowedScenes() : List.of()),
                license.getRiskQuota(),
                license.getToolRateLimit(),
                Timestamp.from(license.getExpiry()),
                Timestamp.from(license.getIssuedAt() != null ? license.getIssuedAt() : Instant.now()),
                license.getStatus() != null ? license.getStatus() : "active",
                license.getSignature(),
                "system");
    }

    @Override
    public Optional<AgentLicense> findActiveByAppId(String tenantId, String appId) {
        List<AgentLicense> rows = jdbc.query(
                """
                SELECT app_id, tenant_id, allowed_tools, allowed_scenes, risk_quota,
                       tool_rate_limit, expiry, issued_at, status, signature
                FROM tb_agent_licenses
                WHERE tenant_id = ? AND app_id = ? AND status = 'active'
                ORDER BY issued_at DESC LIMIT 1
                """,
                MAPPER,
                tenantId,
                appId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public List<AgentLicense> listByTenant(String tenantId, String status) {
        String sql = """
                SELECT app_id, tenant_id, allowed_tools, allowed_scenes, risk_quota,
                       tool_rate_limit, expiry, issued_at, status, signature
                FROM tb_agent_licenses
                WHERE tenant_id = ?
                """;
        if (status != null && !status.isBlank()) {
            sql += " AND status = ?";
            return jdbc.query(sql, MAPPER, tenantId, status);
        }
        return jdbc.query(sql, MAPPER, tenantId);
    }

    @Override
    public void revoke(String licenseId, String revokedBy, String reason) {
        jdbc.update(
                "UPDATE tb_agent_licenses SET status = 'revoked', revoked_at = ?, revoke_reason = ? WHERE license_id = ?",
                Timestamp.from(Instant.now()),
                reason,
                licenseId);
        jdbc.update(
                """
                INSERT INTO tb_license_revocations (license_id, tenant_id, app_id, revoked_by, revoke_reason)
                SELECT ?, tenant_id, app_id, ?, ?
                FROM tb_agent_licenses WHERE license_id = ?
                """,
                licenseId,
                revokedBy,
                reason,
                licenseId);
    }

    @Override
    public void saveKeyPair(String keyId, String tenantId, String publicKeyPem, String encryptedPrivateKey) {
        jdbc.update(
                """
                INSERT INTO tb_license_keys (key_id, tenant_id, public_key_pem, private_key_enc, algorithm, status)
                VALUES (?, ?, ?, ?, 'EdDSA', 'active')
                """,
                keyId,
                tenantId,
                publicKeyPem,
                encryptedPrivateKey);
    }

    @Override
    public Optional<String> getActivePublicKey(String tenantId) {
        List<String> rows = jdbc.queryForList(
                "SELECT public_key_pem FROM tb_license_keys WHERE tenant_id = ? AND status = 'active' ORDER BY created_at DESC LIMIT 1",
                String.class,
                tenantId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public Optional<String> getActiveEncryptedPrivateKey(String tenantId) {
        List<String> rows = jdbc.queryForList(
                "SELECT private_key_enc FROM tb_license_keys WHERE tenant_id = ? AND status = 'active' ORDER BY created_at DESC LIMIT 1",
                String.class,
                tenantId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public void rotateKey(String tenantId, String newKeyId, String newPubPem, String newEncPriv) {
        jdbc.update("UPDATE tb_license_keys SET status = 'rotated', rotated_at = ? WHERE tenant_id = ? AND status = 'active'",
                Timestamp.from(Instant.now()), tenantId);
        saveKeyPair(newKeyId, tenantId, newPubPem, newEncPriv);
    }
}
