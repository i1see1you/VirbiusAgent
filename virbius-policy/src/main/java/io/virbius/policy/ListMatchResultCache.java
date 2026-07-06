package io.virbius.policy;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** TTL cache for redis list match results (tenant + list + lookup value). */
public final class ListMatchResultCache {

    private final long ttlMillis;
    private final int maxEntries;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public ListMatchResultCache(long ttlMillis, int maxEntries) {
        this.ttlMillis = Math.max(1_000L, ttlMillis);
        this.maxEntries = Math.max(1_000, maxEntries);
    }

    public Boolean getIfFresh(String key, long nowMillis) {
        Entry e = cache.get(key);
        if (e == null) {
            return null;
        }
        if (nowMillis - e.cachedAtMillis > ttlMillis) {
            cache.remove(key, e);
            return null;
        }
        if (e.hit && e.score > 0 && e.score <= nowMillis / 1000) {
            cache.remove(key, e);
            return null;
        }
        return e.hit;
    }

    public void put(String key, boolean hit, double score, long nowMillis) {
        if (cache.size() >= maxEntries) {
            cache.clear();
        }
        cache.put(key, new Entry(hit, score, nowMillis));
    }

    public static String cacheKey(String tenantId, String listName, String lookupValue) {
        return tenantId + ":" + listName + ":" + lookupValue;
    }

    private record Entry(boolean hit, double score, long cachedAtMillis) {}
}
