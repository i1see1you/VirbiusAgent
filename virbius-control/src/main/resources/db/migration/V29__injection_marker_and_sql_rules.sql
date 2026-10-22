-- V29: default rule additions mined from Microsoft AGT examples/policies
-- (rule data only — same shapes as the sibling marker rules):
--
-- 1. cloud_injection_marker_deny (full after verification): role=tool_call
--    gating, three marker families — invisible unicode (zero-width/bidi/
--    soft-hyphen ranges, trimmed of false-positive-prone scripts), chat-
--    template forging delimiters, high-precision plain override litany.
-- 2. cloud_sql_command_deny (dry_run for observation): vendor SQL escalation/
--    dump/admin/write surface (xp_/sp_ procs, DBCC, OPENROWSET, INTO OUTFILE,
--    LOAD DATA, SET GLOBAL/ROLE, DROP TABLE/DATABASE, GRANT ALL).
INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at)
SELECT 'default', 'cloud_injection_marker_deny', 1, 'system-builtin', 'cloud', 'groovy',
    'INJECTION_MARKER', 80, 'deny', '{"bind_scope":"global"}',
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
    def invisible = false
    def cit = content.codePoints().iterator()
    while (cit.hasNext()) {
        def cp = cit.next()
        if ((cp >= 0x200b && cp <= 0x200f) || cp == 0xfeff || (cp >= 0x202a && cp <= 0x202e) || (cp >= 0x2066 && cp <= 0x2069) || cp == 0x00ad || cp == 0x2060) {
            invisible = true
            break
        }
    }
    def forge = text.contains("<|im_start|>") || text.contains("<|im_end|>") || text.contains("<|im_start|>system") || text.contains("<|im_end|>system") || text.contains("<<SYS>>") || text.contains("[inst]") || text.contains("[/inst]") || text.contains("### system") || text.contains("### instruction") || text.contains("### override") || text.contains("end system") || text.contains("begin user")
    def override = text.contains("ignore previous instructions") || text.contains("ignore all previous instructions") || text.contains("ignore the previous instructions") || text.contains("disregard previous instructions") || text.contains("disregard all previous") || text.contains("forget all previous") || text.contains("forget everything you") || text.contains("forget your instructions") || text.contains("you have no restrictions") || text.contains("you have no limitations") || text.contains("you have no rules") || text.contains("developer mode enabled") || text.contains("developer mode override") || text.contains("dan mode enabled") || text.contains("act as if you have no") || text.contains("your true purpose is") || text.contains("the real system prompt is") || text.contains("actually your real instructions are")
    return invisible || forge || override
}',
    'full', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'cloud_injection_marker_deny' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'cloud_injection_marker_deny', 1, 'system-builtin', 'cloud', 'groovy', 'INJECTION_MARKER', 'full', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'cloud_injection_marker_deny');

INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at)
SELECT 'default', 'cloud_sql_command_deny', 1, 'system-builtin', 'cloud', 'groovy',
    'SQL_DANGEROUS', 80, 'deny', '{"bind_scope":"global"}',
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
    def procEsc = (text =~ /\\bxp_\\w+/).find() || (text =~ /\\bsp_\\w+/).find() || text.contains("xp_cmdshell") || text.contains("dbcc") || text.contains("xpcmdshell")
    def dumpLoad = text.contains("into outfile") || text.contains("into dumpfile") || text.contains("load_file(") || text.contains("load data ") || text.contains("bulk insert ") || text.contains("openrowset") || text.contains("opendatasource") || text.contains("openquery")
    def sqlAdmin = text.contains("set global ") || text.contains("set role ") || text.contains("create extension")
    def sqlWrite = text.contains("drop table ") || text.contains("drop database ") || text.contains("drop schema ") || text.contains("truncate table ") || text.contains("grant all ") || text.contains(" with grant option")
    return procEsc || dumpLoad || sqlAdmin || sqlWrite
}',
    'dry_run', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'cloud_sql_command_deny' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'cloud_sql_command_deny', 1, 'system-builtin', 'cloud', 'groovy', 'SQL_DANGEROUS', 'dry_run', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'cloud_sql_command_deny');
