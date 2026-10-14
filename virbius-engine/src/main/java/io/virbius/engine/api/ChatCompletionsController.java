package io.virbius.engine.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * <b>Local LLM serving facade only</b> — an OpenAI-compatible proxy that
 * forwards to a local Ollama {@code /api/chat}. <b>No review, no cloud
 * models</b>: input/output review moved to {@code POST /v1/evaluate}
 * ({@code role=input}/{@code role=output}) plus the official SDK; cloud
 * models are called directly by the application. See PROTOCOL §2.6.5.
 *
 * <p>Requests are passed through verbatim (the legacy {@code virbius}
 * extension object, if present, is ignored).
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
    public ResponseEntity<StreamingResponseBody> chatCompletions(@RequestBody ObjectNode request) {
        String model = request.path("model").asText("virbiusguard");
        boolean stream = request.path("stream").asBoolean(false);
        if (stream) {
            return handleStreaming(request, model);
        }
        ObjectNode result = handleNonStreaming(request, model);
        byte[] json;
        try {
            json = mapper.writeValueAsBytes(result);
        } catch (Exception e) {
            json = ("{\"error\":\"" + e.getMessage() + "\"}").getBytes(StandardCharsets.UTF_8);
        }
        final byte[] body = json;
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(out -> out.write(body));
    }

    // ================================================================
    // Non-streaming passthrough
    // ================================================================

    private ObjectNode handleNonStreaming(ObjectNode request, String model) {
        try {
            ObjectNode ollamaReq = buildOllamaRequest(request, model, false);
            String responseBody = restClient.post()
                    .uri(ollamaChatUri())
                    .header("Content-Type", "application/json")
                    .body(ollamaReq.toString())
                    .retrieve()
                    .body(String.class);
            return convertToOpenAiResponse(responseBody, model);
        } catch (Exception e) {
            log.warn("chat-completions proxy failed: {}", e.getMessage());
            ObjectNode error = mapper.createObjectNode();
            error.put("error", e.getMessage());
            return error;
        }
    }

    // ================================================================
    // Streaming passthrough (Ollama NDJSON → OpenAI SSE)
    // ================================================================

    private ResponseEntity<StreamingResponseBody> handleStreaming(ObjectNode request, String model) {
        StreamingResponseBody body = out -> {
            String chunkId = "chatcmpl-" + System.currentTimeMillis();
            try {
                ObjectNode ollamaReq = buildOllamaRequest(request, model, true);
                restClient.post()
                        .uri(ollamaChatUri())
                        .header("Content-Type", "application/json")
                        .body(ollamaReq.toString())
                        .exchange((req, res) -> {
                            try (InputStream in = res.getBody();
                                 BufferedReader reader = new BufferedReader(
                                         new InputStreamReader(in, StandardCharsets.UTF_8))) {
                                String line;
                                while ((line = reader.readLine()) != null) {
                                    if (line.isBlank()) {
                                        continue;
                                    }
                                    JsonNode chunk = mapper.readTree(line);
                                    String delta = chunk.path("message").path("content").asText("");
                                    boolean done = chunk.path("done").asBoolean(false);
                                    if (!delta.isEmpty()) {
                                        writeChunk(out, openAiChunk(chunkId, model, delta, null));
                                    }
                                    if (done) {
                                        return null;
                                    }
                                }
                            }
                            return null;
                        });
                writeChunk(out, openAiChunk(chunkId, model, null, "stop"));
                out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (Exception e) {
                log.warn("chat-completions streaming failed: {}", e.getMessage());
                try {
                    ObjectNode error = mapper.createObjectNode();
                    error.put("error", "upstream_error: " + e.getMessage());
                    out.write(("data: " + mapper.writeValueAsString(error) + "\n\n")
                            .getBytes(StandardCharsets.UTF_8));
                    out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (Exception ignored) {
                    // client already gone
                }
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header("Cache-Control", "no-cache")
                .body(body);
    }

    // ================================================================
    // Helpers
    // ================================================================

    private ObjectNode buildOllamaRequest(ObjectNode request, String model, boolean stream) {
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
        return ollamaReq;
    }

    private URI ollamaChatUri() {
        return URI.create(ollamaBaseUrl.replaceAll("/+$", "") + "/api/chat");
    }

    private ObjectNode openAiChunk(String id, String model, String deltaContent, String finishReason) {
        ObjectNode chunk = mapper.createObjectNode();
        chunk.put("id", id);
        chunk.put("object", "chat.completion.chunk");
        chunk.put("created", System.currentTimeMillis() / 1000);
        chunk.put("model", model);
        ArrayNode choices = mapper.createArrayNode();
        ObjectNode choice = mapper.createObjectNode();
        choice.put("index", 0);
        ObjectNode delta = mapper.createObjectNode();
        if (deltaContent != null && !deltaContent.isEmpty()) {
            delta.put("content", deltaContent);
        }
        choice.set("delta", delta);
        if (finishReason != null) {
            choice.put("finish_reason", finishReason);
        } else {
            choice.putNull("finish_reason");
        }
        choices.add(choice);
        chunk.set("choices", choices);
        return chunk;
    }

    private void writeChunk(java.io.OutputStream out, ObjectNode chunk) throws java.io.IOException {
        out.write(("data: " + mapper.writeValueAsString(chunk) + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
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
