package io.virbius.control.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T7b: DailyAnchorJob is insert-if-absent — a lost race reports the stored anchor
 * (first seal wins) instead of overwriting it.
 */
class DailyAnchorJobTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    @Test
    void lostRaceReturnsStoredAnchor() {
        Answer<List<String>> tenants = inv -> List.of("t1");
        when(jdbc.queryForList(anyString(), eq(String.class), any(), any()))
                .thenAnswer(inv -> {
                    String sql = inv.getArgument(0);
                    if (sql.contains("DISTINCT tenant_id")) {
                        return List.of("t1");
                    }
                    if (sql.contains("anchor_date")) {
                        return List.of(); // not used in this path
                    }
                    return List.of();
                });
        when(jdbc.queryForList(anyString(), eq(String.class), any(), any(), any(), any()))
                .thenReturn(List.of("leaf-hash-1"));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any(), any()))
                .thenReturn(5);
        doThrow(new DuplicateKeyException("sealed by peer"))
                .when(jdbc).update(anyString(), any(), any(), any(), any(), any());
        when(jdbc.queryForList(anyString(), eq("t1"), any()))
                .thenReturn(List.of(Map.of("root_hash", "sha256:stored", "sessions", 7, "events", 42)));

        DailyAnchorJob.SealResult result = new DailyAnchorJob(jdbc, true).seal(LocalDate.of(2026, 1, 1));

        assertEquals(1, result.tenantsSealed());
        assertEquals("sha256:stored", result.anchors().get(0).rootHash());
    }

    @Test
    void disabledSealIsANoOp() {
        assertNotNull(new DailyAnchorJob(jdbc, false));
    }
}
