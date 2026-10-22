-- V28: default-rule variant extension for existing installs (mirror of seed.sql).
--
-- Review-derived (SkillSpector corpus classes + runtime red-team 2026-10-21 r2):
-- 1. cloud_encoded_exec_deny gains decode-to-shell execution through
--    gunzip/gzip -d, openssl enc -d (gated on a shell-pipe fuse) and the perl
--    MIME::Base64 + eval/system loader — the same decode+executor fusing the
--    base64 family already uses.
-- 2. cloud_destructive_command_deny gains raw disk writes (dd/cat/shred to
--    sd/nvme/vd/hd/mapper/disk devices) and root-wide find -delete sweeps.
-- Marker-level additions, scoped to role=tool_call like the original bodies,
-- so ordinary data ops (tar/gzip logs, openssl decode to file, dd to file or
-- /dev/null, find in workdirs) stay allowed.
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
    def gz = text.contains("gzip -d") || text.contains("gunzip")
    def ossl = text.contains("openssl") && text.contains("enc") && text.contains("-d")
    def pl = text.contains("mime::base64") && (text.contains("system(") || text.contains("eval("))
    return ((b64 || gz) && shell) || py || (ossl && shell) || pl || ps || cert || xxd || js
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
    def rmRoot = text.contains("rm -rf /") && !text.contains("rm -rf /tmp") && !text.contains("rm -rf /home") && !text.contains("rm -rf /var/tmp") && !text.contains("rm -rf /users")
    def diskFam = text.contains("/dev/sd") || text.contains("/dev/nvme") || text.contains("/dev/vd") || text.contains("/dev/hd") || text.contains("/dev/mapper") || text.contains("/dev/disk")
    def diskWrite = text.contains("shred") && text.contains("/dev/")
    diskWrite = diskWrite || ((text.contains("of=") || text.contains("> /dev/") || text.contains(">> /dev/")) && diskFam)
    return rmRoot
        || text.contains("--no-preserve-root")
        || text.contains("mkfs")
        || text.contains("dd if=/dev/zero of=/dev/")
        || (text.contains("-delete") && text.contains("find / "))
        || diskWrite
        || text.contains("shutdown -h")
        || text.contains("shutdown now")
        || text.contains(":(){ :|:& };:")
        || text.contains("chmod -r 777 /")
}',
    modified_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'default'
  AND rule_id = 'cloud_destructive_command_deny'
  AND rule_revision = (
      SELECT current_revision FROM tb_rules_current
      WHERE tenant_id = 'default' AND rule_id = tb_rule_history.rule_id
  );

UPDATE tb_rules_current
SET updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'default'
  AND rule_id = 'cloud_destructive_command_deny';
