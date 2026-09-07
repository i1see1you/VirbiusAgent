package io.virbius.engine.eval;

import io.virbius.engine.config.FileProperties;
import io.virbius.policy.ImageHasher;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import redis.clients.jedis.JedisPool;

/**
 * Known-malicious-image sample lists, one per {@code dimension=image} access
 * list: exact SHA-256 fast path plus pHash Hamming-distance evidence
 * (tolerates re-encode/resize evasion).
 *
 * <p>Entries are written by the control plane as fingerprint values
 * {@code <sha256hex>:<phashhex>} and materialized into tenant/list-scoped
 * Redis keys:
 * <pre>
 *   virbius:imgbl:{tenantId}:{listName}:exact  SET  sha256-hex
 *   virbius:imgbl:{tenantId}:{listName}:phash  HASH phash-hex -&gt; sha256-hex
 * </pre>
 * The engine materializes full in-process snapshots per tenant/list (a few KB
 * each) and refreshes them periodically. Redis being unavailable only means a
 * stale snapshot — matching never blocks and never requires a network call per
 * request.
 *
 * <p>This service produces MATCH EVIDENCE only (layer, distance, sha); all
 * policy (thresholds, actions) lives in ordinary {@code groovy} rule scripts
 * that call {@code imageBlacklist(listName)} — same split as keyword lists,
 * where {@code listMatch} yields the boolean and the rule decides.
 */
