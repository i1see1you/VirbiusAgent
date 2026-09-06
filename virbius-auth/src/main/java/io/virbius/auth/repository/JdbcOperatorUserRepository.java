package io.virbius.auth.repository;

import io.virbius.auth.domain.OperatorUser;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcOperatorUserRepository implements OperatorUserRepository {

    private final JdbcTemplate jdbc;

    public JdbcOperatorUserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long count() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM tb_operator_user", Long.class);
        return n == null ? 0 : n;
    }

    @Override
    public Optional<OperatorUser> findByUsername(String username) {
        List<OperatorUser> rows =
                jdbc.query("SELECT * FROM tb_operator_user WHERE username = ?", this::map, username);
        return rows.stream().findFirst();
    }

    @Override
    public Optional<OperatorUser> findById(String userId) {
        List<OperatorUser> rows =
                jdbc.query("SELECT * FROM tb_operator_user WHERE user_id = ?", this::map, userId);
        return rows.stream().findFirst();
    }

    @Override
    public List<OperatorUser> listAll() {
        return jdbc.query("SELECT * FROM tb_operator_user ORDER BY created_at", this::map);
    }

    @Override
    public void insert(OperatorUser user) {
        jdbc.update(
                "INSERT INTO tb_operator_user (user_id, username, password_hash, role, tenant_id, status, created_at)"
                        + " VALUES (?,?,?,?,?,?,?)",
                user.userId(),
                user.username(),
                user.passwordHash(),
                user.role(),
                user.tenantId(),
                user.status(),
                Timestamp.from(user.createdAt()));
    }

    @Override
    public void disable(String userId) {
        jdbc.update("UPDATE tb_operator_user SET status = ? WHERE user_id = ?", OperatorUser.STATUS_DISABLED, userId);
    }

    private OperatorUser map(ResultSet rs, int ignored) throws SQLException {
        Timestamp created = rs.getTimestamp("created_at");
        return new OperatorUser(
                rs.getString("user_id"),
                rs.getString("username"),
                rs.getString("password_hash"),
                rs.getString("role"),
                rs.getString("tenant_id"),
                rs.getString("status"),
                created == null ? null : created.toInstant());
    }
}
