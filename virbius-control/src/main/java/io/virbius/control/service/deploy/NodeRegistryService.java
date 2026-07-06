package io.virbius.control.service.deploy;

import io.virbius.control.config.ControlJedisPools;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/**
 * Lists / aggregates engine + gateway node registrations from Redis (each node periodically
 * HSETs its own metadata under {@code virbius:nodes:{layer}:{tenant}:{instance_id}} with a 60s
 * TTL). Used by the control plane UI to display live pool / revision status during a rollout.
 */
@Service
public class NodeRegistryService {

    private static final Logger log = LoggerFactory.getLogger(NodeRegistryService.class);

    private final Optional<JedisPool> pool;

    public NodeRegistryService(ControlJedisPools jedisPools) {
        this.pool = jedisPools.pool();
    }

    /** List all live nodes for the given layer + tenant (live = key still exists, TTL not expired). */
    public List<Map<String, String>> listNodes(String layer, String tenantId) {
        if (pool.isEmpty()) {
            return List.of();
        }
        String prefix = DeployRolloutKeys.nodeKeyPrefix(layer, tenantId);
        List<Map<String, String>> out = new ArrayList<>();
        try (Jedis jedis = pool.get().getResource()) {
            for (String key : jedis.keys(prefix + "*")) {
                Map<String, String> hash = jedis.hgetAll(key);
                if (hash.isEmpty()) {
                    continue;
                }
                Map<String, String> row = new LinkedHashMap<>(hash);
                row.put("instance_id", key.substring(prefix.length()));
                out.add(row);
            }
        } catch (Exception ex) {
            log.warn("list nodes failed layer={} tenant={}: {}", layer, tenantId, ex.getMessage());
        }
        return out;
    }

    /** Counts nodes per pool for a layer/tenant. */
    public Map<String, Integer> poolDistribution(String layer, String tenantId) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("stable", 0);
        counts.put("canary", 0);
        counts.put("unknown", 0);
        for (Map<String, String> node : listNodes(layer, tenantId)) {
            String p = node.getOrDefault("pool", "unknown");
            counts.merge(p, 1, Integer::sum);
        }
        return counts;
    }
}
