package io.virbius.engine.eval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.engine.cache.RuleEntry;
import io.virbius.policy.MatchContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class AsyncActionHandler {

    private static final Logger log = LoggerFactory.getLogger(AsyncActionHandler.class);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(.+?)}}");
    private final ObjectMapper mapper = new ObjectMapper();

    public void executeIfConfigured(RuleEntry rule, SignalDto signal, MatchContext matchCtx) {
        String config = rule.asyncActionConfig();
        if (config == null || config.isBlank()) {
            return;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> cfg = mapper.readValue(config, Map.class);
            String type = cfg != null ? (String) cfg.get("type") : null;
            if (type == null || type.isBlank()) {
                log.warn("async_action_config missing type for rule={}", rule.ruleId());
                return;
            }
            Map<String, Object> ctx = buildContext(rule, signal, matchCtx);
            switch (type) {
                case "redis_stream" -> publishToRedisStream(rule, signal, cfg, ctx);
                case "webhook" -> callWebhook(rule, signal, cfg, ctx);
                default -> log.warn("unknown async_action type={} for rule={}", type, rule.ruleId());
            }
        } catch (Exception e) {
            log.warn("async action failed rule={} tenant={}: {}", rule.ruleId(), rule.tenantId(), e.getMessage());
        }
    }

    private void publishToRedisStream(RuleEntry rule, SignalDto signal, Map<String, Object> cfg,
                                       Map<String, Object> ctx) throws JsonProcessingException {
        String streamKey = cfg.containsKey("stream_key")
                ? (String) cfg.get("stream_key")
                : "virbius:rule:hit:actions";
        @SuppressWarnings("unchecked")
        Map<String, Object> template = cfg.containsKey("message")
                ? (Map<String, Object>) cfg.get("message")
                : Map.of();
        Map<String, Object> message = resolvePlaceholders(template, ctx);
        log.info("async-action redis_stream key={} message={}", streamKey, mapper.writeValueAsString(message));
    }

    private void callWebhook(RuleEntry rule, SignalDto signal, Map<String, Object> cfg,
                              Map<String, Object> ctx) throws JsonProcessingException {
        String url = (String) cfg.get("url");
        if (url == null || url.isBlank()) {
            log.warn("webhook url missing for rule={}", rule.ruleId());
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> template = cfg.containsKey("body")
                ? (Map<String, Object>) cfg.get("body")
                : Map.of();
        Map<String, Object> body = resolvePlaceholders(template, ctx);
        log.info("async-action webhook url={} body={}", url, mapper.writeValueAsString(body));
    }

    private static Map<String, Object> buildContext(RuleEntry rule, SignalDto signal, MatchContext matchCtx) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("rule_id", rule.ruleId());
        ctx.put("rule_revision", rule.ruleRevision());
        ctx.put("tenant_id", rule.tenantId());
        ctx.put("reason_code", rule.reasonCode());
        ctx.put("intent_action", rule.intentAction());
        ctx.put("risk_score", rule.riskScore());
        ctx.put("enforce_mode", rule.enforceMode());
        ctx.put("canary_percent", rule.canaryPercent());
        ctx.put("hit_at", java.time.Instant.now().toString());

        if (matchCtx != null) {
            ctx.put("user_id", matchCtx.userId() != null ? matchCtx.userId() : "");
            ctx.put("device_id", matchCtx.deviceId() != null ? matchCtx.deviceId() : "");
            ctx.put("client_ip", matchCtx.clientIp() != null ? matchCtx.clientIp() : "");
            ctx.put("session_id", matchCtx.sessionId() != null ? matchCtx.sessionId() : "");
            ctx.put("content", matchCtx.content() != null ? matchCtx.content() : "");
            ctx.put("scene", matchCtx.scene() != null ? matchCtx.scene() : "");
            ctx.put("route_uri", matchCtx.routeUri() != null ? matchCtx.routeUri() : "");
            ctx.put("vars", matchCtx.varsOrEmpty());
        }
        return ctx;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> resolvePlaceholders(Map<String, Object> template, Map<String, Object> ctx) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : template.entrySet()) {
            result.put(entry.getKey(), resolveValue(entry.getValue(), ctx));
        }
        return result;
    }

    private static Object resolveValue(Object value, Map<String, Object> ctx) {
        if (value instanceof String s) {
            return resolveString(s, ctx);
        }
        if (value instanceof Map) {
            return resolvePlaceholders((Map<String, Object>) value, ctx);
        }
        if (value instanceof List) {
            return ((List<?>) value).stream().map(v -> resolveValue(v, ctx)).toList();
        }
        return value;
    }

    private static String resolveString(String template, Map<String, Object> ctx) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String path = m.group(1);
            Object resolved = resolveByPath(path, ctx);
            m.appendReplacement(sb, Matcher.quoteReplacement(resolved != null ? String.valueOf(resolved) : ""));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static Object resolveByPath(String path, Map<String, Object> ctx) {
        String[] parts = path.split("\\.");
        Object current = ctx;
        for (String part : parts) {
            if (current instanceof Map) {
                current = ((Map<String, Object>) current).get(part);
            } else {
                return null;
            }
        }
        return current;
    }
}
