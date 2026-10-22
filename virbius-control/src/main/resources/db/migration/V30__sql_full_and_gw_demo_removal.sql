-- V30: finalize default rule set.
--
-- 1. cloud_sql_command_deny promotion dry_run -> full, validated via the
--    groovy-only runtime probe (7 attacks attributed SQL_DANGEROUS, 6 benign
--    passes incl. SELECT/mysql/dbcc-wording/reset-password cases; seed.sql
--    consistent across fresh and migration paths).
-- 2. Delete the 4 disabled gw_* demo pairs (content/subject_network allow+
--    deny) — placeholders superseded by the script-rule migration; owner
--    decision keeps only the two rl_rate gateway rules.
--
-- Result: default set 31 -> 27, all active rules in rollout_state full.

UPDATE tb_rule_history
SET rollout_state = 'full', modified_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'default'
  AND rule_id = 'cloud_sql_command_deny'
  AND rule_revision = (
      SELECT current_revision FROM tb_rules_current
      WHERE tenant_id = 'default' AND rule_id = tb_rule_history.rule_id
  );

UPDATE tb_rules_current
SET rollout_state = 'full', updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'default'
  AND rule_id = 'cloud_sql_command_deny';

DELETE FROM tb_rules_current
WHERE tenant_id = 'default'
  AND rule_id IN ('gw_content_deny', 'gw_content_allow', 'gw_subject_network_deny', 'gw_subject_network_allow');

DELETE FROM tb_rule_history
WHERE tenant_id = 'default'
  AND rule_id IN ('gw_content_deny', 'gw_content_allow', 'gw_subject_network_deny', 'gw_subject_network_allow');
