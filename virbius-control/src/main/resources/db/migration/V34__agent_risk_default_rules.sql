
-- V34: Agent-risk default rules (VE evaluation gap fill, 2026-10-24).
-- Layers: cloud / edge / gateway / falco. Rule data only.
-- New rules are inserted revision=1 with rollout_state=full; idempotent (WHERE NOT EXISTS).

INSERT INTO tb_cumulative (
    tenant_id, cumulative_name, description, dimension, window_kind, window_minutes, window_hours,
    timezone, priority, status)
SELECT 'default', 'tool_req_1m', 'Per-tool rolling 1-minute limit', 'var:tool_name', 'rolling', 1, NULL,
    NULL, 12, 'active' FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_cumulative WHERE tenant_id = 'default' AND cumulative_name = 'tool_req_1m');

-- cloud_dynamic_code_exec_deny (cloud/groovy)
INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at)
SELECT 'default', 'cloud_dynamic_code_exec_deny', 1, 'system-builtin', 'cloud', 'groovy',
    'DYNAMIC_CODE_EXEC', 80, 'deny', '{"bind_scope":"global"}',
    'def decide(ctx) {
    // cloud_dynamic_code_exec_deny — 动态代码执行引信（eval/exec/子进程）
    // 命中: python -c "eval(user_input)" | node -e "new Function(x)" | child_process.exec(cmd)
    // 不命中(防误报): 文档提到 eval() | 普通 evaluate/executive 单词 | chat 内容
    // 维护: virbius default rules 2026-10-24
    def role = ctx.var("role")
    if (role != null && role != "tool_call") { return false }
    def tool = ctx.var("tool_name")
    if (tool == null || tool.isBlank() || tool == "chat_input") { return false }
    def content = ctx.var("content")
    if (content == null || content.isBlank()) { return false }
    def tn = tool.toLowerCase()
    // 仅代码执行类工具，避免把普通工具参数里的 eval/exec 字样误判
    def execTool = ["execute_code", "run_code", "code_interpreter", "python", "python3",
        "node", "bash", "shell", "subprocess", "run_command", "exec_command", "eval"].any { tn.contains(it) }
    if (!execTool) { return false }
    def text = content.toLowerCase()
    def dyn = text.contains("eval(") || text.contains("exec(") || text.contains("new function(")
    def proc = text.contains("child_process.") || text.contains("subprocess.")
        || text.contains("os.system(") || text.contains("runtime.getruntime().exec(")
    if (dyn || proc) { ctx.setIntent("deny"); return true }
    return false
}',
    'full', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'cloud_dynamic_code_exec_deny' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'cloud_dynamic_code_exec_deny', 1, 'system-builtin', 'cloud', 'groovy', 'DYNAMIC_CODE_EXEC', 'full', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'cloud_dynamic_code_exec_deny');

-- cloud_suspicious_egress_deny (cloud/groovy)
INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at)
SELECT 'default', 'cloud_suspicious_egress_deny', 1, 'system-builtin', 'cloud', 'groovy',
    'SUSPICIOUS_EGRESS', 80, 'deny', '{"bind_scope":"global"}',
    'def decide(ctx) {
    // cloud_suspicious_egress_deny — 已知外渗/回连通道域名拦截
    // 命中: POST https://webhook.site/... | curl https://requestbin.net/... | ngrok 隧道
    // 不命中(防误报): 文档/README 出现域名（chat_input）| 普通业务域名
    // 维护: virbius default rules 2026-10-24
    def role = ctx.var("role")
    if (role != null && role != "tool_call") { return false }
    def tool = ctx.var("tool_name")
    if (tool == null || tool.isBlank() || tool == "chat_input") { return false }
    def content = ctx.var("content")
    if (content == null || content.isBlank()) { return false }
    def text = content.toLowerCase()
    // 仅收录一次性接收/隧道/粘贴类服务（业务域名不在此列）
    def domains = ["webhook.site", "requestbin.net", "requestbin.io", "pipedream.net",
        "interact.sh", "beeceptor.com", "transfer.sh", "pastebin.com", "paste.ee",
        "ngrok.io", "ngrok-free.app", "burpcollaborator.net", "oast.fun", "dnslog.cn"]
    if (domains.any { text.contains(it) }) { ctx.setIntent("deny"); return true }
    return false
}',
    'full', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'cloud_suspicious_egress_deny' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'cloud_suspicious_egress_deny', 1, 'system-builtin', 'cloud', 'groovy', 'SUSPICIOUS_EGRESS', 'full', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'cloud_suspicious_egress_deny');

