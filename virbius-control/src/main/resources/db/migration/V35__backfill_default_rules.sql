-- V35: backfill default rules that only existed in db/seed.sql.
--
-- Production/staging use Flyway only (spring.sql.init.mode=never); V1-V34
-- insert the cloud/edge/falco rules that changed over time, but the initial
-- prompt-* set, cloud_session_risk_escalation, edge_dlp_phone,
-- edge_l0_content_deny and the two 1h gateway rate rules were only present in
-- the dev seed. A fresh MySQL install therefore ended up with 26 of 41 rules.
--
-- This migration is insert-only and idempotent (WHERE NOT EXISTS); operator
-- customizations for an existing rule_id are preserved. Result on a fresh
-- install: 41 rules (37 full / 4 dry_run) + 3 cumulative definitions.
-- cumulatives required by the gateway 1h rate rules
INSERT INTO tb_cumulative (
    tenant_id, cumulative_name, description, dimension, window_kind, window_minutes, window_hours,
    timezone, priority, status)
SELECT 'default', 'user_req_1h', 'User rolling 1-hour request limit', 'user_id', 'rolling', 60, NULL,
    NULL, 10, 'active' FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_cumulative WHERE tenant_id = 'default' AND cumulative_name = 'user_req_1h');

INSERT INTO tb_cumulative (
    tenant_id, cumulative_name, description, dimension, window_kind, window_minutes, window_hours,
    timezone, priority, status)
SELECT 'default', 'app_req_1h', 'App rolling 1-hour request limit (var:app_id)', 'var:app_id', 'rolling', 60, NULL,
    NULL, 11, 'active' FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_cumulative WHERE tenant_id = 'default' AND cumulative_name = 'app_req_1h');

-- cloud_session_risk_escalation (cloud/groovy)
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
    'full', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'cloud_session_risk_escalation' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'cloud_session_risk_escalation', 1, 'demo-default', 'cloud', 'groovy', 'CLOUD_SESSION_RISK_ESCALATION', 'full', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'cloud_session_risk_escalation');

-- edge_dlp_phone (edge/dlp-dsl)
INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at)
SELECT 'default', 'edge_dlp_phone', 1, 'demo-default', 'edge', 'dlp-dsl',
    'DLP_PHONE', 0, 'allow', '{}',
    '{"entity_type":"phone_cn","priority":0,"mask_template":"{{VIRBIUS_PHONE_CN_{seq}}}"}',
    'full', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'edge_dlp_phone' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'edge_dlp_phone', 1, 'demo-default', 'edge', 'dlp-dsl', 'DLP_PHONE', 'full', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'edge_dlp_phone');

-- edge_l0_content_deny (edge/lua-dsl)
INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at)
SELECT 'default', 'edge_l0_content_deny', 1, 'demo-default', 'edge', 'lua-dsl',
    'EDGE_CONTENT_KEYWORD_DENY', 100, 'deny', '{}',
    '{"keywords":["demo-deny-keyword-1","demo-deny-keyword-2","jailbreak","ignore previous instructions"],"list_type":"deny"}',
    'dry_run', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'edge_l0_content_deny' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'edge_l0_content_deny', 1, 'demo-default', 'edge', 'lua-dsl', 'EDGE_CONTENT_KEYWORD_DENY', 'dry_run', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'edge_l0_content_deny');

-- prompt-agent (cloud/prompt)
INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at)
SELECT 'default', 'prompt-agent', 1, 'demo-default', 'cloud', 'prompt',
    'AGENT', 100, 'deny', '{"bind_scope":"global"}',
    '"Agent tool misuse, MCP tool abuse, unauthorized tool calls, data exfiltration or harmful actions via tools."',
    'full', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'prompt-agent' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'prompt-agent', 1, 'demo-default', 'cloud', 'prompt', 'AGENT', 'full', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'prompt-agent');

-- prompt-copyright (cloud/prompt)
INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at)
SELECT 'default', 'prompt-copyright', 1, 'demo-default', 'cloud', 'prompt',
    'COPYRIGHT', 80, 'deny', '{"bind_scope":"global"}',
    '"Copyrighted content, pirated resources, infringing generation."',
    'full', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'prompt-copyright' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'prompt-copyright', 1, 'demo-default', 'cloud', 'prompt', 'COPYRIGHT', 'full', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'prompt-copyright');

-- prompt-illegal (cloud/prompt)
INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at)
SELECT 'default', 'prompt-illegal', 1, 'demo-default', 'cloud', 'prompt',
    'ILLEGAL', 100, 'deny', '{"bind_scope":"global"}',
    '"Drug manufacturing, fraud, illegal intrusion and other non-violent illegal activities."',
    'full', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'prompt-illegal' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'prompt-illegal', 1, 'demo-default', 'cloud', 'prompt', 'ILLEGAL', 'full', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'prompt-illegal');

