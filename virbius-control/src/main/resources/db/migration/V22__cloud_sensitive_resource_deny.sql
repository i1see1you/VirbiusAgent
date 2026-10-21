-- V22: default cloud Groovy rule for credential-store access via tool calls.
--
-- Mirrors db/seed.sql for fresh SQLite/dev installs. The rule is deliberately
-- scoped to role=tool_call and only matches well-known credential paths so
-- ordinary project reads (README, workdir files, .env.example) stay allowed.
INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
    reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at)
SELECT 'default', 'cloud_sensitive_resource_deny', 1, 'system-builtin', 'cloud', 'groovy',
    'SENSITIVE_RESOURCE_ACCESS', 80, 'deny', '{"bind_scope":"global"}',
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
    def markers = ["id_rsa", "id_ed25519", "id_ecdsa", "id_dsa",
        ".aws/credentials", ".aws/config", ".config/gcloud", ".kube/config",
        ".docker/config.json", ".git-credentials", ".netrc", ".npmrc", ".pypirc",
        ".azure/", ".gnupg/", "wallet.dat", "/etc/shadow", "/etc/sudoers",
        "/proc/self/environ", "service_account.json", "service-account.json",
        "credentials.json"]
    if (markers.any { text.contains(it) }) {
        ctx.setIntent("deny")
        return true
    }
    return false
}',
    'full', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM tb_rule_history
    WHERE tenant_id = 'default' AND rule_id = 'cloud_sensitive_resource_deny' AND rule_revision = 1
);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'cloud_sensitive_resource_deny', 1, 'system-builtin', 'cloud', 'groovy', 'SENSITIVE_RESOURCE_ACCESS', 'full', CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM tb_rules_current
    WHERE tenant_id = 'default' AND rule_id = 'cloud_sensitive_resource_deny'
);
