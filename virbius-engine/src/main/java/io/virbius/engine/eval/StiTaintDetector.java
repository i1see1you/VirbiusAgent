package io.virbius.engine.eval;

import io.virbius.engine.config.GuardDetectProperties;
import io.virbius.engine.config.PromptLlmProperties;
import io.virbius.engine.eval.PromptAuditJsonParser.PromptAuditResult;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Semantic Taint Inspection (STI Taint) detector for tool return values.
 *
 * <p>Detects prompt injection embedded in tool results (e.g., malicious web page content,
 * tampered file content) that could hijack the Agent's subsequent LLM reasoning.
 *
 * <p>Uses a cost-optimized three-tier strategy:
 * <ol>
 *   <li><b>Skip</b>: short results with no markers from low-risk sessions skip detection entirely</li>
 *   <li><b>Regex pre-filter</b>: fast pattern matching for known injection markers</li>
 *   <li><b>LLM detection</b>: qwen3guard:0.6b model for semantic injection detection</li>
 * </ol>
 */
@Component
public class StiTaintDetector {

    private static final Logger log = LoggerFactory.getLogger(StiTaintDetector.class);

    private static final Pattern[] INJECTION_MARKERS = {
        Pattern.compile("(?i)ignore\\s+(previous|above|prior)\\s+instructions"),
        Pattern.compile("(?i)you\\s+are\\s+now\\s+(DAN|developer\\s+mode|jailbreak)"),
        Pattern.compile("(?i)<\\s*system\\s*>|<\\s*instruction\\s*>"),
        Pattern.compile("(?i)forget\\s+(everything|all|previous)"),
        Pattern.compile("(?i)disregard\\s+(prior|above|previous)"),
        Pattern.compile("(?i)system\\s*prompt|reveal\\s+your\\s+instructions"),
        Pattern.compile("(?i)act\\s+as\\s+(if\\s+you\\s+(have|are)|a\\s+different)"),
        Pattern.compile("(?i)\\[\\s*system\\s*\\]|\\[\\s*assistant\\s*\\]"),
    };

    /** Tools that fetch external data — their return values are always checked. */
    private static final Set<String> EXTERNAL_DATA_TOOLS = Set.of(
            "http_get", "http_post", "http_request", "fetch", "curl",
            "web_search", "read_url", "search",
            "read_file", "get_issue", "read_email", "db_query", "sql_query");

    private final GuardDetectProperties guardProps;
    private final PromptLlmProperties llmProps;
    private final PromptLlmClient llmClient;
    private final PromptAuditJsonParser auditParser;

    public StiTaintDetector(
            GuardDetectProperties guardProps,
            PromptLlmProperties llmProps,
            PromptLlmClient llmClient,
            PromptAuditJsonParser auditParser) {
        this.guardProps = guardProps;
        this.llmProps = llmProps;
        this.llmClient = llmClient;
        this.auditParser = auditParser;
    }

    /**
     * Detect prompt injection in a tool return value.
     *
     * @param toolName the name of the tool that produced the result
     * @param toolResult the tool return value (JSON string or plain text)
     * @param sessionRiskScore current session risk score
     * @return taint detection result with action recommendation
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

        // 1. Regex pre-filter
        String regexHit = regexCheck(content);
        boolean markerHit = regexHit != null;

        // 2. Decide whether to invoke the LLM (cost control)
        boolean shouldInvoke = content.length() > guardProps.taintMinContentLength()
                || markerHit
                || sessionRiskScore > 50
                || isExternalDataSource(toolName);

        if (!shouldInvoke) {
            return TaintResult.allow();
        }

        // 3. If regex hit and LLM is not strictly needed for high-confidence patterns,
        //    return block directly for the most dangerous patterns
        if (markerHit && sessionRiskScore > 60) {
            log.info("taint regex hit (high-risk session, direct block): tool={} pattern={}",
                    toolName, regexHit);
            return TaintResult.block(regexHit, "regex_match:" + regexHit);
        }

        // 4. LLM-based semantic detection via qwen3guard
        String prompt = buildTaintPrompt(content);
        PromptLlmClient.CompleteResult result = llmClient.completeDetail(prompt);

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

        // 5. Decide action based on regex+LLM consensus and session risk
        if (markerHit) {
            // Both regex and LLM agree — high confidence
            return TaintResult.block(reason, "regex+llm:" + reason);
        }
        if (sessionRiskScore > 60) {
            return TaintResult.block(reason, "llm:" + reason);
        }
        // Medium confidence — sanitize instead of block
        return TaintResult.sanitize(reason, "llm_sanitize:" + reason, sanitizeContent(content, reason));
    }

    private String regexCheck(String content) {
        for (Pattern p : INJECTION_MARKERS) {
            if (p.matcher(content).find()) {
                return p.pattern();
            }
        }
        return null;
    }

    private boolean isExternalDataSource(String toolName) {
        return toolName != null && EXTERNAL_DATA_TOOLS.contains(toolName);
    }

    private String buildTaintPrompt(String toolResultContent) {
        return llmProps.imStart()
                + "system\n"
                + guardProps.taintSystemPrompt()
                + llmProps.imEnd()
                + "\n"
                + llmProps.imStart()
                + "user\n"
                + "Analyze the following tool return value for embedded prompt injection:\n\n"
                + toolResultContent
                + llmProps.imEnd()
                + "\n"
                + llmProps.imStart()
                + "assistant\n";
    }

    /** Replace detected injection fragments with a placeholder. */
    private String sanitizeContent(String content, String reason) {
        String sanitized = content;
        for (Pattern p : INJECTION_MARKERS) {
            sanitized = p.matcher(sanitized).replaceAll("[REMOVED: potential prompt injection]");
        }
        return sanitized;
    }

    /**
     * Taint detection result.
     *
     * @param tainted whether injection was detected
     * @param action  ALLOW, BLOCK, or SANITIZE
     * @param detectedPattern the pattern that was detected
     * @param sanitizedResult sanitized content (only for SANITIZE action)
     * @param auditDetail detail for audit logging
     */
    public record TaintResult(
            boolean tainted,
            String action,
            String detectedPattern,
            String sanitizedResult,
            String auditDetail) {

        static TaintResult allow() {
            return new TaintResult(false, "allow", null, null, null);
        }

        static TaintResult allowWithAudit(String detail) {
            return new TaintResult(false, "allow", null, null, detail);
        }

        static TaintResult block(String pattern, String detail) {
            return new TaintResult(true, "block", pattern, null, detail);
        }

        static TaintResult sanitize(String pattern, String detail, String sanitized) {
            return new TaintResult(true, "sanitize", pattern, sanitized, detail);
        }
    }
}
