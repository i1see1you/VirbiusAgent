package io.virbius.engine.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.virbius.engine.config.FileProperties;
import io.virbius.policy.ImageHasher;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * The service produces match EVIDENCE only (layer/distance/sha); the distance
 * window → policy mapping lives in runtime=image-blacklist rule rows and is
 * covered by FileGuardServiceTest.
 */
class ImageBlacklistServiceTest {

    private static final String TENANT = "default";
    private static final String LIST = "phishing";

    private static FileProperties props() {
        return new FileProperties(true, 20L * 1024 * 1024, 5, 50, 8000,
                3500, 400, 32, 10, 64,
                "ovisocr2", "http://127.0.0.1:11434", 30000,
                0.5, 45_000, 1000, 60, 3, 12_000,
                true, 60);
    }

    private static byte[] pngBytes() {
        try {
            BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = img.createGraphics();
            g.setColor(java.awt.Color.WHITE);
            g.fillOval(10, 10, 60, 60);
            g.dispose();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static ImageBlacklistService serviceWith(Set<String> exact, Map<Long, String> phash) {
        ImageBlacklistService svc = new ImageBlacklistService(props(),
                (redis.clients.jedis.JedisPool) null);
        svc.installSnapshotForTesting(TENANT, LIST, exact, phash);
        return svc;
    }

    @Test
    void exactShaHit() {
        byte[] img = pngBytes();
        String sha = ImageHasher.sha256Hex(img);
        ImageBlacklistService svc = serviceWith(Set.of(sha), Map.of());
        Optional<ImageBlacklistService.BlacklistHit> hit = svc.match(TENANT, LIST, img);
        assertTrue(hit.isPresent());
        assertEquals("exact", hit.get().layer());
        assertEquals(0, hit.get().distance());
        assertEquals(sha, hit.get().sha());
    }

    @Test
    void exactBeatsNearbyPhash() {
        byte[] img = pngBytes();
        // same image also near in phash space: the exact fast path must win
        ImageBlacklistService svc = serviceWith(
                Set.of(ImageHasher.sha256Hex(img)),
                Map.of(ImageHasher.phash(img) ^ 0x1, "sample-near"));
        Optional<ImageBlacklistService.BlacklistHit> hit = svc.match(TENANT, LIST, img);
        assertTrue(hit.isPresent());
        assertEquals("exact", hit.get().layer());
        assertEquals(ImageHasher.sha256Hex(img), hit.get().sha());
    }

    @Test
    void phashEvidenceCarriesDistanceForRuleWindows() {
        byte[] img = pngBytes();
        long h = ImageHasher.phash(img);
        // 12 bits away: evidence reports distance; whether 12 is deny/review/miss
        // is decided by each rule's window, not here
        ImageBlacklistService svc = serviceWith(Set.of(), Map.of(h ^ 0xFFF, "sample-1"));
        Optional<ImageBlacklistService.BlacklistHit> hit = svc.match(TENANT, LIST, img);
        assertTrue(hit.isPresent());
        assertEquals("phash", hit.get().layer());
        assertEquals("sample-1", hit.get().sha());
        assertEquals(12, hit.get().distance());
    }

    @Test
    void nearestOfSeveralSamplesWins() {
        byte[] img = pngBytes();
        long h = ImageHasher.phash(img);
        ImageBlacklistService svc = serviceWith(Set.of(),
                Map.of(h ^ 0xFFFF, "far-sample", h ^ 0x7, "near-sample"));
        Optional<ImageBlacklistService.BlacklistHit> hit = svc.match(TENANT, LIST, img);
        assertTrue(hit.isPresent());
        assertEquals("near-sample", hit.get().sha());
        assertEquals(3, hit.get().distance());
    }

    @Test
    void undecodableImageYieldsNoHit() {
        ImageBlacklistService svc = serviceWith(Set.of(), Map.of(-1L, "far"));
        // sha256 misses, phash is null for non-image bytes → no evidence, no throw
        assertTrue(svc.match(TENANT, LIST, "not an image".getBytes()).isEmpty());
    }

    @Test
    void matchAllReportsBestHitPerConfiguredList() {
        byte[] img = pngBytes();
        ImageBlacklistService svc = new ImageBlacklistService(props(),
                (redis.clients.jedis.JedisPool) null);
        svc.installSnapshotForTesting(TENANT, "list-a",
                Set.of(ImageHasher.sha256Hex(img)), Map.of());
        // second list with only a phash sample 5 bits away
        svc.installSnapshotForTesting(TENANT, "list-b", Set.of(),
                Map.of(ImageHasher.phash(img) ^ 0x1F, "sample-b"));
        Map<String, ImageBlacklistService.BlacklistHit> all = svc.matchAll(TENANT, img);
        assertEquals(2, all.size());
        assertEquals("exact", all.get("list-a").layer());
        assertEquals("sample-b", all.get("list-b").sha());
        assertEquals(5, all.get("list-b").distance());
    }

    @Test
    void matchAllOnUnknownTenantIsEmpty() {
        ImageBlacklistService svc = new ImageBlacklistService(props(),
                (redis.clients.jedis.JedisPool) null);
        assertTrue(svc.matchAll("nobody", pngBytes()).isEmpty());
    }

    @Test
    void betterPrefersExactThenSmallerDistance() {
        ImageBlacklistService.BlacklistHit exact = new ImageBlacklistService.BlacklistHit("exact", 0, "s1");
        ImageBlacklistService.BlacklistHit near = new ImageBlacklistService.BlacklistHit("phash", 3, "s2");
        ImageBlacklistService.BlacklistHit far = new ImageBlacklistService.BlacklistHit("phash", 12, "s3");
        assertEquals(exact, ImageBlacklistService.better(exact, near));
        assertEquals(exact, ImageBlacklistService.better(near, exact));
        assertEquals(near, ImageBlacklistService.better(near, far));
        assertEquals(far, ImageBlacklistService.better(far, null));
        assertEquals(near, ImageBlacklistService.better(null, near));
    }

    @Test
    void disabledYieldsNoMatch() {
        FileProperties p = new FileProperties(true, 20L * 1024 * 1024, 5, 50, 8000,
                3500, 400, 32, 10, 64,
                "ovisocr2", "http://127.0.0.1:11434", 30000,
                0.5, 45_000, 1000, 60, 3, 12_000,
                false, 60);
        ImageBlacklistService svc = new ImageBlacklistService(p,
                (redis.clients.jedis.JedisPool) null);
        svc.installSnapshotForTesting(TENANT, LIST,
                Set.of(ImageHasher.sha256Hex(pngBytes())), Map.of());
        assertTrue(svc.match(TENANT, LIST, pngBytes()).isEmpty());
    }

    @Test
    void samplesAreTenantAndListScoped() {
        byte[] img = pngBytes();
        ImageBlacklistService svc = new ImageBlacklistService(props(),
                (redis.clients.jedis.JedisPool) null);
        svc.installSnapshotForTesting("tenant-a", LIST,
                Set.of(ImageHasher.sha256Hex(img)), Map.of());
        assertTrue(svc.match("tenant-a", LIST, img).isPresent());
        // other tenant, or same tenant under a different list name → no hit
        assertTrue(svc.match("tenant-b", LIST, img).isEmpty());
        assertTrue(svc.match("tenant-a", "other-list", img).isEmpty());
    }
}
