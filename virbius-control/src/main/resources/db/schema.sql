-- virbius-control 表结构（PostgreSQL / MySQL / SQLite 通用 JDBC 方言）
-- 时间列用 TIMESTAMP；主键/短文本用 VARCHAR；JSON 用 TEXT

CREATE TABLE IF NOT EXISTS tb_tenants (
    tenant_id   VARCHAR(64) PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS tb_bundles (
    tenant_id         VARCHAR(64) NOT NULL,
    bundle_id         VARCHAR(128) NOT NULL,
    version           VARCHAR(64) NOT NULL,
    status            VARCHAR(32) NOT NULL DEFAULT 'draft',
    publish_id        VARCHAR(64),
    sync_ack_json     TEXT,
    metadata_json     TEXT,
    metadata_version  INTEGER NOT NULL DEFAULT 0,
    created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, bundle_id, version)
);

-- 请求因子（逻辑删除；发布后该 bundle 版本只读）
CREATE TABLE IF NOT EXISTS tb_context_bindings (
    tenant_id    VARCHAR(64)  NOT NULL,
    bundle_id    VARCHAR(128) NOT NULL,
    version      VARCHAR(64)  NOT NULL,
    logical      VARCHAR(64)  NOT NULL,
    src_from     VARCHAR(16)  NOT NULL,
    src_name     VARCHAR(128),
    src_field    VARCHAR(128),
    scope_json   TEXT,
    deleted_at   TIMESTAMP,
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, bundle_id, version, logical)
);

CREATE INDEX IF NOT EXISTS idx_ctx_bindings_lookup
    ON tb_context_bindings (tenant_id, bundle_id, version);

-- 扩展因子（逻辑删除；发布后该 bundle 版本只读）
CREATE TABLE IF NOT EXISTS tb_extended_vars (
    tenant_id    VARCHAR(64)  NOT NULL,
    bundle_id    VARCHAR(128) NOT NULL,
    version      VARCHAR(64)  NOT NULL,
    logical      VARCHAR(64)  NOT NULL,
    expr         TEXT NOT NULL,
    scope_json   TEXT,
    deleted_at   TIMESTAMP,
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, bundle_id, version, logical)
);

CREATE INDEX IF NOT EXISTS idx_ext_vars_lookup
    ON tb_extended_vars (tenant_id, bundle_id, version);

CREATE TABLE IF NOT EXISTS tb_rules_current (
    tenant_id         VARCHAR(64) NOT NULL,
    rule_id           VARCHAR(128) NOT NULL,
    current_revision  INTEGER NOT NULL,
    bundle_id         VARCHAR(128) NOT NULL,
    layer             VARCHAR(32) NOT NULL,
    runtime           VARCHAR(32) NOT NULL,
    reason_code       VARCHAR(64) NOT NULL,
    intent_action     VARCHAR(32) NOT NULL DEFAULT 'deny',
    rollout_state     VARCHAR(16) NOT NULL DEFAULT 'draft',
    updated_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, rule_id)
);

CREATE TABLE IF NOT EXISTS tb_rule_history (
    tenant_id       VARCHAR(64) NOT NULL,
    rule_id         VARCHAR(128) NOT NULL,
    rule_revision   INTEGER NOT NULL,
    bundle_id       VARCHAR(128) NOT NULL,
    layer           VARCHAR(32) NOT NULL,
    runtime         VARCHAR(32) NOT NULL,
    reason_code     VARCHAR(64) NOT NULL,
    risk_score      INTEGER NOT NULL DEFAULT 100,
    scope_json      TEXT,
    body_json       TEXT,
    body_hash       VARCHAR(64),
    intent_action   VARCHAR(32) NOT NULL DEFAULT 'deny',
    is_async        INTEGER NOT NULL DEFAULT 0,
    async_action_config TEXT,
    rollout_state   VARCHAR(16) NOT NULL DEFAULT 'draft',
    canary_percent  INTEGER,
    effective_from  TIMESTAMP NOT NULL,
    effective_to    TIMESTAMP,
    modified_at     TIMESTAMP NOT NULL,
    modified_by     VARCHAR(64),
    publish_id      VARCHAR(64),
    PRIMARY KEY (tenant_id, rule_id, rule_revision),
    CHECK (risk_score >= 0 AND risk_score <= 100),
    CHECK (rollout_state IN ('draft', 'disabled', 'dry_run', 'canary', 'full')),
    CHECK (
        (rollout_state = 'canary' AND canary_percent BETWEEN 1 AND 100)
        OR (rollout_state <> 'canary' AND canary_percent IS NULL)
    )
);

CREATE INDEX IF NOT EXISTS idx_tb_rule_history_tenant_layer
    ON tb_rule_history (tenant_id, layer);

CREATE INDEX IF NOT EXISTS idx_tb_rule_history_effective
    ON tb_rule_history (tenant_id, rule_id, effective_to);

