package io.virbius.auth.repository;

import io.virbius.auth.domain.AuthCode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAuthCodeRepository implements AuthCodeRepository {

    private final JdbcTemplate jdbc;

    public JdbcAuthCodeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(AuthCode code) {
        jdbc.update(
                "INSERT INTO tb_auth_code (code_hash, user_id, return_uri, expires_at, consumed_at) VALUES (?,?,?,?,?)",
                code.codeHash(),
                code.userId(),
                code.returnUri(),
                Timestamp.from(code.expiresAt()),
                code.consumedAt() == null ? null : Timestamp.from(code.consumedAt()));
    }

    @Override
    public Optional<AuthCode> findByHash(String codeHash) {
        List<AuthCode> rows = jdbc.query("SELECT * FROM tb_auth_code WHERE code_hash = ?", this::map, codeHash);
        return rows.stream().findFirst();
    }

    @Override
    public void markConsumed(String codeHash, Instant consumedAt) {
        jdbc.update(
                "UPDATE tb_auth_code SET consumed_at = ? WHERE code_hash = ?", Timestamp.from(consumedAt), codeHash);
    }

    private AuthCode map(ResultSet rs, int ignored) throws SQLException {
        Timestamp exp = rs.getTimestamp("expires_at");
        Timestamp consumed = rs.getTimestamp("consumed_at");
        return new AuthCode(
                rs.getString("code_hash"),
                rs.getString("user_id"),
                rs.getString("return_uri"),
                exp == null ? null : exp.toInstant(),
                consumed == null ? null : consumed.toInstant());
    }
}
