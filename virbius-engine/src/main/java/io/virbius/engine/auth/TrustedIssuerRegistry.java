package io.virbius.engine.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import redis.clients.jedis.JedisPool;

/**
 * Trusted evaluate-token issuers (P0-min: control is the single issuer).
 *
 * <p>Two sources, per the N1 decision:
 * <ul>
 *   <li><b>Bootstrap anchor</b> — control's platform public PEM baked into the
 *       engine config ({@code virbius.evaluate.auth.bootstrap-pem-file}); this
 *       MUST succeed at startup, otherwise the engine fails fast (a deployment
 *       error, not a runtime outage).</li>
 *   <li><b>Redis enrichment</b> — {@code virbius:engine:issuers} (kid → PEM)
 *       published by control; covers key rotation. Loaded at startup with a
 *       bounded retry and refreshed periodically; failure here degrades to
 *       WARN, never blocks the boot (the anchor already guarantees
 *       verifiability of control-signed tokens).</li>
 * </ul>
 */
@Component
public class TrustedIssuerRegistry {

    private static final Logger log = LoggerFactory.getLogger(TrustedIssuerRegistry.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public record IssuedTokenClaims(
            String kid,
            String issuer,
            String subject,
            String audience,
            String tenantId,
            String appId,
            String agentAid,
            String sid,
            String jti,
            boolean crossTenant,
            List<String> allowedTools,
            Integer riskQuota,
            Integer toolRateLimit,
            long issuedAt,
            long expiresAt) {}

    private final JedisPool jedisPool;
    private final String redisKey;
    private final long bootRetrySeconds;

    /** kid → verification key (volatile: replaced wholesale on refresh). */
    private volatile ConcurrentHashMap<String, PublicKey> keys = new ConcurrentHashMap<>();

    /** Signature-verify cache: token hash → (claims, untilEpochMs). Revocation is
     *  NOT cached here — the RevocationCache is consulted on every request (B1). */
    private final ConcurrentHashMap<String, CachedVerification> verifyCache = new ConcurrentHashMap<>();
    private final long verifyCacheTtlSeconds;

    private record CachedVerification(IssuedTokenClaims claims, long untilEpochMs) {}

    public TrustedIssuerRegistry(
            JedisPool jedisPool,
            @Value("${virbius.evaluate.auth.issuer-redis-key:virbius:engine:issuers}") String redisKey,
            @Value("${virbius.evaluate.auth.boot-retry-seconds:60}") long bootRetrySeconds,
            @Value("${virbius.evaluate.auth.verify-cache-ttl-seconds:900}") long verifyCacheTtlSeconds) {
        this.jedisPool = jedisPool;
        this.redisKey = redisKey;
        this.bootRetrySeconds = bootRetrySeconds;
        this.verifyCacheTtlSeconds = verifyCacheTtlSeconds;
    }

    /** Boot: bounded-retry Redis enrichment. The caller (EvaluateAuthFilter init)
     *  handles the bootstrap anchor; this method only enriches. */
    public void loadFromRedisWithRetry() {
        long deadline = System.currentTimeMillis() + bootRetrySeconds * 1000;
        while (true) {
            try {
                refreshFromRedis();
                return;
            } catch (Exception e) {
                if (System.currentTimeMillis() >= deadline) {
                    log.warn("issuer Redis enrichment failed after {}s retry (running with keys loaded so far): {}",
                            bootRetrySeconds, e.getMessage());
                    return;
                }
                log.info("issuer Redis load failed, retrying: {}", e.getMessage());
                sleep(1000);
            }
        }
    }

    /** Periodic refresh (rotation propagation + reconciliation). */
    @Scheduled(fixedDelayString = "PT30S")
    public void scheduledRefresh() {
        try {
            refreshFromRedis();
        } catch (Exception e) {
            log.warn("scheduled issuer refresh failed: {}", e.getMessage());
        }
    }

    private void refreshFromRedis() {
        Map<String, String> hash;
        try (var jedis = jedisPool.getResource()) {
            hash = jedis.hgetAll(redisKey);
        }
        if (hash == null || hash.isEmpty()) {
            log.debug("issuer Redis hash empty (key={}); keeping existing keys", redisKey);
            return;
        }
        ConcurrentHashMap<String, PublicKey> next = new ConcurrentHashMap<>();
        for (Map.Entry<String, String> e : hash.entrySet()) {
            PublicKey key = parsePem(e.getValue());
            if (key != null) {
                next.put(e.getKey(), key);
            } else {
                log.warn("ignoring unparsable issuer PEM for kid={}", e.getKey());
            }
        }
        if (!next.isEmpty()) {
            this.keys = next;
            log.debug("issuer registry refreshed: {} key(s)", next.size());
        }
    }

    /** Register the config-baked bootstrap anchor (called once at boot; the
     *  anchor is additive — Redis keys are merged on top). */
    public void registerBootstrapKey(String kid, String pem) {
        PublicKey key = parsePem(pem);
        if (key == null) {
            throw new IllegalStateException("bootstrap issuer PEM unparsable (kid=" + kid + ")");
        }
        keys.put(kid, key);
        log.info("bootstrap issuer anchor registered (kid={})", kid);
    }

    public boolean hasAnyKey() {
        return !keys.isEmpty();
    }

    public int keyCount() {
        return keys.size();
    }

    /**
     * Verify a compact EdDSA token and return its claims. Uses the signature
     * cache for repeated tokens (streaming windows); revocation is NOT part of
     * this check (B1) — the caller must consult the RevocationCache.
     *
     * @return empty when the signature/audience/expiry check fails.
     */
    public Optional<IssuedTokenClaims> verify(String token, String expectedAudience) {
        long nowMs = System.currentTimeMillis();
        String cacheKey = Integer.toHexString(token.hashCode()) + ":" + token.length();
        CachedVerification cached = verifyCache.get(cacheKey);
        if (cached != null && cached.untilEpochMs() > nowMs) {
            return Optional.of(cached.claims());
        }
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                log.debug("token rejected: {} segments", parts.length);
                return Optional.empty();
            }
            String kid = extractKid(parts[0]);
            if (kid == null) {
                log.debug("token rejected: no kid in header");
                return Optional.empty();
            }
            PublicKey key = keys.get(kid);
            if (key == null) {
                log.debug("token rejected: unknown kid={} (known={})", kid, keys.keySet());
                return Optional.empty();
            }
            Signature sig = Signature.getInstance("Ed25519");
            sig.initVerify(key);
            sig.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
            if (!sig.verify(Base64.getUrlDecoder().decode(parts[2]))) {
                log.debug("token rejected: bad signature (kid={})", kid);
                return Optional.empty();
            }
            Map<String, Object> payload = JSON.readValue(
                    Base64.getUrlDecoder().decode(parts[1]), Map.class);
            IssuedTokenClaims claims = toClaims(payload, kid);
            if (!expectedAudience.equals(claims.audience())) {
                log.debug("token rejected: aud={} expected={}", claims.audience(), expectedAudience);
                return Optional.empty();
            }
            long nowSec = nowMs / 1000;
            if (claims.expiresAt() <= nowSec || claims.issuedAt() > nowSec + 60) {
                log.debug("token rejected: iat={} exp={} now={}", claims.issuedAt(), claims.expiresAt(), nowSec);
                return Optional.empty();
            }
            long cacheTtl = Math.min(verifyCacheTtlSeconds, claims.expiresAt() - nowSec);
            if (cacheTtl > 0) {
                verifyCache.put(cacheKey,
                        new CachedVerification(claims, nowMs + cacheTtl * 1000));
            }
            return Optional.of(claims);
        } catch (Exception e) {
            log.debug("token verification failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public void clearCachesForTesting() {
        verifyCache.clear();
    }

    @SuppressWarnings("unchecked")
    private static IssuedTokenClaims toClaims(Map<String, Object> payload, String kid) {
        Map<String, Object> scope =
                (Map<String, Object>) payload.getOrDefault("scope", new HashMap<String, Object>());
        return new IssuedTokenClaims(
                kid,
                str(payload.get("iss")),
                str(payload.get("sub")),
                str(payload.get("aud")),
                nullable(payload.get("tenant_id")),
                nullable(payload.get("app_id")),
                nullable(payload.get("agent_aid")),
                nullable(payload.get("sid")),
                nullable(payload.get("jti")),
                Boolean.TRUE.equals(scope.get("cross_tenant")),
                (List<String>) scope.get("allowed_tools"),
                scope.get("risk_quota") instanceof Number n ? n.intValue() : null,
                scope.get("tool_rate_limit") instanceof Number n ? n.intValue() : null,
                payload.get("iat") instanceof Number n ? n.longValue() : 0,
                payload.get("exp") instanceof Number n ? n.longValue() : 0);
    }

    private static String extractKid(String headerB64) {
        try {
            Map<String, Object> header = JSON.readValue(
                    Base64.getUrlDecoder().decode(headerB64), Map.class);
            Object kid = header.get("kid");
            return kid == null ? null : kid.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static PublicKey parsePem(String pem) {
        try {
            String body = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(body);
            return KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            return null;
        }
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static String nullable(Object o) {
        return o == null ? null : o.toString();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
