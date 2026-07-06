package io.virbius.control.service;

import io.virbius.control.common.exception.BusinessException;
import io.virbius.control.domain.RuleRevision;
import io.virbius.control.domain.RolloutStateHelper;
import io.virbius.control.domain.TenantRolloutPolicy;
import io.virbius.control.domain.dto.response.RuleResponseMapper;
import io.virbius.control.domain.enums.RolloutState;
import io.virbius.control.repository.RegistryRepository;
import io.virbius.control.repository.RolloutEventRepository;
import io.virbius.control.repository.TenantRolloutPolicyRepository;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class RolloutService {

    private final RegistryRepository store;
    private final PromotionGateService gateService;
    private final RolloutEventRepository eventRepository;
    private final TenantRolloutPolicyRepository policyRepository;
    private final BundleStagingService stagingService;

    public RolloutService(
            RegistryRepository store,
            PromotionGateService gateService,
            RolloutEventRepository eventRepository,
            TenantRolloutPolicyRepository policyRepository,
            BundleStagingService stagingService) {
        this.store = store;
        this.gateService = gateService;
        this.eventRepository = eventRepository;
        this.policyRepository = policyRepository;
        this.stagingService = stagingService;
    }

    public Map<String, Object> applyRollout(
            String tenantId,
            String ruleId,
            String rolloutState,
            Integer canaryPercent,
            boolean force,
            String comment,
            String trigger,
            String operator) {
        RuleRevision current = store.getCurrentRule(tenantId, ruleId).orElseThrow();
        String from = RolloutStateHelper.stateOf(current);
        String to = RolloutStateHelper.normalize(rolloutState);
        RolloutStateHelper.validateTransition(from, to);
        RolloutStateHelper.validateCanaryPercent(to, canaryPercent);

        if (shouldCheckConcurrentLimit(from, to)) {
            TenantRolloutPolicy policy = policyRepository.getOrDefault(tenantId);
            int active = store.countByRolloutStates(
                    tenantId, List.of("dry_run", "canary"), ruleId);
            if (active >= policy.maxConcurrentRollouts()) {
                throw new BusinessException(429,
                        "Active rollout count " + active + " has reached the limit " + policy.maxConcurrentRollouts()
                                + ". Please finish existing rollouts before operating new rules.");
            }
        }

        if (RolloutState.CANARY.value().equals(to) && RolloutState.CANARY.value().equals(from)) {
            // percent step within canary
        } else if (!from.equals(to) && requiresGate(from, to)) {
            gateService.requirePassOrForce(tenantId, current, to, canaryPercent, force, comment);
        }

        RuleRevision updated = store.updateRollout(tenantId, ruleId, to, canaryPercent);
        eventRepository.recordEvent(
                tenantId, ruleId, updated.ruleRevision(), to, canaryPercent, trigger, operator);

        String layer = resolveLayer(updated);
        String diffType = "rollout_only";
        try {
            stagingService.applyRuleChange(tenantId, "poc-default", layer, ruleId, diffType);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(RolloutService.class)
                    .warn("auto-deploy staging failed for rule {}: {}", ruleId, e.getMessage());
        }

        return RuleResponseMapper.toDetail(updated);
    }

    public Map<String, Object> publish(String tenantId, String ruleId) {
        return applyRollout(
                tenantId, ruleId, RolloutState.DRY_RUN.value(), null, false, null, "publish", "admin");
    }

    public Map<String, Object> rollback(String tenantId, String ruleId) {
        return applyRollout(
                tenantId, ruleId, RolloutState.DRY_RUN.value(), null, false, null, "rollback", "admin");
    }

    public Map<String, Object> disable(String tenantId, String ruleId) {
        return applyRollout(
                tenantId, ruleId, RolloutState.DISABLED.value(), null, false, null, "manual", "admin");
    }

    public Map<String, Object> recover(String tenantId, String ruleId) {
        return applyRollout(
                tenantId, ruleId, RolloutState.DRAFT.value(), null, false, null, "recover", "admin");
    }

    public Map<String, Object> evaluate(
            String tenantId, String ruleId, String targetState, Integer canaryPercent) {
        RuleRevision current = store.getCurrentRule(tenantId, ruleId).orElseThrow();
        Map<String, Object> result = gateService.evaluate(tenantId, current, targetState, canaryPercent);
        gateService.recordEvaluateLog(tenantId, ruleId, result);
        return result;
    }

    private static boolean shouldCheckConcurrentLimit(String from, String to) {
        return ("draft".equals(from) && "dry_run".equals(to))
                || ("dry_run".equals(from) && "canary".equals(to));
    }

    private static boolean requiresGate(String from, String to) {
        if (RolloutState.DRY_RUN.value().equals(from) && RolloutState.CANARY.value().equals(to)) {
            return true;
        }
        if (RolloutState.CANARY.value().equals(from)
                && (RolloutState.CANARY.value().equals(to) || RolloutState.FULL.value().equals(to))) {
            return true;
        }
        return false;
    }

    private static String resolveLayer(RuleRevision rule) {
        String layer = rule.layer();
        if (layer != null && !layer.isBlank()) {
            return layer;
        }
        return "cloud";
    }
}
