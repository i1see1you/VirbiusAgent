package io.virbius.engine.eval;

import io.virbius.engine.audit.AuditWriter;
import io.virbius.engine.cache.PolicyDataCache;
import io.virbius.engine.challenge.ChallengeService;
import io.virbius.engine.eval.PromptInjectionDetector.InjectionDetectionResult;
import io.virbius.engine.eval.StiTaintDetector.TaintResult;
import io.virbius.engine.eval.TrustViolationDetector.TrustViolationResult;
import io.virbius.policy.MatchContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class EvaluateOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(EvaluateOrchestrator.class);

    private final ScriptRuleRunner scriptRuleRunner;
    private final PromptRunner promptRunner;
    private final AuditWriter auditWriter;
    private final PolicyMerger policyMerger;
    private final ChallengeService challengeService;
    private final PromptInjectionDetector injectionDetector;
    private final StiTaintDetector taintDetector;
    private final SessionRiskManager sessionRiskManager;
    private final TrustViolationDetector trustViolationDetector;
    private final PolicyDataCache policyDataCache;

    // P2: intent_action weighted risk accumulation
    private final double blockWeight;
    private final double challengeWeight;
    private final double reviewWeight;
    private final double allowWeight;

    public EvaluateOrchestrator(
            ScriptRuleRunner scriptRuleRunner,
            PromptRunner promptRunner,
            AuditWriter auditWriter,
            PolicyMerger policyMerger,
            ChallengeService challengeService,
            PromptInjectionDetector injectionDetector,
            StiTaintDetector taintDetector,
            SessionRiskManager sessionRiskManager,
            TrustViolationDetector trustViolationDetector,
            PolicyDataCache policyDataCache,
            @Value("${virbius.session-risk.intent-weight.block:0.5}") double blockWeight,
            @Value("${virbius.session-risk.intent-weight.challenge:0.1}") double challengeWeight,
            @Value("${virbius.session-risk.intent-weight.review:0.0}") double reviewWeight,
            @Value("${virbius.session-risk.intent-weight.allow:0.0}") double allowWeight) {
        this.scriptRuleRunner = scriptRuleRunner;
        this.promptRunner = promptRunner;
        this.auditWriter = auditWriter;
        this.policyMerger = policyMerger;
        this.challengeService = challengeService;
        this.injectionDetector = injectionDetector;
        this.taintDetector = taintDetector;
        this.sessionRiskManager = sessionRiskManager;
        this.trustViolationDetector = trustViolationDetector;
        this.policyDataCache = policyDataCache;
        this.blockWeight = blockWeight;
        this.challengeWeight = challengeWeight;
        this.reviewWeight = reviewWeight;
        this.allowWeight = allowWeight;
    }

    public EvaluateResponseDto evaluate(EvaluateRequestDto req) {
        // A1: Inject tool_name / tool_session_key as vars for cumulative aggregation
        String toolName = req.toolName() != null ? req.toolName() : "";
        String sessionId = req.sessionId() != null ? req.sessionId() : "";
        Map<String, String> vars = new HashMap<>(req.vars() != null ? req.vars() : Map.of());
        vars.put("tool_name", toolName);
        if (!toolName.isEmpty() && !sessionId.isEmpty()) {
            vars.put("tool_session_key", "tool:" + toolName + "-session:" + sessionId);
        }
        // Expose request content as a var so groovy rules can inspect it via ctx.var('content')
        if (req.content() != null && !req.content().isBlank()) {
            vars.put("content", req.content());
        }
        List<SignalDto> signals = new ArrayList<>();
        if (req.priorSignals() != null) {
            signals.addAll(req.priorSignals());
        }

        // --- P1.1: Prompt Injection Detection (before existing rules) ---
        InjectionDetectionResult injectionResult = injectionDetector.detect(req.content());
        if (injectionResult.hit()) {
            log.info("prompt injection detected: tenant={} session={} pattern={} riskDelta={}",
                    req.tenantId(), req.sessionId(),
                    injectionResult.matchedPattern(), injectionResult.riskDelta());
            signals.add(new SignalDto(
                    "PROMPT_INJECTION",
                    1,
                    "cloud",
                    "cloud",
                    injectionResult.riskDelta(),
                    injectionResult.matchedPattern(),
                    "deny",
                    "full",
                    null,
                    null));
        }

        // --- P1.4: Trust Violation Detection (boundary leakage / injection leakage) ---
        // Inspect Agent-generated content for leaked trust boundary tags or
        // injection patterns originating from high/network risk tool results.
        TrustViolationResult trustResult = trustViolationDetector.detect(req.content(), toolName);
        if (trustResult.violated()) {
            log.info("trust violation detected: tenant={} session={} type={} pattern={} riskDelta={}",
                    req.tenantId(), req.sessionId(),
                    trustResult.violationType(), trustResult.matchedPattern(),
                    trustResult.riskDelta());
            signals.add(new SignalDto(
                    "TRUST_VIOLATION",
                    1,
                    "cloud",
                    "cloud",
                    trustResult.riskDelta(),
                    trustResult.matchedPattern(),
                    "warn",
                    "full",
                    null,
                    null));
        }

        MatchContext matchCtx = MatchContext.forToolCallWithRoute(
                req.content(),
                req.userId(),
                req.deviceId(),
                null,
                req.sessionId(),
                vars,
                req.routeUri(),
                toolName);

        signals.addAll(promptRunner.run(req.tenantId(), matchCtx));
        signals.addAll(scriptRuleRunner.run(req.tenantId(), matchCtx, req.priorSignals()));

        PolicyMerger.PolicyMergeResult merged = policyMerger.merge(req.tenantId(), req.sessionId(), signals);
        EngineDecisionDto decision = merged.decision();
        SignalDto primary = merged.primarySignal();
        boolean degraded = false;

        String primaryRuleId = primary != null ? primary.ruleId() : "POLICY_ALLOW";
        int primaryRevision = primary != null ? primary.ruleRevision() : 0;
        String reasonCode = primary != null ? primary.reasonCode() : "POLICY_ALLOW";

        auditWriter.write(req, decision, primaryRuleId, primaryRevision, reasonCode, degraded);

        // A1: Auto-ingest cumulative counters (configuration-driven, after rule evaluation)
        try {
            scriptRuleRunner.ingestCumulatives(
                    req.tenantId() != null ? req.tenantId() : "default", matchCtx);
        } catch (Exception e) {
            log.warn("cumulative ingest failed: {}", e.getMessage());
        }

        // A1: Record tool call in session state (for session-level toolCallCount)
        try {
            scriptRuleRunner.recordToolCall(sessionId, toolName, req.argsJson(),
                    !"block".equalsIgnoreCase(decision.effectiveAction()));
        } catch (Exception e) {
            log.warn("recordToolCall failed: {}", e.getMessage());
        }

        // Compute args hash for challenge binding
        String argsJson = req.argsJson() != null ? req.argsJson() : "";
        String argsHash = ChallengeService.computeArgsHash(toolName, argsJson);

        // Per-tool approval mode from the tool registry (via PolicyDataCache):
        //   strict → exemption bound to session+tool+args_hash (exact args required)
        //   lax    → exemption bound to session+tool (any args, tolerates LLM args jitter)
        String approvalMode = lookupApprovalMode(req.tenantId(), toolName);

        // Check session-level exemption: if this session+tool was previously approved
        // (binding per approval_mode), bypass the challenge and allow the call.
        // This check is done BEFORE risk score update so that exempted calls
        // do not accumulate chain_anomaly risk from the triggering rule.
        String effectiveAction = decision.effectiveAction();
        String challengeId = null;
        boolean exempted = "challenge".equalsIgnoreCase(effectiveAction)
                && challengeService.hasActiveExemption(sessionId, toolName, argsHash, approvalMode);
        if (exempted) {
            log.info("challenge bypassed by session exemption: tenant={} session={} tool={} args_hash={} approval_mode={}",
                    req.tenantId(), sessionId, toolName, argsHash, approvalMode);
            effectiveAction = "allow";
        }

        // --- P1.3: Session Risk adaptive scoring (multi-dimensional weighted + time decay) ---
        // When a challenge is bypassed by session exemption, skip chain_anomaly
        // accumulation so that approved retries don't inflate the risk score.
        int sessionRiskScore = 0;
        try {
            int injectionHits = (int) signals.stream()
                    .filter(s -> "PROMPT_INJECTION".equals(s.ruleId()))
                    .count();
            // P2: weight chainDelta by intent_action so that challenge/review
            // don't inflate risk as aggressively as block.
            int chainDelta = exempted ? 0 : signals.stream()
                    .filter(s -> s.ruleId() != null
                            && !"PROMPT_INJECTION".equals(s.ruleId())
                            && s.score() > 0)
                    .mapToInt(s -> {
                        double weight = switch (s.intentAction() == null ? "allow" : s.intentAction().toLowerCase()) {
                            case "deny", "block" -> blockWeight;
                            case "challenge" -> challengeWeight;
                            case "review" -> reviewWeight;
                            default -> allowWeight;
                        };
                        return (int) Math.round(s.score() * weight);
                    })
                    .sum();
            RiskUpdateInput riskInput = new RiskUpdateInput(
                    sessionId,
                    req.tenantId() != null ? req.tenantId() : "default",
                    req.riskQuota() > 0 ? req.riskQuota() : 100, // from License JWT, fallback 100
                    injectionHits,
                    injectionResult.hit() ? injectionResult.riskDelta() : 15,
                    chainDelta,
                    0); // falco alerts are async, consumed from pending counter
            sessionRiskScore = sessionRiskManager.updateRiskScore(riskInput);
        } catch (Exception e) {
            log.warn("sessionRiskManager.updateRiskScore failed: {}", e.getMessage());
        }

        // If effective_action is still "challenge", create a challenge record in Redis
        if ("challenge".equalsIgnoreCase(effectiveAction)) {
            challengeId = challengeService.createChallenge(
                    req.tenantId() != null ? req.tenantId() : "default",
                    req.sessionId() != null ? req.sessionId() : "",
                    toolName,
                    argsHash,
                    primaryRuleId,
                    reasonCode,
                    decision.maxRiskScore(),
                    approvalMode);
        }

        return new EvaluateResponseDto(
                effectiveAction,
                decision.maxRiskScore(),
                sessionRiskScore,
                primaryRuleId,
                primaryRevision,
                reasonCode,
                req.traceId(),
                degraded,
                decision.enforceMode(),
                challengeId,
                argsHash);
    }

    /**
     * Look up the tool's challenge approval mode from the policy cache.
     *
     * <p>Unregistered tools (or registries without the field) default to
     * {@code strict} — the safe, args-bound behavior.
     *
     * @param tenantId the request tenant (falls back to "default")
     * @param toolName the tool being called
     * @return {@code strict} or {@code lax}
     */
    private String lookupApprovalMode(String tenantId, String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return ChallengeService.APPROVAL_MODE_STRICT;
        }
        try {
            PolicyDataCache.TenantPolicyData data =
                    policyDataCache.get(tenantId != null && !tenantId.isBlank() ? tenantId : "default");
            if (data == null) {
                return ChallengeService.APPROVAL_MODE_STRICT;
            }
            PolicyDataCache.ToolPolicyEntry entry = data.toolPolicies().get(toolName);
            if (entry != null && ChallengeService.APPROVAL_MODE_LAX.equalsIgnoreCase(
                    entry.approvalMode() != null ? entry.approvalMode().trim() : "")) {
                return ChallengeService.APPROVAL_MODE_LAX;
            }
        } catch (Exception e) {
            log.warn("approval_mode lookup failed for tool={}: {}", toolName, e.getMessage());
        }
        return ChallengeService.APPROVAL_MODE_STRICT;
    }

    /**
     * P1.2: Evaluate a tool return value for STI Taint (prompt injection in tool results).
     *
     * <p>Called by MCP Proxy after a tool completes, before returning the result to the Agent.
     */
    public ToolResultResponseDto evaluateToolResult(ToolResultRequestDto req) {
        TaintResult result = taintDetector.detect(
                req.toolName(),
                req.toolResult(),
                req.sessionRiskScore());

        String action = result.action();
        String reason = result.auditDetail() != null ? result.auditDetail() : action;

        if (result.tainted()) {
            log.info("STI taint detected: tenant={} session={} tool={} action={} pattern={}",
                    req.tenantId(), req.sessionId(), req.toolName(),
                    action, result.detectedPattern());
        }

        return new ToolResultResponseDto(
                action,
                result.detectedPattern(),
                reason,
                req.traceId());
    }

    /**
     * P1.3: LLM-based injection detection for memory writes.
     *
     * <p>Called by MCP Proxy after local Memory Interceptor checks (size, credentials, PII)
     * pass, to perform semantic injection detection on the content being written.
     *
     * <p>Delegates to PromptInjectionDetector for the actual LLM detection.
     */
    public MemoryCheckResponseDto checkMemory(MemoryCheckRequestDto req) {
        InjectionDetectionResult result = injectionDetector.detect(req.getContent());

        if (result.hit()) {
            log.info("memory injection detected: tenant={} session={} tool={} pattern={}",
                    req.getTenantId(), req.getSessionId(), req.getToolName(), result.matchedPattern());
            return MemoryCheckResponseDto.builder()
                    .allowed(false)
                    .blockReason(result.matchedPattern())
                    .riskScore(result.riskDelta())
                    .model("qwen3guard:0.6b")
                    .metadata("llm_injection_detected")
                    .build();
        }

        return MemoryCheckResponseDto.builder()
                .allowed(true)
                .blockReason(null)
                .riskScore(0)
                .model("qwen3guard:0.6b")
                .metadata(null)
                .build();
    }
}