-- prompt-jailbreak (cloud/prompt)
INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at)
SELECT 'default', 'prompt-jailbreak', 1, 'demo-default', 'cloud', 'prompt',
    'JAILBREAK', 100, 'deny', '{"bind_scope":"global"}',
    '"DAN, role-play bypass, ignore previous instructions and other prompt injection / jailbreak attacks."',
    'full', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'prompt-jailbreak' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'prompt-jailbreak', 1, 'demo-default', 'cloud', 'prompt', 'JAILBREAK', 'full', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'prompt-jailbreak');

-- prompt-pii (cloud/prompt)
INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at)
SELECT 'default', 'prompt-pii', 1, 'demo-default', 'cloud', 'prompt',
    'PII', 100, 'deny', '{"bind_scope":"global"}',
    '"ID numbers, phone numbers, bank card numbers and other personal sensitive information leakage."',
    'full', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'prompt-pii' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'prompt-pii', 1, 'demo-default', 'cloud', 'prompt', 'PII', 'full', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'prompt-pii');

-- prompt-political (cloud/prompt)
INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at)
SELECT 'default', 'prompt-political', 1, 'demo-default', 'cloud', 'prompt',
    'POLITICAL', 80, 'deny', '{"bind_scope":"global"}',
    '"Territorial sovereignty, ethnic religion, historical events and other politically sensitive topics."',
    'full', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'prompt-political' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'prompt-political', 1, 'demo-default', 'cloud', 'prompt', 'POLITICAL', 'full', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'prompt-political');

-- prompt-self-harm (cloud/prompt)
INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at)
SELECT 'default', 'prompt-self-harm', 1, 'demo-default', 'cloud', 'prompt',
    'SELF_HARM', 100, 'deny', '{"bind_scope":"global"}',
    '"Suicide, self-harm, self-injury and other behaviors endangering personal safety."',
    'full', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'prompt-self-harm' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'prompt-self-harm', 1, 'demo-default', 'cloud', 'prompt', 'SELF_HARM', 'full', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'prompt-self-harm');

-- prompt-sexual (cloud/prompt)
INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at)
SELECT 'default', 'prompt-sexual', 1, 'demo-default', 'cloud', 'prompt',
    'SEXUAL', 100, 'deny', '{"bind_scope":"global"}',
    '"Pornography, obscenity, and other inappropriate sexual content."',
    'full', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'prompt-sexual' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'prompt-sexual', 1, 'demo-default', 'cloud', 'prompt', 'SEXUAL', 'full', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'prompt-sexual');

-- prompt-unethical (cloud/prompt)
INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at)
SELECT 'default', 'prompt-unethical', 1, 'demo-default', 'cloud', 'prompt',
    'UNETHICAL', 80, 'deny', '{"bind_scope":"global"}',
    '"Cheating, plagiarism, discrimination, and other unethical behavior."',
    'full', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'prompt-unethical' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'prompt-unethical', 1, 'demo-default', 'cloud', 'prompt', 'UNETHICAL', 'full', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'prompt-unethical');

-- prompt-violent (cloud/prompt)
INSERT INTO tb_rule_history (
    tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
    rollout_state, canary_percent, effective_from, modified_at)
SELECT 'default', 'prompt-violent', 1, 'demo-default', 'cloud', 'prompt',
    'VIOLENT', 100, 'deny', '{"bind_scope":"global"}',
    '"Violence, harm, terrorism and other extreme behavior."',
    'full', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'prompt-violent' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'prompt-violent', 1, 'demo-default', 'cloud', 'prompt', 'VIOLENT', 'full', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'prompt-violent');

-- rl_rate_app_1h (gateway/lua)
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
    'full', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'rl_rate_app_1h' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'rl_rate_app_1h', 1, 'demo-default', 'gateway', 'lua', 'GW_APP_RATE_1H', 'full', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'rl_rate_app_1h');

-- rl_rate_user_1h (gateway/lua)
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
    'full', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rule_history WHERE tenant_id = 'default' AND rule_id = 'rl_rate_user_1h' AND rule_revision = 1);

INSERT INTO tb_rules_current (tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, rollout_state, updated_at)
SELECT 'default', 'rl_rate_user_1h', 1, 'demo-default', 'gateway', 'lua', 'GW_USER_RATE_1H', 'full', CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_rules_current WHERE tenant_id = 'default' AND rule_id = 'rl_rate_user_1h');
