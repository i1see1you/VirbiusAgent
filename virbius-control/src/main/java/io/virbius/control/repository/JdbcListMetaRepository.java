package io.virbius.control.repository;

import io.virbius.control.domain.AccessListEntry;
import io.virbius.control.domain.AccessListMeta;
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
public class JdbcListMetaRepository implements ListMetaRepository {

    private final JdbcTemplate jdbc;

    public JdbcListMetaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<AccessListMeta> META_MAPPER = (rs, rowNum) -> mapMeta(rs);

    private static final RowMapper<AccessListEntry> ENTRY_MAPPER = (rs, rowNum) -> mapEntry(rs);

    private static AccessListMeta mapMeta(ResultSet rs) throws SQLException {
        return new AccessListMeta(
                rs.getString("tenant_id"),
                rs.getString("list_name"),
                rs.getString("dimension"),
                rs.getString("remark"));
    }

    private static AccessListEntry mapEntry(ResultSet rs) throws SQLException {
        return new AccessListEntry(
                rs.getString("value"),
                rs.getString("remark"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("expires_at")));
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    @Override
    public List<AccessListMeta> listMeta(String tenantId) {
        return jdbc.query(
                """
                SELECT tenant_id, list_name, dimension, remark
                FROM tb_access_list_meta WHERE tenant_id = ?
                ORDER BY list_name
                """,
                META_MAPPER,
                tenantId);
    }

    @Override
    public Optional<AccessListMeta> getMeta(String tenantId, String listName) {
        List<AccessListMeta> rows = jdbc.query(
                """
                SELECT tenant_id, list_name, dimension, remark
                FROM tb_access_list_meta WHERE tenant_id = ? AND list_name = ?
                """,
                META_MAPPER,
                tenantId,
                listName);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public void upsertMeta(AccessListMeta meta) {
        int updated = jdbc.update(
                """
                UPDATE tb_access_list_meta SET dimension=?, remark=?, updated_at=CURRENT_TIMESTAMP
                WHERE tenant_id=? AND list_name=?
                """,
                meta.dimension(),
                meta.remark(),
                meta.tenantId(),
                meta.listName());
        if (updated == 0) {
            jdbc.update(
                    """
                    INSERT INTO tb_access_list_meta (tenant_id, list_name, dimension, remark)
                    VALUES (?,?,?,?)
                    """,
                    meta.tenantId(),
                    meta.listName(),
                    meta.dimension(),
                    meta.remark());
        }
    }

    @Override
    public void deleteMeta(String tenantId, String listName) {
        jdbc.update("DELETE FROM tb_access_list_entry WHERE tenant_id = ? AND list_name = ?", tenantId, listName);
        jdbc.update("DELETE FROM tb_access_list_meta WHERE tenant_id = ? AND list_name = ?", tenantId, listName);
    }

    @Override
    public List<AccessListEntry> listEntries(String tenantId, String listName) {
        return jdbc.query(
                """
                SELECT value, remark, created_at, expires_at
                FROM tb_access_list_entry WHERE tenant_id = ? AND list_name = ?
                ORDER BY value
                """,
                ENTRY_MAPPER,
                tenantId,
                listName);
    }

    @Override
    public void replaceEntries(String tenantId, String listName, List<AccessListEntry> entries) {
        jdbc.update("DELETE FROM tb_access_list_entry WHERE tenant_id = ? AND list_name = ?", tenantId, listName);
        if (entries == null) {
            return;
        }
        for (AccessListEntry e : entries) {
            insertEntry(tenantId, listName, e.value(), e.remark(), e.expiresAt());
        }
    }

    @Override
    public boolean addEntry(String tenantId, String listName, String value, String remark, Instant expiresAt) {
        int n = jdbc.update(
                """
                INSERT INTO tb_access_list_entry (tenant_id, list_name, value, remark, expires_at)
                SELECT ?, ?, ?, ?, ? FROM (SELECT 1) AS _one
                WHERE NOT EXISTS (
                  SELECT 1 FROM tb_access_list_entry WHERE tenant_id=? AND list_name=? AND value=?
                )
                """,
                tenantId,
                listName,
                value,
                remark,
                expiresAt == null ? null : Timestamp.from(expiresAt),
                tenantId,
                listName,
                value);
        return n > 0;
    }

    @Override
    public boolean removeEntry(String tenantId, String listName, String value) {
        return jdbc.update(
                        "DELETE FROM tb_access_list_entry WHERE tenant_id = ? AND list_name = ? AND value = ?",
                        tenantId,
                        listName,
                        value)
                > 0;
    }

    private void insertEntry(String tenantId, String listName, String value, String remark, Instant expiresAt) {
        jdbc.update(
                """
                INSERT INTO tb_access_list_entry (tenant_id, list_name, value, remark, expires_at)
                VALUES (?,?,?,?,?)
                """,
                tenantId,
                listName,
                value,
                remark,
                expiresAt == null ? null : Timestamp.from(expiresAt));
    }
}
