package io.virbius.control.audit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.virbius.control.config.ControlJedisPools;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T11: the MySQL CAS retry is bounded; on success the event carries its chain
 * fields, on exhaustion (C1) the fields are stripped so the ingestor cannot persist
 * a seq the chain never advanced to.
 */
class HashChainOrchestratorRetryTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    private HashChainOrchestrator orchestrator() {
        return new HashChainOrchestrator(new ControlJedisPools(""), jdbc, true);
    }

    private Map<String, Object> event() {
        Map<String, Object> e = new HashMap<>();
        e.put("tenant_id", "t1");
        e.put("event_id", "e1");
        e.put("trace_id", "tr1");
        return e;
    }

    @Test
    void successAfterRetriesKeepsChainFields() {
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("seq", 3L, "last_hash", "sha256:aa", "version", 2)));
        when(jdbc.update(anyString(), any(), any(), any(), any(), any())).thenReturn(0, 0, 1);
        Map<String, Object> e = event();

        orchestrator().chainBatch("t1", List.of(e));

        assertTrue(e.containsKey("audit_seq"));
    }

    @Test
    void exhaustedRetriesStripChainFields() {
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("seq", 3L, "last_hash", "sha256:aa", "version", 2)));
        when(jdbc.update(anyString(), any(), any(), any(), any(), any())).thenReturn(0, 0, 0);
        Map<String, Object> e = event();

        orchestrator().chainBatch("t1", List.of(e));

        assertFalse(e.containsKey("audit_seq"));
        assertFalse(e.containsKey("prev_hash"));
        assertFalse(e.containsKey("curr_hash"));
    }
}
