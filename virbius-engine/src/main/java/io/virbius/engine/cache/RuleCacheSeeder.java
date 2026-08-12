package io.virbius.engine.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.engine.eval.ScriptRuleRunner;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import redis.clients.jedis.JedisPool;

@Component
public class RuleCacheSeeder {

    private static final Logger log = LoggerFactory.getLogger(RuleCacheSeeder.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SNAPSHOT_KEY_PREFIX = "virbius:engine";

    private final RuleCache cache;
    private final PolicyDataCache policyData;
    private final JedisPool jedisPool;
    private final List<String> configuredTenants;

    public RuleCacheSeeder(
            RuleCache cache,
            PolicyDataCache policyData,
            JedisPool jedisPool,
            @Value("${virbius.tenants:}") List<String> configuredTenants) {
        this.cache = cache;
        this.policyData = policyData;
        this.jedisPool = jedisPool;
        this.configuredTenants = configuredTenants != null
                ? configuredTenants.stream().filter(t -> t != null && !t.isBlank()).toList()
                : List.of();
    }

    @PostConstruct
    public void load() {
        Set<String> tenants = discoverTenants();
        int seeded = 0;
        for (String tenant : tenants) {
            if (seedTenant(tenant)) {
                seeded++;
            }
        }
        log.info("seed complete: {}/{} tenants seeded", seeded, tenants.size());
    }

    private boolean seedTenant(String tenantId) {
        // Avoid overwriting rules already loaded by the stream subscriber
        if (!cache.rulesForTenant(tenantId).isEmpty()) {
            log.debug("tenant={} already seeded by subscriber, skipping", tenantId);
            return false;
        }
        String snapshotKey = SNAPSHOT_KEY_PREFIX + ":" + tenantId + ":snapshot";
        try (var jedis = jedisPool.getResource()) {
            String body = jedis.get(snapshotKey);
            if (body == null || body.isBlank()) {
                log.debug("snapshot key not found for tenant={}", tenantId);
                return false;
            }
            Map<String, Object> snapshot = JSON.readValue(body, new TypeReference<>() {});
            String version = snapshot.getOrDefault("policy_version", "bootstrap").toString();
            List<Map<String, Object>> rawRules = JSON.convertValue(snapshot.get("rules"), new TypeReference<>() {});
            List<RuleEntry> rules = rawRules.stream().map(RuleEntry::fromMap).toList();
            cache.replaceTenant(tenantId, version, rules);

            List<PolicyDataCache.ListBlock> rawLists = JSON.convertValue(
                    snapshot.get("lists"), new TypeReference<>() {});
            List<PolicyDataCache.RedisListIndexBlock> rawRedisIndex = JSON.convertValue(
                    snapshot.get("redis_list_index"), new TypeReference<>() {});
            List<PolicyDataCache.CumulativeBlock> rawCumulatives = JSON.convertValue(
                    snapshot.get("cumulatives"), new TypeReference<>() {});
            List<PolicyDataCache.ToolPolicyEntry> rawToolPolicies = JSON.convertValue(
                    snapshot.get("tool_policies"), new TypeReference<>() {});
            PolicyDataCache.TenantPolicyData data = ScriptRuleRunner.fromBlocks(
                    rawLists != null ? rawLists : List.of(),
                    rawRedisIndex != null ? rawRedisIndex : List.of(),
                    rawCumulatives != null ? rawCumulatives : List.of(),
                    rawToolPolicies != null ? rawToolPolicies : List.of());
            policyData.replace(tenantId, data);
            log.info("seeded tenant={} version={} rules={}", tenantId, version, rules.size());
            return true;
        } catch (Exception e) {
            log.warn("failed to seed tenant={}: {}", tenantId, e.getMessage());
            return false;
        }
    }

    private Set<String> discoverTenants() {
        Set<String> tenants = new HashSet<>();
        if (!configuredTenants.isEmpty()) {
            tenants.addAll(configuredTenants);
        }
        try (var jedis = jedisPool.getResource()) {
            String cursor = "0";
            do {
                var result = jedis.scan(cursor,
                        new redis.clients.jedis.params.ScanParams()
                                .match(SNAPSHOT_KEY_PREFIX + ":*:snapshot")
                                .count(100));
                cursor = result.getCursor();
                for (String key : result.getResult()) {
                    String tenantId = key.substring(SNAPSHOT_KEY_PREFIX.length() + 1,
                            key.length() - ":snapshot".length());
                    tenants.add(tenantId);
                }
            } while (!cursor.equals("0"));
        } catch (Exception e) {
            log.warn("tenant discovery via SCAN failed: {}", e.getMessage());
        }
        if (tenants.isEmpty()) {
            log.info("no tenants discovered; engine will be seeded on first publish");
        }
        return tenants;
    }
}