-- risk_score：0=放行，1–99=灰区，100=应拦截
-- 旧库请 VIRBIUS_REBUILD_DB=1 重建

CREATE TABLE IF NOT EXISTS tb_access_list (
    tenant_id    VARCHAR(64) NOT NULL,
    polarity     VARCHAR(16) NOT NULL,
    dimension    VARCHAR(32) NOT NULL,
    value        VARCHAR(512) NOT NULL,
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, polarity, dimension, value),
    CHECK (polarity IN ('deny', 'allow')),
    CHECK (dimension IN ('keyword', 'user_id', 'device_id', 'ip_cidr', 'var'))
);

CREATE INDEX IF NOT EXISTS idx_tb_access_list_tenant
    ON tb_access_list (tenant_id, polarity, dimension);

-- Named access lists (list_name model; allow/deny via list_match rule risk_score)
CREATE TABLE IF NOT EXISTS tb_access_list_meta (
    tenant_id    VARCHAR(64) NOT NULL,
    list_name    VARCHAR(128) NOT NULL,
    dimension    VARCHAR(32) NOT NULL,
    remark       VARCHAR(512),
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, list_name)
);

CREATE TABLE IF NOT EXISTS tb_access_list_entry (
    tenant_id    VARCHAR(64) NOT NULL,
    list_name    VARCHAR(128) NOT NULL,
    value        VARCHAR(512) NOT NULL,
    remark       VARCHAR(512),
    expires_at   TIMESTAMP,
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, list_name, value)
);

CREATE INDEX IF NOT EXISTS idx_tb_access_list_entry_tenant
    ON tb_access_list_entry (tenant_id, list_name);

-- Cumulative counter definitions (window + dimension only; conditions on rules)
CREATE TABLE IF NOT EXISTS tb_cumulative (
    tenant_id              VARCHAR(64) NOT NULL,
    cumulative_name        VARCHAR(128) NOT NULL,
    description            VARCHAR(512),
    dimension              VARCHAR(32) NOT NULL,
    window_kind            VARCHAR(32) NOT NULL,
    window_minutes         INTEGER,
    window_hours           INTEGER,
    timezone               VARCHAR(64),
    priority               INTEGER NOT NULL DEFAULT 0,
    status                 VARCHAR(16) NOT NULL DEFAULT 'active',
    ingest_predicate_runtime VARCHAR(16),
    ingest_predicate       TEXT,
    created_at             TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, cumulative_name),
    CHECK (window_kind IN ('rolling', 'calendar_day')),
    CHECK (status IN ('active', 'disabled'))
);

CREATE TABLE IF NOT EXISTS tb_tenant_rollout_policy (
    tenant_id                   VARCHAR(64) PRIMARY KEY,
    auto_mode                   VARCHAR(16) NOT NULL DEFAULT 'assisted',
    canary_ladder_json          TEXT NOT NULL DEFAULT '[5,20,50,100]',
    min_dry_run_hours           INTEGER NOT NULL DEFAULT 1,
    min_review_count            INTEGER NOT NULL DEFAULT 100,
    max_review_rate             REAL NOT NULL DEFAULT 0.05,
    max_review_spike_ratio      REAL NOT NULL DEFAULT 2.0,
    min_hours_per_step          INTEGER NOT NULL DEFAULT 12,
    min_block_samples_per_step  INTEGER NOT NULL DEFAULT 10,
    allow_force                 INTEGER NOT NULL DEFAULT 1,
    rollback_block_spike_ratio  REAL NOT NULL DEFAULT 3.0,
    edge_audit_sample_rate_allow REAL NOT NULL DEFAULT 0.1,
    max_concurrent_rollouts     INTEGER NOT NULL DEFAULT 10,
    updated_at                  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS tb_rule_ladder_state (
    tenant_id          VARCHAR(64) NOT NULL,
    rule_id            VARCHAR(128) NOT NULL,
    ladder_status      VARCHAR(16) NOT NULL DEFAULT 'idle',
    ladder_started_at  TIMESTAMP,
    updated_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, rule_id)
);

