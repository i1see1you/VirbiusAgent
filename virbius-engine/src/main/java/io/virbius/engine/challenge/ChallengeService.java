package io.virbius.engine.challenge;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import redis.clients.jedis.JedisPool;

/**
 * Manages challenge lifecycle: creation, approval, rejection, token verification.
 *
 * <p>Challenge records and tokens are stored in Redis with TTL:
 * <ul>
 *   <li>{@code challenge:{id}} — challenge record (TTL: 600s default)</li>
 *   <li>{@code challenge:token:{token}} — one-time-use token (TTL: 600s)</li>
 *   <li>{@code challenge:queue:{tenantId}} — ZSET of pending challenge IDs</li>
 *   <li>{@code challenge:exempt:{session}:{tool}:{args_hash}} — session-level exemption (TTL: 600s)</li>
 * </ul>
 *
 * <p>When a challenge is approved, an exemption record is written so that
 * subsequent calls with the same session+tool+args_hash bypass the challenge
 * and are allowed directly within the exemption TTL window.
 */
@Service
public class ChallengeService {

    private static final Logger log = LoggerFactory.getLogger(ChallengeService.class);
    private static final String KEY_CHALLENGE = "challenge:%s";
    private static final String KEY_TOKEN = "challenge:token:%s";
    private static final String KEY_QUEUE = "challenge:queue:%s";
    private static final String KEY_EXEMPT = "challenge:exempt:%s:%s:%s";
    private static final SecureRandom RNG = new SecureRandom();

    private final Optional<JedisPool> jedisPool;
    private final ObjectMapper mapper;
    private final int challengeTtlSeconds;
    private final int tokenTtlSeconds;
    private final int exemptionTtlSeconds;

    public ChallengeService(
            Optional<JedisPool> jedisPool,
            ObjectMapper mapper,
            @Value("${virbius.challenge.ttl-seconds:600}") int challengeTtlSeconds,
            @Value("${virbius.challenge.token-ttl-seconds:600}") int tokenTtlSeconds,
            @Value("${virbius.challenge.exemption-ttl-seconds:600}") int exemptionTtlSeconds) {
        this.jedisPool = jedisPool;
        this.mapper = mapper;
        this.challengeTtlSeconds = challengeTtlSeconds;
        this.tokenTtlSeconds = tokenTtlSeconds;
        this.exemptionTtlSeconds = exemptionTtlSeconds;
    }

