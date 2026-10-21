-- V26: option A gateway cleanup for existing installs.
--
-- Keep only the two rate-limit rules and enforce them; drop the demo
-- subject/keyword rules. After V25+V26 the default tenant has no dry_run rules.
DELETE FROM tb_rules_current
WHERE tenant_id = 'default'
  AND rule_id IN ('rl_deny_devices','rl_deny_keywords','rl_deny_users');

DELETE FROM tb_rule_history
WHERE tenant_id = 'default'
  AND rule_id IN ('rl_deny_devices','rl_deny_keywords','rl_deny_users');

UPDATE tb_rule_history
SET rollout_state = 'full'
WHERE tenant_id = 'default'
  AND rule_id IN ('rl_rate_user_1h','rl_rate_app_1h')
  AND rule_revision = (
      SELECT current_revision FROM tb_rules_current
      WHERE tenant_id = 'default' AND rule_id = tb_rule_history.rule_id
  );

UPDATE tb_rules_current
SET rollout_state = 'full', updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'default'
  AND rule_id IN ('rl_rate_user_1h','rl_rate_app_1h');
