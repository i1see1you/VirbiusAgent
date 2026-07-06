package io.virbius.policy;

import java.util.Optional;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/** ZSCORE-based list membership with optional match-result TTL cache. */
public class GatewayListRedisMatcher {

    private final JedisPool pool;
    private final ListMatchResultCache cache;

    public GatewayListRedisMatcher(JedisPool pool, ListMatchResultCache cache) {
        this.pool = pool;
        this.cache = cache;
    }

    public boolean matches(String tenantId, String listName, String redisKey, String lookupValue) {
        if (lookupValue == null || lookupValue.isBlank()) {
            return false;
        }
        long nowMs = System.currentTimeMillis();
        long nowSec = nowMs / 1000;
        String cacheKey = ListMatchResultCache.cacheKey(tenantId, listName, lookupValue);
        Boolean cached = cache.getIfFresh(cacheKey, nowMs);
        if (cached != null) {
            return cached;
        }
        Double score;
        try (Jedis jedis = pool.getResource()) {
            score = jedis.zscore(redisKey, lookupValue);
        } catch (Exception ex) {
            return false;
        }
        if (score == null) {
            cache.put(cacheKey, false, 0, nowMs);
            return false;
        }
        boolean hit = ListRedisScores.isActive(score, nowSec);
        cache.put(cacheKey, hit, score, nowMs);
        return hit;
    }

    public static Optional<GatewayListRedisMatcher> create(
            Optional<JedisPool> pool, long cacheTtlSec, int cacheMaxEntries) {
        return pool.map(p -> new GatewayListRedisMatcher(p, new ListMatchResultCache(cacheTtlSec * 1000L, cacheMaxEntries)));
    }
}
