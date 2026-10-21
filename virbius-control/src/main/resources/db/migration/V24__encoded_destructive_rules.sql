-- V24: data-only default rules for encoded execution and destructive commands.
--
-- Both rules are scoped to role=tool_call and start in dry_run so operators can
-- baseline false positives before promoting to full. They complement the
-- existing credential/chain/prompt layers with deterministic tool-argument
-- checks; no engine code is required.

INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
    reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at)
SELECT 'default', 'cloud_encoded_exec_deny', 1, 'system-builtin', 'cloud', 'groovy',
    'ENCODED_EXEC', 80, 'deny', '{"bind_scope":"global"}',
    'def decide(ctx) {
    def role = ctx.var("role")
    if (role != null && role != "tool_call") {
        return false
    }
    def tool = ctx.var("tool_name")
    if (tool == null || tool.isBlank() || tool == "chat_input") {
        return false
    }
    def content = ctx.var("content")
    if (content == null || content.isBlank()) {
        return false
    }
    def text = content.toLowerCase()
    def b64 = text.contains("base64 -d") || text.contains("base64 --decode")
    def shell = text.contains("| bash") || text.contains("| sh") || text.contains("bash -c")
    def py = text.contains("exec(base64.b64decode")
    def ps = text.contains("powershell") && text.contains("-enc")
    def cert = text.contains("certutil") && text.contains("-decode")
    def xxd = text.contains("xxd -r") && (text.contains("| sh") || text.contains("| bash"))
    def js = text.contains("frombase64string") || (text.contains("atob(") && text.contains("eval("))
    return (b64 && shell) || py || ps || cert || xxd || js
}',
    'dry_run', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM tb_rule_history
    WHERE tenant_id = 'default' AND rule_id = 'cloud_encoded_exec_deny' AND rule_revision = 1
);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'cloud_encoded_exec_deny', 1, 'system-builtin', 'cloud', 'groovy', 'ENCODED_EXEC', 'dry_run', CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM tb_rules_current
    WHERE tenant_id = 'default' AND rule_id = 'cloud_encoded_exec_deny'
);

INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
    reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at)
SELECT 'default', 'cloud_destructive_command_deny', 1, 'system-builtin', 'cloud', 'groovy',
    'DESTRUCTIVE_COMMAND', 80, 'deny', '{"bind_scope":"global"}',
    'def decide(ctx) {
    def role = ctx.var("role")
    if (role != null && role != "tool_call") {
        return false
    }
    def tool = ctx.var("tool_name")
    if (tool == null || tool.isBlank() || tool == "chat_input") {
        return false
    }
    def content = ctx.var("content")
    if (content == null || content.isBlank()) {
        return false
    }
    def text = content.toLowerCase()
    def rmRoot = text.contains("rm -rf /") && !text.contains("rm -rf /tmp") && !text.contains("rm -rf /home") && !text.contains("rm -rf /var/tmp") && !text.contains("rm -rf /users")
    return rmRoot
        || text.contains("--no-preserve-root")
        || text.contains("mkfs")
        || text.contains("dd if=/dev/zero of=/dev/")
        || text.contains("shutdown -h")
        || text.contains("shutdown now")
        || text.contains(":(){ :|:& };:")
        || text.contains("chmod -r 777 /")
}',
    'dry_run', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM tb_rule_history
    WHERE tenant_id = 'default' AND rule_id = 'cloud_destructive_command_deny' AND rule_revision = 1
);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'cloud_destructive_command_deny', 1, 'system-builtin', 'cloud', 'groovy', 'DESTRUCTIVE_COMMAND', 'dry_run', CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM tb_rules_current
    WHERE tenant_id = 'default' AND rule_id = 'cloud_destructive_command_deny'
);
