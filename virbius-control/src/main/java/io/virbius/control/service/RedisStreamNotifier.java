package io.virbius.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.control.config.ControlJedisPools;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.StreamEntryID;

@Component
public class RedisStreamNotifier implements CacheReloadNotifier {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamNotifier.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String STREAM_KEY = "virbius:engine:cache-reload";
    private static final String SNAPSHOT_KEY_PREFIX = "virbius:engine";

    private final JedisPool jedisPool;

    public RedisStreamNotifier(ControlJedisPools jedisPools) {
        this.jedisPool = jedisPools.pool().orElseThrow(
                () -> new IllegalStateException("Redis is required for cache-reload notifier"));
    }

    @Override
    public Map<String, Object> publish(String tenantId, String policyVersion, Map<String, Object> payload) {
        return doPublish(tenantId, policyVersion, payload);
    }

    private Map<String, Object> doPublish(String tenantId, String policyVersion, Map<String, Object> payload) {
        try {
            String payloadJson = JSON.writeValueAsString(payload);
            String snapshotKey = SNAPSHOT_KEY_PREFIX + ":" + tenantId + ":snapshot";
            StreamEntryID messageId;
            try (var jedis = jedisPool.getResource()) {
                jedis.set(snapshotKey, payloadJson);
                messageId = jedis.xadd(STREAM_KEY, StreamEntryID.NEW_ENTRY, Map.of(
                        "tenant_id", tenantId,
                        "policy_version", policyVersion,
                        "payload", payloadJson));
            }
            log.info("published cache-reload to stream {} messageId={} tenant={} version={}",
                    STREAM_KEY, messageId, tenantId, policyVersion);
            return Map.of("ok", true, "message_id", messageId.toString());
        } catch (Exception e) {
            log.warn("failed to publish cache-reload to stream {}: {}", STREAM_KEY, e.getMessage());
            return Map.of("ok", false, "error", e.getMessage());
        }
    }
}
