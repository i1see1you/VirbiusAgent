package io.virbius.policy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/** Publishes gateway list entries to Redis ZSET (score = expire_at). */
public class GatewayListRedisPublisher {

    private static final Logger log = LoggerFactory.getLogger(GatewayListRedisPublisher.class);

    private final JedisPool pool;
    private final String keyPrefix;

    public GatewayListRedisPublisher(JedisPool pool, String keyPrefix) {
        this.pool = pool;
        this.keyPrefix = keyPrefix == null || keyPrefix.isBlank() ? GatewayListRedisKeys.DEFAULT_PREFIX : keyPrefix;
    }

    public void publishList(String tenantId, String listName, List<Entry> entries) {
        String live = GatewayListRedisKeys.listKey(keyPrefix, tenantId, listName);
        String staging = GatewayListRedisKeys.stagingKey(live);
        long now = Instant.now().getEpochSecond();
        try (Jedis jedis = pool.getResource()) {
            jedis.del(staging);
            if (entries != null) {
                for (Entry e : entries) {
                    if (e.value() == null || e.value().isBlank()) {
                        continue;
                    }
                    double score = ListRedisScores.toScore(e.expiresAt());
                    if (score > 0 && score <= now) {
                        continue;
                    }
                    jedis.zadd(staging, score, e.value());
                }
            }
            if (jedis.exists(staging)) {
                jedis.rename(staging, live);
            } else {
                jedis.del(staging);
                jedis.del(live);
            }
        } catch (Exception ex) {
            log.warn("redis list publish failed tenant={} list={}: {}", tenantId, listName, ex.getMessage());
            throw ex;
        }
    }

    public void addEntry(String tenantId, String listName, String value, Instant expiresAt) {
        String key = GatewayListRedisKeys.listKey(keyPrefix, tenantId, listName);
        long now = Instant.now().getEpochSecond();
        double score = ListRedisScores.toScore(expiresAt);
        if (score > 0 && score <= now) {
            try (Jedis jedis = pool.getResource()) {
                jedis.zrem(key, value);
            }
            return;
        }
        try (Jedis jedis = pool.getResource()) {
            jedis.zadd(key, score, value);
        }
    }

    public void removeEntry(String tenantId, String listName, String value) {
        String key = GatewayListRedisKeys.listKey(keyPrefix, tenantId, listName);
        try (Jedis jedis = pool.getResource()) {
            jedis.zrem(key, value);
        }
    }

    /** Remove expired members (score in [1, now]); retains score=0 permanent entries. */
    public long sweepExpired(String tenantId, String listName) {
        String key = GatewayListRedisKeys.listKey(keyPrefix, tenantId, listName);
        long now = Instant.now().getEpochSecond();
        if (now <= 1) {
            return 0;
        }
        try (Jedis jedis = pool.getResource()) {
            return jedis.zremrangeByScore(key, 1, now);
        }
    }

    public record Entry(String value, Instant expiresAt) {}
}
