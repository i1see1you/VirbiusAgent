package io.virbius.engine.eval.judge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;
import io.virbius.engine.config.PromptJudgeProperties;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Jev /v1/systemone client. Wire contract (verified against the NeoHorse
 * official runtime on 2026-11-11, see docs/judge-protocol-v1.md):
 *
 * <pre>
 * POST {base-url}/v1/systemone
 * {"model": "...", "state": {"content": "..."},
 *  "questions": {"&lt;ruleId&gt;": {"type": "noul", "instructions": "..."}}}
 * 200 -&gt; {"model": "...", "answers": {"&lt;ruleId&gt;": {"type": "noul", "noul": 0.98}},
 *         "usage": {"input_tokens": 32, "output_tokens": 30}}
 * errors -&gt; HTTP status + {"detail": "..."}; 429/529 = busy with Retry-After
 * </pre>
 *
 * The path is a protocol constant, not configuration. The vendor state limit is
 * in tokens (per-backend {@code maxStateTokens}), so oversized content is
 * truncated head+tail to a CJK-aware token budget (and the {@code maxContentChars}
 * ceiling) before being sent — a char-only budget overflows on Chinese, where
 * ~1 char ≈ 1 token, letting fail_open rules be bypassed by oversized inputs.
 */
abstract class AbstractHttpJudgeBackend implements PromptJudgeBackend {

    private static final Logger log = LoggerFactory.getLogger(AbstractHttpJudgeBackend.class);

    static final String PATH = "/v1/systemone";
    private static final long MAX_RETRY_AFTER_MS = 2000L;
    private static final int MARKER_TOKEN_RESERVE = 16;

    private final String id;
    private final String baseUrl;
    private final String model;
    private final String authToken;
    private final ObjectMapper mapper;
    private final RestClient client;
    private final MeterRegistry registry;
    private final int maxContentChars;

    protected AbstractHttpJudgeBackend(
            String id,
            PromptJudgeProperties properties,
            ObjectMapper mapper,
            MeterRegistry registry) {
        this(id, properties, mapper, registry, buildClient(properties.timeoutMs()));
    }

