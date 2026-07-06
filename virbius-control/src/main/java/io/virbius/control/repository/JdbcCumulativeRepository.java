package io.virbius.control.repository;

import io.virbius.control.domain.CumulativeDef;
import io.virbius.policy.CumulativeDimension;
import io.virbius.policy.CumulativeWindow;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCumulativeRepository implements CumulativeRepository {

    private final JdbcTemplate jdbc;

    public JdbcCumulativeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        ensurePredicateColumns();
    }

    private void ensurePredicateColumns() {
        try {
            jdbc.execute("ALTER TABLE tb_cumulative ADD COLUMN ingest_predicate_runtime VARCHAR(16)");
        } catch (Exception ignored) {
        }
        try {
            jdbc.execute("ALTER TABLE tb_cumulative ADD COLUMN ingest_predicate TEXT");
        } catch (Exception ignored) {
        }
    }

    private static final RowMapper<CumulativeDef> MAPPER = (rs, rowNum) -> map(rs);

    private static CumulativeDef map(ResultSet rs) throws SQLException {
        Integer windowMinutes = (Integer) rs.getObject("window_minutes");
        Integer windowHours = (Integer) rs.getObject("window_hours");
        return new CumulativeDef(
                rs.getString("tenant_id"),
                rs.getString("cumulative_name"),
                rs.getString("description"),
                rs.getString("dimension"),
                rs.getString("window_kind"),
                windowMinutes,
                windowHours,
                rs.getString("timezone"),
                rs.getInt("priority"),
                rs.getString("status"),
                hasColumn(rs, "ingest_predicate_runtime") ? rs.getString("ingest_predicate_runtime") : null,
                hasColumn(rs, "ingest_predicate") ? rs.getString("ingest_predicate") : null);
    }

    private static boolean hasColumn(ResultSet rs, String column) {
        try {
            rs.findColumn(column);
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private static final String SELECT_COLS =
            """
            SELECT tenant_id, cumulative_name, description, dimension, window_kind, window_minutes,
                   window_hours, timezone, priority, status, ingest_predicate_runtime, ingest_predicate
            FROM tb_cumulative
            """;

    @Override
    public List<CumulativeDef> list(String tenantId, String status) {
        String sql = SELECT_COLS + " WHERE tenant_id = ?";
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (status != null && !status.isBlank()) {
            sql += " AND status = ?";
            args.add(status);
        }
        sql += " ORDER BY priority, cumulative_name";
        return jdbc.query(sql, MAPPER, args.toArray());
    }

    @Override
    public Optional<CumulativeDef> get(String tenantId, String cumulativeName) {
        List<CumulativeDef> rows = jdbc.query(
                SELECT_COLS + " WHERE tenant_id = ? AND cumulative_name = ?",
                MAPPER,
                tenantId,
                cumulativeName);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public void upsert(CumulativeDef def) {
        validate(def);
        int updated = jdbc.update(
                """
                UPDATE tb_cumulative SET description=?, dimension=?, window_kind=?, window_minutes=?, window_hours=?,
                    timezone=?, priority=?, status=?, ingest_predicate_runtime=?, ingest_predicate=?, updated_at=CURRENT_TIMESTAMP
                WHERE tenant_id=? AND cumulative_name=?
                """,
                def.description(),
                def.dimension(),
                def.windowKind(),
                def.windowMinutes(),
                def.windowHours(),
                def.timezone(),
                def.priority(),
                def.status(),
                normalizeRuntime(def.ingestPredicateRuntime(), def.ingestPredicate()),
                normalizePredicate(def.ingestPredicate()),
                def.tenantId(),
                def.cumulativeName());
        if (updated == 0) {
            jdbc.update(
                    """
                    INSERT INTO tb_cumulative (
                      tenant_id, cumulative_name, description, dimension, window_kind, window_minutes,
                      window_hours, timezone, priority, status, ingest_predicate_runtime, ingest_predicate)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                    """,
                    def.tenantId(),
                    def.cumulativeName(),
                    def.description(),
                    def.dimension(),
                    def.windowKind(),
                    def.windowMinutes(),
                    def.windowHours(),
                    def.timezone(),
                    def.priority(),
                    def.status(),
                    normalizeRuntime(def.ingestPredicateRuntime(), def.ingestPredicate()),
                    normalizePredicate(def.ingestPredicate()));
        }
    }

    @Override
    public void delete(String tenantId, String cumulativeName) {
        jdbc.update("DELETE FROM tb_cumulative WHERE tenant_id = ? AND cumulative_name = ?", tenantId, cumulativeName);
    }

    private static String normalizePredicate(String predicate) {
        return predicate != null && !predicate.isBlank() ? predicate : null;
    }

    private static String normalizeRuntime(String runtime, String predicate) {
        if (predicate == null || predicate.isBlank()) {
            return null;
        }
        return runtime != null && !runtime.isBlank() ? runtime : "lua";
    }

    private static void validate(CumulativeDef def) {
        CumulativeDimension.validate(def.dimension());
        if ("calendar_day".equalsIgnoreCase(def.windowKind())) {
            if (def.timezone() == null || def.timezone().isBlank()) {
                throw new IllegalArgumentException("calendar_day requires timezone");
            }
        } else {
            CumulativeWindow.windowMinutes(def.windowKind(), def.windowMinutes(), def.windowHours());
        }
        if (def.windowMinutes() != null && def.windowHours() != null) {
            throw new IllegalArgumentException("window_minutes and window_hours are mutually exclusive");
        }
        if (def.ingestPredicate() != null && !def.ingestPredicate().isBlank()) {
            String rt = def.ingestPredicateRuntime() != null && !def.ingestPredicateRuntime().isBlank()
                    ? def.ingestPredicateRuntime()
                    : "lua";
            if (!"lua".equalsIgnoreCase(rt)) {
                throw new IllegalArgumentException("ingest_predicate_runtime must be lua");
            }
        }
    }
}
