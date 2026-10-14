package io.virbius.engine.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import redis.clients.jedis.JedisPool;

/**
 * Thin bean over {@link SessionStatePreloader} so native detectors (chain /
 * retry-escape) can read a session's tool history without owning Redis wiring.
 */
@Component
public class SessionHistoryService {

    private final SessionStatePreloader preloader;

    public SessionHistoryService(Optional<JedisPool> jedisPool, ObjectMapper mapper) {
        this.preloader = jedisPool.map(pool -> new SessionStatePreloader(pool, mapper)).orElse(null);
    }

    /**
     * Session tool history, most recent first (as stored). Empty when Redis is
     * unavailable or the session is blank.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> history(String sessionId) {
        if (preloader == null || sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        Object h = preloader.preload(sessionId).get("history");
        return h instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }
}
