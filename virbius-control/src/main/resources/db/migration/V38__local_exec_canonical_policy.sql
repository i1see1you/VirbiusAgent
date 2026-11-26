-- V38: local-exec policy identity is the canonical virbius__* name.
--
-- The M1 local executor no longer normalizes canonical names to the legacy
-- logical names before evaluating engine rules. Default chain rules must
-- therefore also match virbius__shell / virbius__python / virbius__node.

UPDATE tb_rule_history
SET body_json = REPLACE(
        body_json,
        'def execTools = ["exec_cmd", "shell", "bash", "sh", "execute_python", "run_command", "subprocess"]',
        'def execTools = ["virbius__shell", "virbius__python", "virbius__node", "exec_cmd", "shell", "bash", "sh", "execute_python", "run_command", "subprocess"]')
WHERE tenant_id = 'default'
  AND rule_id IN ('chain-write-exec', 'chain-priv-esc', 'retry-escape')
  AND rule_revision = (
      SELECT current_revision FROM tb_rules_current
      WHERE tenant_id = 'default' AND rule_id = tb_rule_history.rule_id
  );

UPDATE tb_rules_current
SET updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'default'
  AND rule_id IN ('chain-write-exec', 'chain-priv-esc', 'retry-escape');

-- The default gVisor tool policy used to be registered under the legacy
-- logical name. Move it to the canonical tool name for new evaluations.
UPDATE tb_tool_registry
SET tool_name = 'virbius__shell'
WHERE tenant_id = 'default'
  AND tool_name = 'exec_cmd';
