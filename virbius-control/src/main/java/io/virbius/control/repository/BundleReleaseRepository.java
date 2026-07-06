package io.virbius.control.repository;

import io.virbius.control.domain.BundleRelease;
import java.util.List;
import java.util.Optional;

public interface BundleReleaseRepository {

    BundleRelease create(BundleRelease release);

    Optional<BundleRelease> get(String tenantId, String bundleId, String version);

    List<BundleRelease> list(String tenantId, String bundleId);

    void updateStatus(String tenantId, String bundleId, String version, String status);

    String getActiveVersion(String tenantId, String bundleId);

    void setActiveVersion(String tenantId, String bundleId, String version);
}
