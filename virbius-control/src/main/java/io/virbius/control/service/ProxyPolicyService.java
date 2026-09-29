package io.virbius.control.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.control.common.exception.BusinessException;
import io.virbius.control.config.ControlJedisPools;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import redis.clients.jedis.StreamEntryID;

/**
 * Stores the hot-reloadable MCP proxy policy per tenant and pushes it to Redis.
 * Listen address, Redis URL, and license paths stay in the proxy's local toml.
 */
@Service
public class ProxyPolicyService {

    private static final Logger log = LoggerFactory.getLogger(ProxyPolicyService.class);
    static final String STREAM_KEY = "virbius:proxy:config-reload";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ControlJedisPools jedisPools;

    public ProxyPolicyService(JdbcTemplate jdbc, ObjectMapper mapper, ControlJedisPools jedisPools) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.jedisPools = jedisPools;
    }

    public Map<String, Object> get(String tenantId) {
        List<String> rows = jdbc.query(
                "SELECT body FROM tb_proxy_policy WHERE tenant_id = ?",
                (rs, n) -> rs.getString("body"),
                tenantId);
        if (rows.isEmpty()) {
            return defaults();
        }
        try {
            Map<String, Object> parsed = mapper.readValue(rows.get(0), new TypeReference<>() {});
            return normalize(parsed);
        } catch (Exception e) {
            log.warn("stored proxy policy for {} is invalid, returning defaults: {}", tenantId, e.getMessage());
            return defaults();
        }
    }

    public Map<String, Object> save(String tenantId, Map<String, Object> body) {
        Map<String, Object> policy = normalize(body == null ? Map.of() : body);
        String json;
        try {
            json = mapper.writeValueAsString(policy);
        } catch (Exception e) {
            throw new BusinessException(400, "proxy policy could not be encoded");
        }
        int updated = jdbc.update(
                "UPDATE tb_proxy_policy SET body = ?, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = ?",
                json,
                tenantId);
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO tb_proxy_policy (tenant_id, body) VALUES (?, ?)",
                    tenantId,
                    json);
        }
        publish(tenantId, json);
        Map<String, Object> out = new LinkedHashMap<>(policy);
        out.put("published", true);
        return out;
    }

    private void publish(String tenantId, String json) {
        var pool = jedisPools.pool().orElseThrow(
                () -> new BusinessException(503, "Redis is required to push proxy policy"));
        String snapshotKey = "virbius:proxy:" + tenantId + ":snapshot";
        try (var jedis = pool.getResource()) {
            jedis.set(snapshotKey, json);
            jedis.xadd(STREAM_KEY, StreamEntryID.NEW_ENTRY, Map.of("tenant_id", tenantId));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(502, "proxy policy saved but redis publish failed: " + e.getMessage());
        }
        log.info("published proxy policy tenant={} key={}", tenantId, snapshotKey);
    }

    static Map<String, Object> defaults() {
        return normalize(Map.of());
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> normalize(Map<String, Object> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("upstreams", normalizeUpstreams(raw.get("upstreams")));
        out.put("session_ttl_secs", bound(raw.get("session_ttl_secs"), 1800, 30, 604800, "session_ttl_secs"));
        out.put("fallback_policy", fallback(raw.get("fallback_policy")));

        Map<String, Object> fast = map(raw.get("fast_path"));
        Map<String, Object> fastOut = new LinkedHashMap<>();
        fastOut.put("enabled", bool(fast.get("enabled"), true));
        fastOut.put("warmup_calls", bound(fast.get("warmup_calls"), 5, 0, 10000, "fast_path.warmup_calls"));
        fastOut.put("risk_threshold", bound(fast.get("risk_threshold"), 30, 0, 100, "fast_path.risk_threshold"));
        out.put("fast_path", fastOut);

        Map<String, Object> fail = map(raw.get("failover"));
        Map<String, Object> failOut = new LinkedHashMap<>();
        failOut.put("high_risk_fail_closed", bool(fail.get("high_risk_fail_closed"), true));
        failOut.put("low_risk_fail_open", bool(fail.get("low_risk_fail_open"), true));
        failOut.put("engine_timeout_ms", bound(fail.get("engine_timeout_ms"), 3000, 100, 60000, "failover.engine_timeout_ms"));
        out.put("failover", failOut);

        Map<String, Object> review = map(raw.get("output_review"));
        Map<String, Object> reviewOut = new LinkedHashMap<>();
        reviewOut.put("enabled", bool(review.get("enabled"), true));
        reviewOut.put("min_text_length", bound(review.get("min_text_length"), 512, 0, 1_000_000, "output_review.min_text_length"));
        reviewOut.put("min_risk_score", bound(review.get("min_risk_score"), 50, 0, 100, "output_review.min_risk_score"));
        reviewOut.put("fail_open", bool(review.get("fail_open"), true));
        out.put("output_review", reviewOut);

        Map<String, Object> memory = map(raw.get("memory"));
        Map<String, Object> memoryOut = new LinkedHashMap<>();
        memoryOut.put("enabled", bool(memory.get("enabled"), false));
        memoryOut.put("max_entry_size", bound(memory.get("max_entry_size"), 4096, 1, 1_000_000, "memory.max_entry_size"));
        memoryOut.put("tool_patterns", strings(memory.get("tool_patterns")));
        out.put("memory", memoryOut);
        return out;
    }

    private static List<Map<String, Object>> normalizeUpstreams(Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return out;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) {
                continue;
            }
            String name = str(m.get("name"));
            String url = str(m.get("url"));
            if (name.isBlank() || url.isBlank()) {
                throw new BusinessException(400, "each upstream needs a name and a url");
            }
            String sse = str(m.get("sse_path"));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", name);
            row.put("url", url);
            row.put("sse_path", sse.isBlank() ? "/sse" : sse);
            out.add(row);
        }
        return out;
    }

    private static String fallback(Object raw) {
        String v = str(raw);
        if (v.isBlank()) {
            return "minimum_privilege";
        }
        if (v.equals("minimum_privilege") || v.equals("default_deny") || v.equals("audit_only")) {
            return v;
        }
        throw new BusinessException(400, "fallback_policy must be minimum_privilege, default_deny, or audit_only");
    }

    private static int bound(Object raw, int fallback, int min, int max, String field) {
        if (raw == null) {
            return fallback;
        }
        int n;
        if (raw instanceof Number num) {
            n = num.intValue();
        } else {
            try {
                n = Integer.parseInt(raw.toString().trim());
            } catch (NumberFormatException e) {
                throw new BusinessException(400, field + " must be an integer");
            }
        }
        if (n < min || n > max) {
            throw new BusinessException(400, field + " must be between " + min + " and " + max);
        }
        return n;
    }

    private static boolean bool(Object raw, boolean fallback) {
        if (raw instanceof Boolean b) {
            return b;
        }
        if (raw == null) {
            return fallback;
        }
        return Boolean.parseBoolean(raw.toString());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object raw) {
        if (raw instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return Map.of();
    }

    private static List<String> strings(Object raw) {
        List<String> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return out;
        }
        for (Object item : list) {
            String s = str(item);
            if (!s.isBlank()) {
                out.add(s);
            }
        }
        return out;
    }

    private static String str(Object raw) {
        return raw == null ? "" : raw.toString().trim();
    }
}
