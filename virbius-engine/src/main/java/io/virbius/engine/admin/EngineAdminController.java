package io.virbius.engine.admin;

import io.virbius.engine.cache.CacheReloadSubscriber;
import io.virbius.engine.cache.PolicyDataCache;
import io.virbius.engine.cache.RuleCache;
import io.virbius.engine.cache.RuleCacheSeeder;
import io.virbius.engine.cache.RuleEntry;
import io.virbius.engine.eval.ScriptRuleRunner;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import redis.clients.jedis.JedisPool;

@RestController
@RequestMapping("/admin")
public class EngineAdminController {

    private final RuleCache cache;
    private final PolicyDataCache policyData;
    private final Optional<JedisPool> jedisPool;
    private final CacheReloadSubscriber cacheReloadSubscriber;
    private final RuleCacheSeeder ruleCacheSeeder;
    /**
     * Once readiness has been observed true, latch it: a transient Redis blip must
     * NOT flip every replica to NotReady at once (that would remove all engine pods
     * from the Service endpoints). Post-first-success degradation is served by the
     * per-path Redis fallbacks and surfaced via the {@code redis} field / metric.
     * Set {@code virbius.admin.ready-latch=false} to re-evaluate readiness on every
     * probe instead (a Redis outage then pulls the pods from the Service — intended
     * only when the caller-side posture must fail closed rather than degrade).
     */
    private volatile boolean everReady = false;
    private final boolean readyLatch;

    public EngineAdminController(
            RuleCache cache,
            PolicyDataCache policyData,
            Optional<JedisPool> jedisPool,
            CacheReloadSubscriber cacheReloadSubscriber,
            RuleCacheSeeder ruleCacheSeeder,
            @Value("${virbius.admin.ready-latch:true}") boolean readyLatch) {
        this.cache = cache;
        this.policyData = policyData;
        this.jedisPool = jedisPool;
        this.cacheReloadSubscriber = cacheReloadSubscriber;
        this.ruleCacheSeeder = ruleCacheSeeder;
        this.readyLatch = readyLatch;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    /**
     * Readiness gate for multi-replica deployments: a fresh replica must not
     * receive traffic before Redis is reachable and the cache-reload consumer
     * group is established (otherwise it serves with an unseeded rule cache).
     * Returns 503 with per-check status until ready. Liveness stays on
     * {@code /admin/health} — readiness failures must never restart the pod.
     */
    @GetMapping("/ready")
    public ResponseEntity<Map<String, Object>> ready() {
        boolean redisOk = pingRedis();
        boolean subscriberReady = cacheReloadSubscriber.isReady();
        boolean seeded = ruleCacheSeeder.isSeeded();
        if (redisOk && subscriberReady && seeded) {
            everReady = true;
        }
        boolean ok = readyLatch ? everReady : (redisOk && subscriberReady && seeded);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", ok ? "ready" : "not-ready");
        body.put("redis", redisOk ? "up" : "down");
        body.put("cache_subscriber", subscriberReady ? "ready" : "starting");
        body.put("rule_seeder", seeded ? "done" : "seeding");
        return ResponseEntity.status(ok ? 200 : 503).body(body);
    }

    private boolean pingRedis() {
        if (jedisPool.isEmpty()) {
            return false;
        }
        try (var jedis = jedisPool.get().getResource()) {
            jedis.ping();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @GetMapping("/policy-version")
    public Map<String, Object> policyVersion(@RequestParam(defaultValue = "default") String tenant_id) {
        return Map.of(
                "tenant_id", tenant_id,
                "policy_version", cache.policyVersion(tenant_id),
                "cache_generation", cache.cacheGeneration(),
                "loaded_at", cache.loadedAt().toString(),
                "rule_count", cache.rulesForTenant(tenant_id).size());
    }

    @PostMapping("/cache/reload")
    public Map<String, Object> reload(
            @RequestParam String tenant_id,
            @RequestParam(required = false) String bundle_version,
            @RequestParam(defaultValue = "full") String mode,
            @RequestBody(required = false) CacheReloadRequest body) {
        long start = System.currentTimeMillis();
        String version = bundle_version != null ? bundle_version : cache.policyVersion(tenant_id);
        List<RuleEntry> rules = body != null && body.rules() != null ? body.rules() : List.of();
        if (!rules.isEmpty()) {
            cache.replaceTenant(tenant_id, version, rules);
        }
        if (body != null && (body.lists() != null || body.redisListIndex() != null || body.cumulatives() != null || body.toolPolicies() != null)) {
            PolicyDataCache.TenantPolicyData data = ScriptRuleRunner.fromBlocks(
                    body.lists() != null ? body.lists() : List.of(),
                    body.redisListIndex() != null ? body.redisListIndex() : List.of(),
                    body.cumulatives() != null ? body.cumulatives() : List.of(),
                    body.toolPolicies() != null ? body.toolPolicies() : List.of());
            policyData.replace(tenant_id, data);
        }
        return Map.of(
                "ok", true,
                "cache_generation", cache.cacheGeneration(),
                "policy_version", cache.policyVersion(tenant_id),
                "rules_loaded", cache.rulesForTenant(tenant_id).size(),
                "duration_ms", System.currentTimeMillis() - start,
                "mode", mode,
                "tenant_id", tenant_id);
    }
}
