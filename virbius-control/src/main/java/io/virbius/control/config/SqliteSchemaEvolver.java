package io.virbius.control.config;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Dev-only SQLite schema evolution. {@code spring.sql.init} applies
 * db/schema.sql with CREATE TABLE IF NOT EXISTS, so a column added to the
 * baseline never reaches an existing dev database file, and Flyway is disabled
 * in dev (rebuilding via VIRBIUS_REBUILD_DB=1 would wipe the data). This
 * callback adds the known missing columns idempotently at startup, before the
 * Kafka listener containers start (SmartInitializingSingleton runs ahead of
 * SmartLifecycle), so ingestors never hit a half-evolved table.
 *
 * <p>Keep the list in sync with db/schema.sql and the matching Vn migration.
 * MySQL/staging/prod evolve via Flyway instead and are skipped by the dialect
 * guard.
 */
@Component
@Profile("dev")
public class SqliteSchemaEvolver implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(SqliteSchemaEvolver.class);

    /** (table, column, SQLite type) additions; see V20__audit_event_session_id.sql. */
    private record ColumnDef(String table, String column, String type) {}

    private static final List<ColumnDef> COLUMNS = List.of(
            new ColumnDef("tb_audit_events", "session_id", "VARCHAR(128)"),
            new ColumnDef("tb_audit_events_archive", "session_id", "VARCHAR(128)"),
            new ColumnDef("tb_evaluation_setting", "probe_tenants",
                    "VARCHAR(1024) NOT NULL DEFAULT ''"));

    /**
     * Indexes that depend on evolved columns. They cannot live in db/schema.sql:
     * spring.sql.init runs BEFORE this evolver, so on an existing database the
     * statement would reference a column that has not been added yet (startup
     * failure). MySQL/prod creates them via the Flyway migration instead.
     */
    private static final List<String> INDEXES = List.of(
            "CREATE INDEX IF NOT EXISTS idx_audit_events_tenant_session"
                    + " ON tb_audit_events (tenant_id, session_id, intercepted_at)");

    private final JdbcTemplate jdbc;
    private final SqlDialectConfig dialect;

    public SqliteSchemaEvolver(JdbcTemplate jdbc, SqlDialectConfig dialect) {
        this.jdbc = jdbc;
        this.dialect = dialect;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (!dialect.isSqlite()) {
            return;
        }
        for (ColumnDef c : COLUMNS) {
            try {
                List<String> cols = jdbc.query(
                        "PRAGMA table_info(" + c.table() + ")",
                        (rs, i) -> rs.getString("name"));
                if (cols.isEmpty() || cols.contains(c.column())) {
                    continue;
                }
                jdbc.execute("ALTER TABLE " + c.table()
                        + " ADD COLUMN " + c.column() + " " + c.type());
                log.info("sqlite schema evolution: added {}.{}", c.table(), c.column());
            } catch (Exception e) {
                log.warn("sqlite schema evolution failed for {}.{}: {}",
                        c.table(), c.column(), e.getMessage());
            }
        }
        for (String ddl : INDEXES) {
            try {
                jdbc.execute(ddl);
            } catch (Exception e) {
                log.warn("sqlite schema evolution failed for index: {}", e.getMessage());
            }
        }
    }
}
