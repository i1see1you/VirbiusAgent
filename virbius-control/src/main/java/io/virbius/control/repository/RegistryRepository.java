package io.virbius.control.repository;

import io.virbius.control.domain.BundleVersion;
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
}
