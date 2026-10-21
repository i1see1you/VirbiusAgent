-- V25: default-rule cleanup for existing installs (mirror of the seed tail).
--
-- Drop demo/redundant rules and promote the remaining non-gateway dry_run
-- rules to full. Gateway rules stay dry_run by owner decision.
DELETE FROM tb_rules_current
WHERE tenant_id = 'default'
  AND rule_id IN ('cloud_prompt_l1','cloud_rl_deny_keywords','cloud_rl_deny_vars',
                  'cloud_tool_chain_detect','edge_l0_content_allow');

DELETE FROM tb_rule_history
WHERE tenant_id = 'default'
  AND rule_id IN ('cloud_prompt_l1','cloud_rl_deny_keywords','cloud_rl_deny_vars',
                  'cloud_tool_chain_detect','edge_l0_content_allow');

UPDATE tb_rule_history
SET rollout_state = 'full'
WHERE tenant_id = 'default'
  AND rule_id IN ('cloud_encoded_exec_deny','cloud_destructive_command_deny',
                  'cloud_session_risk_escalation','edge_l0_content_deny')
  AND rule_revision = (
      SELECT current_revision FROM tb_rules_current
      WHERE tenant_id = 'default' AND rule_id = tb_rule_history.rule_id
  );

UPDATE tb_rules_current
SET rollout_state = 'full', updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'default'
  AND rule_id IN ('cloud_encoded_exec_deny','cloud_destructive_command_deny',
                  'cloud_session_risk_escalation','edge_l0_content_deny');
