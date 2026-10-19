package io.virbius.control.service;

import io.virbius.control.config.ControlJedisPools;
import io.virbius.control.domain.AccessListEntry;
import io.virbius.control.domain.AccessListMeta;
import io.virbius.control.domain.AccessListMetaDimension;
import io.virbius.control.repository.ListMetaRepository;
import io.virbius.policy.ImageHasher;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import redis.clients.jedis.JedisPool;

/**
 * Image-list support for the access-list system: hashing on upload and the
 * engine-facing Redis materialization of {@code dimension=image} lists.
 *
 * <p>List entries for image lists are fingerprint strings
 * {@code <sha256hex>:<phashhex>} — the value column IS the sample identity
 * (same bytes always produce the same fingerprint, so the entry-table PK
 * dedups re-uploads exactly like keyword values). This helper:
 * <ul>
 *   <li>{@link #uploadImageEntry}: decodes/validates an uploaded sample file,
 *       computes both hashes and inserts the entry via the list repository;</li>
 *   <li>{@link #rebuildImageListKeys}: rebuilds the tenant-scoped engine keys
 *       {@code virbius:imgbl:{tenantId}:{listName}:exact} (SET of sha256) and
 *       {@code :phash} (HASH phash-hex → sha256) from all image lists, wiping
 *       stale {@code virbius:imgbl:*} keys (including legacy pre-list ones)
 *       first.</li>
 * </ul>
 * The engine refreshes its per-tenant/per-list snapshots every
 * {@code virbius.file.blacklist-refresh-seconds} (default 60s).
 */
@Service
public class ImageBlacklistAdminService {

    private static final Logger log = LoggerFactory.getLogger(ImageBlacklistAdminService.class);

    static final String KEY_PREFIX = "virbius:imgbl:";

    /** Per-run staging prefix; distinct from KEY_PREFIX so live SCANs never match it. */
    static final String STAGING_KEY_PREFIX = "virbius:imgbl-staging:";
    static final int STAGING_TTL_SECONDS = 120;

    /** Cross-replica single-flight lock for the rebuild (prevents staging/stale races). */
    static final String REBUILD_LOCK_KEY = "virbius:imgbl:rebuild-lock";
    static final long REBUILD_LOCK_TTL_MS = 120_000L;

    /** RENAME + PERSIST in one round-trip: a gap between them would leave the live
     *  key on the 120s staging TTL and silently expire the blacklist. */
    private static final String SWAP_SCRIPT =
            "redis.call('RENAME', KEYS[1], KEYS[2]); redis.call('PERSIST', KEYS[2]); return 1";

    /** Release the lock only if this run still owns it. */
    private static final String RELEASE_SCRIPT =
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end";

    private static final long MAX_UPLOAD_BYTES = 20L * 1024 * 1024;

    /** sha256(64) + ':' + phash-hex(16). */
    static String fingerprintValue(byte[] imageBytes) {
        Long phash = ImageHasher.phash(imageBytes);
        if (phash == null) {
            throw new IllegalArgumentException("not a decodable image (png/jpeg/gif/bmp)");
        }
        return ImageHasher.sha256Hex(imageBytes) + ":" + Long.toUnsignedString(phash, 16);
    }

    private final ListMetaRepository listMetaRepo;
    private final ControlJedisPools jedisPools;

    public ImageBlacklistAdminService(ListMetaRepository listMetaRepo, ControlJedisPools jedisPools) {
        this.listMetaRepo = listMetaRepo;
        this.jedisPools = jedisPools;
    }

    /**
     * Insert an uploaded sample as a fingerprint entry of an image list.
     * The original filename is auto-filled into the remark for provenance:
     * empty remark → filename, non-empty → {@code filename · remark}.
     */
    public Map<String, Object> uploadImageEntry(
            String tenantId, String listName, byte[] imageBytes, String originalFilename, String remark,
            Instant expiresAt) {
        AccessListMeta meta = listMetaRepo
                .getMeta(tenantId, listName)
                .orElseThrow(() -> new IllegalArgumentException("list not found: " + listName));
        if (!AccessListMetaDimension.isImage(meta.dimension())) {
            throw new IllegalArgumentException("list is not an image list: " + listName);
        }
        if (imageBytes == null || imageBytes.length == 0) {
            throw new IllegalArgumentException("empty file");
        }
        if (imageBytes.length > MAX_UPLOAD_BYTES) {
            throw new IllegalArgumentException("file exceeds 20MB limit");
        }
        String name = sanitizeFilename(originalFilename);
        String filledRemark = remark == null || remark.isBlank()
                ? name
                : (name != null ? name + " · " + remark.trim() : remark.trim());
        String value = fingerprintValue(imageBytes);
        boolean added = listMetaRepo.addEntry(tenantId, listName, value, filledRemark, expiresAt);
        Map<String, Object> out = new HashMap<>();
        out.put("added", added);
        out.put("sha256", value.substring(0, 64));
        out.put("phash", value.substring(65));
        out.put("remark", filledRemark);
        return out;
    }

