package io.virbius.control.audit;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * A3: seals each past UTC day under a per-tenant Merkle root over the day's
 * session trace-chain heads, stored in {@code tb_audit_daily_anchor}.
 *
 * <p>The anchor is the precondition for retention archival: after a day is
 * sealed, its rows may be archived (copied out) and later manually deleted by
 * an ops account without losing the ability to prove the retained period's
 * integrity. Leaves are each session's highest chained row of that day
 * ({@code curr_hash}), ordered by {@code session_id} for determinism.
 */
@Component
public class DailyAnchorJob {

    private static final Logger log = LoggerFactory.getLogger(DailyAnchorJob.class);

    private final JdbcTemplate jdbc;
    private final boolean enabled;

    public DailyAnchorJob(
            JdbcTemplate jdbc,
            @Value("${virbius.audit.daily-anchor.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.enabled = enabled;
    }

    /** Seal yesterday (UTC) every day at 02:30 UTC. */
    @Scheduled(cron = "0 30 2 * * *", zone = "UTC")
    public void sealYesterday() {
        if (!enabled) {
            return;
        }
        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        try {
            seal(yesterday);
        } catch (Exception e) {
            log.error("daily anchor failed for {}: {}", yesterday, e.getMessage());
        }
    }

    /** Seal one day (UTC) and return the root hash, or null if no chained rows. */
    public String seal(LocalDate day) {
        Instant from = day.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        List<String> tenants = jdbc.queryForList(
                "SELECT DISTINCT tenant_id FROM tb_agent_trace WHERE occurred_at >= ? AND occurred_at < ?",
                String.class, from.toString(), to.toString());
        int sealed = 0;
        for (String tenantId : tenants) {
            try {
                if (sealTenantDay(tenantId, day, from, to) != null) {
                    sealed++;
                }
            } catch (Exception e) {
                log.error("daily anchor failed: tenant={} day={}: {}", tenantId, day, e.getMessage());
            }
        }
        log.info("daily anchor {}: {} tenant(s) sealed", day, sealed);
        return null;
    }

    private String sealTenantDay(String tenantId, LocalDate day, Instant from, Instant to) {
        // Each session's highest chained row of the day, ordered by session_id.
        List<String> leaves = jdbc.queryForList(
                """
                SELECT t.curr_hash
                FROM tb_agent_trace t
                JOIN (SELECT session_id, MAX(chain_seq) AS max_seq
                      FROM tb_agent_trace
                      WHERE tenant_id = ? AND occurred_at >= ? AND occurred_at < ? AND chain_seq > 0
                      GROUP BY session_id) m
                  ON t.tenant_id = ? AND t.session_id = m.session_id AND t.chain_seq = m.max_seq
                ORDER BY t.session_id
                """,
                String.class, tenantId, from.toString(), to.toString(), tenantId);
        Integer events = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tb_agent_trace WHERE tenant_id = ? AND occurred_at >= ? AND occurred_at < ?",
                Integer.class, tenantId, from.toString(), to.toString());
        if (leaves == null || leaves.isEmpty()) {
            return null;
        }
        String root = MerkleUtil.root(MerkleUtil.buildLevels(leaves));
        jdbc.update("DELETE FROM tb_audit_daily_anchor WHERE tenant_id = ? AND anchor_date = ?",
                tenantId, day.toString());
        jdbc.update(
                "INSERT INTO tb_audit_daily_anchor (tenant_id, anchor_date, root_hash, sessions, events) "
                        + "VALUES (?, ?, ?, ?, ?)",
                tenantId, day.toString(), root, leaves.size(), events == null ? 0 : events);
        log.info("daily anchor sealed: tenant={} day={} sessions={} events={} root={}",
                tenantId, day, leaves.size(), events, root);
        return root;
    }
}
