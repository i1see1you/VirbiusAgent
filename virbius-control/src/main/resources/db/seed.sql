-- Minimal seed data (PostgreSQL / MySQL / SQLite compatible)
-- Idempotent: INSERT ... SELECT ... WHERE NOT EXISTS (no INSERT OR IGNORE / ON CONFLICT dependency)

INSERT INTO tb_tenants (tenant_id, name)
SELECT 'default', 'Default Tenant' FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_tenants WHERE tenant_id = 'default');

INSERT INTO tb_bundles (tenant_id, bundle_id, version, status, metadata_json)
SELECT 'default', 'demo-default', '0.1.0', 'draft',
    '{"scope":{"tenants":["default"],"scenes":["demo_chat"],"apps":["demo-app"]},"scene_registry":{"version":1,"fail_on_unknown_app":false,"fail_on_unresolved_scene":false,"scenes":{"demo_chat":{"app_id":"demo-app","default":true,"uris":["/v1/chat/completions"],"priority":0}}},"gateway":{"evaluate":true,"fail_mode":"open","cloud_scan":{"agent_url":"http://127.0.0.1:9070","timeout_ms":3000},"routes":[{"uri":"/v1/chat/completions","methods":["POST"]}]}}'
FROM (SELECT 1) AS _one
WHERE NOT EXISTS (
    SELECT 1 FROM tb_bundles WHERE tenant_id = 'default' AND bundle_id = 'demo-default' AND version = '0.1.0'
);

UPDATE tb_bundles
SET metadata_json = '{"scope":{"tenants":["default"],"scenes":["demo_chat"],"apps":["demo-app"]},"scene_registry":{"version":1,"fail_on_unknown_app":false,"fail_on_unresolved_scene":false,"scenes":{"demo_chat":{"app_id":"demo-app","default":true,"uris":["/v1/chat/completions"],"priority":0}}},"gateway":{"evaluate":true,"fail_mode":"open","cloud_scan":{"agent_url":"http://127.0.0.1:9070","timeout_ms":3000},"routes":[{"uri":"/v1/chat/completions","methods":["POST"]}]}}'
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

-- rule_history (revision=1)
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

INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at
)
SELECT 'default', 'gw_subject_network_deny', 1, 'demo-default', 'gateway', 'lua',
    'GW_SUBJECT_USER_DENY', 100, 'deny', '{}',
    '{"list_type":"deny","subjects":{"user_ids":["u-demo-banned"],"device_ids":["dev-demo-blocked"],"ip_cidrs":[]}}',
    'disabled', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'gw_subject_network_deny' AND rule_revision = 1);

INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at
)
SELECT 'default', 'gw_subject_network_allow', 1, 'demo-default', 'gateway', 'lua',
    'EDGE_CONTENT_KEYWORD_ALLOW', 0, 'allow', '{}',
    '{"list_type":"allow","subjects":{"user_ids":[],"device_ids":[],"ip_cidrs":[]}}',
    'disabled', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'gw_subject_network_allow' AND rule_revision = 1);

INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at
)
SELECT 'default', 'gw_content_deny', 1, 'demo-default', 'gateway', 'lua',
    'GW_CONTENT_KEYWORD_DENY', 100, 'deny', '{}',
    '{"keywords":["demo-deny-keyword-1","demo-deny-keyword-2","jailbreak","ignore previous instructions"],"list_type":"deny"}',
    'disabled', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'gw_content_deny' AND rule_revision = 1);

INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at
)
SELECT 'default', 'gw_content_allow', 1, 'demo-default', 'gateway', 'lua',
    'EDGE_KEYWORD_WHITELIST', 0, 'allow', '{}',
    '{"keywords":[],"list_type":"allow"}',
    'disabled', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'gw_content_allow' AND rule_revision = 1);

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

-- Script-rules migration: disable legacy gw_* JSON gateway rules; fix rl_* Lua bodies
UPDATE tb_rules_current SET rollout_state = 'disabled', updated_at = CURRENT_TIMESTAMP
  WHERE tenant_id = 'default' AND rule_id IN (
    'gw_content_deny', 'gw_content_allow', 'gw_subject_network_deny', 'gw_subject_network_allow');
UPDATE tb_rule_history SET rollout_state = 'disabled', modified_at = CURRENT_TIMESTAMP
  WHERE tenant_id = 'default' AND rule_id IN (
    'gw_content_deny', 'gw_content_allow', 'gw_subject_network_deny', 'gw_subject_network_allow')
    AND effective_to IS NULL;

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
SELECT 'default', 'assisted', '[5,20,50,100]', 1, 100, 0.05, 2.0, 12, 10, 1, 3.0, 0.1, 10
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

-- ============================================================
-- Demo Falco rules
-- ============================================================

INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at
)
SELECT 'default', 'builtin_sensitive_file_access', 1, 'demo-default', 'falco', 'falco',
    'WARNING', 100, 'deny',
    '{"bind_scope":"global","description":"Detect access to sensitive system files"}',
    '{"condition":"evt.type in (open, openat, openat2) and fd.name in (/etc/shadow, /etc/passwd, /root/.ssh/id_rsa, /root/.ssh/authorized_keys) and evt.is_open_write=true","output":"Sensitive file access (user=%user.name, pid=%proc.pid, ppid=%proc.ppid, pname=%proc.name, file=%fd.name, pcmdline=%proc.pcmdline)","priority":"WARNING","tags":["agent","filesystem","sensitive"]}',
    'full', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'builtin_sensitive_file_access' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'builtin_sensitive_file_access', 1, 'demo-default', 'falco', 'falco', 'WARNING', 'full', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'builtin_sensitive_file_access');

INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at
)
SELECT 'default', 'builtin_agent_process_spawned', 1, 'demo-default', 'falco', 'falco',
    'WARNING', 80, 'deny',
    '{"bind_scope":"global","description":"Detect new processes spawned by Agent"}',
    '{"condition":"evt.type in (execve, execveat) and evt.dir=< and not proc.name startswith \"falco\" and not proc.name startswith \"virbius\"","output":"Agent process spawned (user=%user.name, pid=%proc.pid, ppid=%proc.ppid, command=%proc.cmdline, pcmdline=%proc.pcmdline)","priority":"WARNING","tags":["agent","process"]}',
    'full', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'builtin_agent_process_spawned' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'builtin_agent_process_spawned', 1, 'demo-default', 'falco', 'falco', 'WARNING', 'full', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'builtin_agent_process_spawned');

INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at
)
SELECT 'default', 'builtin_agent_outbound_connection', 1, 'demo-default', 'falco', 'falco',
    'NOTICE', 60, 'deny',
    '{"bind_scope":"global","description":"Detect outbound connections from Agent"}',
    '{"condition":"evt.type=connect and evt.dir=< and fd.typechar=4 and not fd.sip in (127.0.0.1, 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16)","output":"Agent outbound connection (pid=%proc.pid, ppid=%proc.ppid, pname=%proc.name, sip=%fd.sip, sport=%fd.sport, pcmdline=%proc.pcmdline)","priority":"NOTICE","tags":["agent","network"]}',
    'full', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'builtin_agent_outbound_connection' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'builtin_agent_outbound_connection', 1, 'demo-default', 'falco', 'falco', 'NOTICE', 'full', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
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
SELECT 'default', 'cloud_session_risk_escalation', 1, 'demo-default', 'cloud', 'groovy',
    'CLOUD_SESSION_RISK_ESCALATION', 85, 'challenge', '{"bind_scope":"global"}',
    'def decide(ctx) {
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
