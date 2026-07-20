-- ============================================================
-- V2: Migrate hardcoded Falco rules from ConfigMap into the database.
--
-- Previously, 3 Falco rules were hardcoded in falco-config.yaml
-- (falco_rules.local.yaml).  This migration seeds them into the
-- unified rule tables so that ALL Falco rules are manageable via
-- the control-plane API.
--
-- Falco macros (spawned_process, outbound) have been inlined into
-- the condition expressions so the rules are self-contained and
-- no longer depend on falco_rules.yaml (which has been removed
-- from the Falco rules_file list).
--
-- All inserts use WHERE NOT EXISTS so that operator modifications
-- are preserved on re-run.
-- ============================================================

-- ── Rule 1: builtin_sensitive_file_access ──

INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
    reason_code, risk_score, intent_action, is_async, async_action_config,
    scope_json, body_json, rollout_state, canary_percent,
    effective_from, modified_at
)
SELECT
    'default',
    'builtin_sensitive_file_access',
    1,
    'system-builtin',
    'falco',
    'falco',
    'WARNING',
    100,
    'deny',
    0,
    NULL,
    '{"bind_scope":"global","description":"Detect access to sensitive system files"}',
    '{"condition":"evt.type in (open, openat, openat2) and fd.name in (/etc/shadow, /etc/passwd, /root/.ssh/id_rsa, /root/.ssh/authorized_keys) and evt.is_open_write=true","output":"Sensitive file access (user=%user.name, pid=%proc.pid, ppid=%proc.ppid, pname=%proc.name, file=%fd.name, pcmdline=%proc.pcmdline)","priority":"WARNING","tags":["agent","filesystem","sensitive"]}',
    'full',
    NULL,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM tb_rule_history
    WHERE tenant_id = 'default' AND rule_id = 'builtin_sensitive_file_access'
);

INSERT INTO tb_rules_current (
    tenant_id, rule_id, current_revision, bundle_id, layer, runtime,
    reason_code, intent_action, rollout_state, updated_at
)
SELECT
    'default',
    'builtin_sensitive_file_access',
    1,
    'system-builtin',
    'falco',
    'falco',
    'WARNING',
    'deny',
    'full',
    CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM tb_rules_current
    WHERE tenant_id = 'default' AND rule_id = 'builtin_sensitive_file_access'
);

-- ── Rule 2: builtin_agent_process_spawned ──
-- Macro "spawned_process" inlined as: evt.type in (execve, execveat) and evt.dir=<

INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
    reason_code, risk_score, intent_action, is_async, async_action_config,
    scope_json, body_json, rollout_state, canary_percent,
    effective_from, modified_at
)
SELECT
    'default',
    'builtin_agent_process_spawned',
    1,
    'system-builtin',
    'falco',
    'falco',
    'WARNING',
    80,
    'deny',
    0,
    NULL,
    '{"bind_scope":"global","description":"Detect new processes spawned by Agent"}',
    '{"condition":"evt.type in (execve, execveat) and evt.dir=< and not proc.name startswith \"falco\" and not proc.name startswith \"virbius\"","output":"Agent process spawned (user=%user.name, pid=%proc.pid, ppid=%proc.ppid, command=%proc.cmdline, pcmdline=%proc.pcmdline)","priority":"WARNING","tags":["agent","process"]}',
    'full',
    NULL,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM tb_rule_history
    WHERE tenant_id = 'default' AND rule_id = 'builtin_agent_process_spawned'
);

INSERT INTO tb_rules_current (
    tenant_id, rule_id, current_revision, bundle_id, layer, runtime,
    reason_code, intent_action, rollout_state, updated_at
)
SELECT
    'default',
    'builtin_agent_process_spawned',
    1,
    'system-builtin',
    'falco',
    'falco',
    'WARNING',
    'deny',
    'full',
    CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM tb_rules_current
    WHERE tenant_id = 'default' AND rule_id = 'builtin_agent_process_spawned'
);

-- ── Rule 3: builtin_agent_outbound_connection ──
-- Macro "outbound" inlined as: evt.type=connect and evt.dir=< and fd.typechar=4

INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
    reason_code, risk_score, intent_action, is_async, async_action_config,
    scope_json, body_json, rollout_state, canary_percent,
    effective_from, modified_at
)
SELECT
    'default',
    'builtin_agent_outbound_connection',
    1,
    'system-builtin',
    'falco',
    'falco',
    'NOTICE',
    60,
    'deny',
    0,
    NULL,
    '{"bind_scope":"global","description":"Detect outbound connections from Agent"}',
    '{"condition":"evt.type=connect and evt.dir=< and fd.typechar=4 and not fd.sip in (127.0.0.1, 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16)","output":"Agent outbound connection (pid=%proc.pid, ppid=%proc.ppid, pname=%proc.name, sip=%fd.sip, sport=%fd.sport, pcmdline=%proc.pcmdline)","priority":"NOTICE","tags":["agent","network"]}',
    'full',
    NULL,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM tb_rule_history
    WHERE tenant_id = 'default' AND rule_id = 'builtin_agent_outbound_connection'
);

INSERT INTO tb_rules_current (
    tenant_id, rule_id, current_revision, bundle_id, layer, runtime,
    reason_code, intent_action, rollout_state, updated_at
)
SELECT
    'default',
    'builtin_agent_outbound_connection',
    1,
    'system-builtin',
    'falco',
    'falco',
    'NOTICE',
    'deny',
    'full',
    CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM tb_rules_current
    WHERE tenant_id = 'default' AND rule_id = 'builtin_agent_outbound_connection'
);
