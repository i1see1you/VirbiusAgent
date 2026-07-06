package io.virbius.control.job;

import io.virbius.control.common.exception.BusinessException;
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

@Component
public class LadderJob {

    private static final Logger log = LoggerFactory.getLogger(LadderJob.class);

    private final JdbcTemplate jdbc;
    private final RegistryRepository registry;
    private final TenantRolloutPolicyRepository policyRepository;
    private final RolloutService rolloutService;
    private final PublishOrchestrator orchestrator;

    public LadderJob(
            JdbcTemplate jdbc,
            RegistryRepository registry,
            TenantRolloutPolicyRepository policyRepository,
            RolloutService rolloutService,
            PublishOrchestrator orchestrator) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.policyRepository = policyRepository;
        this.rolloutService = rolloutService;
        this.orchestrator = orchestrator;
    }

    @Scheduled(fixedDelayString = "${rollout.ladder.poll-ms:900000}")
    public void tick() {
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
