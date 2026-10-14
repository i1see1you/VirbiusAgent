package io.virbius.engine.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.virbius.engine.eval.EvaluateOrchestrator;
import io.virbius.engine.eval.EvaluateRequestDto;
import io.virbius.engine.eval.EvaluateResponseDto;
import io.virbius.engine.trace.TraceStepPublisher;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.web.client.RestClientException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * OpenAI-compatible /v1/chat/completions proxy that forwards to Ollama's /api/chat.
 *
 * <p>Used by VirbiusLLM engine as an LLM serving endpoint for the main engine's
 * PromptLlmClient (injection detection, prompt audit, etc.).
 *
 * <p><b>Output review (B1/B2) and chat trace steps (A1a)</b>: when the request
 * carries a {@code "virbius"} extension object, the proxy (1) emits an {@code
 * input} trace step for the last user message, (2) reviews the assistant's answer
 * through the standard evaluate pipeline ({@code role=output}) — whole response
 * for non-streaming, sliding windows for streaming — and (3) emits an {@code
 * output} trace step carrying the review decision. Requests without the extension
 * pass through unchanged, keeping internal guard-model calls (PromptLlmClient)
 * free of recursive review.
 *
 * <p>Extension shape:
 * <pre>{@code
 * "virbius": {
 *   "review_output": true,
 *   "tenant_id": "default",
 *   "session_id": "sess-1",
 *   "trace_id":   "...",          // optional; generated when absent
 *   "user_id":    "u-1",          // optional
 *   "app_id":     "my-agent"      // optional
 * }
 * }</pre>
 */
@RestController
public class ChatCompletionsController {

    private static final Logger log = LoggerFactory.getLogger(ChatCompletionsController.class);

    private final String ollamaBaseUrl;
    private final ObjectMapper mapper;
    private final RestClient restClient;
    private final EvaluateOrchestrator orchestrator;
    private final TraceStepPublisher tracePublisher;
    private final java.util.Set<String> skipModels;
    private final String refusalText;
    private final boolean failClosed;
    private final int streamWindowChars;
    private final int streamContextChars;
    private final boolean streamFirst;
    private final OutputContentRules outputContentRules;
    private final OutputActionResolver outputActionResolver;

    public ChatCompletionsController(
            @Value("${virbius.ollama-base-url:${VIRBIUS_OLLAMA_BASE_URL:http://127.0.0.1:11434}}") String ollamaBaseUrl,
            ObjectMapper mapper,
            EvaluateOrchestrator orchestrator,
            TraceStepPublisher tracePublisher,
            OutputContentRules outputContentRules,
            OutputActionResolver outputActionResolver,
            @Value("${virbius.output-review.skip-models:virbiusguard}") String skipModels,
            @Value("${virbius.output-review.refusal-text:抱歉，该内容已被安全策略拦截。}") String refusalText,
            @Value("${virbius.output-review.fail-closed:false}") boolean failClosed,
            @Value("${virbius.output-review.streaming.window-chars:200}") int streamWindowChars,
            @Value("${virbius.output-review.streaming.context-chars:50}") int streamContextChars,
            @Value("${virbius.output-review.streaming.stream-first:false}") boolean streamFirst) {
        this.ollamaBaseUrl = ollamaBaseUrl;
        this.mapper = mapper;
        this.orchestrator = orchestrator;
        this.tracePublisher = tracePublisher;
        this.outputContentRules = outputContentRules;
        this.outputActionResolver = outputActionResolver;
        this.skipModels = new java.util.HashSet<>(
                java.util.Arrays.asList(skipModels.toLowerCase().split("\\s*,\\s*")));
        this.refusalText = refusalText;
        this.failClosed = failClosed;
        this.streamWindowChars = streamWindowChars;
        this.streamContextChars = streamContextChars;
        this.streamFirst = streamFirst;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(120));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /** Parsed {@code virbius} extension. */
    private record ExtContext(
            boolean present, boolean review,
            String tenantId, String sessionId, String traceId, String userId, String appId) {
        static ExtContext of(JsonNode request, java.util.Set<String> skipModels, String model) {
            JsonNode ext = request.path("virbius");
            boolean present = ext.isObject();
            boolean review = present && ext.path("review_output").asBoolean(false)
                    && !skipModels.contains(model.toLowerCase());
            String tenantId = present ? ext.path("tenant_id").asText("default") : "default";
            String sessionId = present ? ext.path("session_id").asText("") : "";
            String traceId = present && !ext.path("trace_id").asText("").isBlank()
                    ? ext.path("trace_id").asText()
                    : "chat-" + UUID.randomUUID();
            String userId = present ? ext.path("user_id").asText("") : "";
            String appId = present ? ext.path("app_id").asText("chat") : "chat";
            return new ExtContext(present, review, tenantId, sessionId, traceId, userId, appId);
        }
    }

