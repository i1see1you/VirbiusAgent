package io.virbius.control.service;

import io.virbius.control.common.exception.BusinessException;
import io.virbius.control.domain.BundleRelease;
import io.virbius.control.domain.RuleRevision;
import io.virbius.control.repository.BundleStagingRepository;
import io.virbius.control.repository.RegistryRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import redis.clients.jedis.JedisPool;

@Service
public class PublishOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PublishOrchestrator.class);
    private static final String LOCK_PREFIX = "virbius:deploy:";
    private static final long LOCK_TTL_SECONDS = 1800;

    private final RegistryRepository ruleRepo;
    private final BundleStagingRepository stagingRepo;
    private final PublishService publishService;
    private final AccessListService accessListService;
    private final ArtifactService artifactService;
    private final Optional<JedisPool> jedisPool;

    public PublishOrchestrator(
            RegistryRepository ruleRepo,
            BundleStagingRepository stagingRepo,
            PublishService publishService,
            AccessListService accessListService,
            ArtifactService artifactService,
            Optional<JedisPool> jedisPool) {
        this.ruleRepo = ruleRepo;
        this.stagingRepo = stagingRepo;
        this.publishService = publishService;
        this.accessListService = accessListService;
        this.artifactService = artifactService;
        this.jedisPool = jedisPool;
    }

    public Map<String, Object> publishRelease(String tenantId, String bundleId, BundleRelease release) {
        String lockKey = LOCK_PREFIX + tenantId + ":" + bundleId;
        String lockOwner = acquireLock(lockKey);
        if (lockOwner == null) {
            throw new BusinessException(423, "Deployment in progress, please wait for the current deployment to complete");
        }
        try {
            for (String layer : new String[]{"cloud", "gateway", "edge"}) {
                stagingRepo.updateStatus(tenantId, bundleId, layer, "deploying");
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("version", release.version());
            result.put("ok", true);

            Map<String, Object> engineResult = publishService.runtimeSnapshot(tenantId);
            result.put("cloud", engineResult);

            Map<String, Object> gwResult = accessListService.syncRules(tenantId);
            result.put("gateway", gwResult);

            result.put("edge", Map.of("note", "edge manifests written during deploy-rollout"));

            return result;
        } catch (Exception e) {
            log.error("publish release failed: {}", e.getMessage());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", false);
            result.put("error", e.getMessage());
            return result;
        } finally {
            releaseLock(lockKey, lockOwner);
        }
    }

    public Map<String, Object> rollbackTo(String tenantId, String bundleId, BundleRelease target) {
        String lockKey = LOCK_PREFIX + tenantId + ":" + bundleId;
        String lockOwner = acquireLock(lockKey);
        if (lockOwner == null) {
            throw new BusinessException(423, "Deployment in progress, please wait for the current deployment to complete");
        }
        try {
            for (String layer : new String[]{"cloud", "gateway", "edge"}) {
                stagingRepo.updateStatus(tenantId, bundleId, layer, "deploying");
            }

            restoreRulesFromSnapshot(tenantId, target);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("rollback_to", target.version());

            Map<String, Object> engineResult = publishService.runtimeSnapshot(tenantId);
            result.put("cloud", engineResult);

            Map<String, Object> gwResult = accessListService.syncRules(tenantId);
            result.put("gateway", gwResult);

            result.put("edge", Map.of("note", "edge manifests written during deploy-rollout"));

            return result;
        } catch (Exception e) {
            log.error("rollback failed: {}", e.getMessage());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", false);
            result.put("error", e.getMessage());
            return result;
        } finally {
            releaseLock(lockKey, lockOwner);
        }
    }

    @SuppressWarnings("unchecked")
    private void restoreRulesFromSnapshot(String tenantId, BundleRelease release) {
        for (Map<String, Object> ruleMap : release.frozenSnapshot()) {
            String ruleId = (String) ruleMap.get("rule_id");
            String rolloutState = (String) ruleMap.getOrDefault("rollout_state", "draft");
            Integer canaryPercent = ruleMap.get("canary_percent") instanceof Number n ? n.intValue() : null;
            try {
                ruleRepo.updateRollout(tenantId, ruleId, rolloutState, canaryPercent);
            } catch (Exception e) {
                log.warn("failed to restore rule {} from snapshot: {}", ruleId, e.getMessage());
            }
        }
    }

    private String acquireLock(String key) {
        if (jedisPool.isEmpty()) {
            return "local-" + System.currentTimeMillis();
        }
        String owner = "control-" + Thread.currentThread().getId() + "-" + System.currentTimeMillis();
        try (var jedis = jedisPool.get().getResource()) {
            boolean ok = jedis.setnx(key, owner) == 1;
            if (ok) {
                jedis.expire(key, LOCK_TTL_SECONDS);
                return owner;
            }
            return null;
        } catch (Exception e) {
            log.warn("lock acquire error, proceeding without lock: {}", e.getMessage());
            return owner;
        }
    }

    private void releaseLock(String key, String owner) {
        if (jedisPool.isEmpty() || owner == null) {
            return;
        }
        try (var jedis = jedisPool.get().getResource()) {
            String current = jedis.get(key);
            if (owner.equals(current)) {
                jedis.del(key);
            }
        } catch (Exception e) {
            log.warn("lock release error: {}", e.getMessage());
        }
    }

    public boolean isDeployInProgress(String tenantId, String bundleId) {
        if (jedisPool.isEmpty()) {
            return false;
        }
        try (var jedis = jedisPool.get().getResource()) {
            return jedis.exists(LOCK_PREFIX + tenantId + ":" + bundleId);
        } catch (Exception e) {
            return false;
        }
    }
}
