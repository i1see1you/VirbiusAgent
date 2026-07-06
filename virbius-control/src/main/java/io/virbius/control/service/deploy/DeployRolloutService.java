package io.virbius.control.service.deploy;

import io.virbius.control.common.exception.BusinessException;
import io.virbius.control.domain.DeployEvent;
import io.virbius.control.domain.DeployRollout;
import io.virbius.control.domain.DeployRolloutStateHelper;
import io.virbius.control.domain.TenantRolloutPolicy;
import io.virbius.control.domain.enums.DeployRolloutState;
import io.virbius.control.gateway.artifact.GatewayArtifactPart;
import io.virbius.control.gateway.artifact.GatewayArtifactPointer;
import io.virbius.control.gateway.artifact.RedisGatewayArtifactBlobStore;
import io.virbius.control.repository.BundleStagingRepository;
import io.virbius.control.repository.DeployRolloutRepository;
import io.virbius.control.repository.TenantRolloutPolicyRepository;
import io.virbius.control.service.ArtifactService;
import io.virbius.control.service.BundleReleaseService;
import io.virbius.control.service.EngineArtifactPointer;
import io.virbius.control.service.RedisEngineArtifactStore;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates machine-bucket canary deployments for engine + gateway. Edge is always full
 * release. Reuses the same Redis deploy lock as {@code PublishOrchestrator} to prevent
 * concurrent deploys.
 */
@Service
public class DeployRolloutService {

