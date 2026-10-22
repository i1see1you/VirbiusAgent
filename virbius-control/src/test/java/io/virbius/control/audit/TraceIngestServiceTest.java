package io.virbius.control.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Producer-side signal on the ingest status: last_event_at / total_events. */
class TraceIngestServiceTest {

    private SingleConnectionDataSource ds;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws Exception {
        Class.forName("org.sqlite.JDBC");
        Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        ds = new SingleConnectionDataSource(conn, true);
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE tb_agent_trace ("
                + "tenant_id TEXT, occurred_at TEXT)");
    }

    @AfterEach
    void tearDown() {
        ds.destroy();
    }

    @Test
    void statusReportsLastEventAndTotalPerTenant() {
        jdbc.update("INSERT INTO tb_agent_trace VALUES (?,?)", "t1", "2026-10-20T00:00:01Z");
        jdbc.update("INSERT INTO tb_agent_trace VALUES (?,?)", "t1", "2026-10-20T00:00:05Z");
        jdbc.update("INSERT INTO tb_agent_trace VALUES (?,?)", "t2", "2026-10-20T00:00:09Z");
        TraceIngestService service = new TraceIngestService(true, "virbius-trace-events", jdbc);

        Map<String, Object> out = service.status("t1");

        assertEquals(true, out.get("enabled"));
        assertEquals(2L, out.get("total_events"));
        assertEquals("2026-10-20T00:00:05Z", out.get("last_event_at"));
    }

    @Test
    void statusWithNoEventsSignalsNullInsteadOfZeroDate() {
        TraceIngestService service = new TraceIngestService(true, "virbius-trace-events", jdbc);

        Map<String, Object> out = service.status("t1");

        assertEquals(0L, out.get("total_events"));
        assertNull(out.get("last_event_at"));
    }
}