-- cloud_high_risk_tool_challenge (cloud/groovy)
INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at)
SELECT 'default', 'cloud_high_risk_tool_challenge', 1, 'system-builtin', 'cloud', 'groovy',
    'HIGH_RISK_TOOL', 60, 'challenge', '{"bind_scope":"global"}',
    'def decide(ctx) {
    // cloud_high_risk_tool_challenge — 高危工具名/动作二次确认
    // 命中: transfer_funds | delete_mailbox | send_email | deploy_app | reset_password
    // 不命中(防误报): get_/list_/search_/read_ 等只读工具 | chat 内容
    // 维护: virbius default rules 2026-10-24
    def role = ctx.var("role")
    if (role != null && role != "tool_call") { return false }
    def tool = ctx.var("tool_name")
    if (tool == null || tool.isBlank() || tool == "chat_input") { return false }
    def tn = tool.toLowerCase()
    // 付款/删除/发布/部署/账号/执行类动作（只读工具不在列表内）
    def markers = ["transfer_", "payment", "payout", "withdraw_", "delete_", "drop_",
        "destroy_", "revoke_", "grant_", "reset_password", "create_user", "update_user",
        "send_email", "send_message", "publish_", "deploy_", "execute_", "run_shell",
        "exec_command", "terraform_apply", "k8s_", "ssh_"]
    if (markers.any { tn.contains(it) }) { ctx.setIntent("challenge"); return true }
    return false
}',
    'full', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'cloud_high_risk_tool_challenge' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'cloud_high_risk_tool_challenge', 1, 'system-builtin', 'cloud', 'groovy', 'HIGH_RISK_TOOL', 'full', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'cloud_high_risk_tool_challenge');

-- cloud_memory_poison_deny (cloud/groovy)
INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at)
SELECT 'default', 'cloud_memory_poison_deny', 1, 'system-builtin', 'cloud', 'groovy',
    'MEMORY_POISON', 80, 'deny', '{"bind_scope":"global"}',
    'def decide(ctx) {
    // cloud_memory_poison_deny — 记忆写入 + 指令覆盖/隐藏指令
    // 命中: memory_save("ignore previous instructions ...") | remember("you are now ...")
    // 不命中(防误报): 普通偏好/事实记忆 | chat 内容
    // 维护: virbius default rules 2026-10-24
    def role = ctx.var("role")
    if (role != null && role != "tool_call") { return false }
    def tool = ctx.var("tool_name")
    if (tool == null || tool.isBlank() || tool == "chat_input") { return false }
    def tn = tool.toLowerCase()
    // 仅记忆写入面：普通 notes 工具不命中
    def memTool = tn.contains("memory") || tn.contains("remember") || tn.contains("memorize")
        || tn.contains("write_note") || tn.contains("save_note")
    if (!memTool) { return false }
    def content = ctx.var("content")
    if (content == null || content.isBlank()) { return false }
    def text = content.toLowerCase()
    // 需要同时出现指令覆盖/隐藏指令，普通记忆内容不命中
    def markers = ["ignore previous", "ignore all previous", "disregard", "system prompt",
        "hidden instruction", "do not reveal", "don''t tell", "you are now", "new instructions",
        "override all", "from now on you", "always obey"]
    if (markers.any { text.contains(it) }) { ctx.setIntent("deny"); return true }
    return false
}',
    'full', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'cloud_memory_poison_deny' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'cloud_memory_poison_deny', 1, 'system-builtin', 'cloud', 'groovy', 'MEMORY_POISON', 'full', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'cloud_memory_poison_deny');

