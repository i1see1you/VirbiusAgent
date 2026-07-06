package io.virbius.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.control.domain.RuleRevision;
import io.virbius.control.domain.RolloutStateHelper;
import io.virbius.control.policy.PolicyDataBuilder;
import io.virbius.control.policy.PolicyMaterializer;
import io.virbius.control.repository.RegistryRepository;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PublishService {

    private static final Logger log = LoggerFactory.getLogger(PublishService.class);

    private final RegistryRepository store;
    private final PolicyMaterializer policyMaterializer;
    private final PolicyDataBuilder policyDataBuilder;
    private final CacheReloadNotifier reloadNotifier;

    public PublishService(
            RegistryRepository store,
            PolicyMaterializer policyMaterializer,
            PolicyDataBuilder policyDataBuilder,
            CacheReloadNotifier reloadNotifier) {
        this.store = store;
        this.policyMaterializer = policyMaterializer;
        this.policyDataBuilder = policyDataBuilder;
        this.reloadNotifier = reloadNotifier;
    }

    public Map<String, Object> runtimeSnapshot(String tenantId) {
        Map<String, Object> syncAck = reloadEngineCache(tenantId, "runtime-only");
        return Map.of("accepted", true, "sync_ack", syncAck);
    }

    public Map<String, Object> buildRuntimeSnapshotPayload(String tenantId) {
        List<RuleRevision> rules = store.listCurrentRules(tenantId, "cloud").stream()
                .filter(RolloutStateHelper::inExecutionPlane)
                .map(r -> policyMaterializer.materialize(tenantId, r))
                .toList();
        ObjectMapper snakeMapper = new ObjectMapper()
                .setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
        com.fasterxml.jackson.databind.node.ObjectNode body = snakeMapper.createObjectNode();
        body.put("policy_version", "runtime-only");
        com.fasterxml.jackson.databind.node.ArrayNode rulesNode = body.putArray("rules");
        for (RuleRevision r : rules) {
            if (!"cloud".equals(r.layer()) || !RolloutStateHelper.inExecutionPlane(r)) {
                continue;
            }
            com.fasterxml.jackson.databind.node.ObjectNode rule = rulesNode.addObject();
            rule.put("tenant_id", r.tenantId());
            rule.put("rule_id", r.ruleId());
            rule.put("rule_revision", r.ruleRevision());
            rule.put("layer", r.layer());
            rule.put("runtime", r.runtime());
            rule.put("reason_code", r.reasonCode());
            rule.put("risk_score", r.riskScore());
            rule.put("intent_action", r.intentAction() != null ? r.intentAction() : "deny");
            rule.put("enforce_mode", r.enforceMode());
            rule.put("rollout_state", r.rolloutState() != null ? r.rolloutState() : "dry_run");
            rule.put("is_async", r.isAsync());
            if (r.asyncActionConfig() != null && !r.asyncActionConfig().isBlank()) {
                rule.put("async_action_config", r.asyncActionConfig());
            }
            if (r.exportedCanaryPercent() != null) {
                rule.put("canary_percent", r.exportedCanaryPercent());
            }
            if (r.scope() != null && !r.scope().isEmpty()) {
                rule.set("scope", snakeMapper.valueToTree(r.scope()));
            }
            if (r.body() != null) {
                if (r.body() instanceof String s) {
                    rule.put("body", s);
                } else {
                    try {
                        rule.put("body", snakeMapper.writeValueAsString(r.body()));
                    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                        throw new RuntimeException("Failed to serialize rule body for " + r.ruleId(), e);
                    }
                }
            }
        }
        body.set("lists", snakeMapper.valueToTree(policyDataBuilder.buildEngineMemoryLists(tenantId)));
        body.set("redis_list_index", snakeMapper.valueToTree(policyDataBuilder.buildEngineRedisListIndex(tenantId)));
        body.set("cumulatives", snakeMapper.valueToTree(policyDataBuilder.buildEngineCumulatives(tenantId)));
        return snakeMapper.convertValue(body, Map.class);
    }

    private Map<String, Object> reloadEngineCache(String tenantId, String bundleVersion) {
        try {
            Map<String, Object> payload = buildRuntimeSnapshotPayload(tenantId);
            payload.put("policy_version", bundleVersion);
            return reloadNotifier.publish(tenantId, bundleVersion, payload);
        } catch (Exception e) {
            log.warn("engine reload error: {}", e.getMessage());
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    public Map<String, Object> ruleRuntimeSnapshot(String tenantId, String ruleId) {
        try {
            RuleRevision rule = store.getCurrentRule(tenantId, ruleId).orElse(null);
            if (rule == null || !RolloutStateHelper.inExecutionPlane(rule)) {
                return ruleRuntimeRemove(tenantId, ruleId);
            }
            RuleRevision materialized = policyMaterializer.materialize(tenantId, rule);
            ObjectMapper snakeMapper = new ObjectMapper()
                    .setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
            Map<String, Object> ruleMap = snakeMapper.convertValue(buildSingleRuleMap(materialized), Map.class);
            return reloadNotifier.publishUpsert(tenantId, "runtime-only", ruleMap);
        } catch (Exception e) {
            log.warn("rule runtime snapshot error for {}: {}", ruleId, e.getMessage());
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    public Map<String, Object> ruleRuntimeRemove(String tenantId, String ruleId) {
        try {
            return reloadNotifier.publishRemove(tenantId, "runtime-only", ruleId);
        } catch (Exception e) {
            log.warn("rule runtime remove error for {}: {}", ruleId, e.getMessage());
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    private Map<String, Object> buildSingleRuleMap(RuleRevision r) {
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("tenant_id", r.tenantId());
        m.put("rule_id", r.ruleId());
        m.put("rule_revision", r.ruleRevision());
        m.put("layer", r.layer());
        m.put("runtime", r.runtime());
        m.put("reason_code", r.reasonCode());
        m.put("risk_score", r.riskScore());
        m.put("intent_action", r.intentAction() != null ? r.intentAction() : "deny");
        m.put("enforce_mode", r.enforceMode());
        m.put("rollout_state", r.rolloutState() != null ? r.rolloutState() : "dry_run");
        m.put("is_async", r.isAsync());
        if (r.asyncActionConfig() != null && !r.asyncActionConfig().isBlank()) {
            m.put("async_action_config", r.asyncActionConfig());
        }
        if (r.exportedCanaryPercent() != null) {
            m.put("canary_percent", r.exportedCanaryPercent());
        }
        if (r.scope() != null && !r.scope().isEmpty()) {
            m.put("scope", r.scope());
        }
        if (r.body() != null) {
            m.put("body", r.body());
        }
        return m;
    }
}