package io.virbius.engine.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;

public class SessionStatePreloader {
    private static final Logger log = LoggerFactory.getLogger(SessionStatePreloader.class);
    private static final String KEY_HISTORY = "session:%s:tool_history";
    private static final String KEY_RISK = "session:%s:risk_score";
    private static final String KEY_COUNT = "session:%s:tool_count:%s";
    private static final int MAX_HISTORY = 50;

    private final JedisPool jedisPool;
    private final ObjectMapper objectMapper;

    public SessionStatePreloader(JedisPool jedisPool, ObjectMapper objectMapper) {
        this.jedisPool = jedisPool;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> preload(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Map.of("history", List.of(), "riskScore", 0, "toolCounts", Map.of());
        }
        try (Jedis jedis = jedisPool.getResource()) {
            String historyKey = KEY_HISTORY.formatted(sessionId);
            String riskKey = KEY_RISK.formatted(sessionId);

            Pipeline pipe = jedis.pipelined();
            var historyFuture = pipe.lrange(historyKey, 0, 9);
            var riskFuture = pipe.get(riskKey);
            pipe.sync();

            List<String> historyRaw = historyFuture.get();
            String riskRaw = riskFuture.get();

            List<Map<String, Object>> history = new ArrayList<>();
            if (historyRaw != null) {
                for (String json : historyRaw) {
                    try {
                        history.add(objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {}));
                    } catch (Exception e) {
                        log.warn("Failed to parse session history entry: {}", e.getMessage());
                    }
                }
            }

            int riskScore = 0;
            if (riskRaw != null && !riskRaw.isBlank()) {
                try { riskScore = Integer.parseInt(riskRaw); } catch (NumberFormatException ignored) {}
            }

            return Map.of(
                "history", history,
                "riskScore", riskScore,
                "toolCounts", Map.of()
            );
        } catch (Exception e) {
            log.error("Failed to preload session state: {}", e.getMessage());
            return Map.of("history", List.of(), "riskScore", 0, "toolCounts", Map.of());
        }
    }

    public void recordToolCall(String sessionId, String toolName, String args, boolean allowed) {
        if (sessionId == null || sessionId.isBlank()) return;
        try (Jedis jedis = jedisPool.getResource()) {
            Pipeline pipe = jedis.pipelined();
            String historyKey = KEY_HISTORY.formatted(sessionId);
            String countKey = KEY_COUNT.formatted(sessionId, toolName);

            Map<String, Object> entry = new HashMap<>();
            entry.put("tool_name", toolName);
            entry.put("args", args != null ? args : "");
            entry.put("allowed", allowed);
            entry.put("ts", System.currentTimeMillis() / 1000);
            try {
                String json = objectMapper.writeValueAsString(entry);
                pipe.lpush(historyKey, json);
                pipe.ltrim(historyKey, 0, MAX_HISTORY - 1);
                pipe.expire(historyKey, 3600);
            } catch (Exception e) {
                log.warn("Failed to serialize session history entry: {}", e.getMessage());
            }

            pipe.incr(countKey);
            pipe.expire(countKey, 3600);
            pipe.sync();
        } catch (Exception e) {
            log.error("Failed to record tool call: {}", e.getMessage());
        }
    }

    public void incrementRiskScore(String sessionId, int delta) {
        if (sessionId == null || sessionId.isBlank() || delta == 0) return;
        try (Jedis jedis = jedisPool.getResource()) {
            String riskKey = KEY_RISK.formatted(sessionId);
            Pipeline pipe = jedis.pipelined();
            pipe.incrBy(riskKey, delta);
            pipe.expire(riskKey, 3600);
            pipe.sync();
        } catch (Exception e) {
            log.error("Failed to increment risk score: {}", e.getMessage());
        }
    }
}
