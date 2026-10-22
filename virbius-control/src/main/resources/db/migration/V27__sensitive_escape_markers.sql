-- V27: default-rule marker extension for existing installs (mirror of seed.sql).
--
-- Review-derived (SkillSpector corpus + runtime red-team 2026-10-21 r2):
-- 1. cloud_sensitive_resource_deny gains cloud-metadata credential-theft
--    (169.254.169.254 / ECS 169.254.170.2 / metadata.google.internal) and
--    container-escape sockets (/var/run/docker.sock, containerd). Pure marker
--    additions, scoped to role=tool_call like the original marker list, so
--    ordinary reads/URLs stay allowed.
-- 2. cloud_encoded_exec_deny gains the Python `b64decode(...) + eval/exec`
--    combination, closing the variant family where the decoder result is
--    evaluated dynamically (the `exec(base64.b64decode` literal was the only
--    Python form previously covered).
-- Both rules are updated in place at the current revision for the default
-- tenant only; online editing via the operations console remains possible.
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
        "169.254.169.254", "169.254.170.2", "metadata.google.internal",
        "/var/run/docker.sock", "/run/containerd/containerd.sock"]
    if (markers.any { text.contains(it) }) {
        ctx.setIntent("deny")
        return true
    }
    return false
}',
    modified_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'default'
  AND rule_id = 'cloud_sensitive_resource_deny'
  AND rule_revision = (
      SELECT current_revision FROM tb_rules_current
      WHERE tenant_id = 'default' AND rule_id = tb_rule_history.rule_id
  );

UPDATE tb_rules_current
SET updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'default'
  AND rule_id = 'cloud_sensitive_resource_deny';

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
    def b64 = text.contains("base64 -d") || text.contains("base64 --decode")
    def shell = text.contains("| bash") || text.contains("| sh") || text.contains("bash -c")
    def py = text.contains("exec(base64.b64decode") || (text.contains("b64decode") && (text.contains("exec(") || text.contains("eval(")))
    def ps = text.contains("powershell") && text.contains("-enc")
    def cert = text.contains("certutil") && text.contains("-decode")
    def xxd = text.contains("xxd -r") && (text.contains("| sh") || text.contains("| bash"))
    def js = text.contains("frombase64string") || (text.contains("atob(") && text.contains("eval("))
    return (b64 && shell) || py || ps || cert || xxd || js
}',
    modified_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'default'
  AND rule_id = 'cloud_encoded_exec_deny'
  AND rule_revision = (
      SELECT current_revision FROM tb_rules_current
      WHERE tenant_id = 'default' AND rule_id = tb_rule_history.rule_id
  );

UPDATE tb_rules_current
SET updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'default'
  AND rule_id = 'cloud_encoded_exec_deny';
