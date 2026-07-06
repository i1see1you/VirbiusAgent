-- Core seed data for production (no dev API keys, no PoC demo rules)

INSERT INTO tb_tenants (tenant_id, name)
SELECT 'default', 'Default Tenant'
WHERE NOT EXISTS (SELECT 1 FROM tb_tenants WHERE tenant_id = 'default');

INSERT INTO tb_tenant_rollout_policy (
    tenant_id, auto_mode, canary_ladder_json, min_dry_run_hours, min_review_count,
    max_review_rate, max_review_spike_ratio, min_hours_per_step,
    min_block_samples_per_step, allow_force, rollback_block_spike_ratio,
    edge_audit_sample_rate_allow, max_concurrent_rollouts
)
SELECT 'default', 'assisted', '[5,20,50,100]', 1, 100, 0.05, 2.0, 12, 10, 1, 3.0, 0.1, 10
WHERE NOT EXISTS (SELECT 1 FROM tb_tenant_rollout_policy WHERE tenant_id = 'default');