    /** @return bare filename without any path components, or null when absent. */
    static String sanitizeFilename(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return null;
        }
        String name = originalFilename.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        name = slash >= 0 ? name.substring(slash + 1) : name;
        return name.isEmpty() ? null : name;
    }

    /**
     * Rebuild the engine-consumed imgbl keys from every tenant's image lists.
     *
     * <p>Multi-replica-safe rebuild: content is first built under a per-run
     * staging prefix ({@code virbius:imgbl-staging:{runId}:...}, TTL-bounded so
     * crashed runs self-clean), then swapped into the live keys with atomic
     * RENAME per key — no window where all live keys are empty (the old
     * wipe-then-rebuild briefly served empty lists, and two replicas made it
     * worse). Stale live keys (removed lists/samples, legacy pre-list keys) are
     * deleted only when they are not part of the freshly desired set, so a
     * concurrent replica's rename can never be undone.
     */
    public Map<String, Object> rebuildImageListKeys() {
        List<String[]> pushList = new ArrayList<>();  // {tenantId, listName}
        Map<String, List<AccessListEntry>> entriesByList = new HashMap<>();
        for (AccessListMeta meta : listMetaRepo.listAllMeta()) {
            if (!AccessListMetaDimension.isImage(meta.dimension())) {
                continue;
            }
            pushList.add(new String[] {meta.tenantId(), meta.listName()});
            entriesByList.put(meta.tenantId() + ":" + meta.listName(),
                    listMetaRepo.listEntries(meta.tenantId(), meta.listName()));
        }
        java.util.Optional<JedisPool> poolOpt = jedisPools.pool();
        if (poolOpt.isEmpty()) {
            int total = entriesByList.values().stream().mapToInt(List::size).sum();
            log.warn("redis unavailable; image lists not pushed ({} entries staged in DB)", total);
            return Map.of("pushed", false, "status", "redis-unavailable", "entries", total);
        }
        String runId = UUID.randomUUID().toString();
        String stagingPrefix = STAGING_KEY_PREFIX + runId.substring(0, 8) + ":";
        try (var jedis = poolOpt.get().getResource()) {
            // Cross-replica single-flight: without it, replica B could rename a
            // stale staging set over a live key that replica A just deleted/rebuilt,
            // resurrecting a removed list; or one run's `desired` set could delete
            // keys the other just renamed in.
            String acquire = jedis.set(REBUILD_LOCK_KEY, runId,
                    redis.clients.jedis.params.SetParams.setParams()
                            .nx().px(REBUILD_LOCK_TTL_MS));
            if (!"OK".equals(acquire)) {
                log.info("image list rebuild skipped: another rebuild holds the lock");
                return Map.of("pushed", false, "status", "locked", "skipped", "locked");
            }
            try {
                // 1. Build all new content under the run-private staging prefix.
                int entries = 0;
                Map<String, String> stagingToLive = new HashMap<>();  // stagingKey -> liveKey
                Instant now = Instant.now();
                for (String[] tl : pushList) {
                    String tenantId = tl[0];
                    String listName = tl[1];
                    String liveBase = KEY_PREFIX + tenantId + ":" + listName;
                    String stagingBase = stagingPrefix + tenantId + ":" + listName;
                    // Redis SET/HASH members have no per-member TTL, so expired samples are
                    // filtered here; the scheduled rebuild keeps the keys in sync afterwards.
                    Map<String, String> phashFields = new HashMap<>();
                    List<String> sha256s = new ArrayList<>();
                    for (AccessListEntry e : entriesByList.get(tenantId + ":" + listName)) {
                        if (!AccessListService.isActiveEntry(e, now)) {
                            continue;
                        }
                        String[] parts = splitFingerprint(e.value());
                        if (parts == null) {
                            log.warn("bad image fingerprint ignored: list={} value={}", listName, e.value());
                            continue;
                        }
                        sha256s.add(parts[0]);
                        phashFields.put(parts[1], parts[0]);
                        entries++;
                    }
                    if (!sha256s.isEmpty()) {
                        String stagingExact = stagingBase + ":exact";
                        jedis.sadd(stagingExact, sha256s.toArray(new String[0]));
                        // TTL at creation: a crash before the swap must not leave a
                        // never-expiring staging key behind.
                        jedis.expire(stagingExact, STAGING_TTL_SECONDS);
                        stagingToLive.put(stagingExact, liveBase + ":exact");
                    }
                    if (!phashFields.isEmpty()) {
                        String stagingPhash = stagingBase + ":phash";
                        jedis.hset(stagingPhash, phashFields);
                        jedis.expire(stagingPhash, STAGING_TTL_SECONDS);
                        stagingToLive.put(stagingPhash, liveBase + ":phash");
                    }
                }
                // 2. Swap staging into live atomically per key. The staging TTL set at
                //    creation self-cleans crashed runs; RENAME keeps that TTL, so the
                //    swap PERSISTs the live key in the same round-trip.
                // 3. Swap staging into live atomically per key.
                int swapFailures = 0;
                for (var swap : stagingToLive.entrySet()) {
                    try {
                        jedis.eval(SWAP_SCRIPT,
                                java.util.List.of(swap.getKey(), swap.getValue()),
                                java.util.List.of());
                    } catch (Exception e) {
                        swapFailures++;
                        log.warn("imgbl swap failed {} -> {}: {}",
                                swap.getKey(), swap.getValue(), e.getMessage());
                    }
                }
                // 4. Delete live keys that are no longer desired (removed lists,
                //    stale samples, legacy pre-list keys). With the rebuild lock held
                //    these are genuinely stale.
                Set<String> desired = new HashSet<>(stagingToLive.values());
                List<String> stale = new ArrayList<>();
                redis.clients.jedis.params.ScanParams params = new redis.clients.jedis.params.ScanParams()
                        .match(KEY_PREFIX + "*").count(500);
                String cursor = redis.clients.jedis.params.ScanParams.SCAN_POINTER_START;
                do {
                    redis.clients.jedis.resps.ScanResult<String> page = jedis.scan(cursor, params);
                    for (String key : page.getResult()) {
                        // Never treat the rebuild lock as a stale live key: it shares
                        // KEY_PREFIX, so a naive delete would drop the lock this run holds.
                        if (!desired.contains(key) && !REBUILD_LOCK_KEY.equals(key)) {
                            stale.add(key);
                        }
                    }
                    cursor = page.getCursor();
                } while (!"0".equals(cursor));
                if (!stale.isEmpty()) {
                    jedis.del(stale.toArray(new String[0]));
                }
                log.info("image lists pushed to redis: lists={}, entries={} (deleted {} stale keys, {} swap failures)",
                        pushList.size(), entries, stale.size(), swapFailures);
                Map<String, Object> result = new java.util.LinkedHashMap<>();
                result.put("pushed", swapFailures == 0);
                result.put("status", swapFailures == 0 ? "ok" : "partial");
                result.put("entries", entries);
                result.put("lists", pushList.size());
                if (swapFailures > 0) {
                    result.put("swap_failures", swapFailures);
                }
                return result;
            } finally {
                try {
                    jedis.eval(RELEASE_SCRIPT, java.util.List.of(REBUILD_LOCK_KEY), java.util.List.of(runId));
                } catch (Exception e) {
                    log.debug("imgbl rebuild lock release failed: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("image list redis push failed: {}", e.getMessage());
            return Map.of("pushed", false, "status", "error", "error", String.valueOf(e.getMessage()));
        }
    }

    /** @return {sha256, phashHex} or null when the value is not a fingerprint. */
    static String[] splitFingerprint(String value) {
        if (value == null || value.length() != 81 || value.charAt(64) != ':') {
            return null;
        }
        return new String[] {value.substring(0, 64), value.substring(65)};
    }
}
