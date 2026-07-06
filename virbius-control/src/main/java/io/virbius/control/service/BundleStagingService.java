package io.virbius.control.service;

import io.virbius.control.domain.BundleStaging;
import io.virbius.control.domain.RuleRevision;
import io.virbius.control.repository.BundleReleaseRepository;
import io.virbius.control.repository.BundleStagingRepository;
import io.virbius.control.repository.RegistryRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class BundleStagingService {

    private static final String DEFAULT_BUNDLE = "poc-default";

    private final BundleStagingRepository stagingRepo;
    private final BundleReleaseRepository releaseRepo;
    private final RegistryRepository ruleRepo;
    private final PublishService publishService;
    private final AccessListService accessListService;
    private final ArtifactService artifactService;

    public BundleStagingService(
            BundleStagingRepository stagingRepo,
            BundleReleaseRepository releaseRepo,
            RegistryRepository ruleRepo,
            PublishService publishService,
            AccessListService accessListService,
            ArtifactService artifactService) {
        this.stagingRepo = stagingRepo;
        this.releaseRepo = releaseRepo;
        this.ruleRepo = ruleRepo;
        this.publishService = publishService;
        this.accessListService = accessListService;
        this.artifactService = artifactService;
    }

    public BundleStaging getStaging(String tenantId, String bundleId, String layer) {
        return stagingRepo.get(tenantId, bundleId, layer).orElse(null);
    }

    public Map<String, Object> applyRuleChange(String tenantId, String bundleId, String layer, String ruleId, String diffType) {
        String baseVersion = releaseRepo.getActiveVersion(tenantId, bundleId);
        if (baseVersion == null) {
            baseVersion = "0.1.0";
        }
        BundleStaging staging = stagingRepo.getOrCreate(tenantId, bundleId, layer, baseVersion);
        staging = stagingRepo.applyRuleDiff(tenantId, bundleId, layer, ruleId, diffType);

        autoDeployStaging(tenantId, bundleId, layer, ruleId, diffType);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("staged", true);
        result.put("auto_deployed", true);
        result.put("layer", layer);
        result.put("rule_id", ruleId);
        result.put("diff_type", diffType);
        result.put("staging_version", staging.version());
        return result;
    }

    public Map<String, Object> removeRuleDiff(String tenantId, String bundleId, String layer, String ruleId) {
        stagingRepo.removeRuleDiff(tenantId, bundleId, layer, ruleId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("removed", true);
        result.put("layer", layer);
        result.put("rule_id", ruleId);
        return result;
    }

    private void autoDeployStaging(String tenantId, String bundleId, String layer, String ruleId, String diffType) {
        RuleRevision rule = ruleRepo.getCurrentRule(tenantId, ruleId).orElse(null);
        if (rule == null) {
            return;
        }
        boolean leavingPlane = "disabled".equals(rule.rolloutState()) || "draft".equals(rule.rolloutState());
        switch (layer) {
            case "cloud" -> {
                if (leavingPlane) {
                    publishService.ruleRuntimeRemove(tenantId, ruleId);
                } else {
                    publishService.ruleRuntimeSnapshot(tenantId, ruleId);
                }
            }
            case "gateway" -> accessListService.refreshArtifacts(tenantId);
            case "edge" -> artifactService.writeEdgeOnly(tenantId);
        }
    }
}
