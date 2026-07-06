package io.virbius.control.gateway.artifact;

import io.virbius.control.domain.GatewayArtifactMeta;
import io.virbius.control.repository.GatewayArtifactMetaRepository;
import io.virbius.control.service.ArtifactService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class GatewayArtifactPublisher {

    private static final Logger log = LoggerFactory.getLogger(GatewayArtifactPublisher.class);

    private final GatewayArtifactProperties properties;
    private final RedisGatewayArtifactBlobStore redisStore;
    private final OssGatewayArtifactBlobStore ossStore;
    private final GatewayArtifactMetaRepository metaRepository;
    private final ArtifactService artifactService;

    public GatewayArtifactPublisher(
            GatewayArtifactProperties properties,
            RedisGatewayArtifactBlobStore redisStore,
            OssGatewayArtifactBlobStore ossStore,
            GatewayArtifactMetaRepository metaRepository,
            ArtifactService artifactService) {
        this.properties = properties;
        this.redisStore = redisStore;
        this.ossStore = ossStore;
        this.metaRepository = metaRepository;
        this.artifactService = artifactService;
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    public Optional<GatewayArtifactPublishResult> publishIfEnabled(
            String tenantId, Map<String, Object> bundleMetadata, String trigger) {
        if (!properties.isEnabled()) {
            return Optional.empty();
        }
        return Optional.of(publish(tenantId, bundleMetadata, trigger));
    }

    public GatewayArtifactPublishResult publish(String tenantId, Map<String, Object> bundleMetadata, String trigger) {
        byte[] accessLists = artifactService.buildAccessListsJsonBytes(tenantId, bundleMetadata);
        byte[] sceneRegistry = artifactService.buildSceneRegistryJsonBytes(tenantId, bundleMetadata);
        String alSha = GatewayArtifactHash.sha256Hex(accessLists);
        String srSha = GatewayArtifactHash.sha256Hex(sceneRegistry);
        boolean localWritten = false;
        if (properties.isLocalFallback()) {
            artifactService.writeGatewayLocalFiles(tenantId, bundleMetadata);
            localWritten = true;
        }
        long revision = redisStore.nextRevision(tenantId);
        Instant publishedAt = Instant.now();
        String publishId = UUID.randomUUID().toString();
        String bundleId = "poc-default";
        String bundleVersion = "0.1.0";
        GatewayArtifactBlobStore blobStore = resolveBlobStore();
        blobStore.putBlob(tenantId, revision, GatewayArtifactPart.ACCESS_LISTS, accessLists);
        blobStore.putBlob(tenantId, revision, GatewayArtifactPart.SCENE_REGISTRY, sceneRegistry);

        String alKey = null;
        String srKey = null;
        String alUrl = null;
        String srUrl = null;
        if (blobStore.storageType().equals("redis")) {
            alKey = redisStore.locatorFor(tenantId, revision, GatewayArtifactPart.ACCESS_LISTS);
            srKey = redisStore.locatorFor(tenantId, revision, GatewayArtifactPart.SCENE_REGISTRY);
        } else {
            alUrl = ossStore.locatorFor(tenantId, revision, GatewayArtifactPart.ACCESS_LISTS);
            srUrl = ossStore.locatorFor(tenantId, revision, GatewayArtifactPart.SCENE_REGISTRY);
        }

        GatewayArtifactPointer pointer = new GatewayArtifactPointer(
                tenantId,
                revision,
                publishedAt.toString(),
                bundleId,
                bundleVersion,
                "2",
                blobStore.storageType(),
                alKey,
                srKey,
                alUrl,
                srUrl,
                alSha,
                srSha,
                accessLists.length,
                sceneRegistry.length,
                publishId,
                trigger != null ? trigger : "manual");

        redisStore.updatePointer(pointer);
        redisStore.recordHistory(tenantId, revision, publishedAt.getEpochSecond());
        redisStore.notifyPublished(pointer);

        metaRepository.save(new GatewayArtifactMeta(
                tenantId,
                revision,
                alSha,
                srSha,
                publishedAt,
                publishId,
                pointer.trigger(),
                blobStore.storageType()));

        Map<String, Object> syncAck = buildSyncAck(tenantId, revision);
        String pointerKey = GatewayArtifactKeys.pointerKey(properties.getRedis().getPointerPrefix(), tenantId);
        return new GatewayArtifactPublishResult(
                revision,
                blobStore.storageType(),
                pointerKey,
                alSha,
                srSha,
                alKey != null ? alKey : alUrl,
                srKey != null ? srKey : srUrl,
                localWritten,
                syncAck);
    }

    public Optional<GatewayArtifactPointer> currentPointer(String tenantId) {
        if (!properties.isEnabled()) {
            return metaRepository.get(tenantId).map(this::pointerFromMeta);
        }
        return redisStore.getPointer(tenantId);
    }

    public byte[] readPart(String tenantId, GatewayArtifactPart part) {
        GatewayArtifactPointer pointer = redisStore
                .getPointer(tenantId)
                .orElseThrow(() -> new IllegalStateException("no gateway artifact pointer for " + tenantId));
        GatewayArtifactBlobStore store =
                "oss".equalsIgnoreCase(pointer.storage()) ? ossStore : redisStore;
        return store.getBlob(pointer, part);
    }

    public List<Map<String, String>> listNodeAcks(String tenantId) {
        return redisStore.listNodeAcks(tenantId);
    }

    private GatewayArtifactBlobStore resolveBlobStore() {
        if (properties.usesOssBlobs()) {
            if (!properties.getOss().configured()) {
                throw new IllegalStateException("storage=oss but virbius.gateway.artifact.oss.base-url is empty");
            }
            return ossStore;
        }
        return redisStore;
    }

    private Map<String, Object> buildSyncAck(String tenantId, long revision) {
        List<Map<String, String>> acks = redisStore.listNodeAcks(tenantId);
        int ok = 0;
        List<String> pending = new java.util.ArrayList<>();
        for (Map<String, String> ack : acks) {
            String rev = ack.get("artifact_revision");
            String status = ack.get("status");
            if (rev != null && Long.parseLong(rev) >= revision && !"error".equals(status)) {
                ok++;
            } else {
                pending.add(ack.getOrDefault("node_id", "?"));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nodes_ok", ok);
        out.put("nodes_total", acks.size());
        out.put("nodes_pending", pending);
        return out;
    }

    private GatewayArtifactPointer pointerFromMeta(GatewayArtifactMeta meta) {
        return new GatewayArtifactPointer(
                meta.tenantId(),
                meta.artifactRevision(),
                meta.publishedAt().toString(),
                null,
                null,
                "2",
                meta.storage(),
                null,
                null,
                null,
                null,
                meta.accessListsSha256(),
                meta.sceneRegistrySha256(),
                0,
                0,
                meta.publishId(),
                meta.trigger());
    }
}
