package io.virbius.control.service;

import io.virbius.control.common.exception.BusinessException;
import io.virbius.control.domain.RuleRevision;
import io.virbius.control.domain.RiskScore;
import io.virbius.control.domain.RolloutStateHelper;
import io.virbius.control.domain.dto.request.UpsertRuleRequest;
import io.virbius.control.domain.dto.request.ValidateScriptRequest;
import io.virbius.control.domain.dto.response.RuleResponseMapper;
import io.virbius.control.domain.enums.IntentAction;
import io.virbius.control.domain.enums.RolloutState;
import io.virbius.control.script.ScriptRuleValidator;
import io.virbius.control.gateway.DlpRuleValidator;
import io.virbius.control.gateway.RuleBindScopeValidator;
import io.virbius.control.groovy.GroovyRuleBodies;
import io.virbius.control.ruleauthoring.ConditionCompiler;
import io.virbius.control.repository.RegistryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class RuleService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final RegistryRepository store;
    private final ScriptRuleValidator scriptRuleValidator;

    public RuleService(
            RegistryRepository store,
            ScriptRuleValidator scriptRuleValidator) {
        this.store = store;
        this.scriptRuleValidator = scriptRuleValidator;
    }

    public Map<String, Object> validateScript(String tenantId, ValidateScriptRequest req) {
        return scriptRuleValidator.validate(tenantId, req.runtime(), req.body());
    }

    public List<Map<String, Object>> listRules(String tenantId, String layer) {
        return store.listCurrentRules(tenantId, layer).stream()
                .map(RuleResponseMapper::toSummary)
                .toList();
    }

    public Map<String, Object> upsertRule(String tenantId, UpsertRuleRequest req) {
        if (req.riskScore() != null
                && (req.riskScore() < RiskScore.MIN || req.riskScore() > RiskScore.MAX)) {
            throw new IllegalArgumentException("risk_score must be between " + RiskScore.MIN + " and " + RiskScore.MAX);
        }
        Optional<RuleRevision> existing = store.getCurrentRule(tenantId, req.ruleId());
        if (existing.isPresent()) {
            RolloutStateHelper.requireNotDisabled(existing.get());
        }
        String rolloutState =
                existing.map(RuleRevision::rolloutState).orElse(RolloutState.DRAFT.value());
        Integer canaryPercent = existing.map(RuleRevision::canaryPercent).orElse(null);

        int normalizedRisk = RiskScore.normalize(req.riskScore());
        String intentAction = req.intentAction() != null && !req.intentAction().isBlank()
                ? IntentAction.parse(req.intentAction()).value()
                : existing.map(RuleRevision::intentAction)
                        .orElse(IntentAction.defaultForRisk(normalizedRisk).value());

        if (DlpRuleValidator.isDlpRuntime(req.runtime())) {
            DlpRuleValidator.validateUpsert(req);
            normalizedRisk = 0;
            intentAction = IntentAction.ALLOW.value();
        }

        if (existing.isPresent() && contentChanged(existing.get(), req, normalizedRisk, intentAction)) {
            if (RolloutStateHelper.inExecutionPlane(existing.get())) {
                String state = existing.get().rolloutState();
                String hint = "dry_run".equals(state) ? "take offline (disable) then edit" : "rollback to dry_run, collect sufficient data, then take offline and edit";
                throw new BusinessException(409,
                        "Rule is currently in " + state + " state and running online. Editing is not allowed. Please " + hint + ".");
            }
        }

        boolean isAsync = req.isAsync() != null && req.isAsync();
        if (isAsync && !IntentAction.ALLOW.value().equals(intentAction)) {
            throw new IllegalArgumentException("async rule must have intent_action=allow");
        }

        RuleRevision draft = new RuleRevision(
                tenantId,
                req.ruleId(),
                0,
                req.bundleId() != null ? req.bundleId() : "poc-default",
                req.layer(),
                req.runtime(),
                req.reasonCode(),
                normalizedRisk,
                intentAction,
                req.scope() != null ? req.scope() : Map.of(),
                resolveBody(req),
                rolloutState,
                canaryPercent,
                null,
                null,
                null,
                isAsync,
                req.asyncActionConfig());
        if ("groovy".equals(req.runtime()) || "lua".equals(req.runtime())) {
            scriptRuleValidator.validateOrThrow(tenantId, req.runtime(), resolveBody(req));
        }
        if ("cumulative".equals(req.runtime()) || "list_match".equals(req.runtime())) {
            throw new IllegalArgumentException("runtime " + req.runtime()
                    + " removed; use lua (gateway) or groovy (cloud) script rules");
        }
        String bundleId = req.bundleId() != null && !req.bundleId().isBlank() ? req.bundleId() : "poc-default";
        Map<String, Object> bundleMetadata = store.getBundle(
                        tenantId, bundleId, RuleBindScopeValidator.defaultBundleVersion())
                .map(b -> b.metadata() != null ? b.metadata() : Map.<String, Object>of())
                .orElse(Map.of());
        RuleBindScopeValidator.validateRouteScenes(req, bundleMetadata);
        RuleRevision saved = store.upsertRule(tenantId, draft);
        return RuleResponseMapper.toDetail(saved);
    }

    public Map<String, Object> getRule(String tenantId, String ruleId) {
        RuleRevision r = store.getCurrentRule(tenantId, ruleId)
                .orElseThrow(() -> new IllegalArgumentException("rule not found: " + ruleId));
        return RuleResponseMapper.toDetail(r);
    }

    public List<Map<String, Object>> listRevisions(String tenantId, String ruleId) {
        return store.listRuleRevisions(tenantId, ruleId).stream()
                .map(RuleResponseMapper::toSummary)
                .toList();
    }

    public Map<String, Object> getRevision(String tenantId, String ruleId, int revision) {
        RuleRevision r = store.getRuleRevision(tenantId, ruleId, revision)
                .orElseThrow(() -> new IllegalArgumentException("revision not found"));
        return RuleResponseMapper.toDetail(r);
    }

    @Deprecated
    public Map<String, Object> updateRuntime(String tenantId, String ruleId, String enforceMode, Integer canaryPercent) {
        RuleRevision r = store.updateRuntime(tenantId, ruleId, enforceMode, canaryPercent);
        return RuleResponseMapper.toDetail(r);
    }

    @Deprecated
    public Map<String, Object> updateRuleStatus(String tenantId, String ruleId, String ruleStatus) {
        RuleRevision current = store.getCurrentRule(tenantId, ruleId).orElseThrow();
        RuleRevision updated = store.updateRuleStatus(tenantId, ruleId, ruleStatus);
        return RuleResponseMapper.toDetail(updated);
    }

    private boolean contentChanged(
            RuleRevision existing, UpsertRuleRequest req, int riskScore, String intentAction) {
        if (!Objects.equals(existing.layer(), req.layer())
                || !Objects.equals(existing.runtime(), req.runtime())
                || !Objects.equals(existing.reasonCode(), req.reasonCode())
                || existing.riskScore() != riskScore
                || !Objects.equals(existing.intentAction(), intentAction)) {
            return true;
        }
        boolean reqAsync = req.isAsync() != null && req.isAsync();
        if (existing.isAsync() != reqAsync
                || !Objects.equals(existing.asyncActionConfig(), req.asyncActionConfig())) {
            return true;
        }
        String oldBody = toJson(existing.body());
        String newBody = toJson(resolveBody(req));
        return !Objects.equals(oldBody, newBody);
    }

    private static String resolveBody(UpsertRuleRequest req) {
        if ("simple".equalsIgnoreCase(req.editorMode())
                && req.condition() != null
                && !req.condition().isEmpty()) {
            Object script = ConditionCompiler.compile(req.layer(), req.runtime(), req.condition()).get("script");
            return String.valueOf(script);
        }
        return GroovyRuleBodies.asScript(req.body());
    }

    private static String toJson(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s;
        }
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}
