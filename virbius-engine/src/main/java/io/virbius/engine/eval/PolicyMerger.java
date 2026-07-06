package io.virbius.engine.eval;

import io.virbius.engine.cache.RuleCache;
import io.virbius.engine.cache.RuleEntry;
import io.virbius.policy.ActionMerge;
import io.virbius.policy.IntentAction;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class PolicyMerger {

    private final RuleCache cache;

    public PolicyMerger(RuleCache cache) {
        this.cache = cache;
    }

    public PolicyMergeResult merge(String tenantId, String sessionId, List<SignalDto> signals) {
        List<ActionMerge.RuleHit> hits = new ArrayList<>();
        for (SignalDto s : signals) {
            String intent = s.intentAction();
            String enforce = s.enforceMode();
            Integer canary = s.canaryPercent();
            if (intent == null || intent.isBlank() || enforce == null || enforce.isBlank()) {
                RuleEntry rule = cache.get(tenantId, s.ruleId());
                if (rule != null) {
                    if (intent == null || intent.isBlank()) {
                        intent = rule.intentAction();
                    }
                    if (enforce == null || enforce.isBlank()) {
                        enforce = rule.enforceMode();
                    }
                    if (canary == null) {
                        canary = rule.canaryPercent() > 0 ? rule.canaryPercent() : null;
                    }
                }
            }
            intent = IntentAction.normalize(intent, (int) s.score());
            enforce = enforce != null && !enforce.isBlank() ? enforce : "dry_run";
            hits.add(new ActionMerge.RuleHit(
                    s.ruleId(),
                    s.ruleRevision(),
                    s.reasonCode(),
                    (int) s.score(),
                    intent,
                    enforce,
                    canary));
        }
        ActionMerge.MergeResult merged = ActionMerge.merge(hits, sessionId);
        String enforceMode = merged.primary() != null ? merged.primary().enforceMode() : "dry_run";
        EngineDecisionDto decision = new EngineDecisionDto(merged.effectiveAction(), merged.maxRiskScore(), enforceMode);
        SignalDto primarySignal = merged.primary() != null ? toPrimarySignal(merged.primary()) : null;
        return new PolicyMergeResult(decision, primarySignal);
    }

    private static SignalDto toPrimarySignal(ActionMerge.RuleHit hit) {
        return new SignalDto(
                hit.ruleId(),
                hit.ruleRevision(),
                "cloud",
                "cloud",
                hit.riskScore(),
                hit.reasonCode(),
                hit.intentAction(),
                hit.enforceMode(),
                hit.canaryPercent(),
                null);
    }

    public record PolicyMergeResult(EngineDecisionDto decision, SignalDto primarySignal) {}
}
