package io.virbius.groovy.l3;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lightweight HTTP client for Groovy L3 scripts to call external ML model
 * serving endpoints (BERT, XGBoost, scikit-learn, etc.).
 *
 * <p>Usage from Groovy:
 * <pre>{@code
 *   def result = mlPredict("http://127.0.0.1:8502/v1/classify",
 *                          [text: ctx.vars.content])
 *   return result.score > 0.8 && result.label == "toxic"
 * }</pre>
 */
public final class MlModelUtil {

    private static final Logger log = LoggerFactory.getLogger(MlModelUtil.class);

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final HttpClient http;
    private final long timeoutMs;

    public MlModelUtil() {
        this(5000);
    }

    public MlModelUtil(long timeoutMs) {
        this.timeoutMs = timeoutMs;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();
    }

    /**
     * POST features to model serving endpoint, return parsed response.
     *
     * @param url      model serving URL (e.g. http://127.0.0.1:8502/v1/classify)
     * @param features input features as key-value map, serialized to JSON body
     * @return parsed response map; on error returns {@code {label: "error", score: 0.0}}
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> predict(String url, Map<String, Object> features) {
        try {
            String body = mapper.writeValueAsString(features != null ? features : Map.of());
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                log.warn("mlPredict {} returned status {}", url, response.statusCode());
                return errorResult("http_" + response.statusCode());
            }
            String raw = response.body();
            if (raw == null || raw.isBlank()) {
                return errorResult("empty_response");
            }
            Object parsed = mapper.readValue(raw, Object.class);
            if (parsed instanceof Map) {
                return new HashMap<>((Map<String, Object>) parsed);
            }
            return Map.of("score", (Object) 0.0, "raw", parsed);
        } catch (Exception e) {
            log.warn("mlPredict {} failed: {}", url, e.getMessage());
            return errorResult(e.getMessage());
        }
    }

    private static Map<String, Object> errorResult(String detail) {
        Map<String, Object> m = new HashMap<>();
        m.put("label", "error");
        m.put("score", 0.0);
        m.put("error", detail != null ? detail : "unknown");
        return m;
    }
}
