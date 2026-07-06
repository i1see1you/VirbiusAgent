package io.virbius.control.repository;

import io.virbius.control.domain.BundleStaging;
import java.util.Optional;

public interface BundleStagingRepository {

    Optional<BundleStaging> get(String tenantId, String bundleId, String layer);

    BundleStaging getOrCreate(String tenantId, String bundleId, String layer, String baseVersion);

    BundleStaging applyRuleDiff(String tenantId, String bundleId, String layer, String ruleId, String diffType);

    BundleStaging removeRuleDiff(String tenantId, String bundleId, String layer, String ruleId);

    void updateStatus(String tenantId, String bundleId, String layer, String status);

    void clear(String tenantId, String bundleId, String newBaseVersion);
}
