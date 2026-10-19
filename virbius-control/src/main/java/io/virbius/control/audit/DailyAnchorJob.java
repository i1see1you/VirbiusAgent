package io.virbius.control.audit;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
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
 *
 * <p>{@link #seal(LocalDate)} returns a {@link SealResult} describing what was
 * actually sealed (counts + per-tenant roots + failures) so the admin API can
 * report the outcome instead of echoing the requested date unconditionally.
 *
 * <p>Multi-replica: two replicas sealing the same tenant-day converge — the
 * first writer wins and the loser reports the stored anchor instead of failing.
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

    /** One sealed tenant-day. */
    public record SealedAnchor(String tenantId, String rootHash, int sessions, int events) {}

    /** Outcome of a seal run for one day. */
    public record SealResult(LocalDate date, List<SealedAnchor> anchors, int failed) {
        public boolean sealed() {
            return !anchors.isEmpty();
        }

        public int tenantsSealed() {
            return anchors.size();
        }

        public int sessions() {
            return anchors.stream().mapToInt(SealedAnchor::sessions).sum();
        }

        public int events() {
            return anchors.stream().mapToInt(SealedAnchor::events).sum();
        }
    }

    /** Seal yesterday (UTC) every day at 02:30 UTC. */
    @Scheduled(cron = "0 30 2 * * *", zone = "UTC")
    public void sealYesterday() {
        if (!enabled) {
            return;
        }
        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        try {
            SealResult result = seal(yesterday);
            if (result.failed() > 0) {
                log.error("daily anchor {}: {} tenant(s) failed to seal", yesterday, result.failed());
            }
        } catch (Exception e) {
            log.error("daily anchor failed for {}: {}", yesterday, e.getMessage());
        }
    }

    /**
     * Seal one day (UTC). Returns what was sealed: one entry per tenant that had
     * chained trace rows that day (empty when no tenant had anything to seal),
     * plus the number of tenants that errored.
     */
    public SealResult seal(LocalDate day) {
        Instant from = day.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        List<String> tenants = jdbc.queryForList(
                "SELECT DISTINCT tenant_id FROM tb_agent_trace WHERE occurred_at >= ? AND occurred_at < ?",
                String.class, from.toString(), to.toString());
        List<SealedAnchor> anchors = new ArrayList<>();
        int failed = 0;
        for (String tenantId : tenants) {
            try {
                SealedAnchor anchor = sealTenantDay(tenantId, day, from, to);
                if (anchor != null) {
                    anchors.add(anchor);
                }
            } catch (Exception e) {
                failed++;
                log.error("daily anchor failed: tenant={} day={}: {}", tenantId, day, e.getMessage());
            }
        }
        log.info("daily anchor {}: {} tenant(s) sealed, {} failed", day, anchors.size(), failed);
        return new SealResult(day, anchors, failed);
    }

    private SealedAnchor sealTenantDay(String tenantId, LocalDate day, Instant from, Instant to) {
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
        int eventCount = events == null ? 0 : events;
        // Sealed tenant-days are immutable (insert-if-absent): a DELETE+INSERT pair
        // would let a slower replica overwrite an already-committed seal (and any
        // external timestamping of it). Re-sealing a sealed day is idempotent —
        // the stored anchor is returned instead.
        try {
            jdbc.update(
                    "INSERT INTO tb_audit_daily_anchor (tenant_id, anchor_date, root_hash, sessions, events) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    tenantId, day.toString(), root, leaves.size(), eventCount);
            log.info("daily anchor sealed: tenant={} day={} sessions={} events={} root={}",
                    tenantId, day, leaves.size(), eventCount, root);
            return new SealedAnchor(tenantId, root, leaves.size(), eventCount);
        } catch (DuplicateKeyException raced) {
            // Another replica sealed this tenant-day first — the stored anchor wins.
            Map<String, Object> existing = get(tenantId, day);
            if (existing != null) {
                log.info("daily anchor race lost: tenant={} day={} — keeping stored root", tenantId, day);
                return new SealedAnchor(
                        tenantId,
                        String.valueOf(existing.get("root_hash")),
                        ((Number) existing.get("sessions")).intValue(),
                        ((Number) existing.get("events")).intValue());
            }
            throw raced;
        }
    }

    /** All seals on record for a tenant, newest day first. */
    public List<Map<String, Object>> list(String tenantId) {
        return jdbc.queryForList(
                "SELECT anchor_date, root_hash, sessions, events, created_at "
                        + "FROM tb_audit_daily_anchor WHERE tenant_id = ? ORDER BY anchor_date DESC",
                tenantId);
    }

    /** One tenant-day seal, or null when the day was never sealed. */
    public Map<String, Object> get(String tenantId, LocalDate day) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT anchor_date, root_hash, sessions, events, created_at "
                        + "FROM tb_audit_daily_anchor WHERE tenant_id = ? AND anchor_date = ?",
                tenantId, day.toString());
        return rows.isEmpty() ? null : rows.get(0);
    }
}
