package io.virbius.control.service.deploy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.control.gateway.artifact.GatewayArtifactPart;
import io.virbius.control.gateway.artifact.RedisGatewayArtifactBlobStore;
import io.virbius.control.service.ArtifactService;
import io.virbius.control.service.PublishService;
import io.virbius.control.service.RedisEngineArtifactStore;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Writes canary artifact blobs (engine + gateway) at a fresh revision so they coexist with the
 * stable revision. The active artifact pointers ({@code virbius:engine:{tenant}:pointer},
 * {@code virbius:config:gateway:{tenant}}) are intentionally NOT updated here — the
 * {@link DeployRolloutPointer} carries both stable + canary revision numbers and lets each node
 * fetch the right one.
 */
@Component
public class DeployArtifactWriter {

    private static final Logger log = LoggerFactory.getLogger(DeployArtifactWriter.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RedisEngineArtifactStore engineStore;
    private final PublishService publishService;
    private final RedisGatewayArtifactBlobStore gatewayBlobStore;
    private final ArtifactService artifactService;

    public DeployArtifactWriter(
            RedisEngineArtifactStore engineStore,
            PublishService publishService,
            RedisGatewayArtifactBlobStore gatewayBlobStore,
            ArtifactService artifactService) {
        this.engineStore = engineStore;
        this.publishService = publishService;
        this.gatewayBlobStore = gatewayBlobStore;
        this.artifactService = artifactService;
    }

    public long writeEngineCanary(String tenantId, String policyVersion, String deployId) {
        try {
            Map<String, Object> payload = publishService.buildRuntimeSnapshotPayload(tenantId);
            payload.put("policy_version", policyVersion);
            byte[] body = JSON.writeValueAsBytes(payload);
            long revision = engineStore.nextRevision(tenantId);
            engineStore.putSnapshot(tenantId, revision, body);
            log.info("engine canary artifact written tenant={} revision={} version={} deploy={}",
                    tenantId, revision, policyVersion, deployId);
            return revision;
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "failed to write engine canary artifact: " + ex.getMessage(), ex);
        }
    }

    public long writeGatewayCanary(String tenantId, String deployId) {
        try {
            Map<String, Object> bundleMetadata = Map.of();
            byte[] accessLists = artifactService.buildAccessListsJsonBytes(tenantId, bundleMetadata);
            byte[] sceneRegistry = artifactService.buildSceneRegistryJsonBytes(tenantId, bundleMetadata);
            long revision = gatewayBlobStore.nextRevision(tenantId);
            gatewayBlobStore.putBlob(tenantId, revision, GatewayArtifactPart.ACCESS_LISTS, accessLists);
            gatewayBlobStore.putBlob(tenantId, revision, GatewayArtifactPart.SCENE_REGISTRY, sceneRegistry);
            log.info("gateway canary artifact written tenant={} revision={} deploy={}",
                    tenantId, revision, deployId);
            return revision;
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "failed to write gateway canary artifact: " + ex.getMessage(), ex);
        }
    }
}
