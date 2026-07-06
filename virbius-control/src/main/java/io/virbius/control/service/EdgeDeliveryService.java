package io.virbius.control.service;

import io.virbius.control.common.exception.ResourceNotFoundException;
import io.virbius.control.domain.EdgeArtifactMeta;
import io.virbius.control.repository.EdgeArtifactMetaRepository;
import io.virbius.control.service.deploy.DeployRolloutPointerStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class EdgeDeliveryService {

    private final Path dataDir;
    private final EdgeArtifactMetaRepository metaRepository;
    private final DeployRolloutPointerStore pointerStore;

    public EdgeDeliveryService(
            @Value("${virbius.data-dir:./data}") String dataDir,
            EdgeArtifactMetaRepository metaRepository,
            DeployRolloutPointerStore pointerStore) {
        this.dataDir = Path.of(dataDir);
        this.metaRepository = metaRepository;
        this.pointerStore = pointerStore;
    }

    /**
     * Returns the policy version response. When a canary deployment is active, includes both
     * stable and canary revision info plus the canary percentage so the SDK can decide which
     * pool to use based on its device_id.
     */
    public Map<String, Object> policyVersion(String tenantId, String appId) {
        EdgeArtifactMeta stable = requireMeta(tenantId, appId, "stable");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenant_id", tenantId);
        out.put("app_id", appId);
        out.put("artifact_revision", stable.artifactRevision());
        out.put("content_sha256", stable.contentSha256());
        out.put("published_at", stable.publishedAt().toString());

        EdgeArtifactMeta canary = metaRepository.get(tenantId, appId, "canary").orElse(null);
        if (canary != null) {
            out.put("stable_revision", stable.artifactRevision());
            out.put("stable_sha256", stable.contentSha256());
            out.put("canary_revision", canary.artifactRevision());
            out.put("canary_sha256", canary.contentSha256());
            // Read active rollout canary_percent for the SDK to make pool decision
            int canaryPercent = pointerStore.getPointer(tenantId)
                    .map(p -> p.canaryPercent())
                    .orElse(0);
            out.put("canary_percent", canaryPercent);
        }
        return out;
    }

    public EdgeArtifactMeta requireMeta(String tenantId, String appId, String pool) {
        return metaRepository
                .get(tenantId, appId, pool)
                .orElseThrow(() -> new ResourceNotFoundException("edge artifact", tenantId + "/" + appId + " pool=" + pool));
    }

    public byte[] readManifestBytes(String tenantId, String appId, String pool) {
        Path file = manifestPath(tenantId, appId, pool);
        if (!Files.isRegularFile(file)) {
            throw new ResourceNotFoundException("edge manifest file", file.toString());
        }
        try {
            return Files.readAllBytes(file);
        } catch (Exception e) {
            throw new IllegalStateException("failed to read edge manifest: " + e.getMessage(), e);
        }
    }

    /**
     * Pool-specific manifest path. Stable uses the backward-compatible filename; canary uses a
     * suffixed filename that exists only during an active deploy-rollout.
     */
    public Path manifestPath(String tenantId, String appId, String pool) {
        String filename = pool != null && pool.equals("canary")
                ? "edge-manifest-canary.json"
                : "edge-manifest.json";
        return dataDir.resolve("edge").resolve(tenantId).resolve(appId).resolve(filename);
    }
}