-- edge_dlp_email (edge/dlp-dsl)
INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at)
SELECT 'default', 'edge_dlp_email', 1, 'system-builtin', 'edge', 'dlp-dsl',
    'DLP_EMAIL', 0, 'allow', '{}',
    '{"entity_type":"email","priority":20,"mask_template":"{{VIRBIUS_EMAIL_{seq}}}"}',
    'full', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'edge_dlp_email' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'edge_dlp_email', 1, 'system-builtin', 'edge', 'dlp-dsl', 'DLP_EMAIL', 'full', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'edge_dlp_email');

-- edge_dlp_idcard (edge/dlp-dsl)
INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at)
SELECT 'default', 'edge_dlp_idcard', 1, 'system-builtin', 'edge', 'dlp-dsl',
    'DLP_IDCARD', 0, 'allow', '{}',
    '{"entity_type":"idcard_cn","priority":20,"mask_template":"{{VIRBIUS_IDCARD_CN_{seq}}}"}',
    'full', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'edge_dlp_idcard' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'edge_dlp_idcard', 1, 'system-builtin', 'edge', 'dlp-dsl', 'DLP_IDCARD', 'full', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'edge_dlp_idcard');

-- edge_dlp_bank_card (edge/dlp-dsl)
INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at)
SELECT 'default', 'edge_dlp_bank_card', 1, 'system-builtin', 'edge', 'dlp-dsl',
    'DLP_BANK_CARD', 0, 'allow', '{}',
    '{"entity_type":"bank_card_cn","priority":20,"mask_template":"{{VIRBIUS_BANK_CARD_CN_{seq}}}"}',
    'full', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'edge_dlp_bank_card' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'edge_dlp_bank_card', 1, 'system-builtin', 'edge', 'dlp-dsl', 'DLP_BANK_CARD', 'full', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'edge_dlp_bank_card');

-- edge_dlp_secret_token (edge/dlp-dsl)
INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at)
SELECT 'default', 'edge_dlp_secret_token', 1, 'system-builtin', 'edge', 'dlp-dsl',
    'DLP_SECRET_TOKEN', 0, 'allow', '{}',
    '{"entity_type":"custom_regex","pattern":"(AKIA[0-9A-Z]{16}|ASIA[0-9A-Z]{16}|sk-[A-Za-z0-9_-]{20,}|gh[pousr]_[A-Za-z0-9]{20,}|xox[baprs]-[A-Za-z0-9-]{10,}|-----BEGIN [A-Z ]*PRIVATE KEY-----|eyJ[A-Za-z0-9_-]{10,}[.][A-Za-z0-9_-]{10,}[.][A-Za-z0-9_-]{10,})","priority":10,"mask_template":"{{VIRBIUS_SECRET_{seq}}}"}',
    'full', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'edge_dlp_secret_token' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'edge_dlp_secret_token', 1, 'system-builtin', 'edge', 'dlp-dsl', 'DLP_SECRET_TOKEN', 'full', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'edge_dlp_secret_token');

-- edge_l0_refusal_bypass_deny (edge/lua-dsl)
INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at)
SELECT 'default', 'edge_l0_refusal_bypass_deny', 1, 'system-builtin', 'edge', 'lua-dsl',
    'EDGE_REFUSAL_BYPASS_DENY', 80, 'deny', '{}',
    '{"keywords":["do not refuse","never refuse","don''t refuse","bypass your safety","ignore your safety","without restrictions","no restrictions","unrestricted mode","must comply","answer regardless","override your instructions"],"list_type":"deny"}',
    'dry_run', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'edge_l0_refusal_bypass_deny' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'edge_l0_refusal_bypass_deny', 1, 'system-builtin', 'edge', 'lua-dsl', 'EDGE_REFUSAL_BYPASS_DENY', 'dry_run', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'edge_l0_refusal_bypass_deny');

