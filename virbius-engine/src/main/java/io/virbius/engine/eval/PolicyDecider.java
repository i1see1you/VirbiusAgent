package io.virbius.engine.eval;

import io.virbius.engine.cache.RuleCache;
import io.virbius.engine.cache.RuleEntry;
import io.virbius.policy.ActionMerge;
import io.virbius.policy.IntentAction;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class PolicyDecider {

    private final RuleCache cache;

    public PolicyDecider(RuleCache cache) {
        this.cache = cache;
    }

    public EngineDecisionDto decide(
            String tenantId,
            String sessionId,
            List<SignalDto> signals,
            String primaryRuleId) {
        List<ActionMerge.RuleHit> hits = new ArrayList<>();
        for (SignalDto s : signals) {
            if (s.intentAction() != null && IntentAction.isAllowIntent(s.intentAction())) {
                return new EngineDecisionDto(IntentAction.ALLOW, 0, "dry_run");
            }
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
            if (IntentAction.isAllowIntent(intent)) {
                return new EngineDecisionDto(IntentAction.ALLOW, 0, "dry_run");
            }
            hits.add(new ActionMerge.RuleHit(
                    s.ruleId(),
                    s.ruleRevision(),
                    s.reasonCode(),
                    (int) s.score(),
                    intent,
                    enforce != null ? enforce : "dry_run",
                    canary));
        }
        ActionMerge.MergeResult merged = ActionMerge.merge(hits, sessionId);
        String mode = merged.primary() != null ? merged.primary().enforceMode() : "dry_run";
        return new EngineDecisionDto(merged.effectiveAction(), merged.maxRiskScore(), mode);
    }
}
