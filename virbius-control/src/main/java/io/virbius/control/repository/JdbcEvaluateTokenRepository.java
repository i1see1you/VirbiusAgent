package io.virbius.control.repository;

import io.virbius.control.repository.EvaluateTokenRepository.IssuedToken;
import io.virbius.control.repository.EvaluateTokenRepository.IssuerKey;
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
public class JdbcEvaluateTokenRepository implements EvaluateTokenRepository {

    private static final RowMapper<IssuerKey> KEY_MAPPER = JdbcEvaluateTokenRepository::mapKey;
    private static final RowMapper<IssuedToken> TOKEN_MAPPER = JdbcEvaluateTokenRepository::mapToken;

    private final JdbcTemplate jdbc;

    public JdbcEvaluateTokenRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // -- issuer key registry ----------------------------------------------------

    @Override
    public void saveIssuer(IssuerKey key) {
        // Portable upsert (SQLite + MySQL): UPDATE first, INSERT when absent.
        // Kids are random so real-world duplicates only happen on idempotent re-save.
        int updated = jdbc.update(
                "UPDATE tb_evaluate_token_issuers SET key_type = ?, public_key_pem = ?,"
                        + " encrypted_private_key = ?, status = ? WHERE kid = ?",
                key.keyType(), key.publicKeyPem(), key.encryptedPrivateKey(), key.status(), key.kid());
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO tb_evaluate_token_issuers"
                            + " (kid, key_type, public_key_pem, encrypted_private_key, status, description)"
                            + " VALUES (?, ?, ?, ?, ?, ?)",
                    key.kid(), key.keyType(), key.publicKeyPem(), key.encryptedPrivateKey(),
                    key.status(), "platform evaluate-token signing key");
        }
    }

    @Override
    public Optional<IssuerKey> findActiveIssuer() {
        List<IssuerKey> rows = jdbc.query(
                "SELECT kid, key_type, public_key_pem, encrypted_private_key, status"
                        + " FROM tb_evaluate_token_issuers WHERE status = 'active' ORDER BY created_at DESC",
                KEY_MAPPER);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public List<IssuerKey> listIssuersByStatus(String status) {
        return jdbc.query(
                "SELECT kid, key_type, public_key_pem, encrypted_private_key, status"
                        + " FROM tb_evaluate_token_issuers WHERE status = ?",
                KEY_MAPPER, status);
    }

    @Override
    public void retireIssuer(String kid) {
        jdbc.update(
                "UPDATE tb_evaluate_token_issuers SET status = 'retired', retired_at = ? WHERE kid = ?",
                Timestamp.from(Instant.now()), kid);
    }

    // -- issuance ledger ----------------------------------------------------------

    @Override
    public void saveToken(IssuedToken token) {
        // jti is 128-bit random — collisions are cryptographically impossible,
        // so a plain INSERT is enough (and portable across dialects).
        jdbc.update(
                "INSERT INTO tb_evaluate_tokens"
                        + " (jti, subject, tenant_id, app_id, license_id, sid, issued_at, expires_at, status)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'active')",
                token.jti(), token.subject(), token.tenantId(), token.appId(), token.licenseId(),
                token.sid(), Timestamp.from(token.issuedAt()), Timestamp.from(token.expiresAt()));
    }

    @Override
    public Optional<Instant> findTokenExpiry(String jti) {
        List<Instant> rows = jdbc.query(
                "SELECT expires_at FROM tb_evaluate_tokens WHERE jti = ? AND status = 'active'",
                (rs, i) -> rs.getTimestamp("expires_at").toInstant(), jti);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public void revokeToken(String jti, String reason) {
        jdbc.update(
                "UPDATE tb_evaluate_tokens SET status = 'revoked', revoked_at = ?, revoke_reason = ?"
                        + " WHERE jti = ?",
                Timestamp.from(Instant.now()), reason, jti);
    }

    @Override
    public int deleteExpiredBefore(Instant cutoff) {
        return jdbc.update(
                "DELETE FROM tb_evaluate_tokens WHERE expires_at < ? AND status = 'active'",
                Timestamp.from(cutoff));
    }

    @Override
    public long countActive() {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tb_evaluate_tokens WHERE status = 'active'", Long.class);
        return n == null ? 0L : n;
    }

    // -- mappers --------------------------------------------------------------------

    private static IssuerKey mapKey(ResultSet rs, int rowNum) throws SQLException {
        return new IssuerKey(
                rs.getString("kid"),
                rs.getString("key_type"),
                rs.getString("public_key_pem"),
                rs.getString("encrypted_private_key"),
                rs.getString("status"));
    }

    private static IssuedToken mapToken(ResultSet rs, int rowNum) throws SQLException {
        return new IssuedToken(
                rs.getString("jti"),
                rs.getString("subject"),
                rs.getString("tenant_id"),
                rs.getString("app_id"),
                rs.getString("license_id"),
                rs.getString("sid"),
                rs.getTimestamp("issued_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant());
    }
}
