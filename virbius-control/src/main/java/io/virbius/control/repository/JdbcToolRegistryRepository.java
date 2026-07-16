package io.virbius.control.repository;

import io.virbius.control.domain.ToolRegistryEntry;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcToolRegistryRepository implements ToolRegistryRepository {

    private final JdbcTemplate jdbc;

    public JdbcToolRegistryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ToolRegistryEntry> MAPPER = (rs, rowNum) -> map(rs);

    private static ToolRegistryEntry map(ResultSet rs) throws SQLException {
        return new ToolRegistryEntry(
                rs.getString("tenant_id"),
                rs.getString("tool_name"),
                rs.getString("risk_class"),
                rs.getString("sandbox_type"),
                rs.getInt("timeout_ms"),
                rs.getBoolean("fast_path"),
                rs.getString("allowed_args_schema"),
                rs.getString("description"));
    }

    private static final String SELECT_COLS = """
            SELECT tenant_id, tool_name, risk_class, sandbox_type, timeout_ms,
                   fast_path, allowed_args_schema, description
            FROM tb_tool_registry
            """;

    @Override
    public List<ToolRegistryEntry> list(String tenantId) {
        return jdbc.query(
                SELECT_COLS + " WHERE tenant_id = ? ORDER BY tool_name",
                MAPPER,
                tenantId);
    }

    @Override
    public Optional<ToolRegistryEntry> get(String tenantId, String toolName) {
        List<ToolRegistryEntry> rows = jdbc.query(
                SELECT_COLS + " WHERE tenant_id = ? AND tool_name = ?",
                MAPPER,
                tenantId,
                toolName);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public void upsert(ToolRegistryEntry entry) {
        int updated = jdbc.update(
                """
                UPDATE tb_tool_registry SET risk_class=?, sandbox_type=?, timeout_ms=?,
                    fast_path=?, allowed_args_schema=?, description=?, updated_at=CURRENT_TIMESTAMP
                WHERE tenant_id=? AND tool_name=?
                """,
                entry.riskClass(),
                entry.sandboxType(),
                entry.timeoutMs(),
                entry.fastPath(),
                entry.allowedArgsSchemaJson(),
                entry.description(),
                entry.tenantId(),
                entry.toolName());
        if (updated == 0) {
            jdbc.update(
                    """
                    INSERT INTO tb_tool_registry (
                      tenant_id, tool_name, risk_class, sandbox_type, timeout_ms,
                      fast_path, allowed_args_schema, description)
                    VALUES (?,?,?,?,?,?,?,?)
                    """,
                    entry.tenantId(),
                    entry.toolName(),
                    entry.riskClass(),
                    entry.sandboxType(),
                    entry.timeoutMs(),
                    entry.fastPath(),
                    entry.allowedArgsSchemaJson(),
                    entry.description());
        }
    }

    @Override
    public void delete(String tenantId, String toolName) {
        jdbc.update(
                "DELETE FROM tb_tool_registry WHERE tenant_id = ? AND tool_name = ?",
                tenantId,
                toolName);
    }
}
