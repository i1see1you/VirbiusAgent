package io.virbius.control.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.control.common.exception.BusinessException;
import io.virbius.control.config.SqlDialectConfig;
import io.virbius.control.domain.RuleRevision;
import io.virbius.control.domain.TenantRolloutPolicy;
import io.virbius.control.repository.RolloutMetricsRepository;
import io.virbius.control.repository.TenantRolloutPolicyRepository;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The dry_run -> full transition is a hard ban (it skips every data-accumulation gate by
 * design), so unlike other gate failures it must NOT be bypassable via force.
 */
class PromotionGateServiceTest {

    private TenantRolloutPolicyRepository policyRepo;
    private PromotionGateService gate;

    @BeforeEach
    void setUp() {
        policyRepo = mock(TenantRolloutPolicyRepository.class);
        RolloutMetricsRepository metricsRepo = mock(RolloutMetricsRepository.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SqlDialectConfig dialect = mock(SqlDialectConfig.class);
        gate = new PromotionGateService(
                policyRepo, metricsRepo, jdbc, new ObjectMapper(), dialect);
    }

    private void policyAllowingForce() {
        TenantRolloutPolicy policy = mock(TenantRolloutPolicy.class);
        when(policy.allowForce()).thenReturn(true);
        when(policyRepo.getOrDefault(anyString())).thenReturn(policy);
    }

    private static RuleRevision ruleInState(String state) {
        return new RuleRevision(
                "t", "r1", 1, "b1", "cloud", "groovy",
                "X", 0, "enforce", Map.of(),
                "def decide(ctx) { return true }",
                state, null, null, null, null, false, null);
    }

    @Test
    void dryRunToFullCannotBeForced() {
        policyAllowingForce();
        BusinessException ex = assertThrows(BusinessException.class,
                () -> gate.requirePassOrForce("t", ruleInState("dry_run"), "full", null, true, "emergency fix"));
        assertTrue(ex.getMessage().contains("dry_run -> full"), ex.getMessage());
    }

    @Test
    void otherGateFailuresCanStillBeForced() {
        TenantRolloutPolicy policy = mock(TenantRolloutPolicy.class);
        when(policy.allowForce()).thenReturn(true);
        // Make the dry_run -> canary gate fail: not enough hours in dry_run.
        when(policy.minDryRunHours()).thenReturn(1000);
        when(policyRepo.getOrDefault(anyString())).thenReturn(policy);

        // Without force: blocked.
        assertThrows(BusinessException.class,
                () -> gate.requirePassOrForce("t", ruleInState("dry_run"), "canary", null, false, null));
        // With force + comment: allowed.
        assertDoesNotThrow(
                () -> gate.requirePassOrForce("t", ruleInState("dry_run"), "canary", null, true, "emergency fix"));
    }
}
