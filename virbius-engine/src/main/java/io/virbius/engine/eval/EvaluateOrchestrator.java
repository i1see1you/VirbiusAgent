package io.virbius.engine.eval;

import io.virbius.engine.audit.AuditWriter;
import io.virbius.engine.challenge.ChallengeService;
import io.virbius.engine.eval.PromptInjectionDetector.InjectionDetectionResult;
import io.virbius.engine.eval.StiTaintDetector.TaintResult;
import io.virbius.policy.MatchContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public EvaluateOrchestrator(
            ScriptRuleRunner scriptRuleRunner,
            PromptRunner promptRunner,
            AuditWriter auditWriter,
            PolicyMerger policyMerger,
            ChallengeService challengeService,
            PromptInjectionDetector injectionDetector,
            StiTaintDetector taintDetector) {
        this.scriptRuleRunner = scriptRuleRunner;
        this.promptRunner = promptRunner;
        this.auditWriter = auditWriter;
        this.policyMerger = policyMerger;
        this.challengeService = challengeService;
        this.injectionDetector = injectionDetector;
        this.taintDetector = taintDetector;
    }

    public EvaluateResponseDto evaluate(EvaluateRequestDto req) {
        Map<String, String> vars = req.vars() != null ? req.vars() : Map.of();
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

        MatchContext matchCtx = MatchContext.withBind(
                req.content(),
                req.userId(),
                req.deviceId(),
                null,
                req.sessionId(),
                vars,
                req.scene(),
                req.routeUri());

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

        // Compute args hash for challenge binding
        String toolName = req.toolName() != null ? req.toolName() : "";
        String argsJson = req.argsJson() != null ? req.argsJson() : "";
        String argsHash = ChallengeService.computeArgsHash(toolName, argsJson);

        // If effective_action is "challenge", create a challenge record in Redis
        String challengeId = null;
        if ("challenge".equalsIgnoreCase(decision.effectiveAction())) {
            challengeId = challengeService.createChallenge(
                    req.tenantId() != null ? req.tenantId() : "default",
                    req.sessionId() != null ? req.sessionId() : "",
                    toolName,
                    argsHash,
                    primaryRuleId,
                    reasonCode,
                    decision.maxRiskScore());
        }

        return new EvaluateResponseDto(
                decision.effectiveAction(),
                decision.maxRiskScore(),
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
                result.sanitizedResult(),
                result.detectedPattern(),
                reason,
                req.traceId());
    }
}
