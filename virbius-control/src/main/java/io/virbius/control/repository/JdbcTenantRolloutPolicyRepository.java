package io.virbius.control.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.control.domain.TenantRolloutPolicy;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTenantRolloutPolicyRepository implements TenantRolloutPolicyRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public JdbcTenantRolloutPolicyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public TenantRolloutPolicy getOrDefault(String tenantId) {
        List<TenantRolloutPolicy> rows = jdbc.query(
                """
                SELECT tenant_id, auto_mode, canary_ladder_json, min_dry_run_hours, min_review_count,
                       max_review_rate, max_review_spike_ratio, min_hours_per_step,
                       min_block_samples_per_step, allow_force, rollback_block_spike_ratio,
                       edge_audit_sample_rate_allow, max_concurrent_rollouts
                FROM tb_tenant_rollout_policy WHERE tenant_id = ?
                """,
                (rs, i) ->
                        new TenantRolloutPolicy(
                                rs.getString("tenant_id"),
                                rs.getString("auto_mode"),
                                parseLadder(rs.getString("canary_ladder_json")),
                                rs.getInt("min_dry_run_hours"),
                                rs.getInt("min_review_count"),
                                rs.getDouble("max_review_rate"),
                                rs.getDouble("max_review_spike_ratio"),
                                rs.getInt("min_hours_per_step"),
                                rs.getInt("min_block_samples_per_step"),
                                rs.getBoolean("allow_force"),
                                rs.getDouble("rollback_block_spike_ratio"),
                                rs.getDouble("edge_audit_sample_rate_allow"),
                                rs.getInt("max_concurrent_rollouts")),
                tenantId);
        return rows.isEmpty() ? TenantRolloutPolicy.defaults(tenantId) : rows.get(0);
    }

    @Override
    public TenantRolloutPolicy save(TenantRolloutPolicy policy) {
        String ladderJson;
        try {
            ladderJson = mapper.writeValueAsString(policy.canaryLadder());
        } catch (Exception e) {
            ladderJson = "[5,20,50,100]";
        }
        int updated = jdbc.update(
                """
                UPDATE tb_tenant_rollout_policy SET
                  auto_mode = ?, canary_ladder_json = ?, min_dry_run_hours = ?, min_review_count = ?,
                  max_review_rate = ?, max_review_spike_ratio = ?, min_hours_per_step = ?,
                  min_block_samples_per_step = ?, allow_force = ?, rollback_block_spike_ratio = ?,
                  edge_audit_sample_rate_allow = ?, max_concurrent_rollouts = ?,
                  updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ?
                """,
                policy.autoMode(),
                ladderJson,
                policy.minDryRunHours(),
                policy.minReviewCount(),
                policy.maxReviewRate(),
                policy.maxReviewSpikeRatio(),
                policy.minHoursPerStep(),
                policy.minBlockSamplesPerStep(),
                policy.allowForce(),
                policy.rollbackBlockSpikeRatio(),
                policy.edgeAuditSampleRateAllow(),
                policy.maxConcurrentRollouts(),
                policy.tenantId());
        if (updated == 0) {
            jdbc.update(
                    """
                    INSERT INTO tb_tenant_rollout_policy (
                      tenant_id, auto_mode, canary_ladder_json, min_dry_run_hours, min_review_count,
                      max_review_rate, max_review_spike_ratio, min_hours_per_step,
                      min_block_samples_per_step, allow_force, rollback_block_spike_ratio,
                      edge_audit_sample_rate_allow, max_concurrent_rollouts
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    policy.tenantId(),
                    policy.autoMode(),
                    ladderJson,
                    policy.minDryRunHours(),
                    policy.minReviewCount(),
                    policy.maxReviewRate(),
                    policy.maxReviewSpikeRatio(),
                    policy.minHoursPerStep(),
                    policy.minBlockSamplesPerStep(),
                    policy.allowForce(),
                    policy.rollbackBlockSpikeRatio(),
                    policy.edgeAuditSampleRateAllow(),
                    policy.maxConcurrentRollouts());
        }
        return getOrDefault(policy.tenantId());
    }

    private List<Integer> parseLadder(String json) {
        if (json == null || json.isBlank()) {
            return TenantRolloutPolicy.defaults("").canaryLadder();
        }
        try {
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Arrays.stream(json.split(","))
                    .map(String::trim)
                    .map(Integer::parseInt)
                    .collect(Collectors.toList());
        }
    }
}
