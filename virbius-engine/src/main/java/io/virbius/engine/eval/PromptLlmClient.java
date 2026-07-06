package io.virbius.engine.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.virbius.engine.config.PromptLlmProperties;
import java.net.URI;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class PromptLlmClient {

    private static final Logger log = LoggerFactory.getLogger(PromptLlmClient.class);

    private final PromptLlmProperties props;
    private final ObjectMapper mapper;
    private final RestClient restClient;

    public PromptLlmClient(PromptLlmProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(props.timeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(props.timeoutMs()));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /** @return assistant text; empty on HTTP failure */
    public String complete(String promptText) {
        return completeDetail(promptText).content();
    }

    public CompleteResult completeDetail(String promptText) {
        String url = props.baseUrl().replaceAll("/+$", "") + props.apiPath();
        ObjectNode body = mapper.createObjectNode();
        body.put("model", props.model());
        body.put("stream", false);
        body.put("temperature", 0);
        ArrayNode messages = body.putArray("messages");
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", promptText);

        try {
            String response =
                    restClient
                            .post()
                            .uri(URI.create(url))
                            .header("Content-Type", "application/json")
                            .body(body.toString())
                            .retrieve()
                            .body(String.class);
            String content = extractAssistantContent(response);
            if (content == null || content.isBlank()) {
                String err = extractApiError(response);
                if (err != null) {
                    return new CompleteResult("", err);
                }
            }
            return new CompleteResult(content != null ? content : "", null);
        } catch (RestClientException e) {
            log.warn("prompt-llm request failed: {}", e.getMessage());
            return new CompleteResult("", extractApiError(e.getMessage()));
        }
    }

    public record CompleteResult(String content, String error) {}

    private String extractApiError(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            int jsonStart = raw.indexOf('{');
            if (jsonStart < 0) {
                return null;
            }
            JsonNode root = mapper.readTree(raw.substring(jsonStart));
            JsonNode err = root.path("error");
            if (err.isObject()) {
                String msg = err.path("message").asText(null);
                if (msg != null && !msg.isBlank()) {
                    return msg;
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        return null;
    }

    private String extractAssistantContent(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "";
        }
        try {
            JsonNode root = mapper.readTree(responseBody);
            JsonNode choices = root.path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                return choices.get(0).path("message").path("content").asText("");
            }
            return root.path("message").path("content").asText("");
        } catch (Exception e) {
            log.warn("prompt-llm response parse failed: {}", e.getMessage());
            return "";
        }
    }
}