    /**
     * Check whether a session-level exemption exists for the given session+tool+args_hash.
     *
     * <p>When an exemption is active, the Engine should override the challenge
     * decision to "allow" so the tool call proceeds without a new challenge.
     *
     * @param sessionId the Agent session ID
     * @param toolName  the tool being called
     * @param argsHash  SHA-256 hash of tool_name:args_json
     * @return {@code true} if an active exemption exists
     */
    public boolean hasActiveExemption(String sessionId, String toolName, String argsHash) {
        if (jedisPool.isEmpty() || sessionId == null || sessionId.isBlank()
                || exemptionTtlSeconds <= 0) {
            return false;
        }
        try (var jedis = jedisPool.get().getResource()) {
            String key = KEY_EXEMPT.formatted(sessionId, toolName, argsHash);
            return jedis.exists(key);
        } catch (Exception e) {
            log.warn("failed to check challenge exemption: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Create a new challenge record in Redis.
     */
    public String createChallenge(
            String tenantId,
            String sessionId,
            String toolName,
            String argsHash,
            String ruleId,
            String reasonCode,
            int riskScore) {
        String challengeId = "ch_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        long now = Instant.now().getEpochSecond();
        long expiresAt = now + challengeTtlSeconds;

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("challenge_id", challengeId);
        record.put("status", "pending");
        record.put("tenant_id", tenantId);
        record.put("session_id", sessionId);
        record.put("tool_name", toolName);
        record.put("args_hash", argsHash);
        record.put("rule_id", ruleId);
        record.put("reason_code", reasonCode);
        record.put("risk_score", riskScore);
        record.put("created_at", now);
        record.put("expires_at", expiresAt);
        record.put("approved_by", null);
        record.put("approved_at", null);
        record.put("token", null);

        if (jedisPool.isEmpty()) {
            log.warn("Redis not configured, challenge created in memory only: id={}", challengeId);
            return challengeId;
        }
        try (var jedis = jedisPool.get().getResource()) {
            String key = KEY_CHALLENGE.formatted(challengeId);
            String json = mapper.writeValueAsString(record);
            jedis.setex(key, challengeTtlSeconds, json);
            jedis.zadd(KEY_QUEUE.formatted(tenantId), now, challengeId);
            log.info("challenge created: id={} tool={} tenant={} session={}",
                    challengeId, toolName, tenantId, sessionId);
        } catch (Exception e) {
            log.error("failed to create challenge: {}", e.getMessage());
        }
        return challengeId;
    }

    /**
     * Approve a challenge and generate a one-time-use token.
     */
    public Map<String, Object> approve(String challengeId, String approvedBy, String comment) {
        Map<String, Object> record = getRecord(challengeId);
        if (record == null) {
            return Map.of("status", "not_found");
        }
        String status = (String) record.get("status");
        if (!"pending".equals(status)) {
            return Map.of("status", status, "challenge_id", challengeId);
        }

        String token = generateToken();
        long now = Instant.now().getEpochSecond();
        long tokenExpiresAt = now + tokenTtlSeconds;

        record.put("status", "approved");
        record.put("approved_by", approvedBy);
        record.put("approved_at", now);
        record.put("comment", comment);
        record.put("token", token);
        record.put("token_expires_at", tokenExpiresAt);

        if (jedisPool.isEmpty()) {
            return Map.of("status", "error", "message", "Redis not configured");
        }
        try (var jedis = jedisPool.get().getResource()) {
            String key = KEY_CHALLENGE.formatted(challengeId);
            String json = mapper.writeValueAsString(record);
            jedis.setex(key, challengeTtlSeconds + tokenTtlSeconds, json);

            // Store token for one-time verification
            Map<String, Object> tokenRecord = new LinkedHashMap<>();
            tokenRecord.put("challenge_id", challengeId);
            tokenRecord.put("used", false);
            tokenRecord.put("tool_name", record.get("tool_name"));
            tokenRecord.put("args_hash", record.get("args_hash"));
            tokenRecord.put("session_id", record.get("session_id"));
            tokenRecord.put("approved_by", approvedBy);
            String tokenJson = mapper.writeValueAsString(tokenRecord);
            jedis.setex(KEY_TOKEN.formatted(token), tokenTtlSeconds, tokenJson);

            // Remove from pending queue
            String tenantId = (String) record.get("tenant_id");
            jedis.zrem(KEY_QUEUE.formatted(tenantId), challengeId);

            // Write session-level exemption so subsequent calls with the same
            // session+tool+args_hash bypass the challenge within the TTL window.
            String sessionForExempt = (String) record.get("session_id");
            String toolForExempt = (String) record.get("tool_name");
            String hashForExempt = (String) record.get("args_hash");
            if (sessionForExempt != null && !sessionForExempt.isBlank()
                    && toolForExempt != null && !toolForExempt.isBlank()
                    && hashForExempt != null && !hashForExempt.isBlank()) {
                String exemptKey = KEY_EXEMPT.formatted(sessionForExempt, toolForExempt, hashForExempt);
                Map<String, Object> exemptRecord = new LinkedHashMap<>();
                exemptRecord.put("challenge_id", challengeId);
                exemptRecord.put("approved_by", approvedBy);
                exemptRecord.put("approved_at", now);
                exemptRecord.put("expires_at", now + exemptionTtlSeconds);
                jedis.setex(exemptKey, exemptionTtlSeconds, mapper.writeValueAsString(exemptRecord));
            }

            log.info("challenge approved: id={} by={} token=***", challengeId, approvedBy);
        } catch (Exception e) {
            log.error("failed to approve challenge: {}", e.getMessage());
            return Map.of("status", "error", "message", e.getMessage());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("challenge_id", challengeId);
        result.put("status", "approved");
        result.put("token", token);
        result.put("expires_at", tokenExpiresAt);
        return result;
    }

    /**
     * Reject a challenge.
     */
    public Map<String, Object> reject(String challengeId, String rejectedBy, String reason) {
        Map<String, Object> record = getRecord(challengeId);
        if (record == null) {
            return Map.of("status", "not_found");
        }
        String status = (String) record.get("status");
        if (!"pending".equals(status)) {
            return Map.of("status", status, "challenge_id", challengeId);
        }

        long now = Instant.now().getEpochSecond();
        record.put("status", "rejected");
        record.put("rejected_by", rejectedBy);
        record.put("rejected_at", now);
        record.put("reject_reason", reason);

        if (jedisPool.isEmpty()) {
            return Map.of("status", "error", "message", "Redis not configured");
        }
        try (var jedis = jedisPool.get().getResource()) {
            String key = KEY_CHALLENGE.formatted(challengeId);
            String json = mapper.writeValueAsString(record);
            jedis.setex(key, challengeTtlSeconds, json);

            String tenantId = (String) record.get("tenant_id");
            jedis.zrem(KEY_QUEUE.formatted(tenantId), challengeId);

            log.info("challenge rejected: id={} by={} reason={}", challengeId, rejectedBy, reason);
        } catch (Exception e) {
            log.error("failed to reject challenge: {}", e.getMessage());
            return Map.of("status", "error", "message", e.getMessage());
        }

        return Map.of("challenge_id", challengeId, "status", "rejected");
    }

    /**
     * Get the status of a challenge.
     */
    public Map<String, Object> getStatus(String challengeId) {
        Map<String, Object> record = getRecord(challengeId);
        if (record == null) {
            return Map.of("status", "not_found", "challenge_id", challengeId);
        }

        String status = (String) record.get("status");
        if ("pending".equals(status)) {
            Object expiresAtObj = record.get("expires_at");
            if (expiresAtObj instanceof Number n) {
                if (Instant.now().getEpochSecond() > n.longValue()) {
                    record.put("status", "expired");
                }
            }
        }

        if ("approved".equals(status)) {
            return record;
        }
        Map<String, Object> safe = new LinkedHashMap<>(record);
        safe.remove("token");
        safe.remove("token_expires_at");
        return safe;
    }

    /**
     * Verify a challenge token (one-time use).
     *
     * <p>Atomically marks the token as used. A second call with the same
     * token will return {@code valid=false}.
     */
    public Map<String, Object> verifyToken(String token, String toolName, String argsHash, String sessionId) {
        if (token == null || token.isBlank()) {
            return Map.of("valid", false, "reason", "empty_token");
        }
        if (jedisPool.isEmpty()) {
            return Map.of("valid", false, "reason", "redis_not_configured");
        }

        try (var jedis = jedisPool.get().getResource()) {
            String tokenKey = KEY_TOKEN.formatted(token);
            String tokenJson = jedis.get(tokenKey);
            if (tokenJson == null) {
                return Map.of("valid", false, "reason", "token_not_found_or_expired");
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> tokenRecord = mapper.readValue(tokenJson, Map.class);
            boolean used = Boolean.TRUE.equals(tokenRecord.get("used"));
            if (used) {
                return Map.of("valid", false, "reason", "token_already_used");
            }

            String expectedTool = String.valueOf(tokenRecord.get("tool_name"));
            String expectedHash = String.valueOf(tokenRecord.get("args_hash"));
            String expectedSession = String.valueOf(tokenRecord.get("session_id"));

            if (!expectedTool.equals(toolName)) {
                return Map.of("valid", false, "reason", "tool_name_mismatch");
            }
            if (!expectedHash.equals(argsHash)) {
                return Map.of("valid", false, "reason", "args_hash_mismatch");
            }
            if (!expectedSession.equals(sessionId)) {
                return Map.of("valid", false, "reason", "session_id_mismatch");
            }

            // Atomically mark as used (getSet for check-and-set)
            tokenRecord.put("used", true);
            String updatedJson = mapper.writeValueAsString(tokenRecord);
            String oldVal = jedis.getSet(tokenKey, updatedJson);
            if (oldVal == null) {
                return Map.of("valid", false, "reason", "token_expired_during_verify");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> oldRecord = mapper.readValue(oldVal, Map.class);
            if (Boolean.TRUE.equals(oldRecord.get("used"))) {
                return Map.of("valid", false, "reason", "token_already_used_race");
            }

            jedis.expire(tokenKey, 60);

            String challengeId = String.valueOf(tokenRecord.get("challenge_id"));
            String approvedBy = String.valueOf(tokenRecord.get("approved_by"));

            log.info("challenge token verified: challenge={} tool={} session={}",
                    challengeId, toolName, sessionId);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("valid", true);
            result.put("challenge_id", challengeId);
            result.put("approved_by", approvedBy);
            return result;

        } catch (Exception e) {
            log.error("failed to verify challenge token: {}", e.getMessage());
            return Map.of("valid", false, "reason", "verify_error:" + e.getMessage());
        }
    }

    /**
     * List pending challenges for a tenant (for dashboard approval queue).
     */
    public java.util.List<Map<String, Object>> listChallenges(String tenantId, String status, int max) {
        java.util.List<Map<String, Object>> results = new java.util.ArrayList<>();
        if (jedisPool.isEmpty()) {
            return results;
        }
        try (var jedis = jedisPool.get().getResource()) {
            String queueKey = KEY_QUEUE.formatted(tenantId);
            var ids = jedis.zrevrange(queueKey, 0, max - 1);
            for (String id : ids) {
                Map<String, Object> record = getRecord(id);
                if (record == null) {
                    continue;
                }
                String recordStatus = (String) record.get("status");
                if ("pending".equals(recordStatus)) {
                    Object expiresAtObj = record.get("expires_at");
                    if (expiresAtObj instanceof Number n
                            && Instant.now().getEpochSecond() > n.longValue()) {
                        record.put("status", "expired");
                    }
                }
                if (status == null || status.isBlank() || status.equals(record.get("status"))) {
                    Map<String, Object> safe = new LinkedHashMap<>(record);
                    safe.remove("token");
                    results.add(safe);
                }
            }
        } catch (Exception e) {
            log.error("failed to list challenges: {}", e.getMessage());
        }
        return results;
    }

    // --- Helpers ---

    @SuppressWarnings("unchecked")
    private Map<String, Object> getRecord(String challengeId) {
        if (jedisPool.isEmpty()) {
            return null;
        }
        try (var jedis = jedisPool.get().getResource()) {
            String json = jedis.get(KEY_CHALLENGE.formatted(challengeId));
            if (json == null) {
                return null;
            }
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.error("failed to get challenge record: {}", e.getMessage());
            return null;
        }
    }

    private String generateToken() {
        byte[] bytes = new byte[16];
        RNG.nextBytes(bytes);
        return "vct_" + HexFormat.of().formatHex(bytes);
    }

    /**
     * Compute SHA-256 hash of the tool call arguments for binding.
     */
    public static String computeArgsHash(String toolName, String argsJson) {
        try {
            String input = toolName + ":" + (argsJson != null ? argsJson : "");
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return "sha256:error";
        }
    }
}
