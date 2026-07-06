package io.virbius.control.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRolloutEventRepository implements RolloutEventRepository {

    private final JdbcTemplate jdbc;

    public JdbcRolloutEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void recordEvent(
            String tenantId,
            String ruleId,
            int ruleRevision,
            String rolloutState,
            Integer canaryPercent,
            String trigger,
            String operator) {
        jdbc.update(
                """
                INSERT INTO tb_rule_rollout_event (
                  tenant_id, rule_id, rule_revision, rollout_state, canary_percent,
                  trigger, operator, effective_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """,
                tenantId,
                ruleId,
                ruleRevision,
                rolloutState,
                canaryPercent,
                trigger != null ? trigger : "manual",
                operator != null ? operator : "admin");
    }
}