@Component
public class ImageBlacklistService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ImageBlacklistService.class);

    static final String KEY_PREFIX = "virbius:imgbl:";

    /** Evidence: layer=exact (byte-identical) or phash, distance 0..64, sha = sample identity. */
    public record BlacklistHit(String layer, int distance, String sha) {}

    private record ListSnapshot(Set<String> exactSha, Map<Long, String> phashToSha) {}

    private final FileProperties props;
    private final JedisPool jedisPool;
    private final ScheduledExecutorService scheduler;
    private volatile Map<String, Map<String, ListSnapshot>> byTenantAndList = Map.of();

    @org.springframework.beans.factory.annotation.Autowired
    public ImageBlacklistService(FileProperties props, ObjectProvider<JedisPool> jedisPool) {
        this(props, jedisPool.getIfAvailable());
    }

    ImageBlacklistService(FileProperties props, JedisPool jedisPool) {
        this.props = props;
        this.jedisPool = jedisPool;
        if (jedisPool != null && props.imageBlacklistEnabled() && props.blacklistRefreshSeconds() > 0) {
            this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "img-blacklist-refresher");
                t.setDaemon(true);
                return t;
            });
            this.scheduler.scheduleWithFixedDelay(this::reload, 0,
                    props.blacklistRefreshSeconds(), TimeUnit.SECONDS);
        } else {
            this.scheduler = null;
        }
    }

    /**
     * Match an image against one list's snapshot. Pure in-memory (hash
     * computation aside); never throws; returns evidence with no policy
     * attached — callers apply their own distance windows.
     */
    public Optional<BlacklistHit> match(String tenantId, String listName, byte[] imageBytes) {
        if (!props.imageBlacklistEnabled() || tenantId == null || listName == null
                || imageBytes == null || imageBytes.length == 0) {
            return Optional.empty();
        }
        ListSnapshot s = byTenantAndList
                .getOrDefault(tenantId, Map.of())
                .get(listName);
        if (s == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(matchSnapshot(s, imageBytes));
    }

    /**
     * Best evidence per configured list for one image — the per-request lookup
     * used by groovy rules via {@code imageBlacklist(listName)}. Hashes are
     * computed once and reused across all lists.
     */
    public Map<String, BlacklistHit> matchAll(String tenantId, byte[] imageBytes) {
        Map<String, ListSnapshot> lists = byTenantAndList.getOrDefault(tenantId, Map.of());
        if (!props.imageBlacklistEnabled() || lists.isEmpty()
                || imageBytes == null || imageBytes.length == 0) {
            return Map.of();
        }
        String sha = ImageHasher.sha256Hex(imageBytes);
        Long phash = ImageHasher.phash(imageBytes);
        Map<String, BlacklistHit> out = new HashMap<>();
        for (Map.Entry<String, ListSnapshot> e : lists.entrySet()) {
            BlacklistHit hit = matchSnapshot(e.getValue(), sha, phash);
            if (hit != null) {
                out.put(e.getKey(), hit);
            }
        }
        return out;
    }

    /** Ranking for merging evidence across images: exact beats phash, then nearer. */
    static BlacklistHit better(BlacklistHit a, BlacklistHit b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        boolean aExact = "exact".equals(a.layer());
        boolean bExact = "exact".equals(b.layer());
        if (aExact != bExact) {
            return aExact ? a : b;
        }
        return a.distance() <= b.distance() ? a : b;
    }

    private BlacklistHit matchSnapshot(ListSnapshot s, byte[] imageBytes) {
        return matchSnapshot(s, ImageHasher.sha256Hex(imageBytes), ImageHasher.phash(imageBytes));
    }

    private static BlacklistHit matchSnapshot(ListSnapshot s, String sha, Long phash) {
        if (s.exactSha().contains(sha)) {
            return new BlacklistHit("exact", 0, sha);
        }
        if (phash == null || s.phashToSha().isEmpty()) {
            return null;
        }
        String bestSha = null;
        int bestDist = Integer.MAX_VALUE;
        for (Map.Entry<Long, String> e : s.phashToSha().entrySet()) {
            int d = ImageHasher.hamming(phash, e.getKey());
            if (d < bestDist) {
                bestDist = d;
                bestSha = e.getValue();
            }
        }
        return bestSha == null ? null : new BlacklistHit("phash", bestDist, bestSha);
    }

    void reload() {
        if (jedisPool == null) {
            return;
        }
        try (var jedis = jedisPool.getResource()) {
            Map<String, Set<String>> exactBy = new HashMap<>();
            Map<String, Map<String, String>> phashBy = new HashMap<>();
            scanKeys(jedis, KEY_PREFIX + "*:exact", k -> exactBy.put(listKey(k), jedis.smembers(k)));
            scanKeys(jedis, KEY_PREFIX + "*:phash", k -> phashBy.put(listKey(k), jedis.hgetAll(k)));

            Set<String> listKeys = new HashSet<>();
            listKeys.addAll(exactBy.keySet());
            listKeys.addAll(phashBy.keySet());
            Map<String, Map<String, ListSnapshot>> next = new HashMap<>();
            int exactTotal = 0;
            int phashTotal = 0;
            for (String lk : listKeys) {
                int sep = lk.indexOf(':');
                String tenant = sep < 0 ? lk : lk.substring(0, sep);
                String list = sep < 0 ? "" : lk.substring(sep + 1);
                Set<String> shas = exactBy.getOrDefault(lk, Set.of());
                Map<Long, String> phashToSha = new HashMap<>();
                for (Map.Entry<String, String> e : phashBy.getOrDefault(lk, Map.of()).entrySet()) {
                    try {
                        phashToSha.put(Long.parseUnsignedLong(e.getKey(), 16), e.getValue());
                    } catch (NumberFormatException ex) {
                        log.warn("bad phash entry ignored: {}", e.getKey());
                    }
                }
                exactTotal += shas.size();
                phashTotal += phashToSha.size();
                next.computeIfAbsent(tenant, t -> new HashMap<>())
                        .put(list, new ListSnapshot(Set.copyOf(shas), Map.copyOf(phashToSha)));
            }
            byTenantAndList = Map.copyOf(next);
            log.info("image blacklist snapshot loaded: lists={}, exact={}, phash={}",
                    listKeys.size(), exactTotal, phashTotal);
        } catch (Exception e) {
            // keep serving the previous snapshot
            log.warn("image blacklist reload failed, keeping stale snapshot: {}", e.getMessage());
        }
    }

    /** @return "tenant:list" for a key like virbius:imgbl:{tenant}:{list}:exact. */
    private static String listKey(String key) {
        int start = KEY_PREFIX.length();
        int end = key.lastIndexOf(':');
        return end > start ? key.substring(start, end) : "";
    }

    private static void scanKeys(redis.clients.jedis.Jedis jedis, String pattern,
                                 java.util.function.Consumer<String> action) {
        redis.clients.jedis.params.ScanParams params = new redis.clients.jedis.params.ScanParams()
                .match(pattern).count(500);
        String cursor = redis.clients.jedis.params.ScanParams.SCAN_POINTER_START;
        do {
            redis.clients.jedis.resps.ScanResult<String> page = jedis.scan(cursor, params);
            for (String key : page.getResult()) {
                action.accept(key);
            }
            cursor = page.getCursor();
        } while (!"0".equals(cursor));
    }

    /** Test hook: install/replace one list's snapshot without Redis (merges per tenant/list). */
    void installSnapshotForTesting(String tenantId, String listName,
                                   Set<String> exactSha, Map<Long, String> phashToSha) {
        Map<String, Map<String, ListSnapshot>> next = new HashMap<>(byTenantAndList);
        next.compute(tenantId, (t, lists) -> {
            Map<String, ListSnapshot> merged = new HashMap<>(lists != null ? lists : Map.of());
            merged.put(listName, new ListSnapshot(Set.copyOf(exactSha), Map.copyOf(phashToSha)));
            return Map.copyOf(merged);
        });
        this.byTenantAndList = Map.copyOf(next);
    }

    @Override
    public void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}
