package io.virbius.engine.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.engine.eval.ScriptRuleRunner;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.params.XReadGroupParams;
import redis.clients.jedis.resps.StreamEntry;

@Component
public class CacheReloadSubscriber {

    private static final Logger log = LoggerFactory.getLogger(CacheReloadSubscriber.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String STREAM_KEY = "virbius:engine:cache-reload";
    private static final String CONSUMER_GROUP = "engine-cache";
    private static final String CONSUMER_NAME = "engine-" + UUID.randomUUID().toString().substring(0, 8);

    private final RuleCache cache;
    private final PolicyDataCache policyData;
    private final Optional<JedisPool> jedisPool;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cache-reload-subscriber");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean running = true;

    public CacheReloadSubscriber(RuleCache cache, PolicyDataCache policyData, Optional<JedisPool> jedisPool) {
        this.cache = cache;
        this.policyData = policyData;
        this.jedisPool = jedisPool;
    }

    @PostConstruct
    public void start() {
        if (jedisPool.isEmpty()) {
            log.warn("Redis not available — cache-reload subscriber disabled");
            return;
        }
        executor.submit(this::consume);
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        executor.shutdownNow();
    }

    private void consume() {
        JedisPool pool = jedisPool.get();
        while (running) {
            try {
                ensureGroup(pool);
                try (var jedis = pool.getResource()) {
                    List<Map.Entry<String, List<StreamEntry>>> results = jedis.xreadGroup(
                            CONSUMER_GROUP, CONSUMER_NAME,
                            XReadGroupParams.xReadGroupParams().count(1).block(5000),
                            Map.of(STREAM_KEY, new StreamEntryID(">")));
                    List<StreamEntry> entries = results != null && !results.isEmpty()
                            ? results.get(0).getValue()
                            : Collections.emptyList();
                    for (StreamEntry entry : entries) {
                        handle(entry);
                        jedis.xack(STREAM_KEY, CONSUMER_GROUP, entry.getID());
                    }
                }
            } catch (redis.clients.jedis.exceptions.JedisConnectionException e) {
                if (running) {
                    log.warn("redis connection lost, retrying in 3s...");
                    sleep(3000);
                }
            } catch (Exception e) {
                log.warn("cache reload subscriber error: {}", e.getMessage());
                sleep(1000);
            }
        }
    }

    private void ensureGroup(JedisPool pool) {
        try (var jedis = pool.getResource()) {
            jedis.xgroupCreate(STREAM_KEY, CONSUMER_GROUP, StreamEntryID.LAST_ENTRY, true);
        } catch (Exception ignored) {
        }
    }

    private void handle(StreamEntry entry) {
        try {
            Map<String, String> fields = entry.getFields();
            String tenantId = fields.get("tenant_id");
            String policyVersion = fields.get("policy_version");
            String payloadJson = fields.get("payload");
            if (tenantId == null || payloadJson == null) {
                return;
            }
            Map<String, Object> payload = JSON.readValue(payloadJson, new TypeReference<>() {});
            String action = payload.get("_action") instanceof String s ? s : null;

            if ("upsert".equals(action)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> ruleMap = (Map<String, Object>) payload.get("rule");
                if (ruleMap != null) {
                    cache.upsert(tenantId, RuleEntry.fromMap(ruleMap));
                    log.info("cache upsert from stream: tenant={} rule={}", tenantId, ruleMap.get("rule_id"));
                }
            } else if ("remove".equals(action)) {
                String removeRuleId = (String) payload.get("remove_rule_id");
                if (removeRuleId != null) {
                    cache.remove(tenantId, removeRuleId);
                    log.info("cache remove from stream: tenant={} rule={}", tenantId, removeRuleId);
                }
            } else {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> rawRules = (List<Map<String, Object>>) payload.get("rules");
                if (rawRules != null) {
                    List<RuleEntry> rules = rawRules.stream().map(RuleEntry::fromMap).toList();
                    cache.replaceAll(policyVersion, rules);
                }
                List<PolicyDataCache.ListBlock> rawLists = JSON.convertValue(
                        payload.get("lists"), new TypeReference<>() {});
                List<PolicyDataCache.RedisListIndexBlock> rawRedisIndex = JSON.convertValue(
                        payload.get("redis_list_index"), new TypeReference<>() {});
                List<PolicyDataCache.CumulativeBlock> rawCumulatives = JSON.convertValue(
                        payload.get("cumulatives"), new TypeReference<>() {});
                if (rawLists != null || rawCumulatives != null) {
                    PolicyDataCache.TenantPolicyData data = ScriptRuleRunner.fromBlocks(
                            rawLists != null ? rawLists : List.of(),
                            rawRedisIndex != null ? rawRedisIndex : List.of(),
                            rawCumulatives != null ? rawCumulatives : List.of());
                    policyData.replace(tenantId, data);
                }
                log.info("cache reloaded from stream: tenant={} version={} rules={}", tenantId, policyVersion,
                        rawRules != null ? rawRules.size() : 0);
            }
        } catch (Exception e) {
            log.warn("failed to process cache-reload message: {}", e.getMessage());
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
