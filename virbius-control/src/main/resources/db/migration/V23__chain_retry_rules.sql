-- V23: migrate ChainPatternDetector / RetryEscapeDetector to default Groovy rules.
--
-- Mirrors db/seed.sql. The four rules replace the Java detectors for control-
-- managed tenants (engine short-circuits the detectors when the rules exist).
-- Defaults are full (challenge intent), matching the plan's "default online"
-- decision; demote to dry_run in the console to roll back to observe-only.
-- chain-exfil sensitive prefixes are deliberately narrower than the Java
-- version (bare /home/ dropped) to bound challenge noise under full.

INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
    reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at)
SELECT 'default', 'chain-exfil', 1, 'system-builtin', 'cloud', 'groovy',
    'CHAIN_ABUSE', 20, 'challenge', '{"bind_scope":"global"}',
    'def decide(ctx) {
    def readTools = ["read_file", "cat", "read", "get_file", "download", "list_dir"]
    def netTools = ["curl", "http_get", "http_post", "fetch", "webhook_call", "wget", "request"]
    def sensitive = ["/etc/", "/root/", "/.ssh", "id_rsa", ".env", "credentials", "secret", "/var/"]
    def seq = []
    def h = ctx.sessionHistory(5)
    for (int i = h.size() - 1; i >= 0; i--) {
        seq.add(h[i])
    }
    seq.add([tool_name: ctx.var("tool_name"), args: ctx.var("content")])
    if (seq.size() > 5) {
        seq = seq.subList(seq.size() - 5, seq.size())
    }
    def step = 0
    for (def e : seq) {
        def t = e.tool_name?.toString()
        def a = (e.args ?: "").toString().toLowerCase()
        if (step == 0 && t != null && readTools.contains(t) && sensitive.any { a.contains(it) }) {
            step = 1
        } else if (step == 1 && t != null && netTools.contains(t)) {
            def i = a.indexOf("http")
            if (i >= 0) {
                def rest = a.substring(i)
                def end = rest.indexOf(34)
                def url = end > 0 ? rest.substring(0, end) : rest
                if (!ctx.isInternalHost(url)) {
                    step = 2
                }
            }
        }
    }
    return step == 2
}',
    'full', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM tb_rule_history
    WHERE tenant_id = 'default' AND rule_id = 'chain-exfil' AND rule_revision = 1
);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'chain-exfil', 1, 'system-builtin', 'cloud', 'groovy', 'CHAIN_ABUSE', 'full', CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM tb_rules_current
    WHERE tenant_id = 'default' AND rule_id = 'chain-exfil'
);

INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
    reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at)
SELECT 'default', 'chain-write-exec', 1, 'system-builtin', 'cloud', 'groovy',
    'CHAIN_ABUSE', 25, 'challenge', '{"bind_scope":"global"}',
    'def decide(ctx) {
    def writeTools = ["write_file", "delete_file", "rm", "mv", "cp", "truncate", "edit_file", "db_write"]
    def execTools = ["exec_cmd", "shell", "bash", "sh", "execute_python", "run_command", "subprocess"]
    def seq = []
    def h = ctx.sessionHistory(6)
    for (int i = h.size() - 1; i >= 0; i--) {
        seq.add(h[i])
    }
    seq.add([tool_name: ctx.var("tool_name"), args: ctx.var("content")])
    if (seq.size() > 6) {
        seq = seq.subList(seq.size() - 6, seq.size())
    }
    def step = 0
    for (def e : seq) {
        def t = e.tool_name?.toString()
        if (step == 0 && t != null && writeTools.contains(t)) {
            step = 1
        } else if (step == 1 && t != null && execTools.contains(t)) {
            step = 2
        }
    }
    return step == 2
}',
    'full', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM tb_rule_history
    WHERE tenant_id = 'default' AND rule_id = 'chain-write-exec' AND rule_revision = 1
);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'chain-write-exec', 1, 'system-builtin', 'cloud', 'groovy', 'CHAIN_ABUSE', 'full', CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM tb_rules_current
    WHERE tenant_id = 'default' AND rule_id = 'chain-write-exec'
);

INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
    reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at)
SELECT 'default', 'chain-priv-esc', 1, 'system-builtin', 'cloud', 'groovy',
    'CHAIN_ABUSE', 15, 'challenge', '{"bind_scope":"global"}',
    'def decide(ctx) {
    def readTools = ["read_file", "cat", "read", "get_file", "download", "list_dir"]
    def writeTools = ["write_file", "delete_file", "rm", "mv", "cp", "truncate", "edit_file", "db_write"]
    def execTools = ["exec_cmd", "shell", "bash", "sh", "execute_python", "run_command", "subprocess"]
    def seq = []
    def h = ctx.sessionHistory(8)
    for (int i = h.size() - 1; i >= 0; i--) {
        seq.add(h[i])
    }
    seq.add([tool_name: ctx.var("tool_name"), args: ctx.var("content")])
    if (seq.size() > 8) {
        seq = seq.subList(seq.size() - 8, seq.size())
    }
    def step = 0
    for (def e : seq) {
        def t = e.tool_name?.toString()
        if (step == 0 && t != null && readTools.contains(t)) {
            step = 1
        } else if (step == 1 && t != null && writeTools.contains(t)) {
            step = 2
        } else if (step == 2 && t != null && execTools.contains(t)) {
            step = 3
        }
    }
    return step == 3
}',
    'full', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM tb_rule_history
    WHERE tenant_id = 'default' AND rule_id = 'chain-priv-esc' AND rule_revision = 1
);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'chain-priv-esc', 1, 'system-builtin', 'cloud', 'groovy', 'CHAIN_ABUSE', 'full', CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM tb_rules_current
    WHERE tenant_id = 'default' AND rule_id = 'chain-priv-esc'
);

INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
    reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at)
SELECT 'default', 'retry-escape', 1, 'system-builtin', 'cloud', 'groovy',
    'RETRY_ESCAPE', 30, 'challenge', '{"bind_scope":"global"}',
    'def decide(ctx) {
    def h = ctx.sessionHistory(10)
    def attempts = []
    for (int i = h.size() - 1; i >= 0; i--) {
        attempts.add(h[i])
    }
    if (attempts.size() > 10) {
        attempts = attempts.subList(attempts.size() - 10, attempts.size())
    }
    def lastBlock = -1
    for (int i = 0; i < attempts.size(); i++) {
        if (attempts[i].allowed == false) {
            lastBlock = i
        }
    }
    if (lastBlock < 0) {
        return false
    }
    def blocked = attempts[lastBlock]
    def trailing = 0
    for (int i = attempts.size() - 1; i > lastBlock; i--) {
        if (attempts[i].allowed != false) {
            trailing++
        } else {
            break
        }
    }
    if (trailing >= 3) {
        return false
    }
    def after = []
    after.addAll(attempts.subList(lastBlock + 1, attempts.size()))
    after.add([tool_name: ctx.var("tool_name"), args: ctx.var("content")])
    if (after.size() < 3) {
        return false
    }
    def bTool = blocked.tool_name?.toString()
    def bArgs = (blocked.args ?: "").toString()
    return after.any {
        def t = it.tool_name?.toString()
        def a = (it.args ?: "").toString()
        (t != null && t != bTool) || a != bArgs
    }
}',
    'full', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM tb_rule_history
    WHERE tenant_id = 'default' AND rule_id = 'retry-escape' AND rule_revision = 1
);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'retry-escape', 1, 'system-builtin', 'cloud', 'groovy', 'RETRY_ESCAPE', 'full', CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM tb_rules_current
    WHERE tenant_id = 'default' AND rule_id = 'retry-escape'
);