    @PostMapping("/v1/chat/completions")
    public ResponseEntity<StreamingResponseBody> chatCompletions(@RequestBody ObjectNode request) {
        String model = request.path("model").asText("virbiusguard");
        boolean stream = request.path("stream").asBoolean(false);
        ExtContext ext = ExtContext.of(request, skipModels, model);
        if (stream) {
            return handleStreaming(request, model, ext);
        }
        ObjectNode result = handleNonStreaming(request, model, ext);
        byte[] json;
        try {
            json = mapper.writeValueAsBytes(result);
        } catch (Exception e) {
            json = ("{\"error\":\"" + e.getMessage() + "\"}").getBytes(StandardCharsets.UTF_8);
        }
        byte[] body = json;
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(out -> out.write(body));
    }

    // ================================================================
    // Non-streaming
    // ================================================================

    private ObjectNode handleNonStreaming(ObjectNode request, String model, ExtContext ext) {
        try {
            ObjectNode ollamaReq = buildOllamaRequest(request, model, false);
            String lastUserMessage = lastUserMessage(request);

            if (ext.present()) {
                tracePublisher.publishInput(
                        ext.traceId(), ext.sessionId(), ext.tenantId(), "chat-in-" + ext.traceId(), 0,
                        ext.userId(), ext.appId(), "user", sha256Hex(lastUserMessage), lastUserMessage.length());
            }

            String responseBody = restClient.post()
                    .uri(ollamaChatUri())
                    .header("Content-Type", "application/json")
                    .body(ollamaReq.toString())
                    .retrieve()
                    .body(String.class);

            ObjectNode result = convertToOpenAiResponse(responseBody, model);

            if (ext.review()) {
                applyOutputReview(result, ext);
            } else if (ext.present()) {
                tracePublisher.publishOutput(
                        ext.traceId(), ext.sessionId(), ext.tenantId(), "chat-out-" + ext.traceId(), 1,
                        ext.userId(), ext.appId(), assistantContent(result), null, null, null, null);
            }

            return result;

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

    /**
     * Run the final assistant answer through the evaluate pipeline (role=output)
     * and translate the decision: block/deny → refusal replacement with
     * {@code finish_reason=content_filter}; anything else passes with a review
     * marker. Engine failures follow {@code virbius.output-review.fail-closed}.
     */
    private void applyOutputReview(ObjectNode result, ExtContext ext) {
        String content = assistantContent(result);
        ReviewVerdict verdict = reviewWindow(content, ext, false);
        String action = outputActionResolver.resolve(verdict.reasonCode(), verdict.blocked());

        ObjectNode review = result.putObject("virbius").putObject("review");
        review.put("action", action);
        review.put("enforced", verdict.blocked());
        if (verdict.reasonCode() != null) review.put("reason_code", verdict.reasonCode());
        if (verdict.ruleId() != null) review.put("rule_id", verdict.ruleId());
        if (verdict.riskScore() != null) review.put("risk_score", verdict.riskScore());

        JsonNode choices = result.path("choices");
        boolean hasChoice = choices.isArray() && !choices.isEmpty();
        String finalDecision = "allow";
        if (verdict.blocked() && hasChoice) {
            ObjectNode choice = (ObjectNode) choices.get(0);
            ObjectNode message = (ObjectNode) choice.path("message");
            switch (action) {
                case OutputActionResolver.OMIT -> {
                    // Drop the offending content entirely.
                    message.put("content", "");
                    choice.put("finish_reason", "content_filter");
                    finalDecision = "omit";
                }
                case OutputActionResolver.REDACT -> {
                    message.put("content", OutputMasker.redact(assistantContent(result)));
                    finalDecision = "redact";
                }
                case OutputActionResolver.WARN -> {
                    // Warn = permit but flag; no content change.
                    finalDecision = "warn";
                }
                default -> {
                    message.put("content", refusalText);
                    choice.put("finish_reason", "content_filter");
                    finalDecision = "block";
                }
            }
        }

        tracePublisher.publishOutput(
                ext.traceId(), ext.sessionId(), ext.tenantId(), "chat-out-" + ext.traceId(), 1,
                ext.userId(), ext.appId(), content,
                verdict.blocked() ? finalDecision : "allow",
                verdict.ruleId(), verdict.reasonCode(), verdict.riskScore());
    }

    // ================================================================
    // Streaming (B2)
    // ================================================================

    private ResponseEntity<StreamingResponseBody> handleStreaming(
            ObjectNode request, String model, ExtContext ext) {
        String lastUserMessage = lastUserMessage(request);
        StreamingResponseBody body = out -> {
            String chunkId = "chatcmpl-" + System.currentTimeMillis();
            // Mutable holders: the exchange lambda below can only capture finals.
            boolean[] blocked = {false};
            String[] blockReason = {null};
            String[] blockRuleId = {null};
            Integer[] blockRisk = {null};
            StringBuilder reviewed = new StringBuilder();
            try {
                if (ext.present()) {
                    tracePublisher.publishInput(
                            ext.traceId(), ext.sessionId(), ext.tenantId(), "chat-in-" + ext.traceId(), 0,
                            ext.userId(), ext.appId(), "user", sha256Hex(lastUserMessage), lastUserMessage.length());
                }
                ObjectNode ollamaReq = buildOllamaRequest(request, model, true);
                StreamReviewWindow window = new StreamReviewWindow(
                        streamWindowChars, streamContextChars);

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

                                    if (!ext.review()) {
                                        // Passthrough: no review requested.
                                        if (!delta.isEmpty()) {
                                            writeChunk(out, openAiChunk(chunkId, model, delta, null));
                                        }
                                        continue;
                                    }

                                    // stream_first=true: client sees tokens before the window runs.
                                    if (streamFirst && !delta.isEmpty()) {
                                        writeChunk(out, openAiChunk(chunkId, model, delta, null));
                                    }

                                    String due = window.due(delta, done);
                                    if (due == null) {
                                        continue;
                                    }
                                    ReviewVerdict verdict = reviewWindow(due, ext, true);
                                    if (verdict.blocked()) {
                                        window.reject();
                                        blocked[0] = true;
                                        blockReason[0] = verdict.reasonCode();
                                        blockRuleId[0] = verdict.ruleId();
                                        blockRisk[0] = verdict.riskScore();
                                        writeViolation(out, verdict.reasonCode());
                                        return null; // terminate the stream
                                    }
                                    String forward = window.accept();
                                    reviewed.append(forward);
                                    if (!streamFirst && !forward.isEmpty()) {
                                        // Forward only newly reviewed content
                                        // (never the carried context overlap).
                                        writeChunk(out, openAiChunk(chunkId, model, forward, null));
                                    }
                                }
                            }
                            return null;
                        });

                if (!blocked[0]) {
                    writeChunk(out, openAiChunk(chunkId, model, null, "stop"));
                    out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            } catch (Exception e) {
                log.warn("chat-completions streaming failed: {}", e.getMessage());
                try {
                    if (failClosed && ext.review() && !blocked[0]) {
                        blocked[0] = true;
                        blockReason[0] = "OUTPUT_REVIEW_ERROR";
                        writeViolation(out, blockReason[0]);
                    } else if (!blocked[0]) {
                        writeViolation(out, "upstream_error");
                    }
                } catch (Exception ignored) {
                    // client already gone
                }
            } finally {
                if (ext.present()) {
                    tracePublisher.publishOutput(
                            ext.traceId(), ext.sessionId(), ext.tenantId(), "chat-out-" + ext.traceId(), 1,
                            ext.userId(), ext.appId(), reviewed.toString(),
                            blocked[0] ? "block" : "allow", blockRuleId[0], blockReason[0], blockRisk[0]);
                }
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header("Cache-Control", "no-cache")
                .body(body);
    }

    // ================================================================
    // Shared helpers
    // ================================================================

    private record ReviewVerdict(
            String decision, boolean blocked, String ruleId, String reasonCode, Integer riskScore) {}

    /** Evaluate one piece of output text (whole response or one stream window). */
    private ReviewVerdict reviewWindow(String content, ExtContext ext, boolean streamChunk) {
        // B4: deterministic output rules first (code/SQL/template/XSS/credential).
        java.util.List<OutputContentRules.Hit> ruleHits = outputContentRules.scan(content);
        if (!ruleHits.isEmpty()) {
            OutputContentRules.Hit hit = ruleHits.get(0);
            return new ReviewVerdict("deny", true, null, hit.reasonCode(), null);
        }
        try {
            EvaluateRequestDto evalReq = new EvaluateRequestDto(
                    ext.tenantId(), "output", ext.sessionId(), content, streamChunk, null,
                    ext.traceId(), ext.userId(), null, Map.of(), null, null, null, null,
                    "chat_completion", null, 0, null);
            EvaluateResponseDto evalResp = orchestrator.evaluate(evalReq);
            boolean blocked = "deny".equalsIgnoreCase(evalResp.effectiveAction())
                    || "block".equalsIgnoreCase(evalResp.effectiveAction());
            return new ReviewVerdict(
                    evalResp.effectiveAction(), blocked,
                    evalResp.ruleId(), evalResp.reasonCode(), evalResp.maxRiskScore());
        } catch (Exception e) {
            log.warn("output review failed (fail-{}): {}", failClosed ? "closed" : "open", e.getMessage());
            if (failClosed) {
                return new ReviewVerdict("deny", true, null, "OUTPUT_REVIEW_ERROR", null);
            }
            return new ReviewVerdict("allow", false, null, null, null);
        }
    }

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

    private static String lastUserMessage(ObjectNode request) {
        String last = "";
        JsonNode srcMessages = request.path("messages");
        if (srcMessages.isArray()) {
            for (JsonNode msg : srcMessages) {
                if ("user".equals(msg.path("role").asText("user"))) {
                    last = msg.path("content").asText("");
                }
            }
        }
        return last;
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

    /** NeMo-style violation: an error payload terminates the stream. */
    private void writeViolation(java.io.OutputStream out, String reasonCode) throws java.io.IOException {
        ObjectNode error = mapper.createObjectNode();
        ObjectNode inner = error.putObject("error");
        inner.put("type", "guardrails_violation");
        inner.put("message", refusalText);
        if (reasonCode != null) {
            inner.put("reason_code", reasonCode);
        }
        out.write(("data: " + mapper.writeValueAsString(error) + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static String assistantContent(ObjectNode result) {
        JsonNode choices = result.path("choices");
        if (choices.isArray() && !choices.isEmpty()) {
            return choices.get(0).path("message").path("content").asText("");
        }
        return "";
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "";
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