    AbstractHttpJudgeBackend(
            String id,
            PromptJudgeProperties properties,
            ObjectMapper mapper,
            MeterRegistry registry,
            RestClient client) {
        PromptJudgeProperties.Backend config = properties.backends().getOrDefault(id,
                new PromptJudgeProperties.Backend(null, false, null, null, 0));
        this.id = id;
        this.baseUrl = config.baseUrl() == null ? "" : config.baseUrl().replaceAll("/+$", "");
        this.model = config.model() == null ? "" : config.model();
        this.authToken = config.authToken() == null ? "" : config.authToken();
        this.mapper = mapper;
        this.client = client;
        this.registry = registry;
        this.maxContentChars = properties.maxContentChars();
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public JudgeResult judge(JudgeRequest request) {
        long started = System.nanoTime();
        try {
            return doJudge(request, started);
        } finally {
            registry.summary("virbius.prompt.judge.latency.ms", "backend", id)
                    .record(elapsedMs(started));
        }
    }

    private JudgeResult doJudge(JudgeRequest request, long started) {
        if (request == null || request.content() == null || request.content().isBlank()) {
            return JudgeResult.failed("content required", elapsedMs(started));
        }
        if (request.rules().isEmpty()) {
            return new JudgeResult(Map.of(), elapsedMs(started), null);
        }
        if (model.isBlank()) {
            log.warn("prompt judge backend {} has no model configured", id);
            return JudgeResult.failed("prompt judge backend " + id + " has no model configured",
                    elapsedMs(started));
        }

        String content = truncateContent(request.content());
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.putObject("state").put("content", content);
        addQuestions(body, request.rules());
        registry.summary("virbius.prompt.judge.batch.questions", "backend", id)
                .record(request.rules().size());

        PostOutcome outcome = post(body, request.allowBusyRetry());
        if (outcome.error() != null) {
            return JudgeResult.failed(outcome.error(), elapsedMs(started));
        }
        return parse(outcome.raw(), request.rules(), started);
    }

    private PostOutcome post(ObjectNode body, boolean allowBusyRetry) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return new PostOutcome(client
                        .post()
                        .uri(URI.create(baseUrl + PATH))
                        .headers(headers -> {
                            headers.set("Content-Type", "application/json");
                            if (!authToken.isBlank()) {
                                headers.setBearerAuth(authToken);
                            }
                        })
                        .body(body.toString())
                        .retrieve()
                        .body(String.class), null);
            } catch (RestClientResponseException e) {
                int status = e.getStatusCode().value();
                if ((status == 429 || status == 529) && allowBusyRetry && attempt == 0) {
                    long delay = retryAfterMs(e.getResponseHeaders());
                    log.warn("prompt judge backend {} busy (HTTP {}); retrying in {} ms",
                            id, status, delay);
                    sleepQuietly(delay);
                    continue;
                }
                if (status == 429 || status == 529) {
                    result("busy");
                    return new PostOutcome(null, "HTTP " + status + ": " + errorDetail(e));
                }
                result(status >= 400 && status < 500 ? "http_4xx" : "http_5xx");
                log.warn("prompt judge backend {} failed: HTTP {}: {}", id, status,
                        errorDetail(e));
                return new PostOutcome(null, "HTTP " + status + ": " + errorDetail(e));
            } catch (RestClientException | IllegalArgumentException e) {
                result("timeout");
                log.warn("prompt judge backend {} failed: {}", id, e.getMessage());
                return new PostOutcome(null, e.getMessage());
            }
        }
        result("busy");
        log.warn("prompt judge backend {} still busy after retry", id);
        return new PostOutcome(null, "model busy after retry");
    }

    private JudgeResult parse(String raw, List<JudgeRule> rules, long started) {
        if (raw == null || raw.isBlank()) {
            result("contract");
            return JudgeResult.failed("empty judge response", elapsedMs(started));
        }
        JsonNode root;
        try {
            root = mapper.readTree(raw);
        } catch (Exception e) {
            result("contract");
            return JudgeResult.failed("invalid judge response: " + e.getMessage(),
                    elapsedMs(started));
        }
        JsonNode answers = root.path("answers");
        if (!answers.isObject()) {
            result("contract");
            return JudgeResult.failed("invalid judge response: answers missing",
                    elapsedMs(started));
        }
        Map<String, JudgeAnswer> out = new LinkedHashMap<>();
        for (JudgeRule rule : rules) {
            JsonNode answer = answers.path(rule.id());
            if (!answer.isObject()) {
                out.put(rule.id(), JudgeAnswer.failed("missing answer"));
                continue;
            }
            JsonNode noul = answer.path("noul");
            if (!noul.isNumber()) {
                out.put(rule.id(), JudgeAnswer.failed("missing noul"));
                continue;
            }
            double probability = noul.asDouble();
            if (!Double.isFinite(probability)) {
                out.put(rule.id(), JudgeAnswer.failed("invalid noul"));
                continue;
            }
            if (probability < 0.0 || probability > 1.0) {
                log.warn("prompt judge backend {} rule {} noul out of range: {}; clamped",
                        id, rule.id(), probability);
                probability = Math.max(0.0, Math.min(1.0, probability));
            }
            registry.summary("virbius.prompt.judge.noul", "backend", id).record(probability);
            out.put(rule.id(), JudgeAnswer.probability(probability));
        }
        result("ok");
        return new JudgeResult(out, elapsedMs(started), null);
    }

    private void addQuestions(ObjectNode body, List<JudgeRule> rules) {
        ObjectNode questions = body.putObject("questions");
        for (JudgeRule rule : rules) {
            ObjectNode question = questions.putObject(rule.id());
            question.put("type", rule.type());
            question.put("instructions", rule.instructions());
        }
    }

    private String truncateContent(String content) {
        int tokenBudget = stateTokenBudget();
        boolean overChars = maxContentChars > 0 && content.length() > maxContentChars;
        boolean overTokens = tokenBudget > 0 && estimateTokens(content) > tokenBudget;
        if (!overChars && !overTokens) {
            return content;
        }
        int limit = overChars ? Math.min(maxContentChars, content.length()) : content.length();
        if (overTokens) {
            int est = estimateTokens(content);
            long scaled = (long) limit * Math.max(0, tokenBudget - MARKER_TOKEN_RESERVE) / est;
            limit = (int) Math.min(limit, scaled);
        }
        String kept = headTail(content, limit);
        while (tokenBudget > 0 && estimateTokens(kept) > tokenBudget && limit > 0) {
            limit = limit * 3 / 4;
            kept = headTail(content, limit);
        }
        registry.counter("virbius.prompt.judge.content.truncated", "backend", id).increment();
        log.warn("prompt judge backend {} content truncated: {} -> {} chars (tokenBudget={})",
                id, content.length(), kept.length(), tokenBudget);
        return kept;
    }

    private int stateTokenBudget() {
        int maxStateTokens = capabilities().maxStateTokens();
        // 25% headroom absorbs CJK tokenization variance (~1 token/char) and the marker.
        return maxStateTokens <= 0 ? 0 : (int) (maxStateTokens * 0.75);
    }

    private static String headTail(String content, int limit) {
        int half = Math.max(0, limit / 2);
        String marker = "\n…[truncated " + (content.length() - 2 * half) + " chars]…\n";
        if (half == 0) {
            return marker;
        }
        return content.substring(0, half) + marker + content.substring(content.length() - half);
    }

    // Dependency-free token estimate: CJK ~1 token/char, other scripts ~4 chars/token.
    private static int estimateTokens(String s) {
        int cjk = 0;
        for (int i = 0; i < s.length(); i++) {
            if (isCjk(s.charAt(i))) {
                cjk++;
            }
        }
        return cjk + (s.length() - cjk + 3) / 4;
    }

    private static boolean isCjk(char c) {
        if ((c >= 0x3000 && c <= 0x303F) || (c >= 0xFF00 && c <= 0xFFEF)) {
            return true;
        }
        Character.UnicodeScript script = Character.UnicodeScript.of(c);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }

    private void result(String tag) {
        registry.counter("virbius.prompt.judge.requests", "backend", id, "result", tag)
                .increment();
    }

    private String errorDetail(RestClientResponseException e) {
        try {
            JsonNode detail = mapper.readTree(e.getResponseBodyAsString()).path("detail");
            if (detail.isTextual() && !detail.asText().isBlank()) {
                return detail.asText();
            }
        } catch (Exception ignored) {
            // fall through to the raw body
        }
        String body = e.getResponseBodyAsString();
        return body == null ? "" : body.substring(0, Math.min(body.length(), 200));
    }

    private static long retryAfterMs(HttpHeaders headers) {
        if (headers != null) {
            String value = headers.getFirst("Retry-After");
            if (value != null) {
                try {
                    return Math.max(0L, Math.min(MAX_RETRY_AFTER_MS,
                            (long) (Double.parseDouble(value) * 1000)));
                } catch (NumberFormatException ignored) {
                    // fall through to the default
                }
            }
        }
        return 1000L;
    }

    private static void sleepQuietly(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    private static RestClient buildClient(int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        return RestClient.builder().requestFactory(factory).build();
    }

    private record PostOutcome(String raw, String error) {}
}