    private static final Logger log = LoggerFactory.getLogger(DeployRolloutService.class);
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneOffset.UTC);
    private static final String LOCK_PREFIX = "virbius:deploy:";
    private static final long LOCK_TTL_SECONDS = 1800;

    private final DeployRolloutRepository rolloutRepo;
    private final TenantRolloutPolicyRepository policyRepo;
    private final DeployRolloutPointerStore pointerStore;
    private final DeployArtifactWriter artifactWriter;
    private final RedisEngineArtifactStore engineStore;
    private final RedisGatewayArtifactBlobStore gatewayBlobStore;
    private final ArtifactService artifactService;
    private final NodeRegistryService nodeRegistryService;
    private final io.virbius.control.repository.EdgeArtifactMetaRepository edgeMetaRepo;
    private final BundleReleaseService releaseService;
    private final BundleStagingRepository stagingRepo;

    public DeployRolloutService(
            DeployRolloutRepository rolloutRepo,
            TenantRolloutPolicyRepository policyRepo,
            DeployRolloutPointerStore pointerStore,
            DeployArtifactWriter artifactWriter,
            RedisEngineArtifactStore engineStore,
            RedisGatewayArtifactBlobStore gatewayBlobStore,
            ArtifactService artifactService,
            NodeRegistryService nodeRegistryService,
            io.virbius.control.repository.EdgeArtifactMetaRepository edgeMetaRepo,
            BundleReleaseService releaseService,
            BundleStagingRepository stagingRepo) {
        this.rolloutRepo = rolloutRepo;
        this.policyRepo = policyRepo;
        this.pointerStore = pointerStore;
        this.artifactWriter = artifactWriter;
        this.engineStore = engineStore;
        this.gatewayBlobStore = gatewayBlobStore;
        this.artifactService = artifactService;
        this.nodeRegistryService = nodeRegistryService;
        this.edgeMetaRepo = edgeMetaRepo;
        this.releaseService = releaseService;
        this.stagingRepo = stagingRepo;
    }

    // ---------------------------------------------------------------
    // Prepare
    // ---------------------------------------------------------------

    public DeployRollout prepare(String tenantId, String bundleId, String bundleVersion,
                                 String targetVersion,
                                 String layer, String description, String operator) {
        Optional<DeployRollout> active = rolloutRepo.findActive(tenantId);
        if (active.isPresent()) {
            throw new BusinessException(409,
                    "Active deployment " + active.get().deployId()
                            + ", state=" + active.get().state()
                            + ". Please wait for completion or rollback.");
        }

        if (targetVersion == null || targetVersion.isBlank()) {
            targetVersion = DTF.format(Instant.now());
        }
        String resolvedVersion = (bundleVersion == null || bundleVersion.isBlank())
                ? releaseService.nextVersion(tenantId, bundleId)
                : bundleVersion;
        String bundleFullId = generateBundleId(bundleId, resolvedVersion);
        boolean doEngine = layer == null || layer.isBlank() || "cloud".equalsIgnoreCase(layer);
        boolean doGateway = layer == null || layer.isBlank() || "gateway".equalsIgnoreCase(layer);
        boolean doEdge = layer == null || layer.isBlank() || "edge".equalsIgnoreCase(layer);
        String effectiveLayer;
        if (doEngine && doGateway && doEdge) {
            effectiveLayer = "cloud+gateway+edge";
        } else if (doEngine && doGateway) {
            effectiveLayer = "cloud+gateway";
        } else if (doEngine && doEdge) {
            effectiveLayer = "cloud+edge";
        } else if (doGateway && doEdge) {
            effectiveLayer = "gateway+edge";
        } else if (doEngine) {
            effectiveLayer = "cloud";
        } else if (doGateway) {
            effectiveLayer = "gateway";
        } else {
            effectiveLayer = "edge";
        }

        acquireLock(tenantId);
        try {
            // Clear stale staging diffs from any previous incomplete deploy
            stagingRepo.clear(tenantId, bundleId, resolvedVersion);

            TenantRolloutPolicy policy = policyRepo.getOrDefault(tenantId);
            List<Integer> ladder = policy.canaryLadder();

            EngineArtifactPointer stableEnginePtr = engineStore.getPointer(tenantId).orElse(null);
            GatewayArtifactPointer stableGatewayPtr = gatewayBlobStore.getPointer(tenantId).orElse(null);

            long stableEngineRev = stableEnginePtr != null ? stableEnginePtr.artifactRevision() : 0L;
            long stableGatewayRev = stableGatewayPtr != null ? stableGatewayPtr.artifactRevision() : 0L;

            long canaryEngineRev = doEngine
                    ? artifactWriter.writeEngineCanary(tenantId, targetVersion, "prepare")
                    : stableEngineRev;
            long canaryGatewayRev = doGateway
                    ? artifactWriter.writeGatewayCanary(tenantId, "prepare")
                    : stableGatewayRev;

            // Edge canary: snapshot stable manifest, write canary manifest
            long stableEdgeRev = 0L;
            long canaryEdgeRev = 0L;
            if (doEdge) {
                // Snapshot existing edge-manifest.json as stable backup (if any)
                Map<String, String> stablePaths = artifactService.snapshotStableEdge(tenantId);
                // Write canary manifest from current rules
                Map<String, String> canaryPaths = artifactService.writeEdgeForPool(tenantId, "canary");
                // If no prior stable manifest existed, write stable from current rules too
                if (stablePaths.isEmpty()) {
                    artifactService.writeEdgeForPool(tenantId, "stable");
                }
                stableEdgeRev = 1L;
                canaryEdgeRev = canaryPaths.isEmpty() ? 0L : 1L;
            }

            String deployId = UUID.randomUUID().toString().replace("-", "");
            String now = DTF.format(Instant.now());
            DeployRollout rollout = new DeployRollout(
                    deployId, tenantId, bundleFullId,
                    DeployRolloutState.PENDING.value(),
                    0, false,
                    targetVersion, null,
                    canaryEngineRev, stableEngineRev,
                    canaryGatewayRev, stableGatewayRev,
                    canaryEdgeRev, stableEdgeRev,
                    ladder, null, null, null,
                    operator, description);

            rolloutRepo.create(rollout);

            DeployRolloutPointer pointer = new DeployRolloutPointer(
                    tenantId, deployId, DeployRolloutState.PENDING.value(),
                    0,
                    canaryEngineRev, stableEngineRev,
                    canaryGatewayRev, stableGatewayRev,
                    canaryEdgeRev, stableEdgeRev,
                    targetVersion, null, now);
            pointerStore.writePointer(pointer);
            pointerStore.notifyChange(tenantId, deployId, "prepare");

            rolloutRepo.recordEvent(new DeployEvent(
                    UUID.randomUUID().toString().replace("-", ""),
                    deployId, tenantId,
                    "prepare", description, null,
                    null, null,
                    DeployRolloutState.PENDING.value(), 0,
                    effectiveLayer, operator, description, Instant.now()));

            return rollout;
        } finally {
            releaseLock(tenantId);
        }
    }

    // ---------------------------------------------------------------
    // Upgrade (start canary / advance ladder / resume from paused)
    // ---------------------------------------------------------------

    public DeployRollout upgrade(String tenantId, String deployId,
                                 String operator, String note) {
        DeployRollout rollout = rolloutRepo.get(deployId)
                .filter(r -> r.tenantId().equals(tenantId))
                .orElseThrow(() -> new BusinessException("Deployment not found: " + deployId));

        String fromState = rollout.state();
        DeployRolloutState from = DeployRolloutState.parse(fromState);
        List<Integer> ladder = rollout.canaryLadder();

        String toState;
        int toPercent;

        switch (from) {
            case PENDING:
                DeployRolloutStateHelper.validateTransition(fromState, DeployRolloutState.CANARY.value());
                int firstStep = ladder.isEmpty() ? 5 : ladder.get(0);
                toState = DeployRolloutState.CANARY.value();
                toPercent = firstStep;
                break;
            case CANARY:
                int next = computeNextEffectiveStep(tenantId, ladder, rollout.canaryPercent());
                if (next == 0) {
                    throw new BusinessException("Already at the end of the canary ladder, execute fullRelease or deployEdge");
                }
                if (next >= 100) {
                    toState = DeployRolloutState.FULL.value();
                    toPercent = 100;
                } else {
                    toState = DeployRolloutState.CANARY.value();
                    toPercent = next;
                }
                break;
            case PAUSED:
                DeployRolloutStateHelper.validateTransition(fromState, DeployRolloutState.CANARY.value());
                toState = DeployRolloutState.CANARY.value();
                toPercent = rollout.canaryPercent();
                break;
            default:
                throw new BusinessException("Current state " + fromState + " does not support upgrade operation");
        }

        // TODO: optional PromotionGateService check for deploy rollout
        acquireLock(tenantId);
        try {
            String now = DTF.format(Instant.now());
            rolloutRepo.updateState(deployId, toState, toPercent,
                    from == DeployRolloutState.FULL);

            DeployRolloutPointer currentPtr = pointerStore.getPointer(tenantId).orElse(null);
            if (currentPtr != null) {
                DeployRolloutPointer updated = new DeployRolloutPointer(
                        currentPtr.tenantId(), currentPtr.deployId(), toState, toPercent,
                        currentPtr.canaryEngineRevision(), currentPtr.stableEngineRevision(),
                        currentPtr.canaryGatewayRevision(), currentPtr.stableGatewayRevision(),
                        currentPtr.canaryEdgeRevision(), currentPtr.stableEdgeRevision(),
                        currentPtr.targetVersion(), currentPtr.prevVersion(), now);
                pointerStore.writePointer(updated);
                pointerStore.notifyChange(tenantId, deployId, toState);
            }

            rolloutRepo.recordEvent(new DeployEvent(
                    UUID.randomUUID().toString().replace("-", ""),
                    deployId, tenantId,
                    "upgrade", note, null,
                    fromState, rollout.canaryPercent(),
                    toState, toPercent,
                    "cloud+gateway", operator, note, Instant.now()));

            log.info("deploy upgraded tenant={} deploy={} {}% {}->{}",
                    tenantId, deployId, toPercent, fromState, toState);

            return rolloutRepo.get(deployId).orElseThrow();
        } finally {
            releaseLock(tenantId);
        }
    }

    // ---------------------------------------------------------------
    // Pause
    // ---------------------------------------------------------------

    public DeployRollout pause(String tenantId, String deployId, String operator, String note) {
        DeployRollout rollout = rolloutRepo.get(deployId)
                .filter(r -> r.tenantId().equals(tenantId))
                .orElseThrow(() -> new BusinessException("Deployment not found: " + deployId));

        DeployRolloutStateHelper.validateTransition(rollout.state(), DeployRolloutState.PAUSED.value());

        acquireLock(tenantId);
        try {
            String now = DTF.format(Instant.now());
            rolloutRepo.updateState(deployId, DeployRolloutState.PAUSED.value(),
                    rollout.canaryPercent(), rollout.edgeDeployed());

            DeployRolloutPointer currentPtr = pointerStore.getPointer(tenantId).orElse(null);
            if (currentPtr != null) {
                DeployRolloutPointer updated = new DeployRolloutPointer(
                        currentPtr.tenantId(), currentPtr.deployId(),
                        DeployRolloutState.PAUSED.value(), currentPtr.canaryPercent(),
                        currentPtr.canaryEngineRevision(), currentPtr.stableEngineRevision(),
                        currentPtr.canaryGatewayRevision(), currentPtr.stableGatewayRevision(),
                        currentPtr.canaryEdgeRevision(), currentPtr.stableEdgeRevision(),
                        currentPtr.targetVersion(), currentPtr.prevVersion(), now);
                pointerStore.writePointer(updated);
                pointerStore.notifyChange(tenantId, deployId, "pause");
            }

            rolloutRepo.recordEvent(new DeployEvent(
                    UUID.randomUUID().toString().replace("-", ""),
                    deployId, tenantId,
                    "pause", note, null,
                    rollout.state(), rollout.canaryPercent(),
                    DeployRolloutState.PAUSED.value(), rollout.canaryPercent(),
                    "cloud+gateway", operator, note, Instant.now()));

            return rolloutRepo.get(deployId).orElseThrow();
        } finally {
            releaseLock(tenantId);
        }
    }

    // ---------------------------------------------------------------
    // Rollback (any active state -> rolled_back)
    // ---------------------------------------------------------------

    public DeployRollout rollback(String tenantId, String deployId, String operator, String note) {
        DeployRollout rollout = rolloutRepo.get(deployId)
                .filter(r -> r.tenantId().equals(tenantId))
                .orElseThrow(() -> new BusinessException("Deployment not found: " + deployId));

        DeployRolloutStateHelper.validateTransition(rollout.state(), DeployRolloutState.ROLLED_BACK.value());

        acquireLock(tenantId);
        try {
            rolloutRepo.markFinalized(deployId, DeployRolloutState.ROLLED_BACK.value());
            pointerStore.clearPointer(tenantId);
            pointerStore.notifyChange(tenantId, deployId, "rollback");

            rolloutRepo.recordEvent(new DeployEvent(
                    UUID.randomUUID().toString().replace("-", ""),
                    deployId, tenantId,
                    "rollback", note, null,
                    rollout.state(), rollout.canaryPercent(),
                    DeployRolloutState.ROLLED_BACK.value(), 0,
                    "cloud+gateway", operator, note, Instant.now()));

            // Clear staging diffs so next "prepare" shows a clean diff
            String baseBundleId = rollout.bundleId().contains("@")
                    ? rollout.bundleId().substring(0, rollout.bundleId().indexOf("@"))
                    : rollout.bundleId();
            stagingRepo.clear(tenantId, baseBundleId, rollout.targetVersion());

            log.info("deploy rolled back tenant={} deploy={}", tenantId, deployId);
            return rolloutRepo.get(deployId).orElseThrow();
        } finally {
            releaseLock(tenantId);
        }
    }

    // ---------------------------------------------------------------
    // Deploy Edge (full release to all edge nodes)
    // ---------------------------------------------------------------

    public DeployRollout deployEdge(String tenantId, String deployId, String operator, String note) {
        DeployRollout rollout = rolloutRepo.get(deployId)
                .filter(r -> r.tenantId().equals(tenantId))
                .orElseThrow(() -> new BusinessException("Deployment not found: " + deployId));

        DeployRolloutStateHelper.validateTransition(rollout.state(), DeployRolloutState.EDGE_DONE.value());

        acquireLock(tenantId);
        try {
            // Edge canary manifests were already written in prepare(); just transition state
            rolloutRepo.updateState(deployId, DeployRolloutState.EDGE_DONE.value(),
                    rollout.canaryPercent(), true);

            pointerStore.notifyChange(tenantId, deployId, "edge_done");

            rolloutRepo.recordEvent(new DeployEvent(
                    UUID.randomUUID().toString().replace("-", ""),
                    deployId, tenantId,
                    "deploy_edge", note, null,
                    rollout.state(), rollout.canaryPercent(),
                    DeployRolloutState.EDGE_DONE.value(), rollout.canaryPercent(),
                    "edge", operator, note, Instant.now()));

            log.info("edge deployed tenant={} deploy={}", tenantId, deployId);
            return rolloutRepo.get(deployId).orElseThrow();
        } finally {
            releaseLock(tenantId);
        }
    }

    // ---------------------------------------------------------------
    // Finalize (edge_done -> finalized, update stable pointers)
    // ---------------------------------------------------------------

    public DeployRollout finalize(String tenantId, String deployId, String operator, String note) {
        DeployRollout rollout = rolloutRepo.get(deployId)
                .filter(r -> r.tenantId().equals(tenantId))
                .orElseThrow(() -> new BusinessException("Deployment not found: " + deployId));

        DeployRolloutStateHelper.validateTransition(rollout.state(), DeployRolloutState.FINALIZED.value());

        acquireLock(tenantId);
        try {
            if (rollout.canaryGatewayRevision() != null && rollout.canaryGatewayRevision() > 0) {
                GatewayArtifactPointer stableGatewayPtr = gatewayBlobStore.getPointer(tenantId).orElse(null);
                if (stableGatewayPtr != null) {
                    long canaryRev = rollout.canaryGatewayRevision();
                    String alKey = gatewayBlobStore.locatorFor(tenantId, canaryRev, GatewayArtifactPart.ACCESS_LISTS);
                    String srKey = gatewayBlobStore.locatorFor(tenantId, canaryRev, GatewayArtifactPart.SCENE_REGISTRY);
                    GatewayArtifactPointer newPtr = new GatewayArtifactPointer(
                            tenantId,
                            canaryRev,
                            DTF.format(Instant.now()),
                            stableGatewayPtr.bundleId(),
                            stableGatewayPtr.bundleVersion(),
                            stableGatewayPtr.schemaVersion(),
                            stableGatewayPtr.storage(),
                            alKey,
                            srKey,
                            stableGatewayPtr.accessListsUrl(),
                            stableGatewayPtr.sceneRegistryUrl(),
                            stableGatewayPtr.accessListsSha256(),
                            stableGatewayPtr.sceneRegistrySha256(),
                            stableGatewayPtr.accessListsBytes(),
                            stableGatewayPtr.sceneRegistryBytes(),
                            stableGatewayPtr.publishId(),
                            "finalize");
                    gatewayBlobStore.updatePointer(newPtr);
                    gatewayBlobStore.notifyPublished(newPtr);
                }
            }

            if (rollout.canaryEngineRevision() != null && rollout.canaryEngineRevision() > 0) {
                EngineArtifactPointer stableEnginePtr = engineStore.getPointer(tenantId).orElse(null);
                if (stableEnginePtr != null) {
                    EngineArtifactPointer newPtr = new EngineArtifactPointer(
                            tenantId,
                            rollout.canaryEngineRevision(),
                            stableEnginePtr.contentSha256(),
                            rollout.targetVersion(),
                            DTF.format(Instant.now()),
                            "finalize");
                    engineStore.updatePointer(tenantId, newPtr);
                    engineStore.notifyReload(tenantId, rollout.canaryEngineRevision());
                }
            }

            // Promote edge canary: remove stable, rename canary → stable
            if (rollout.canaryEdgeRevision() != null && rollout.canaryEdgeRevision() > 0) {
                artifactService.promoteEdgeCanary(tenantId);
            }

            rolloutRepo.markFinalized(deployId, DeployRolloutState.FINALIZED.value());
            pointerStore.clearPointer(tenantId);
            pointerStore.notifyChange(tenantId, deployId, "finalized");

            rolloutRepo.recordEvent(new DeployEvent(
                    UUID.randomUUID().toString().replace("-", ""),
                    deployId, tenantId,
                    "finalize", note, null,
                    rollout.state(), rollout.canaryPercent(),
                    DeployRolloutState.FINALIZED.value(), 0,
                    "cloud+gateway", operator, note, Instant.now()));

            // Record release (frozen snapshot + active version + clear staging)
            String baseBundleId = rollout.bundleId().contains("@")
                    ? rollout.bundleId().substring(0, rollout.bundleId().indexOf("@"))
                    : rollout.bundleId();
            String releaseVersion = rollout.bundleId().contains("@")
                    ? rollout.bundleId().substring(rollout.bundleId().indexOf("@") + 1)
                    : rollout.targetVersion();
            releaseService.recordRelease(tenantId, baseBundleId, releaseVersion);

            log.info("deploy finalized tenant={} deploy={}", tenantId, deployId);
            return rolloutRepo.get(deployId).orElseThrow();
        } finally {
            releaseLock(tenantId);
        }
    }

    // ---------------------------------------------------------------
    // Lock helpers
    // ---------------------------------------------------------------

    private void acquireLock(String tenantId) {
        String lockKey = LOCK_PREFIX + tenantId;
        if (pointerStore.redisAvailable()) {
            String owner = "control-" + Thread.currentThread().getId() + "-" + System.currentTimeMillis();
            pointerStore.getPointer(tenantId); // warm up connection
            try (var jedis = pointerStore.requireJedis()) {
                boolean ok = jedis.setnx(lockKey, owner) == 1;
                if (ok) {
                    jedis.expire(lockKey, LOCK_TTL_SECONDS);
                    return;
                }
            } catch (Exception e) {
                log.warn("lock acquire error, proceeding without lock: {}", e.getMessage());
                return;
            }
            throw new BusinessException(423, "Deployment in progress, please wait for the current deployment to complete");
        }
    }

    private void releaseLock(String tenantId) {
        String lockKey = LOCK_PREFIX + tenantId;
        if (pointerStore.redisAvailable()) {
            try (var jedis = pointerStore.requireJedis()) {
                String current = jedis.get(lockKey);
                if (current != null && current.startsWith("control-")) {
                    jedis.del(lockKey);
                }
            } catch (Exception e) {
                log.warn("lock release error: {}", e.getMessage());
            }
        }
    }

    // ---------------------------------------------------------------
    // Ladder step skip helpers
    // ---------------------------------------------------------------

    /**
     * Find the next ladder step that actually moves at least one new live node into the canary
     * pool. Intermediate ladder steps that wouldn't change any node's pool are skipped.
     *
     * <p>Falls back to the plain next ladder step if no live nodes are visible (e.g. Redis
     * unavailable or nodes just starting up) so the caller keeps the original behaviour.
     */
    int computeNextEffectiveStep(String tenantId, List<Integer> ladder, int currentPercent) {
        int next = DeployRolloutStateHelper.nextLadderStep(ladder, currentPercent);
        if (next == 0) {
            return 0;
        }
        List<Integer> buckets = collectLiveBuckets(tenantId);
        if (buckets.isEmpty()) {
            return next;
        }
        int candidate = next;
        while (candidate > 0 && candidate < 100) {
            int low = currentPercent;
            int high = candidate;
            boolean hasNewNode = false;
            for (int b : buckets) {
                if (b >= low && b < high) {
                    hasNewNode = true;
                    break;
                }
            }
            if (hasNewNode) {
                return candidate;
            }
            int skipped = candidate;
            candidate = DeployRolloutStateHelper.nextLadderStep(ladder, candidate);
            log.info("skip ladder step {}% (no new node enters canary) tenant={}", skipped, tenantId);
            if (candidate == 0) {
                return 100;
            }
        }
        return candidate > 0 ? candidate : 100;
    }

    private List<Integer> collectLiveBuckets(String tenantId) {
        List<Integer> buckets = new ArrayList<>();
        for (String layer : List.of("cloud", "gateway")) {
            try {
                for (Map<String, String> node : nodeRegistryService.listNodes(layer, tenantId)) {
                    String instanceId = node.get("instance_id");
                    if (instanceId == null || instanceId.isBlank()) {
                        continue;
                    }
                    buckets.add(BucketCalculator.bucketOf(instanceId));
                }
            } catch (Exception ex) {
                log.warn("collect live buckets failed layer={} tenant={}: {}", layer, tenantId, ex.getMessage());
            }
        }
        return buckets;
    }

    static String generateBundleId(String bundleId, String bundleVersion) {
        String base = (bundleId != null && !bundleId.isBlank()) ? bundleId : "default";
        if (bundleVersion == null || bundleVersion.isBlank()) {
            throw new IllegalArgumentException("bundleVersion must be resolved before calling generateBundleId");
        }
        return base + "@" + bundleVersion;
    }
}
