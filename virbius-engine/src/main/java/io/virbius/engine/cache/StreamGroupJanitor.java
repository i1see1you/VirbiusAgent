package io.virbius.engine.cache;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.XPendingParams;
import redis.clients.jedis.resps.StreamConsumerInfo;
import redis.clients.jedis.resps.StreamConsumersInfo;
import redis.clients.jedis.resps.StreamGroupInfo;
import redis.clients.jedis.resps.StreamPendingEntry;

/**
 * Consumer-group hygiene for the engine's per-instance Redis streams.</p>
 *
 * <p>Engine replicas are Deployments, so every rollout/recreate uses a new
 * per-instance group ({@code engine-cache-*}/{@code engine-deploy-*}); a pod killed
 * without a graceful shutdown never destroys its group, so groups and their PEL
 * metadata accumulate forever. {@link #reapStaleGroups} runs once at startup and
 * destroys groups that share the prefix but have had no active consumer for a long
 * time (callers pass 30 min — a live replica reads with a &le;5s blocking read, so a
 * 30-min-idle group is a dead pod with high confidence; a replica merely paused or
 * partitioned for &gt;30 min still loses its group, which the startup snapshot
 * seed / direct pointer read recovers). A group with no consumers at all is left
 * alone (it may be a fresh replica that has not completed its first read).</p>
 *
 * <p>{@link #drainPending} acks a group's pending entries so the group being removed
 * is left clean; it does NOT re-process them. Any notification missed while a replica
 * is down is covered by the startup rule seeder / {@code NodePoolResolver} Redis
 * read, not by this drain.</p>
 */
public final class StreamGroupJanitor {

    private static final Logger log = LoggerFactory.getLogger(StreamGroupJanitor.class);

    private StreamGroupJanitor() {
    }

    /** Destroy prefix-matching groups whose consumers have all been idle &ge; staleIdleMs. */
    public static void reapStaleGroups(Jedis jedis, String streamKey, String groupPrefix,
                                       String keepGroup, long staleIdleMs) {
        try {
            List<StreamGroupInfo> groups = jedis.xinfoGroups(streamKey);
            if (groups == null) {
                return;
            }
            for (StreamGroupInfo group : groups) {
                String name = group.getName();
                if (name == null || !name.startsWith(groupPrefix) || name.equals(keepGroup)) {
                    continue;
                }
                List<StreamConsumersInfo> consumers = jedis.xinfoConsumers(streamKey, name);
                if (consumers == null || consumers.isEmpty()) {
                    continue; // never-read fresh group: not ours to judge
                }
                long minIdle = Long.MAX_VALUE;
                for (StreamConsumerInfo c : consumers) {
                    minIdle = Math.min(minIdle, c.getIdle());
                }
                if (minIdle >= staleIdleMs) {
                    jedis.xgroupDestroy(streamKey, name);
                    log.info("reaped stale consumer group {} on {}", name, streamKey);
                }
            }
        } catch (Exception e) {
            log.debug("stream group reap skipped on {}: {}", streamKey, e.getMessage());
        }
    }

    /** Best-effort ack of this group's pending entries (work already attempted). */
    public static void drainPending(Jedis jedis, String streamKey, String group) {
        try {
            List<StreamPendingEntry> pending = jedis.xpending(streamKey, group,
                    XPendingParams.xPendingParams().count(1000));
            if (pending == null || pending.isEmpty()) {
                return;
            }
            var ids = pending.stream()
                    .map(StreamPendingEntry::getID)
                    .toArray(redis.clients.jedis.StreamEntryID[]::new);
            jedis.xack(streamKey, group, ids);
        } catch (Exception e) {
            log.debug("pending drain skipped for {} on {}: {}", group, streamKey, e.getMessage());
        }
    }
}
