-- V31: payload-injection coverage (Fangcun Guard agent_safety mining, rules-only).
--
-- 1. cloud_sensitive_resource_deny: add metadata.azure.com to the metadata
--    endpoint markers (in-place, same revision — V25-V30 precedent).
-- 2. New rule cloud_payload_injection_deny (full; tool_call gated, risk 80 deny)
--    covering XXE external-entity/external-DTD markers, Mongo server-side-JS
--    operators ($where/$function/$accumulator), Python __import__ execution.
--    Deliberately excluded (FP-guarded, confirmed against the source set):
--    full NoSQL operator family, import os/subprocess, bare eval(/exec(,
--    <!DOCTYPE html> without SYSTEM, generic path traversal / internal IP SSRF.
--
-- Result: default set 27 -> 28, all full.

UPDATE tb_rule_history
SET body_json = 'def decide(ctx) {
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
        "credentials.json",
        "169.254.169.254", "169.254.170.2", "metadata.google.internal", "metadata.azure.com",
        "/var/run/docker.sock", "/run/containerd/containerd.sock"]
    if (markers.any { text.contains(it) }) {
        ctx.setIntent("deny")
        return true
    }
    return false
}', modified_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'default'
  AND rule_id = 'cloud_sensitive_resource_deny'
  AND rule_revision = (
      SELECT current_revision FROM tb_rules_current
      WHERE tenant_id = 'default' AND rule_id = tb_rule_history.rule_id
  );

UPDATE tb_rules_current
SET updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'default' AND rule_id = 'cloud_sensitive_resource_deny';

INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at)
SELECT 'default', 'cloud_payload_injection_deny', 1, 'system-builtin', 'cloud', 'groovy',
    'PAYLOAD_INJECTION', 80, 'deny', '{"bind_scope":"global"}',
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
    def xxe = text.contains("<!entity") || (text =~ /<!doctype\s+[\w-]+\s+system/).find()
    def nosqlJs = (text =~ /\$(where|function|accumulator)\s*:/).find()
    def pyDyn = text.contains("__import__(")
    return xxe || nosqlJs || pyDyn
}',
    'full', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'cloud_payload_injection_deny' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'cloud_payload_injection_deny', 1, 'system-builtin', 'cloud', 'groovy', 'PAYLOAD_INJECTION', 'full', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'cloud_payload_injection_deny');
