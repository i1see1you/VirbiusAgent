package io.virbius.control.repository;

import io.virbius.control.domain.Tenant;
import io.virbius.control.domain.TenantApiCredential;
import io.virbius.control.security.ApiRole;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTenantApiCredentialRepository implements TenantApiCredentialRepository {

    private static final RowMapper<TenantApiCredential> MAPPER = JdbcTenantApiCredentialRepository::mapRow;

    private final JdbcTemplate jdbc;

    public JdbcTenantApiCredentialRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static TenantApiCredential mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new TenantApiCredential(
                rs.getString("credential_id"),
                rs.getString("tenant_id"),
                ApiRole.parse(rs.getString("role")),
                rs.getString("key_hash"),
                rs.getString("key_prefix"),
                rs.getString("label"),
                rs.getString("status"),
                rs.getString("created_by"),
                rs.getTimestamp("created_at").toInstant(),
                timestampToInstant(rs.getTimestamp("revoked_at")),
                timestampToInstant(rs.getTimestamp("last_used_at")));
    }

    private static Instant timestampToInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }

    @Override
    public Optional<TenantApiCredential> findActiveByKeyHash(String keyHash) {
        List<TenantApiCredential> rows = jdbc.query(
                """
                SELECT credential_id, tenant_id, role, key_hash, key_prefix, label, status,
                       created_by, created_at, revoked_at, last_used_at
                FROM tb_tenant_api_credential
                WHERE key_hash = ? AND status = ?
                """,
                MAPPER,
                keyHash,
                TenantApiCredential.STATUS_ACTIVE);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public List<TenantApiCredential> listByTenant(String tenantId) {
        return jdbc.query(
                """
                SELECT credential_id, tenant_id, role, key_hash, key_prefix, label, status,
                       created_by, created_at, revoked_at, last_used_at
                FROM tb_tenant_api_credential
                WHERE tenant_id = ?
                ORDER BY created_at DESC
                """,
                MAPPER,
                tenantId);
    }

    @Override
    public List<TenantApiCredential> listPlatformCredentials() {
        return listByTenant(TenantApiCredential.PLATFORM_TENANT);
    }

    @Override
    public void insert(TenantApiCredential credential) {
        jdbc.update(
                """
                INSERT INTO tb_tenant_api_credential
                    (credential_id, tenant_id, role, key_hash, key_prefix, label, status,
                     created_by, created_at, revoked_at, last_used_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                credential.credentialId(),
                credential.tenantId(),
                credential.role().value(),
                credential.keyHash(),
                credential.keyPrefix(),
                credential.label(),
                credential.status(),
                credential.createdBy(),
                Timestamp.from(credential.createdAt()),
                credential.revokedAt() != null ? Timestamp.from(credential.revokedAt()) : null,
                credential.lastUsedAt() != null ? Timestamp.from(credential.lastUsedAt()) : null);
    }

    @Override
    public void revoke(String tenantId, String credentialId) {
        jdbc.update(
                """
                UPDATE tb_tenant_api_credential
                SET status = ?, revoked_at = ?
                WHERE tenant_id = ? AND credential_id = ? AND status = ?
                """,
                TenantApiCredential.STATUS_REVOKED,
                Timestamp.from(Instant.now()),
                tenantId,
                credentialId,
                TenantApiCredential.STATUS_ACTIVE);
    }

    @Override
    public void touchLastUsed(String credentialId) {
        jdbc.update(
                """
                UPDATE tb_tenant_api_credential
                SET last_used_at = ?
                WHERE credential_id = ?
                """,
                Timestamp.from(Instant.now()),
                credentialId);
    }
}
