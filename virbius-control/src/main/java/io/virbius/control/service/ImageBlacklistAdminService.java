package io.virbius.control.service;

import io.virbius.control.config.ControlJedisPools;
import io.virbius.control.domain.AccessListEntry;
import io.virbius.control.domain.AccessListMeta;
import io.virbius.control.domain.AccessListMetaDimension;
import io.virbius.control.repository.ListMetaRepository;
import io.virbius.policy.ImageHasher;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
            String tenantId, String listName, byte[] imageBytes, String originalFilename, String remark) {
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
        boolean added = listMetaRepo.addEntry(tenantId, listName, value, filledRemark, null);
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

    /** Rebuild the engine-consumed imgbl keys from every tenant's image lists. */
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
            return Map.of("pushed", false, "entries", total);
        }
        try (var jedis = poolOpt.get().getResource()) {
            // wipe previous keys: stale lists, removed samples, legacy global keys
            List<String> stale = new ArrayList<>();
            redis.clients.jedis.params.ScanParams params = new redis.clients.jedis.params.ScanParams()
                    .match(KEY_PREFIX + "*").count(500);
            String cursor = redis.clients.jedis.params.ScanParams.SCAN_POINTER_START;
            do {
                redis.clients.jedis.resps.ScanResult<String> page = jedis.scan(cursor, params);
                stale.addAll(page.getResult());
                cursor = page.getCursor();
            } while (!"0".equals(cursor));
            if (!stale.isEmpty()) {
                jedis.del(stale.toArray(new String[0]));
            }
            int entries = 0;
            for (String[] tl : pushList) {
                String tenantId = tl[0];
                String listName = tl[1];
                Map<String, String> phashFields = new HashMap<>();
                for (AccessListEntry e : entriesByList.get(tenantId + ":" + listName)) {
                    String[] parts = splitFingerprint(e.value());
                    if (parts == null) {
                        log.warn("bad image fingerprint ignored: list={} value={}", listName, e.value());
                        continue;
                    }
                    jedis.sadd(KEY_PREFIX + tenantId + ":" + listName + ":exact", parts[0]);
                    phashFields.put(parts[1], parts[0]);
                    entries++;
                }
                if (!phashFields.isEmpty()) {
                    jedis.hset(KEY_PREFIX + tenantId + ":" + listName + ":phash", phashFields);
                }
            }
            log.info("image lists pushed to redis: lists={}, entries={} (wiped {} stale keys)",
                    pushList.size(), entries, stale.size());
            return Map.of("pushed", true, "entries", entries, "lists", pushList.size());
        } catch (Exception e) {
            log.warn("image list redis push failed: {}", e.getMessage());
            return Map.of("pushed", false, "error", String.valueOf(e.getMessage()));
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
