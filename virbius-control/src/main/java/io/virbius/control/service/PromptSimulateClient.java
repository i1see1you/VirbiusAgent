package io.virbius.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PromptSimulateClient {

    private static final Logger log = LoggerFactory.getLogger(PromptSimulateClient.class);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String engineBaseUrl;

    public PromptSimulateClient(@Value("${virbius.engine.base-url:http://127.0.0.1:8082}") String engineBaseUrl) {
        this.engineBaseUrl = engineBaseUrl;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> simulate(String ruleId, String body, String reasonCode, String content) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("rule_id", ruleId);
        payload.put("body", body);
        payload.put("reason_code", reasonCode);
        payload.put("content", content);

        String url = engineBaseUrl + "/v1/simulate/prompt";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 300) {
            log.warn("prompt simulate failed status={} body={}", resp.statusCode(), resp.body());
            throw new IllegalStateException("engine prompt simulate HTTP " + resp.statusCode());
        }
        return mapper.readValue(resp.body(), Map.class);
    }
}