-- gw_rate_tool_1m (gateway/lua)
INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at)
SELECT 'default', 'gw_rate_tool_1m', 1, 'system-builtin', 'gateway', 'lua',
    'GW_TOOL_RATE_1M', 70, 'challenge', '{"bind_scope":"global"}',
    '-- virbius:generated v1
function decide(ctx)
  return getCumulative(''tool_req_1m'') >= 60
end',
    'full', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'gw_rate_tool_1m' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'gw_rate_tool_1m', 1, 'system-builtin', 'gateway', 'lua', 'GW_TOOL_RATE_1M', 'full', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'gw_rate_tool_1m');

-- builtin_reverse_shell (falco/falco)
INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at)
SELECT 'default', 'builtin_reverse_shell', 1, 'system-builtin', 'falco', 'falco',
    'CRITICAL', 90, 'deny', '{"bind_scope":"global","description":"Detect reverse-shell / connect-back patterns"}',
    '{"condition":"evt.type in (execve, execveat) and evt.dir=< and ((proc.name in (nc, ncat, netcat, socat) and proc.cmdline contains \\" -e \\") or (proc.name in (bash, sh, dash, zsh) and (proc.cmdline contains \\"/dev/tcp/\\" or proc.cmdline contains \\"/dev/udp/\\")) or (proc.name in (python, python3) and proc.cmdline contains \\"pty.spawn\\"))","output":"Reverse shell pattern (pid=%proc.pid, ppid=%proc.ppid, container=%container.id, pname=%proc.name, command=%proc.cmdline, pcmdline=%proc.pcmdline)","priority":"CRITICAL","tags":["agent","network","reverse-shell"]}',
    'full', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'builtin_reverse_shell' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'builtin_reverse_shell', 1, 'system-builtin', 'falco', 'falco', 'CRITICAL', 'full', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'builtin_reverse_shell');

-- builtin_container_escape (falco/falco)
INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at)
SELECT 'default', 'builtin_container_escape', 1, 'system-builtin', 'falco', 'falco',
    'CRITICAL', 90, 'deny', '{"bind_scope":"global","description":"Detect namespace/container escape primitives"}',
    '{"condition":"(evt.type in (execve, execveat) and evt.dir=< and proc.name in (nsenter, setns, unshare)) or (evt.type in (open, openat, openat2) and fd.name in (/proc/1/root, /var/run/docker.sock, /run/containerd/containerd.sock))","output":"Container escape primitive (pid=%proc.pid, ppid=%proc.ppid, container=%container.id, pname=%proc.name, file=%fd.name, command=%proc.cmdline)","priority":"CRITICAL","tags":["agent","container","escape"]}',
    'full', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'builtin_container_escape' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'builtin_container_escape', 1, 'system-builtin', 'falco', 'falco', 'CRITICAL', 'full', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'builtin_container_escape');

-- builtin_crypto_mining (falco/falco)
INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at)
SELECT 'default', 'builtin_crypto_mining', 1, 'system-builtin', 'falco', 'falco',
    'WARNING', 80, 'deny', '{"bind_scope":"global","description":"Detect crypto-mining binaries and stratum connections"}',
    '{"condition":"evt.type in (execve, execveat) and evt.dir=< and (proc.name in (xmrig, minerd, cpuminer, ethminer, nbminer) or proc.cmdline contains \\"stratum+tcp://\\" or proc.cmdline contains \\"stratum+ssl://\\")","output":"Crypto mining activity (pid=%proc.pid, ppid=%proc.ppid, container=%container.id, pname=%proc.name, command=%proc.cmdline)","priority":"WARNING","tags":["agent","crypto-mining"]}',
    'full', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'builtin_crypto_mining' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'builtin_crypto_mining', 1, 'system-builtin', 'falco', 'falco', 'WARNING', 'full', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'builtin_crypto_mining');

