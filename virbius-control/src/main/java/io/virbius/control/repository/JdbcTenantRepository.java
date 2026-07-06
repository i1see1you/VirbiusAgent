package io.virbius.control.repository;

import io.virbius.control.domain.Tenant;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTenantRepository implements TenantRepository {

    private static final RowMapper<Tenant> MAPPER = JdbcTenantRepository::mapRow;

    private final JdbcTemplate jdbc;

    public JdbcTenantRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Tenant mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Tenant(
                rs.getString("tenant_id"),
                rs.getString("name"),
                rs.getTimestamp("created_at").toInstant());
    }

    @Override
    public List<Tenant> listAll() {
        return jdbc.query(
                "SELECT tenant_id, name, created_at FROM tb_tenants ORDER BY created_at",
                MAPPER);
    }

    @Override
    public Optional<Tenant> findById(String tenantId) {
        List<Tenant> rows = jdbc.query(
                "SELECT tenant_id, name, created_at FROM tb_tenants WHERE tenant_id = ?",
                MAPPER,
                tenantId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public boolean exists(String tenantId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tb_tenants WHERE tenant_id = ?", Integer.class, tenantId);
        return n != null && n > 0;
    }

    @Override
    public void insert(Tenant tenant) {
        jdbc.update(
                "INSERT INTO tb_tenants (tenant_id, name, created_at) VALUES (?, ?, ?)",
                tenant.tenantId(),
                tenant.name(),
                Timestamp.from(tenant.createdAt()));
    }

    @Override
    public void updateName(String tenantId, String name) {
        jdbc.update("UPDATE tb_tenants SET name = ? WHERE tenant_id = ?", name, tenantId);
    }
}
