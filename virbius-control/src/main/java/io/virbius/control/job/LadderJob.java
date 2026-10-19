package io.virbius.control.job;

import io.virbius.control.common.exception.BusinessException;
import io.virbius.control.config.ControlJedisPools;
import io.virbius.control.domain.RuleRevision;
import io.virbius.control.domain.TenantRolloutPolicy;
import io.virbius.control.domain.enums.RolloutState;
import io.virbius.control.repository.RegistryRepository;
import io.virbius.control.repository.TenantRolloutPolicyRepository;
import io.virbius.control.service.PublishOrchestrator;
import io.virbius.control.service.RolloutService;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Auto canary-ladder advance. Multi-replica note: the mutation is serialized by
 * the per-tenant deploy lock inside {@code PublishOrchestrator} (423 → skip),
 * but that lock degrades to "proceed without lock" when Redis errors — exactly
 * the failover window. Since this job is auto-advance (delaying one tick is
 * free), it refuses to run while Redis is unhealthy instead of racing.
 */
@Component
public class LadderJob {

    private static final Logger log = LoggerFactory.getLogger(LadderJob.class);

    private final JdbcTemplate jdbc;
    private final RegistryRepository registry;
    private final TenantRolloutPolicyRepository policyRepository;
    private final RolloutService rolloutService;
    private final PublishOrchestrator orchestrator;
    private final ControlJedisPools jedisPools;

    public LadderJob(
            JdbcTemplate jdbc,
            RegistryRepository registry,
            TenantRolloutPolicyRepository policyRepository,
            RolloutService rolloutService,
            PublishOrchestrator orchestrator,
            ControlJedisPools jedisPools) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.policyRepository = policyRepository;
        this.rolloutService = rolloutService;
        this.orchestrator = orchestrator;
        this.jedisPools = jedisPools;
    }

    @Scheduled(fixedDelayString = "${virbius.rollout.ladder.poll-ms:900000}")
    public void tick() {
        if (!redisHealthy()) {
            return;
        }
        List<Map<String, Object>> running = jdbc.queryForList(
                "SELECT tenant_id, rule_id FROM tb_rule_ladder_state WHERE ladder_status = 'running'");
        for (Map<String, Object> row : running) {
            String tenantId = (String) row.get("tenant_id");
            String ruleId = (String) row.get("rule_id");
            try {
                step(tenantId, ruleId);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Skip the auto-advance while Redis is unreachable: the deploy lock backing
     * {@code applyRollout} degrades to an unprotected local owner in that state,
     * so two replicas could double-advance the ladder. An absent pool (dev,
     * no-Redis) keeps the legacy single-replica behavior.
     */
    private boolean redisHealthy() {
        var poolOpt = jedisPools.pool();
        if (poolOpt.isEmpty()) {
            return true;
        }
        try (var jedis = poolOpt.get().getResource()) {
            jedis.ping();
            return true;
        } catch (Exception e) {
            log.warn("redis unhealthy ({}), skipping ladder auto-advance this tick", e.getMessage());
            return false;
        }
    }

    private void step(String tenantId, String ruleId) {
        RuleRevision current = registry.getCurrentRule(tenantId, ruleId).orElse(null);
        if (current == null) {
            return;
        }
        TenantRolloutPolicy policy = policyRepository.getOrDefault(tenantId);
        if (!"auto".equalsIgnoreCase(policy.autoMode())) {
            return;
        }
        List<Integer> ladder = policy.canaryLadder();
        String state = current.rolloutState();
        Integer percent = current.canaryPercent();
        String targetState;
        Integer targetPercent = null;
        if (RolloutState.DRY_RUN.value().equals(state)) {
            targetState = RolloutState.CANARY.value();
            targetPercent = ladder.get(0);
        } else if (RolloutState.CANARY.value().equals(state)) {
            int idx = ladder.indexOf(percent != null ? percent : 0);
            if (idx < 0) {
                idx = 0;
            }
            if (idx + 1 >= ladder.size()) {
                return;
            }
            int next = ladder.get(idx + 1);
            if (next >= 100) {
                targetState = RolloutState.FULL.value();
            } else {
                targetState = RolloutState.CANARY.value();
                targetPercent = next;
            }
        } else {
            return;
        }
        Map<String, Object> eval = rolloutService.evaluate(tenantId, ruleId, targetState, targetPercent);
        if (Boolean.TRUE.equals(eval.get("pass"))) {
            try {
                rolloutService.applyRollout(
                        tenantId, ruleId, targetState, targetPercent, false, null, "ladder", "system");
            } catch (BusinessException e) {
                if (e.getCode() == 423) {
                    log.info("deploy in progress, skip ladder step for {}", ruleId);
                    return;
                }
                throw e;
            }
        }
    }
}
