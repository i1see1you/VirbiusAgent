package io.virbius.control.service;

import io.virbius.control.common.exception.ResourceNotFoundException;
import io.virbius.control.domain.GatewayArtifactMeta;
import io.virbius.control.gateway.artifact.GatewayArtifactPart;
import io.virbius.control.gateway.artifact.GatewayArtifactPointer;
import io.virbius.control.gateway.artifact.GatewayArtifactPublisher;
import io.virbius.control.repository.GatewayArtifactMetaRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class GatewayDeliveryService {

    private final Path dataDir;
    private final GatewayArtifactMetaRepository metaRepository;
    private final GatewayArtifactPublisher publisher;

    public GatewayDeliveryService(
            @Value("${virbius.data-dir:./data}") String dataDir,
            GatewayArtifactMetaRepository metaRepository,
            GatewayArtifactPublisher publisher) {
        this.dataDir = Path.of(dataDir);
        this.metaRepository = metaRepository;
        this.publisher = publisher;
    }

    public Map<String, Object> policyVersion(String tenantId) {
        GatewayArtifactMeta meta = requireMeta(tenantId);
        GatewayArtifactPointer pointer = publisher.currentPointer(tenantId).orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenant_id", tenantId);
        out.put("artifact_revision", meta.artifactRevision());
        out.put("access_lists_sha256", meta.accessListsSha256());
        out.put("scene_registry_sha256", meta.sceneRegistrySha256());
        out.put("published_at", meta.publishedAt().toString());
        out.put("storage", meta.storage() != null ? meta.storage() : "redis");
        out.put("schema_version", "2");
        if (pointer != null) {
            if (pointer.accessListsKey() != null) {
                out.put("access_lists_locator", pointer.accessListsKey());
            }
            if (pointer.sceneRegistryKey() != null) {
                out.put("scene_registry_locator", pointer.sceneRegistryKey());
            }
            if (pointer.accessListsUrl() != null) {
                out.put("access_lists_locator", pointer.accessListsUrl());
            }
            if (pointer.sceneRegistryUrl() != null) {
                out.put("scene_registry_locator", pointer.sceneRegistryUrl());
            }
        }
        return out;
    }

    public GatewayArtifactMeta requireMeta(String tenantId) {
        return metaRepository
                .get(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("gateway artifact", tenantId));
    }

    public byte[] readPartBytes(String tenantId, GatewayArtifactPart part) {
        if (publisher.isEnabled()) {
            try {
                return publisher.readPart(tenantId, part);
            } catch (Exception ex) {
                return readLocalPartBytes(tenantId, part);
            }
        }
        return readLocalPartBytes(tenantId, part);
    }

    public Map<String, Object> adminDetail(String tenantId) {
        Map<String, Object> out = new LinkedHashMap<>(policyVersion(tenantId));
        publisher.currentPointer(tenantId).ifPresent(p -> {
            out.put("publish_id", p.publishId());
            out.put("trigger", p.trigger());
        });
        return out;
    }

    public List<Map<String, String>> listNodes(String tenantId) {
        return publisher.listNodeAcks(tenantId);
    }

    private byte[] readLocalPartBytes(String tenantId, GatewayArtifactPart part) {
        Path file = part == GatewayArtifactPart.ACCESS_LISTS
                ? dataDir.resolve("gateway").resolve(tenantId + "-access-lists.json")
                : dataDir.resolve("gateway").resolve(tenantId + "-scene-registry.json");
        if (!Files.isRegularFile(file)) {
            throw new ResourceNotFoundException("gateway artifact file", file.toString());
        }
        try {
            return Files.readAllBytes(file);
        } catch (Exception e) {
            throw new IllegalStateException("failed to read " + file + ": " + e.getMessage(), e);
        }
    }
}
