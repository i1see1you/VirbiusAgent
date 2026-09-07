package io.virbius.engine.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import io.virbius.engine.config.FileProperties;
import io.virbius.policy.ImageHasher;
import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Calls OvisOCR2 (via Ollama native /api/chat) to extract text from an image.
 *
 * <p>Protected by a content-hash result cache (repeated images skip the model)
 * and a circuit breaker (endpoint outage degrades to BREAKER_OPEN instead of
 * piling up requests). Failure results are cached with a short TTL so poison
 * retries cannot hammer the endpoint.
 */
@Component
public class OcrClient {

    private static final Logger log = LoggerFactory.getLogger(OcrClient.class);

    private static final String EXTRACT_PROMPT =
            "提取图片中的全部文字，只输出纯文本原文。";

    private static final Duration FAILURE_CACHE_TTL = Duration.ofSeconds(60);

    private final FileProperties props;
    private final ObjectMapper mapper;
    private final RestClient restClient;
    private final OcrCircuitBreaker breaker;
    private final Cache<String, OcrResult> cache;

    public OcrClient(FileProperties props, ObjectMapper mapper, OcrCircuitBreaker breaker) {
        this.props = props;
        this.mapper = mapper;
        this.breaker = breaker;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(props.ocrTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(props.ocrTimeoutMs()));
        this.restClient = RestClient.builder().requestFactory(factory).build();
        Duration successTtl = Duration.ofMinutes(props.ocrCacheTtlMinutes());
        this.cache = Caffeine.newBuilder()
                .maximumSize(props.ocrCacheMaxEntries())
                .expireAfter(new Expiry<String, OcrResult>() {
                    @Override
                    public long expireAfterCreate(String key, OcrResult value, long now) {
                        return ttlFor(value, successTtl).toNanos();
                    }

                    @Override
                    public long expireAfterUpdate(String key, OcrResult value,
                                                  long now, long currentDurationNanos) {
                        return ttlFor(value, successTtl).toNanos();
                    }

                    @Override
                    public long expireAfterRead(String key, OcrResult value,
                                                long now, long currentDurationNanos) {
                        return ttlFor(value, successTtl).toNanos();
                    }
                })
                .build();
    }

    /**
     * Extract text from an image. Never throws; the status distinguishes
     * "no text found" (EMPTY) from "OCR unavailable" (FAILED / BREAKER_OPEN).
     */
    public OcrResult extract(byte[] imageBytes) {
        String key = ImageHasher.sha256Hex(imageBytes);
        OcrResult cached = cache.getIfPresent(key);
        if (cached != null) {
            log.debug("ocr cache hit");
            return cached;
        }
        if (!breaker.allowRequest()) {
            return OcrResult.breakerOpen();
        }
        OcrResult result = callRemote(imageBytes);
        if (result.status() != OcrResult.Status.BREAKER_OPEN) {
            cache.put(key, result);
        }
        return result;
    }

    private OcrResult callRemote(byte[] imageBytes) {
        String url = props.ocrBaseUrl().replaceAll("/+$", "") + "/api/chat";
        ObjectNode body = mapper.createObjectNode();
        body.put("model", props.ocrModel());
        body.put("stream", false);
        body.put("temperature", 0);
        body.put("think", false);
        body.putObject("options").put("num_predict", 400);
        ArrayNode messages = body.putArray("messages");
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", EXTRACT_PROMPT);
        ArrayNode images = user.putArray("images");
        images.add(Base64.getEncoder().encodeToString(imageBytes));

        try {
            String resp = restClient.post()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .body(body.toString())
                    .retrieve()
                    .body(String.class);
            String text = mapper.readTree(resp).path("message").path("content").asText("");
            breaker.recordSuccess();
            return text.isBlank() ? OcrResult.empty() : OcrResult.ok(text);
        } catch (Exception e) {
            log.warn("ocr extract failed: {}", e.getMessage());
            breaker.recordFailure();
            return OcrResult.failed();
        }
    }

    private static Duration ttlFor(OcrResult value, Duration successTtl) {
        return value.status() == OcrResult.Status.FAILED ? FAILURE_CACHE_TTL : successTtl;
    }
}
