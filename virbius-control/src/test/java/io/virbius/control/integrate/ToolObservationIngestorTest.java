package io.virbius.control.integrate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.virbius.control.config.SqlDialectConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class ToolObservationIngestorTest {

    private SqlDialectConfig sqliteDialect() {
        return new SqlDialectConfig(mock(DataSource.class));
    }

    private Map<String, Object> event(String id, String phase) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("event_id", id);
        e.put("call_id", "c-" + id);
        e.put("phase", phase);
        e.put("tenant_id", "PAYLOAD_TENANT");
        e.put("app_id", "PAYLOAD_APP");
        e.put("device_id", "pod-1");
        e.put("session_id", "thread-1");
        e.put("trace_id", "lc-1");
        e.put("tool_name", "mail__send");
        e.put("observed_at", "2026-10-11T08:00:00Z");
        return e;
    }

    @Test
    void observationInsertUsesSqliteIgnorePrefix() {
        ToolObservationIngestor ingestor = new ToolObservationIngestor(mock(JdbcTemplate.class), sqliteDialect());
        assertTrue(ingestor.observationInsertSql().startsWith("INSERT OR IGNORE INTO tb_tool_observation"));
    }

    @Test
    void ingestOverridesTenantAndAppFromPath() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.batchUpdate(anyString(), anyList())).thenReturn(new int[] {1});
        ToolObservationIngestor ingestor = new ToolObservationIngestor(jdbc, sqliteDialect());
        int accepted = ingestor.ingestEvents("acme", "crm-agent", List.of(event("ev1", "started")));
        assertEquals(1, accepted);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Object[]>> batchCaptor = ArgumentCaptor.forClass(List.class);
        verify(jdbc).batchUpdate(anyString(), batchCaptor.capture());
        Object[] row = batchCaptor.getValue().get(0);
        assertEquals("ev1", row[0]);
        assertEquals("started", row[2]);
        assertEquals("acme", row[3]);
        assertEquals("crm-agent", row[4]);
        assertEquals("pod-1", row[5]);
    }

    @Test
    void ingestSkipsInvalidPhaseWithoutTouchingDatabase() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ToolObservationIngestor ingestor = new ToolObservationIngestor(jdbc, sqliteDialect());
        int accepted = ingestor.ingestEvents("acme", "crm-agent", List.of(event("ev1", "boom")));
        assertEquals(0, accepted);
        verify(jdbc, never()).batchUpdate(anyString(), anyList());
    }

    @Test
    void inventoryUpsertAcceptsRiskHintVocabulary() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ToolObservationIngestor ingestor = new ToolObservationIngestor(jdbc, sqliteDialect());
        int accepted = ingestor.upsertInventory("acme", "crm", List.of(Map.of(
                "tool_name", "db_delete",
                "risk_hint", "high",
                "matched_rules", List.of("verb:delete"),
                "recommended_mode", "B",
                "bound_mode", "A")));
        assertEquals(1, accepted);
    }

    @Test
    void isolationGuardNoAuditOrChainTables() {
        ToolObservationIngestor ingestor = new ToolObservationIngestor(mock(JdbcTemplate.class), sqliteDialect());
        List<String> statements = List.of(
                ingestor.observationInsertSql(),
                ingestor.livenessUpsertSql(),
                ingestor.livenessEventUpsertSql(),
                ingestor.inventoryUpsertSql(),
                new ObservationRetentionJob(mock(JdbcTemplate.class), 14).retentionDeleteSql());
        for (String sql : statements) {
            String lower = sql.toLowerCase();
            assertFalse(lower.contains("tb_audit"), sql);
            assertFalse(lower.contains("chain"), sql);
            assertFalse(lower.contains("hash"), sql);
        }
    }

    @Test
    void isolationGuardTablesAreOnlyIntegrateTables() {
        ToolObservationIngestor ingestor = new ToolObservationIngestor(mock(JdbcTemplate.class), sqliteDialect());
        assertTrue(ingestor.observationInsertSql().contains("tb_tool_observation"));
        assertTrue(ingestor.livenessUpsertSql().contains("tb_obs_app_liveness"));
        assertTrue(ingestor.inventoryUpsertSql().contains("tb_tool_inventory"));
    }
}
