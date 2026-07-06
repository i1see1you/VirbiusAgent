package io.virbius.engine.cache;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
public class RuleCache {

    private final Map<String, RuleEntry> rules = new ConcurrentHashMap<>();
    private final AtomicLong generation = new AtomicLong(0);
    private volatile String policyVersion = "0.0.0";
    private volatile Instant loadedAt = Instant.now();

    public RuleCache() {
    }

    public void replaceAll(String policyVersion, List<RuleEntry> entries) {
        rules.clear();
        for (RuleEntry e : entries) {
            rules.put(key(e.tenantId(), e.ruleId()), e);
        }
        this.policyVersion = policyVersion;
        this.loadedAt = Instant.now();
        generation.incrementAndGet();
    }

    public void upsert(String tenantId, RuleEntry entry) {
        rules.put(key(tenantId, entry.ruleId()), entry);
        generation.incrementAndGet();
    }

    public void remove(String tenantId, String ruleId) {
        rules.remove(key(tenantId, ruleId));
        generation.incrementAndGet();
    }

    public List<RuleEntry> rulesForTenant(String tenantId) {
        List<RuleEntry> out = new ArrayList<>();
        for (RuleEntry e : rules.values()) {
            if (e.tenantId().equals(tenantId)) {
                out.add(e);
            }
        }
        return out;
    }

    public RuleEntry get(String tenantId, String ruleId) {
        return rules.get(key(tenantId, ruleId));
    }

    public long cacheGeneration() {
        return generation.get();
    }

    public String policyVersion() {
        return policyVersion;
    }

    public Instant loadedAt() {
        return loadedAt;
    }

    public int ruleCount() {
        return rules.size();
    }

    private static String key(String tenantId, String ruleId) {
        return tenantId + ":" + ruleId;
    }
}
