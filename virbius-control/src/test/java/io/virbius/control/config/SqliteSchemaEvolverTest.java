package io.virbius.control.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * Dev SQLite schema evolution: a column added to db/schema.sql must reach an
 * EXISTING dev database file (CREATE TABLE IF NOT EXISTS cannot), without
 * wiping data and without failing when the column is already there.
 */
class SqliteSchemaEvolverTest {

    private SingleConnectionDataSource ds;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws Exception {
        Class.forName("org.sqlite.JDBC");
        Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        ds = new SingleConnectionDataSource(conn, true);
        jdbc = new JdbcTemplate(ds);
        // Pre-V20 shape: no session_id column.
        jdbc.execute("CREATE TABLE tb_audit_events ("
                + "event_id VARCHAR(128), tenant_id VARCHAR(64), trace_id VARCHAR(128),"
                + "intercepted_at TIMESTAMP)");
    }

    @AfterEach
    void tearDown() {
        ds.destroy();
    }

    @Test
    void addsMissingColumnAndKeepsExistingRows() {
        jdbc.update("INSERT INTO tb_audit_events VALUES ('e1','t1','tr1','2026-10-20T00:00:00Z')");
        evolve();

        List<String> cols = columns();
        assertTrue(cols.contains("session_id"), "session_id must be added");
        // Existing rows survive the ALTER (SQLite ADD COLUMN keeps data).
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM tb_audit_events WHERE event_id = 'e1'", Integer.class));
        // New column is nullable: old rows read back NULL, not a default lie.
        assertEquals("e1", jdbc.queryForObject(
                "SELECT event_id FROM tb_audit_events WHERE session_id IS NULL", String.class));
        // The session-scoped index follows the column (schema.sql cannot host
        // it: spring.sql.init runs before this evolver).
        assertEquals(1, indexCount());
    }

    @Test
    void secondRunIsANoOp() {
        evolve();
        evolve();

        assertEquals(1, columns().stream().filter("session_id"::equals).count());
        assertEquals(1, indexCount());
    }

    @Test
    void missingTableIsSkippedQuietly() {
        jdbc.execute("DROP TABLE tb_audit_events");

        evolve();

        assertEquals(0, columns().size());
    }

    private void evolve() {
        new SqliteSchemaEvolver(jdbc, new SqlDialectConfig(ds)).afterSingletonsInstantiated();
    }

    private List<String> columns() {
        return jdbc.query("PRAGMA table_info(tb_audit_events)", (rs, i) -> rs.getString("name"));
    }

    private int indexCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index'"
                        + " AND name = 'idx_audit_events_tenant_session'", Integer.class);
    }
}
