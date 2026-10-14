package io.virbius.engine.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes decision-chain trace steps ({@code input} / {@code output}) from the
 * engine side to the Control plane, mirroring the MCP proxy's trace event format
 * (see {@code virbius-mcp-proxy/src/trace_collector.rs}).
 *
 * <p>The proxy only observes tool_call/tool_result; the agent's chat input and
 * final answer are only visible where the LLM traffic terminates — here.
 *
 * <p>Best-effort and non-blocking: publish failures are logged and swallowed so
 * tracing never breaks the request path. If Kafka is unavailable the publisher
 * degrades to a no-op.
 */
@Component
public class TraceStepPublisher {

    private static final Logger log = LoggerFactory.getLogger(TraceStepPublisher.class);
    private static final int PREVIEW_MAX = 2048;

    private final ObjectMapper mapper = new ObjectMapper();
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String topic;
    private final boolean enabled;

    public TraceStepPublisher(
            ObjectProvider<KafkaTemplate<String, String>> kafkaTemplateProvider,
            @Value("${trace.ingest.kafka.topic:virbius-trace-events}") String topic,
            @Value("${virbius.trace.publish-enabled:true}") boolean enabled) {
        this.kafkaTemplate = kafkaTemplateProvider.getIfAvailable();
        this.topic = topic;
        this.enabled = enabled;
        if (this.kafkaTemplate == null) {
            log.warn("KafkaTemplate not available; trace step publishing disabled");
        }
    }

    /**
     * Publish a chat {@code input} step (the last user message entering the model).
     *
     * @param contentHash sha-256 hex of the content (never the raw content)
     */
    public void publishInput(
            String traceId, String sessionId, String tenantId, String stepId, int stepSeq,
            String userId, String appId, String inputRole, String contentHash, int contentSize) {
        Map<String, Object> e = base(traceId, sessionId, tenantId, stepId, stepSeq, "input", userId, appId);
        e.put("input_role", inputRole != null ? inputRole : "user");
        e.put("input_content_hash", contentHash);
        e.put("content_size", contentSize);
        publish(e);
    }

    /**
     * Publish a chat {@code output} step (the model's final answer) with the
     * review decision attached.
     */
    public void publishOutput(
            String traceId, String sessionId, String tenantId, String stepId, int stepSeq,
            String userId, String appId, String contentPreview,
            String toolDecision, String ruleId, String reasonCode, Integer riskScore) {
        Map<String, Object> e = base(traceId, sessionId, tenantId, stepId, stepSeq, "output", userId, appId);
        if (contentPreview != null) {
            e.put("tool_result_preview",
                    contentPreview.length() > PREVIEW_MAX ? contentPreview.substring(0, PREVIEW_MAX) : contentPreview);
            e.put("content_size", contentPreview.length());
        }
        if (toolDecision != null && !toolDecision.isBlank()) {
            e.put("tool_decision", toolDecision);
        }
        if (ruleId != null && !ruleId.isBlank()) {
            e.put("rule_id", ruleId);
        }
        if (reasonCode != null && !reasonCode.isBlank()) {
            e.put("reason_code", reasonCode);
        }
        if (riskScore != null) {
            e.put("risk_score", riskScore);
        }
        publish(e);
    }

    private Map<String, Object> base(
            String traceId, String sessionId, String tenantId, String stepId, int stepSeq,
            String stepType, String userId, String appId) {
        Map<String, Object> e = new HashMap<>();
        e.put("trace_id", traceId);
        e.put("session_id", sessionId);
        e.put("tenant_id", tenantId != null && !tenantId.isBlank() ? tenantId : "default");
        e.put("step_id", stepId);
        e.put("step_seq", stepSeq);
        e.put("step_type", stepType);
        e.put("layer", "cloud");
        if (userId != null && !userId.isBlank()) {
            e.put("user_id", userId);
        }
        e.put("app_id", appId != null && !appId.isBlank() ? appId : "chat");
        e.put("content_sampled", true);
        e.put("dlp_masked", false);
        e.put("occurred_at", Instant.now().toString());
        return e;
    }

    private void publish(Map<String, Object> event) {
        if (!enabled || kafkaTemplate == null) {
            return;
        }
        try {
            kafkaTemplate.send(topic, mapper.writeValueAsString(event));
        } catch (Exception ex) {
            log.warn("trace step publish failed (step_type={}): {}", event.get("step_type"), ex.getMessage());
        }
    }
}