-- Fix pre-existing Falco output/condition incompatibilities (rule data only):
--   * %proc.cgroup.id is not a Falco field (0.37-0.41); use %container.id.
--   * fd.sip does not accept CIDR; use fd.net for the RFC1918/loopback exclusion.
-- Engine cgroup tier falls back to pid/ppid until code parses a valid field.

UPDATE tb_rule_history
SET body_json = '{"condition":"evt.type in (open, openat, openat2) and fd.name in (/etc/shadow, /etc/passwd, /root/.ssh/id_rsa, /root/.ssh/authorized_keys) and evt.is_open_write=true","output":"Sensitive file access (user=%user.name, pid=%proc.pid, ppid=%proc.ppid, container=%container.id, pname=%proc.name, file=%fd.name, pcmdline=%proc.pcmdline)","priority":"WARNING","tags":["agent","filesystem","sensitive"]}', modified_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'default' AND rule_id = 'builtin_sensitive_file_access'
  AND rule_revision = (SELECT current_revision FROM tb_rules_current
                       WHERE tenant_id = 'default' AND rule_id = tb_rule_history.rule_id);

UPDATE tb_rules_current SET updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'default' AND rule_id = 'builtin_sensitive_file_access';

UPDATE tb_rule_history
SET body_json = '{"condition":"evt.type in (execve, execveat) and evt.dir=< and not proc.name startswith \\"falco\\" and not proc.name startswith \\"virbius\\"","output":"Agent process spawned (user=%user.name, pid=%proc.pid, ppid=%proc.ppid, container=%container.id, command=%proc.cmdline, pcmdline=%proc.pcmdline)","priority":"WARNING","tags":["agent","process"]}', modified_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'default' AND rule_id = 'builtin_agent_process_spawned'
  AND rule_revision = (SELECT current_revision FROM tb_rules_current
                       WHERE tenant_id = 'default' AND rule_id = tb_rule_history.rule_id);

UPDATE tb_rules_current SET updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'default' AND rule_id = 'builtin_agent_process_spawned';

UPDATE tb_rule_history
SET body_json = '{"condition":"evt.type=connect and evt.dir=< and fd.typechar=4 and not fd.net in (127.0.0.0/8, 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16)","output":"Agent outbound connection (pid=%proc.pid, ppid=%proc.ppid, container=%container.id, pname=%proc.name, sip=%fd.sip, sport=%fd.sport, pcmdline=%proc.pcmdline)","priority":"NOTICE","tags":["agent","network"]}', modified_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'default' AND rule_id = 'builtin_agent_outbound_connection'
  AND rule_revision = (SELECT current_revision FROM tb_rules_current
                       WHERE tenant_id = 'default' AND rule_id = tb_rule_history.rule_id);

UPDATE tb_rules_current SET updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'default' AND rule_id = 'builtin_agent_outbound_connection';

-- V34: downgrade broad event-level rules to dry_run (observed, not scored).
-- Rationale: event-level false positives >5% for process-spawn/outbound and
-- edge keyword rules (docs/quotes/policy text). They remain in the execution
-- plane for telemetry; precision comes from the narrow sibling rules.
UPDATE tb_rule_history SET rollout_state = 'dry_run', modified_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'default' AND rule_id IN ('builtin_agent_process_spawned','builtin_agent_outbound_connection','edge_l0_content_deny','edge_l0_refusal_bypass_deny')
  AND rule_revision = (SELECT current_revision FROM tb_rules_current
                       WHERE tenant_id = 'default' AND rule_id = tb_rule_history.rule_id);

UPDATE tb_rules_current SET rollout_state = 'dry_run', updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'default' AND rule_id IN ('builtin_agent_process_spawned','builtin_agent_outbound_connection','edge_l0_content_deny','edge_l0_refusal_bypass_deny');
