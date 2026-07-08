package io.virbius.engine.eval;

import io.virbius.engine.audit.AuditWriter;
import io.virbius.engine.challenge.ChallengeService;
import io.virbius.policy.MatchContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class EvaluateOrchestrator {

    private final ScriptRuleRunner scriptRuleRunner;
    private final PromptRunner promptRunner;
    private final AuditWriter auditWriter;
    private final PolicyMerger policyMerger;
    private final ChallengeService challengeService;

    public EvaluateOrchestrator(
            ScriptRuleRunner scriptRuleRunner,
            PromptRunner promptRunner,
            AuditWriter auditWriter,
            PolicyMerger policyMerger,
            ChallengeService challengeService) {
        this.scriptRuleRunner = scriptRuleRunner;
        this.promptRunner = promptRunner;
        this.auditWriter = auditWriter;
        this.policyMerger = policyMerger;
        this.challengeService = challengeService;
    }

    public EvaluateResponseDto evaluate(EvaluateRequestDto req) {
        Map<String, String> vars = req.vars() != null ? req.vars() : Map.of();
        List<SignalDto> signals = new ArrayList<>();
        if (req.priorSignals() != null) {
            signals.addAll(req.priorSignals());
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
}