CREATE TABLE IF NOT EXISTS tb_rule_rollout_event (
    id               INTEGER PRIMARY KEY,
    tenant_id        VARCHAR(64) NOT NULL,
    rule_id          VARCHAR(128) NOT NULL,
    rule_revision    INTEGER NOT NULL,
    rollout_state    VARCHAR(16) NOT NULL,
    canary_percent   INTEGER,
    trigger          VARCHAR(32) NOT NULL,
    operator         VARCHAR(64),
    effective_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS tb_rule_gate_log (
    id                    INTEGER PRIMARY KEY,
    tenant_id             VARCHAR(64) NOT NULL,
    rule_id               VARCHAR(128) NOT NULL,
    from_state            VARCHAR(16),
    to_state              VARCHAR(16),
    pass                  INTEGER NOT NULL,
    reasons_json          TEXT,
    metrics_snapshot_json TEXT,
    operator              VARCHAR(64),
    comment               TEXT,
    created_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS tb_audit_events (
    id                INTEGER PRIMARY KEY,
    event_id          VARCHAR(128),
    trace_id          VARCHAR(128) NOT NULL,
    trace_id_source   VARCHAR(16),
    tenant_id         VARCHAR(64) NOT NULL,
    scene             VARCHAR(64) NOT NULL,
    layer             VARCHAR(16) NOT NULL,
    rule_id           VARCHAR(128) NOT NULL,
    rule_revision     INTEGER NOT NULL,
    reason_code       VARCHAR(64) NOT NULL,
    effective_action  VARCHAR(16) NOT NULL,
    max_risk_score    INTEGER NOT NULL,
    rollout_state     VARCHAR(16),
    canary_percent    INTEGER,
    in_canary_bucket  INTEGER,
    degraded          INTEGER,
    sampled_allow     INTEGER,
    sample_rate_allow REAL,
    intercepted_at    TIMESTAMP NOT NULL,
    user_id           VARCHAR(256),
    device_id         VARCHAR(256),
    created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_tb_audit_events_rule ON tb_audit_events (tenant_id, rule_id, intercepted_at);

CREATE UNIQUE INDEX IF NOT EXISTS uq_tb_audit_events_event_id ON tb_audit_events (event_id);

CREATE TABLE IF NOT EXISTS tb_audit_ingest_checkpoint (
    stream_key      VARCHAR(128) PRIMARY KEY,
    last_entry_id   VARCHAR(64) NOT NULL,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_audit_tenant_rule_time
  ON tb_audit_events(tenant_id, rule_id, intercepted_at);

CREATE TABLE IF NOT EXISTS tb_rule_metrics_1m (
    tenant_id           VARCHAR(64) NOT NULL,
    rule_id             VARCHAR(128) NOT NULL,
    minute_bucket       TIMESTAMP NOT NULL,
    rollout_state       VARCHAR(16),
    canary_percent      INTEGER,
    cnt_review          INTEGER NOT NULL DEFAULT 0,
    cnt_block           INTEGER NOT NULL DEFAULT 0,
    cnt_captcha         INTEGER NOT NULL DEFAULT 0,
    cnt_allow           INTEGER NOT NULL DEFAULT 0,
    cnt_total_requests  INTEGER NOT NULL DEFAULT 0,
    cnt_degraded        INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, rule_id, minute_bucket)
);

CREATE TABLE IF NOT EXISTS tb_tenant_request_stats_1h (
    tenant_id    VARCHAR(64) NOT NULL,
    scene        VARCHAR(64) NOT NULL,
    layer        VARCHAR(16) NOT NULL,
    hour_bucket  TIMESTAMP NOT NULL,
    cnt_total    INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, scene, layer, hour_bucket)
);

CREATE TABLE IF NOT EXISTS tb_edge_artifact_meta (
    tenant_id         VARCHAR(64) NOT NULL,
    app_id            VARCHAR(128) NOT NULL,
    pool              VARCHAR(16) NOT NULL DEFAULT 'stable',
    artifact_revision BIGINT NOT NULL DEFAULT 0,
    content_sha256    VARCHAR(64) NOT NULL,
    published_at      TIMESTAMP NOT NULL,
    PRIMARY KEY (tenant_id, app_id, pool)
);

CREATE TABLE IF NOT EXISTS tb_tenant_api_credential (
    credential_id   VARCHAR(36)  NOT NULL,
    tenant_id       VARCHAR(64)  NOT NULL,
    role            VARCHAR(32)  NOT NULL,
    key_hash        VARCHAR(64)  NOT NULL,
    key_prefix      VARCHAR(16)  NOT NULL,
    label           VARCHAR(128),
    status          VARCHAR(16)  NOT NULL DEFAULT 'active',
    created_by      VARCHAR(64),
    created_at      TIMESTAMP    NOT NULL,
    revoked_at      TIMESTAMP,
    last_used_at    TIMESTAMP,
    PRIMARY KEY (credential_id),
    UNIQUE (key_hash),
    CHECK (role IN ('tenant_viewer', 'tenant_admin', 'platform_admin')),
    CHECK (status IN ('active', 'revoked'))
);

CREATE INDEX IF NOT EXISTS idx_tb_tenant_api_cred_tenant
    ON tb_tenant_api_credential (tenant_id, status);

CREATE TABLE IF NOT EXISTS tb_deploy_state (
    tenant_id   VARCHAR(64) NOT NULL,
    layer       VARCHAR(16) NOT NULL,
    deployed_at TIMESTAMP NOT NULL,
    artifact    TEXT,
    PRIMARY KEY (tenant_id, layer),
    CHECK (layer IN ('gateway', 'cloud', 'edge'))
);

CREATE TABLE IF NOT EXISTS tb_bundle_releases (
    tenant_id        VARCHAR(64) NOT NULL,
    bundle_id        VARCHAR(128) NOT NULL,
    version          VARCHAR(64) NOT NULL,
    status           VARCHAR(32) NOT NULL DEFAULT 'deploying',
    frozen_snapshot  TEXT NOT NULL,
    created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deployed_at      TIMESTAMP,
    PRIMARY KEY (tenant_id, bundle_id, version),
    CHECK (status IN ('deploying', 'active', 'superseded', 'failed'))
);

CREATE TABLE IF NOT EXISTS tb_bundle_active (
    tenant_id        VARCHAR(64) NOT NULL,
    bundle_id        VARCHAR(128) NOT NULL,
    release_version  VARCHAR(64) NOT NULL,
    updated_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, bundle_id)
);

CREATE TABLE IF NOT EXISTS tb_bundle_staging (
    tenant_id        VARCHAR(64) NOT NULL,
    bundle_id        VARCHAR(128) NOT NULL,
    layer            VARCHAR(16) NOT NULL,
    base_version     VARCHAR(64) NOT NULL,
    status           VARCHAR(16) NOT NULL DEFAULT 'editing',
    rule_diffs       TEXT NOT NULL DEFAULT '{}',
    version          INTEGER NOT NULL DEFAULT 1,
    updated_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, bundle_id, layer),
    CHECK (layer IN ('edge', 'gateway', 'cloud')),
    CHECK (status IN ('editing', 'deploying', 'deployed'))
);

