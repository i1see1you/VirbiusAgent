package io.virbius.engine.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * OpenAI-compatible /v1/chat/completions proxy that forwards to Ollama's /api/chat.
 *
 * <p>Used by VirbiusLLM engine as an LLM serving endpoint for the main engine's
 * PromptLlmClient (injection detection, prompt audit, etc.).
 */
@RestController
public class ChatCompletionsController {

    private static final Logger log = LoggerFactory.getLogger(ChatCompletionsController.class);

    private final String ollamaBaseUrl;
    private final ObjectMapper mapper;
    private final RestClient restClient;

    public ChatCompletionsController(
            @Value("${virbius.ollama-base-url:${VIRBIUS_OLLAMA_BASE_URL:http://127.0.0.1:11434}}") String ollamaBaseUrl,
            ObjectMapper mapper) {
        this.ollamaBaseUrl = ollamaBaseUrl;
        this.mapper = mapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(120));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @PostMapping("/v1/chat/completions")
    public ObjectNode chatCompletions(@RequestBody ObjectNode request) {
        try {
            String model = request.path("model").asText("virbiusguard");
            boolean stream = request.path("stream").asBoolean(false);

            ObjectNode ollamaReq = mapper.createObjectNode();
            ollamaReq.put("model", model);
            ollamaReq.put("stream", stream);

            ArrayNode messages = mapper.createArrayNode();
            JsonNode srcMessages = request.path("messages");
            if (srcMessages.isArray()) {
                for (JsonNode msg : srcMessages) {
                    ObjectNode m = mapper.createObjectNode();
                    m.put("role", msg.path("role").asText("user"));
                    m.put("content", msg.path("content").asText(""));
                    messages.add(m);
                }
            }
            ollamaReq.set("messages", messages);

            if (request.has("temperature")) {
                ollamaReq.put("temperature", request.path("temperature").asDouble(0));
            }
            if (request.has("max_tokens")) {
                ollamaReq.put("num_predict", request.path("max_tokens").asInt(512));
            }

            String url = ollamaBaseUrl.replaceAll("/+$", "") + "/api/chat";
            String responseBody = restClient.post()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .body(ollamaReq.toString())
                    .retrieve()
                    .body(String.class);

            return convertToOpenAiResponse(responseBody, model);

        } catch (RestClientException e) {
            log.warn("chat-completions proxy failed: {}", e.getMessage());
            ObjectNode error = mapper.createObjectNode();
            error.put("error", e.getMessage());
            return error;
        } catch (Exception e) {
            log.error("chat-completions unexpected error", e);
            ObjectNode error = mapper.createObjectNode();
            error.put("error", e.getMessage());
            return error;
        }
    }

    private ObjectNode convertToOpenAiResponse(String ollamaResponse, String model) {
        try {
            JsonNode ollama = mapper.readTree(ollamaResponse);
            ObjectNode result = mapper.createObjectNode();
            result.put("id", "chatcmpl-" + System.currentTimeMillis());
            result.put("object", "chat.completion");
            result.put("created", System.currentTimeMillis() / 1000);
            result.put("model", model);

            ArrayNode choices = mapper.createArrayNode();
            ObjectNode choice = mapper.createObjectNode();
            choice.put("index", 0);

            ObjectNode message = mapper.createObjectNode();
            message.put("role", "assistant");
            message.put("content", ollama.path("message").path("content").asText(""));
            choice.set("message", message);
            choice.put("finish_reason", "stop");
            choices.add(choice);
            result.set("choices", choices);

            ObjectNode usage = mapper.createObjectNode();
            usage.put("prompt_tokens", ollama.path("prompt_eval_count").asInt(0));
            usage.put("completion_tokens", ollama.path("eval_count").asInt(0));
            usage.put("total_tokens", ollama.path("prompt_eval_count").asInt(0) + ollama.path("eval_count").asInt(0));
            result.set("usage", usage);

            return result;
        } catch (Exception e) {
            ObjectNode fallback = mapper.createObjectNode();
            fallback.put("error", "Failed to parse Ollama response: " + e.getMessage());
            return fallback;
        }
    }
}
