package io.virbius.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.control.config.ControlJedisPools;
import java.util.Optional;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import redis.clients.jedis.JedisPool;

/**
 * Publishes evaluate-auth revocations to the engine.
 *
 * <p>Two delivery paths, both idempotent:
 * <ul>
 *   <li>Redis hash {@code virbius:engine:revocations} (full snapshot; the engine
 *       boot-loads it and reconciles periodically — survives pub/sub message loss)</li>
 *   <li>Redis pub/sub channel {@code virbius:license:revoked} (near-real-time push;
 *       payload extended with agent_aid / jti / expires_at)</li>
 * </ul>
 */
@Component
public class EvaluateRevocationPublisher {

    private static final Logger log = LoggerFactory.getLogger(EvaluateRevocationPublisher.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public static final String REVOCATION_HASH_KEY = "virbius:engine:revocations";
    public static final String REVOCATION_CHANNEL = "virbius:license:revoked";

    private final Optional<JedisPool> jedisPool;

    public EvaluateRevocationPublisher(ControlJedisPools pools) {
        this.jedisPool = pools.pool();
    }

    /**
     * Publish a revocation entry.
     *
     * @param entryKey  the hash field: {@code agent:<aid>} for agent-level or
     *                  {@code jti:<jti>} for token-level revocation
     * @param agentAid  the agent identity (nullable for pure jti revocation)
     * @param jti       the token id (nullable for agent-level)
     * @param reason    human-readable reason
     * @param expiresAt temporary-revocation expiry (null = permanent); P0 stores
     *                  the field, the engine applies it from P1
     */
    public void publish(String entryKey, String agentAid, String jti, String reason, String expiresAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (agentAid != null) {
            payload.put("agent_aid", agentAid);
        }
        if (jti != null) {
            payload.put("jti", jti);
        }
        payload.put("reason", reason == null ? "" : reason);
        payload.put("expires_at", expiresAt);
        if (jedisPool.isEmpty()) {
            log.warn("Redis not configured; evaluate revocation not propagated (key={})", entryKey);
            return;
        }
        String json;
        try {
            json = JSON.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("failed to serialize revocation payload: {}", e.getMessage());
            return;
        }
        try (var jedis = jedisPool.get().getResource()) {
            jedis.hset(REVOCATION_HASH_KEY, entryKey, json);
            jedis.publish(REVOCATION_CHANNEL, json);
        } catch (Exception e) {
            log.warn("failed to publish evaluate revocation (key={}): {}", entryKey, e.getMessage());
        }
    }

    /** Publish the trusted-issuer set (engine verifies tokens against these keys). */
    public void publishIssuers(Map<String, String> kidToPem) {
        if (jedisPool.isEmpty()) {
            log.warn("Redis not configured; evaluate issuer set not published");
            return;
        }
        try (var jedis = jedisPool.get().getResource()) {
            jedis.del("virbius:engine:issuers");
            if (!kidToPem.isEmpty()) {
                jedis.hset("virbius:engine:issuers", kidToPem);
            }
            jedis.publish("virbius:engine:issuers:changed", "republished");
        } catch (Exception e) {
            log.warn("failed to publish evaluate issuer set: {}", e.getMessage());
        }
    }
}
