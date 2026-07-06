package io.virbius.control.repository;

import io.virbius.control.domain.BundleVersion;
import io.virbius.control.domain.ContextVarBinding;
import io.virbius.control.domain.ExtendedVar;
import io.virbius.control.domain.RuleRevision;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface RegistryRepository {

    List<BundleVersion> listBundles(String tenantId);

    Optional<BundleVersion> getBundle(String tenantId, String bundleId, String version);

    BundleVersion createBundle(String tenantId, String bundleId);

    RuleRevision upsertRule(String tenantId, RuleRevision draft);

    List<RuleRevision> listCurrentRules(String tenantId, String layer);

    Optional<RuleRevision> getRuleRevision(String tenantId, String ruleId, int revision);

    RuleRevision updateRollout(String tenantId, String ruleId, String rolloutState, Integer canaryPercent);

    @Deprecated
    RuleRevision updateRuntime(String tenantId, String ruleId, String enforceMode, Integer canaryPercent);

    @Deprecated
    RuleRevision updateRuleStatus(String tenantId, String ruleId, String ruleStatus);

    void updateBundleMetadata(
            String tenantId, String bundleId, String version, Map<String, Object> metadata, int expectedVersion);

    default void updateBundleMetadata(
            String tenantId, String bundleId, String version, Map<String, Object> metadata) {
        updateBundleMetadata(tenantId, bundleId, version, metadata, -1);
    }

    List<RuleRevision> listRuleRevisions(String tenantId, String ruleId);

    int countByRolloutStates(String tenantId, List<String> rolloutStates, String excludeRuleId);

    Optional<RuleRevision> getCurrentRule(String tenantId, String ruleId);

    // ---- 请求因子（context bindings）----

    List<ContextVarBinding> listContextBindings(
            String tenantId, String bundleId, String version, boolean includeDeleted);

    default List<ContextVarBinding> listContextBindings(String tenantId, String bundleId, String version) {
        return listContextBindings(tenantId, bundleId, version, false);
    }

    void replaceContextBindings(
            String tenantId, String bundleId, String version, List<ContextVarBinding> bindings);

    void deleteContextBinding(String tenantId, String bundleId, String version, String logical);

    // ---- 扩展因子（extended vars）----

    List<ExtendedVar> listExtendedVars(
            String tenantId, String bundleId, String version, boolean includeDeleted);

    default List<ExtendedVar> listExtendedVars(String tenantId, String bundleId, String version) {
        return listExtendedVars(tenantId, bundleId, version, false);
    }

    void replaceExtendedVars(
            String tenantId, String bundleId, String version, List<ExtendedVar> vars);

    void deleteExtendedVar(String tenantId, String bundleId, String version, String logical);
}
