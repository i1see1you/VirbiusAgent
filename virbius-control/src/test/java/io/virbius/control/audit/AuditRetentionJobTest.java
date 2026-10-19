package io.virbius.control.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/** T7a: AuditRetentionJob tolerates a concurrent run's DuplicateKeyException. */
class AuditRetentionJobTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    @Test
    void concurrentDuplicateDoesNotAbortTheRun() {
        when(jdbc.queryForList(anyString())).thenReturn(List.of(Map.of(
                "tenant_id", "t1", "table_name", "agent_trace", "retention_days", 30, "mode", "archive")));
        doThrow(new DuplicateKeyException("raced"))
                .when(jdbc).update(anyString(), any(), any(), any());

        Map<String, Object> summary = new AuditRetentionJob(jdbc, true).archiveAll();

        assertEquals(0, summary.get("archived_trace_rows"));
    }

    /** V6: the audit-events branch has its own catch (2-arg insert). */
    @Test
    void auditBranchConcurrentDuplicateDoesNotAbort() {
        when(jdbc.queryForList(anyString())).thenReturn(List.of(Map.of(
                "tenant_id", "t1", "table_name", "audit_events", "retention_days", 30, "mode", "archive")));
        doThrow(new DuplicateKeyException("raced"))
                .when(jdbc).update(anyString(), any(), any());

        Map<String, Object> summary = new AuditRetentionJob(jdbc, true).archiveAll();

        assertEquals(0, summary.get("archived_audit_events"));
    }

    @Test
    void disabledJobIsANoOp() {
        new AuditRetentionJob(jdbc, false).run();
    }
}
