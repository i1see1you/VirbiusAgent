package io.virbius.control.service;

import io.virbius.control.common.exception.BusinessException;
import io.virbius.control.domain.BundleRelease;
import io.virbius.control.domain.RuleRevision;
import io.virbius.control.domain.dto.response.RuleResponseMapper;
import io.virbius.control.repository.BundleReleaseRepository;
import io.virbius.control.repository.BundleStagingRepository;
import io.virbius.control.repository.RegistryRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class BundleReleaseService {

    private static final String DEFAULT_BUNDLE = "poc-default";

    private final BundleReleaseRepository releaseRepo;
    private final BundleStagingRepository stagingRepo;
    private final RegistryRepository ruleRepo;
    private final PublishOrchestrator orchestrator;

    public BundleReleaseService(
            BundleReleaseRepository releaseRepo,
            BundleStagingRepository stagingRepo,
            RegistryRepository ruleRepo,
            PublishOrchestrator orchestrator) {
        this.releaseRepo = releaseRepo;
        this.stagingRepo = stagingRepo;
        this.ruleRepo = ruleRepo;
        this.orchestrator = orchestrator;
    }

    public String getActiveVersion(String tenantId, String bundleId) {
        return releaseRepo.getActiveVersion(tenantId, bundleId);
    }

    public List<BundleRelease> listReleases(String tenantId, String bundleId) {
        return releaseRepo.list(tenantId, bundleId);
    }

    public BundleRelease getRelease(String tenantId, String bundleId, String version) {
        return releaseRepo.get(tenantId, bundleId, version).orElseThrow(
                () -> new IllegalArgumentException("release not found: " + version));
    }

    public Map<String, Object> publishRelease(String tenantId, String bundleId, String version) {
        List<RuleRevision> allRules = ruleRepo.listCurrentRules(tenantId, null);
        List<Map<String, Object>> snapshot = allRules.stream()
                .map(RuleResponseMapper::toDetail)
                .toList();

        BundleRelease release = releaseRepo.create(
                new BundleRelease(tenantId, bundleId, version, "deploying", snapshot, null, null));

        Map<String, Object> result = orchestrator.publishRelease(tenantId, bundleId, release);

        if (Boolean.TRUE.equals(result.get("ok"))) {
            releaseRepo.updateStatus(tenantId, bundleId, version, "active");
            String oldActive = releaseRepo.getActiveVersion(tenantId, bundleId);
            releaseRepo.setActiveVersion(tenantId, bundleId, version);
            if (oldActive != null && !oldActive.equals(version)) {
                releaseRepo.updateStatus(tenantId, bundleId, oldActive, "superseded");
            }
            stagingRepo.clear(tenantId, bundleId, version);
        } else {
            releaseRepo.updateStatus(tenantId, bundleId, version, "failed");
        }

        return result;
    }

    public Map<String, Object> rollbackTo(String tenantId, String bundleId, String targetVersion) {
        BundleRelease target = releaseRepo.get(tenantId, bundleId, targetVersion).orElseThrow(
                () -> new IllegalArgumentException("release not found: " + targetVersion));
        if (!"active".equals(target.status()) && !"superseded".equals(target.status())) {
            throw new BusinessException(409, "target release is not active or superseded: " + targetVersion);
        }

        Map<String, Object> result = orchestrator.rollbackTo(tenantId, bundleId, target);

        if (Boolean.TRUE.equals(result.get("ok"))) {
            String currentActive = releaseRepo.getActiveVersion(tenantId, bundleId);
            if (currentActive != null && !currentActive.equals(targetVersion)) {
                releaseRepo.updateStatus(tenantId, bundleId, currentActive, "superseded");
            }
            releaseRepo.updateStatus(tenantId, bundleId, targetVersion, "active");
            releaseRepo.setActiveVersion(tenantId, bundleId, targetVersion);
            stagingRepo.clear(tenantId, bundleId, targetVersion);
        }

        return result;
    }

    public void recordRelease(String tenantId, String bundleId, String version) {
        List<RuleRevision> allRules = ruleRepo.listCurrentRules(tenantId, null);
        List<Map<String, Object>> snapshot = allRules.stream()
                .map(RuleResponseMapper::toDetail)
                .toList();
        releaseRepo.create(
                new BundleRelease(tenantId, bundleId, version, "active", snapshot, null, null));
        String oldActive = releaseRepo.getActiveVersion(tenantId, bundleId);
        releaseRepo.setActiveVersion(tenantId, bundleId, version);
        if (oldActive != null && !oldActive.equals(version)) {
            releaseRepo.updateStatus(tenantId, bundleId, oldActive, "superseded");
        }
        stagingRepo.clear(tenantId, bundleId, version);
    }

    public String nextVersion(String tenantId, String bundleId) {
        String current = releaseRepo.getActiveVersion(tenantId, bundleId);
        if (current == null) {
            return "0.1.0";
        }
        String[] parts = current.split("\\.");
        int patch = parts.length >= 3 ? Integer.parseInt(parts[2]) + 1 : 1;
        String minor = parts.length >= 2 ? parts[1] : "0";
        String major = parts.length >= 1 ? parts[0] : "0";
        return major + "." + minor + "." + patch;
    }
}
