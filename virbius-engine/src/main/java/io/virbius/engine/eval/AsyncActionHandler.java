package io.virbius.engine.eval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.virbius.engine.cache.RuleEntry;
import io.virbius.engine.config.AsyncActionKafkaConfig;
import io.virbius.engine.config.AsyncActionProperties;
import io.virbius.policy.MatchContext;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Executes async rule-hit actions (webhook / kafka).
 *
 * <p>Delivery semantics: at-most-once. No retries, no outbox, no HMAC signing. Every failure
 * or skip is only logged (target scheme://host, never query/body) and metered; delivery
 * never throws and never affects the evaluation outcome. Delivery runs on a dedicated
 * bounded pool so slow webhooks/brokers cannot starve the async rule-evaluation pool.
 */
@Component
public class AsyncActionHandler {

    private static final Logger log = LoggerFactory.getLogger(AsyncActionHandler.class);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(.+?)}}");
    private static final String CONTENT_PLACEHOLDER = "content";
    private static final String CONTENT_PREVIEW_PLACEHOLDER = "content_preview";
    private static final int CONTENT_PREVIEW_CHARS = 512;

    private final ObjectMapper mapper = new ObjectMapper();
    private final AsyncActionProperties props;
    private final KafkaTemplate<String, String> kafka;
    private final HttpClient http;
    private final ThreadPoolExecutor deliveryPool;
    private final MeterRegistry registry;

    @Autowired
    public AsyncActionHandler(
            AsyncActionProperties props,
            @Qualifier(AsyncActionKafkaConfig.TEMPLATE_BEAN) KafkaTemplate<String, String> kafka,
            MeterRegistry registry) {
        this(props, kafka, defaultHttpClient(props), newDeliveryPool(props), registry);
    }

    /** Test seam: full control over http/kafka/pool; no Spring. */
    AsyncActionHandler(
            AsyncActionProperties props,
            KafkaTemplate<String, String> kafka,
            HttpClient http,
            ThreadPoolExecutor deliveryPool,
            MeterRegistry registry) {
        this.props = props;
        this.kafka = kafka;
        this.http = http;
        this.deliveryPool = deliveryPool;
        this.registry = registry;
        // Pre-register so scrapes see the series even before first action.
        for (String type : new String[]{"webhook", "kafka"}) {
            for (String result : new String[]{"success", "failure", "skipped"}) {
                counter(type, result);
            }
        }
    }

    private void meter(String type, String result) {
        counter(type, result).increment();
    }

    private Counter counter(String type, String result) {
        return Counter.builder("virbius.async_action")
                .tag("type", type)
                .tag("result", result)
                .register(registry);
    }

    public void executeIfConfigured(RuleEntry rule, SignalDto signal, MatchContext matchCtx) {
        String config = rule.asyncActionConfig();
        if (config == null || config.isBlank()) {
            return;
        }
        try {
            if (!props.enabled()) {
                meter("unknown", "skipped");
                log.debug("async-action skipped (disabled) rule={}", rule.ruleId());
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> cfg = mapper.readValue(config, Map.class);
            String type = cfg != null ? (String) cfg.get("type") : null;
            if (!"webhook".equals(type) && !"kafka".equals(type)) {
                meter("unknown", "skipped");
                log.warn("async-action skipped: unknown/legacy type={} rule={} "
                        + "(redis_stream was removed; use kafka)", type, rule.ruleId());
                return;
            }
            // Parse + render in the caller (async eval) thread; only I/O goes to the pool.
            DeliveryRequest request = buildRequest(type, rule, signal, matchCtx, cfg);
            if (request == null) {
                meter(type, "skipped");
                return;
            }
            // P4: bounded pool; when saturated, drop rather than backpressure the eval pool.
            if (deliveryPool.getQueue().remainingCapacity() == 0) {
                meter(type, "skipped");
                log.warn("async-action skipped (delivery queue full, max={}) rule={} type={}",
                        props.deliveryQueueMax(), rule.ruleId(), type);
                return;
            }
            deliveryPool.execute(() -> deliver(request));
        } catch (Exception e) {
            meter("unknown", "failure");
            log.warn("async action failed rule={} tenant={}: {}", rule.ruleId(), rule.tenantId(), e.getMessage());
        }
    }

    /** One immutable, fully-rendered delivery unit. */
    record DeliveryRequest(String type, String ruleId, String tenantId, String sessionId,
                           String reasonCode, String target, Map<String, Object> payload,
                           Instant hitAt) {}

    private DeliveryRequest buildRequest(String type, RuleEntry rule, SignalDto signal,
                                         MatchContext matchCtx, Map<String, Object> cfg)
            throws JsonProcessingException {
        Instant hitAt = Instant.now();
        Map<String, Object> ctx = buildContext(rule, signal, matchCtx, hitAt);
        String sessionId = matchCtx != null && matchCtx.sessionId() != null ? matchCtx.sessionId() : "";
        if ("webhook".equals(type)) {
            String url = text(cfg.get("url"));
            if (url.isEmpty() || !isAllowedWebhook(url)) {
                log.warn("async-action skipped: webhook url invalid/not allowed rule={} url={}",
                        rule.ruleId(), safeTarget(url));
                return null;
            }
            Map<String, Object> payload = resolvePlaceholders(mapValue(cfg.get("body")), ctx);
            return new DeliveryRequest(type, rule.ruleId(), rule.tenantId(), sessionId,
                    rule.reasonCode(), url, payload, hitAt);
        }
        String topic = text(cfg.get("topic"));
        if (topic.isEmpty()) {
            topic = props.kafka().topic();
        }
        Map<String, Object> payload = resolvePlaceholders(mapValue(cfg.get("message")), ctx);
        // M1: stable envelope so consumers never parse raw templates.
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("version", 1);
        envelope.put("type", "rule_action");
        envelope.put("rule_id", rule.ruleId());
        envelope.put("tenant_id", rule.tenantId());
        envelope.put("session_id", sessionId);
        envelope.put("reason_code", rule.reasonCode());
        envelope.put("hit_at", hitAt.toString());
        envelope.put("payload", payload);
        return new DeliveryRequest(type, rule.ruleId(), rule.tenantId(), sessionId,
                rule.reasonCode(), topic, envelope, hitAt);
    }

    private void deliver(DeliveryRequest req) {
        long start = System.nanoTime();
        try {
            if ("webhook".equals(req.type())) {
                deliverWebhook(req);
            } else {
                deliverKafka(req);
            }
        } catch (Exception e) {
            meter(req.type(), "failure");
            log.warn("async-action failed type={} rule={} target={} reason={}",
                    req.type(), req.ruleId(), safeTarget(req.target()), e.getMessage());
        } finally {
            long ms = (System.nanoTime() - start) / 1_000_000;
            log.debug("async-action deliver done type={} rule={} duration_ms={}", req.type(), req.ruleId(), ms);
        }
    }

    private void deliverWebhook(DeliveryRequest req) throws Exception {
        String json = mapper.writeValueAsString(req.payload());
        HttpRequest request = HttpRequest.newBuilder(URI.create(req.target()))
                .timeout(Duration.ofMillis(props.webhook().timeoutMs()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        // P2: sendAsync throws synchronously on invalid URI; non-2xx is a failure, not an exception.
        HttpResponse<String> response = http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .get(props.webhook().timeoutMs() * 2L + 1000, TimeUnit.MILLISECONDS);
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            meter(req.type(), "success");
            log.info("async-action delivered type=webhook rule={} target={} status={} body_bytes={}",
                    req.ruleId(), safeTarget(req.target()), status, json.length());
        } else {
            meter(req.type(), "failure");
            log.warn("async-action failed type=webhook rule={} target={} status={}",
                    req.ruleId(), safeTarget(req.target()), status);
        }
    }

    private void deliverKafka(DeliveryRequest req) throws Exception {
        String json = mapper.writeValueAsString(req.payload());
        // P2: send() may throw synchronously (metadata TimeoutException after max.block.ms);
        // treat exactly like an async callback failure.
        CompletableFuture<?> future = kafka.send(req.target(), req.sessionId(), json).toCompletableFuture();
        future.get(props.kafka().maxBlockMs() + 1000L, TimeUnit.MILLISECONDS);
        meter(req.type(), "success");
        log.info("async-action delivered type=kafka rule={} topic={} body_bytes={}",
                req.ruleId(), req.target(), json.length());
    }

    // ---- rendering --------------------------------------------------------

    private Map<String, Object> buildContext(RuleEntry rule, SignalDto signal,
                                             MatchContext matchCtx, Instant hitAt) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("rule_id", rule.ruleId());
        ctx.put("rule_revision", rule.ruleRevision());
        ctx.put("tenant_id", rule.tenantId());
        ctx.put("reason_code", rule.reasonCode());
        ctx.put("intent_action", rule.intentAction());
        ctx.put("risk_score", rule.riskScore());
        ctx.put("enforce_mode", rule.enforceMode());
        ctx.put("canary_percent", rule.canaryPercent());
        ctx.put("hit_at", hitAt.toString());
        if (matchCtx != null) {
            String content = matchCtx.content() != null ? matchCtx.content() : "";
            ctx.put("user_id", matchCtx.userId() != null ? matchCtx.userId() : "");
            ctx.put("device_id", matchCtx.deviceId() != null ? matchCtx.deviceId() : "");
            ctx.put("client_ip", matchCtx.clientIp() != null ? matchCtx.clientIp() : "");
            ctx.put("session_id", matchCtx.sessionId() != null ? matchCtx.sessionId() : "");
            ctx.put("content", truncate(content, props.contentMaxChars()));
            ctx.put("content_preview", truncate(content, CONTENT_PREVIEW_CHARS));
            ctx.put("route_uri", matchCtx.routeUri() != null ? matchCtx.routeUri() : "");
            ctx.put("vars", matchCtx.varsOrEmpty());
        }
        return ctx;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolvePlaceholders(Map<String, Object> template, Map<String, Object> ctx) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : template.entrySet()) {
            result.put(entry.getKey(), resolveValue(entry.getValue(), ctx));
        }
        return result;
    }

    private Object resolveValue(Object value, Map<String, Object> ctx) {
        if (value instanceof String s) {
            return resolveString(s, ctx);
        }
        if (value instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) value;
            return resolvePlaceholders(m, ctx);
        }
        if (value instanceof List) {
            return ((List<?>) value).stream().map(v -> resolveValue(v, ctx)).toList();
        }
        return value;
    }

    private String resolveString(String template, Map<String, Object> ctx) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String path = m.group(1).trim();
            // P1: cap per-field during rendering so serialized JSON stays valid.
            Object resolved = resolveByPath(path, ctx);
            String text = resolved != null ? String.valueOf(resolved) : "";
            boolean contentField = CONTENT_PLACEHOLDER.equals(path) || CONTENT_PREVIEW_PLACEHOLDER.equals(path);
            if (!contentField) {
                text = truncate(text, props.fieldMaxChars());
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(text));
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

    // ---- validation / safety ----------------------------------------------

    /** M4: redirects are never followed (HttpClient default); scheme+host whitelist checked. */
    private boolean isAllowedWebhook(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (Exception e) {
            return false;
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            return false;
        }
        if (uri.getHost() == null) {
            return false;
        }
        List<String> allowed = props.webhook().allowedHostList();
        if (allowed.isEmpty()) {
            return true; // D1: default allow-all (self-hosted); restrict via config when needed.
        }
        String host = uri.getHost();
        String hostPort = uri.getPort() > 0 ? host + ":" + uri.getPort() : host;
        return allowed.stream().anyMatch(a -> a.equalsIgnoreCase(hostPort) || a.equalsIgnoreCase(host));
    }

    /** P3: logs and metrics see only scheme://host; query/path (often tokens) never logged. */
    static String safeTarget(String url) {
        try {
            URI uri = new URI(url);
            StringBuilder sb = new StringBuilder();
            if (uri.getScheme() != null) {
                sb.append(uri.getScheme()).append("://");
            }
            if (uri.getHost() != null) {
                sb.append(uri.getHost());
            } else if (uri.getAuthority() != null) {
                sb.append(uri.getAuthority());
            }
            if (uri.getPort() > 0) {
                sb.append(":").append(uri.getPort());
            }
            return sb.length() > 0 ? sb.toString() : "<invalid-url>";
        } catch (Exception e) {
            return "<invalid-url>";
        }
    }

    // ---- infra ------------------------------------------------------------

    private static HttpClient defaultHttpClient(AsyncActionProperties props) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.min(3000, props.webhook().timeoutMs())))
                .followRedirects(HttpClient.Redirect.NEVER) // M4: redirects never followed.
                .build();
    }

    private static ThreadPoolExecutor newDeliveryPool(AsyncActionProperties props) {
        AtomicInteger seq = new AtomicInteger();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                props.deliveryThreads(), props.deliveryThreads(),
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(props.deliveryQueueMax()),
                r -> {
                    Thread t = new Thread(r, "virbius-async-action-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy()); // caller checks capacity first; abort = race fallback
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    @PreDestroy
    void shutdown() {
        deliveryPool.shutdown();
        try {
            if (!deliveryPool.awaitTermination(3, TimeUnit.SECONDS)) {
                deliveryPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            deliveryPool.shutdownNow();
        }
    }

    private static String truncate(String value, int maxChars) {
        if (value == null || maxChars <= 0 || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars);
    }

    private static String text(Object value) {
        return value instanceof String s ? s.trim() : "";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }
}
