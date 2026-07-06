package io.virbius.policy;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/** Redis HASH minute/10-minute buckets for cumulative counters. */
public class CounterStore {

    private final JedisPool pool;

    public CounterStore(JedisPool pool) {
        this.pool = pool;
    }

    public static String redisKey(String tenantId, String cumulativeName, String value) {
        return "virbius:cum:"
                + tenantId
                + ":"
                + cumulativeName
                + ":"
                + ValueResolver.encodeRedisKeySegment(value);
    }

    public void ingest(
            String tenantId,
            String cumulativeName,
            String value,
            int wMinutes,
            String windowKind,
            ZoneId zone,
            long delta) {
        int g = CumulativeWindow.granularityMinutes(wMinutes, windowKind);
        long slot = CumulativeWindow.currentSlot(Instant.now(), g);
        String key = redisKey(tenantId, cumulativeName, value);
        int ttl = CumulativeWindow.ttlSeconds(wMinutes);
        try (Jedis jedis = pool.getResource()) {
            jedis.hincrBy(key, Long.toString(slot), delta);
            jedis.expire(key, ttl);
        }
    }

    public long read(
            String tenantId,
            String cumulativeName,
            String value,
            int wMinutes,
            String windowKind,
            ZoneId zone) {
        int g = CumulativeWindow.granularityMinutes(wMinutes, windowKind);
        long endSlot = CumulativeWindow.currentSlot(Instant.now(), g);
        long startSlot;
        int buckets = CumulativeWindow.bucketCount(wMinutes, g);
        if ("calendar_day".equalsIgnoreCase(windowKind)) {
            startSlot = CumulativeWindow.startSlotCalendarDay(Instant.now(), zone, g);
        } else {
            startSlot = endSlot - buckets + 1;
        }
        String key = redisKey(tenantId, cumulativeName, value);
        if (startSlot == endSlot) {
            try (Jedis jedis = pool.getResource()) {
                String v = jedis.hget(key, Long.toString(endSlot));
                return v == null ? 0L : Long.parseLong(v);
            }
        }
        List<String> fields = new ArrayList<>();
        for (long s = startSlot; s <= endSlot; s++) {
            fields.add(Long.toString(s));
        }
        long sum = 0;
        try (Jedis jedis = pool.getResource()) {
            List<String> vals = jedis.hmget(key, fields.toArray(new String[0]));
            for (String v : vals) {
                if (v != null && !v.isEmpty()) {
                    sum += Long.parseLong(v);
                }
            }
        }
        return sum;
    }

    public boolean exceeded(long count, int threshold, String compareOp) {
        return RuleConditionEvaluator.evaluateCompare(count, compareOp, threshold);
    }

    public boolean evaluate(RuleCondition condition, long count) {
        return RuleConditionEvaluator.evaluate(count, condition);
    }

    public static Optional<JedisPool> createPool(String redisUrl) {
        if (redisUrl == null || redisUrl.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new JedisPool(redisUrl));
    }
}