CREATE TABLE IF NOT EXISTS tb_gateway_artifact_meta (
    tenant_id              VARCHAR(64) PRIMARY KEY,
    artifact_revision      BIGINT       NOT NULL DEFAULT 0,
    access_lists_sha256    VARCHAR(64)  NOT NULL,
    scene_registry_sha256  VARCHAR(64)  NOT NULL,
    published_at           TIMESTAMP    NOT NULL,
    publish_id             VARCHAR(36),
    trigger                VARCHAR(32),
    storage                VARCHAR(16)  NOT NULL DEFAULT 'redis'
);

-- Deploy rollout: machine-bucket canary deployment of a bundle release across cloud + gateway.
CREATE TABLE IF NOT EXISTS tb_deploy_rollout (
    deploy_id              VARCHAR(64)  PRIMARY KEY,
    tenant_id              VARCHAR(64)  NOT NULL,
    bundle_id              VARCHAR(128) NOT NULL DEFAULT 'poc-default',
    state                  VARCHAR(16)  NOT NULL,
    canary_percent         INTEGER      NOT NULL DEFAULT 0,
    edge_deployed          INTEGER      NOT NULL DEFAULT 0,
    target_version         VARCHAR(64)  NOT NULL,
    prev_version           VARCHAR(64),
    canary_engine_revision  BIGINT,
    stable_engine_revision  BIGINT,
    canary_gateway_revision BIGINT,
    stable_gateway_revision BIGINT,
    canary_edge_revision   BIGINT,
    stable_edge_revision   BIGINT,
    canary_ladder          TEXT         NOT NULL DEFAULT '[5,20,50,100]',
    started_at             TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finalized_at           TIMESTAMP,
    operator               VARCHAR(64),
    note                   TEXT,
    CHECK (state IN ('pending','canary','paused','full','edge_done','rolled_back','finalized'))
);

CREATE INDEX IF NOT EXISTS idx_tb_deploy_rollout_tenant
    ON tb_deploy_rollout (tenant_id, started_at DESC);

CREATE INDEX IF NOT EXISTS idx_tb_deploy_rollout_active
    ON tb_deploy_rollout (tenant_id, state);

-- Deploy event audit log (state transitions, rule demotions, gate evaluations).
CREATE TABLE IF NOT EXISTS tb_deploy_event (
    event_id     VARCHAR(64)  PRIMARY KEY,
    deploy_id    VARCHAR(64)  NOT NULL,
    tenant_id    VARCHAR(64)  NOT NULL,
    event_type   VARCHAR(32)  NOT NULL,
    reason       VARCHAR(64),
    rule_id      VARCHAR(128),
    from_state   VARCHAR(16),
    from_percent INTEGER,
    to_state     VARCHAR(16),
    to_percent   INTEGER,
    layer        VARCHAR(16),
    operator     VARCHAR(64),
    note         TEXT,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_tb_deploy_event_deploy
    ON tb_deploy_event (deploy_id, created_at);

CREATE INDEX IF NOT EXISTS idx_tb_deploy_event_rule
    ON tb_deploy_event (tenant_id, rule_id);
