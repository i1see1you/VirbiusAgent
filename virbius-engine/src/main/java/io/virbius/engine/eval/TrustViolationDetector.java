package io.virbius.engine.eval;

import io.virbius.engine.cache.PolicyDataCache;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Trust Violation Detector: detects when Agent-generated content (e.g., tool call
 * arguments, LLM-generated messages) improperly contains or follows instructions
 * from low-trust sources — specifically from tool results wrapped in
 * {@code <trust_boundary>} or {@code <untrusted_data>} tags.
 *
 * <p>This is the cloud-side counterpart to the Edge-side {@code TrustTagger}.  While
 * the Edge tags high/network risk tool results, this detector inspects subsequent
 * Agent requests to ensure the LLM did not act on injected instructions from those
 * tagged results.
 *
 * <p>Detection heuristics (Phase 1 — pattern-based):
 * <ol>
 *   <li><b>Boundary leakage</b>: Agent content contains raw {@code <trust_boundary>}
 *       or {@code <untrusted_data>} tags, indicating the LLM echoed back tag content
 *       verbatim instead of treating it as data.</li>
 *   <li><b>Instruction from untrusted</b>: Agent content contains patterns commonly
 *       used by injected instructions (e.g., "ignore previous instructions",
 *       "system:", "you are now") that originated from a tool result context.</li>
 * </ol>
 *
 * <p>When a violation is detected, a signal with risk delta is produced. The
 * orchestrator may escalate the session risk or block the action.
 *
 * @see TrustTagger (Edge side, in virbius-core/src/trust.rs)
 */
@Component
public class TrustViolationDetector {

    private static final Logger log = LoggerFactory.getLogger(TrustViolationDetector.class);

    /** Risk classes that produce trust-boundary-tagged results on the Edge. */
    private static final Set<String> TAGGED_RISK_CLASSES = Set.of("high", "network");

    /** Patterns that indicate the Agent is echoing trust boundary tags. */
    private static final List<String> BOUNDARY_LEAKAGE_PATTERNS = List.of(
            "<trust_boundary",
            "</trust_boundary>",
            "<untrusted_data",
            "</untrusted_data>"
    );

    /** Patterns that indicate injection-style instructions leaked from tool results. */
    private static final List<String> INJECTION_LEAKAGE_PATTERNS = List.of(
            "ignore previous instructions",
            "ignore all previous",
            "you are now",
            "system:",
            "new instructions:",
            "disregard the above",
            "forget your instructions",
            "override your instructions"
    );

    private final PolicyDataCache policyDataCache;

    public TrustViolationDetector(PolicyDataCache policyDataCache) {
        this.policyDataCache = policyDataCache;
    }

    /**
     * Detect trust violations in Agent-generated content.
     *
     * <p>Checks whether the content (typically a tool call's arguments or a
     * user-message-forwarded-by-agent) contains leaked trust boundary tags or
     * injection patterns that may have originated from a tagged tool result.
     *
     * @param content the Agent-generated content to inspect
     * @param recentToolName the most recently called tool (for risk-class context),
     *                       or {@code null} if unknown
     * @return detection result
     */
    public TrustViolationResult detect(String content, String recentToolName) {
        if (content == null || content.isBlank()) {
            return TrustViolationResult.ok();
        }

        String lowerContent = content.toLowerCase();

        // 1. Boundary leakage: raw tags present in Agent output
        for (String pattern : BOUNDARY_LEAKAGE_PATTERNS) {
            if (lowerContent.contains(pattern)) {
                log.info("trust violation: boundary leakage detected, pattern='{}', recent_tool={}",
                        pattern, recentToolName);
                return TrustViolationResult.violation(
                        "BOUNDARY_LEAKAGE",
                        pattern,
                        25,
                        "Agent echoed trust boundary tag: " + pattern);
            }
        }

        // 2. Instruction leakage: only flag if the recent tool was high/network risk,
        //    since only those tools' results are wrapped with trust boundaries.
        if (recentToolName != null && !recentToolName.isBlank()) {
            String riskClass = lookupRiskClassString(recentToolName);
            if (TAGGED_RISK_CLASSES.contains(riskClass)) {
                for (String pattern : INJECTION_LEAKAGE_PATTERNS) {
                    if (lowerContent.contains(pattern)) {
                        log.info("trust violation: injection leakage from high-risk tool, "
                                        + "pattern='{}', tool='{}', risk_class='{}'",
                                pattern, recentToolName, riskClass);
                        return TrustViolationResult.violation(
                                "INJECTION_LEAKAGE",
                                pattern,
                                20,
                                "Injection pattern from tagged tool result: " + pattern);
                    }
                }
            }
        }

        return TrustViolationResult.ok();
    }

    /**
     * Look up the risk class string for a tool from the policy cache.
     *
     * @param toolName the tool name
     * @return risk class ("low", "medium", "high", "network"), default "low"
     */
    private String lookupRiskClassString(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return "low";
        }
        PolicyDataCache.TenantPolicyData data = policyDataCache.get("default");
        if (data == null) {
            return "low";
        }
        PolicyDataCache.ToolPolicyEntry entry = data.toolPolicies().get(toolName);
        if (entry != null && entry.riskClass() != null && !entry.riskClass().isBlank()) {
            return entry.riskClass().trim().toLowerCase();
        }
        return "low";
    }

    /**
     * Trust violation detection result.
     *
     * @param violated       whether a violation was detected
     * @param violationType  type of violation (BOUNDARY_LEAKAGE, INJECTION_LEAKAGE)
     * @param matchedPattern the pattern that triggered the detection
     * @param riskDelta      risk score delta to add to the session
     * @param detail         human-readable detail for audit logging
     */
    public record TrustViolationResult(
            boolean violated,
            String violationType,
            String matchedPattern,
            int riskDelta,
            String detail) {

        static TrustViolationResult ok() {
            return new TrustViolationResult(false, null, null, 0, null);
        }

        static TrustViolationResult violation(
                String type, String pattern, int riskDelta, String detail) {
            return new TrustViolationResult(true, type, pattern, riskDelta, detail);
        }
    }
}
