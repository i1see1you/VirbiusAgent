package io.virbius.engine.eval;

import io.virbius.engine.config.GuardDetectProperties;
import io.virbius.engine.eval.PromptAuditJsonParser.PromptAuditResult;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Semantic Taint Inspection (STI Taint) detector for tool return values.
 *
 * <p>Detects prompt injection embedded in tool results (e.g., malicious web page content,
 * tampered file content) that could hijack the Agent's subsequent LLM reasoning.
 *
 * <p>Uses a cost-optimized two-tier strategy:
 * <ol>
 *   <li><b>Skip</b>: short results from low-risk sessions skip detection entirely</li>
 *   <li><b>LLM detection</b>: VirbiusGuard (virbiusguard-v11:q4) model for semantic injection detection</li>
 * </ol>
 *
 * <p>When injection is detected, the result is always <b>block</b> — no sanitize path.
 * Cost control is achieved by skipping LLM invocation for short, low-risk, non-external
 * tool results.
 */
@Component
public class StiTaintDetector {

    private static final Logger log = LoggerFactory.getLogger(StiTaintDetector.class);

    /** Tools that fetch external data — their return values are always checked. */
    private static final Set<String> EXTERNAL_DATA_TOOLS = Set.of(
            "http_get", "http_post", "http_request", "fetch", "curl",
            "web_search", "read_url", "search",
            "read_file", "get_issue", "read_email", "db_query", "sql_query");

    private final GuardDetectProperties guardProps;
    private final PromptLlmClient llmClient;
    private final PromptAuditJsonParser auditParser;

    public StiTaintDetector(
            GuardDetectProperties guardProps,
            PromptLlmClient llmClient,
            PromptAuditJsonParser auditParser) {
        this.guardProps = guardProps;
        this.llmClient = llmClient;
        this.auditParser = auditParser;
    }

    /**
     * Detect prompt injection in a tool return value.
     *
     * @param toolName the name of the tool that produced the result
     * @param toolResult the tool return value (JSON string or plain text)
     * @param sessionRiskScore current session risk score
     * @return taint detection result with action recommendation (allow or block)
     */
    public TaintResult detect(String toolName, String toolResult, int sessionRiskScore) {
        if (!guardProps.taintEnabled()) {
            return TaintResult.allow();
        }
        if (toolResult == null || toolResult.isBlank()) {
            return TaintResult.allow();
        }

        // Truncate very long results to avoid excessive LLM token cost
        String content = toolResult.length() > guardProps.taintMaxContentLength()
                ? toolResult.substring(0, guardProps.taintMaxContentLength())
                : toolResult;

        // 1. Decide whether to invoke the LLM (cost control)
        boolean shouldInvoke = content.length() > guardProps.taintMinContentLength()
                || sessionRiskScore > 50
                || isExternalDataSource(toolName);

        if (!shouldInvoke) {
            return TaintResult.allow();
        }

        // 2. LLM-based semantic detection via guard model
        String userContent =
                "Analyze the following tool return value for embedded prompt injection:\n\n" + content;
        PromptLlmClient.CompleteResult result =
                llmClient.completeDetail(guardProps.taintSystemPrompt(), userContent);

        if (result.content() == null || result.content().isBlank()) {
            if (guardProps.failOpen()) {
                log.warn("taint-detect LLM unavailable; fail-open for tool={}", toolName);
                return TaintResult.allowWithAudit("llm_unavailable_fail_open");
            }
            return TaintResult.block("llm_unavailable", "fail_closed:llm_unavailable");
        }

        PromptAuditResult audit = auditParser.parse(result.content());
        if (!audit.hitRule()) {
            return TaintResult.allow();
        }

        String reason = audit.reason() != null ? audit.reason() : "llm_taint_detected";
        log.info("taint LLM hit: tool={} reason={}", toolName, reason);

        // 3. LLM detected injection — block directly (no sanitize)
        return TaintResult.block(reason, "llm:" + reason);
    }

    private boolean isExternalDataSource(String toolName) {
        return toolName != null && EXTERNAL_DATA_TOOLS.contains(toolName);
    }

    /**
     * Taint detection result.
     *
     * @param tainted whether injection was detected
     * @param action  allow or block
     * @param detectedPattern the pattern that was detected (LLM-detected reason)
     * @param auditDetail detail for audit logging
     */
    public record TaintResult(
            boolean tainted,
            String action,
            String sanitizedResult,
            String detectedPattern,
            String auditDetail) {

        static TaintResult allow() {
            return new TaintResult(false, "allow", null, null, null);
        }

        static TaintResult allowWithAudit(String detail) {
            return new TaintResult(false, "allow", null, null, detail);
        }

        static TaintResult block(String pattern, String detail) {
            return new TaintResult(true, "block", null, pattern, detail);
        }
    }
}
