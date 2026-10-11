-- ============================================================
-- V12: Native-tool integration observability (P0, advisory grade).
--
-- Independent channel for LangChain native-tool observations emitted by the
-- virbius-integrate plugin. Design invariants (do not violate):
--   * NOT part of the rule rollout pipeline (B1 telemetry, like the audit allow log).
--   * NOT hash-chained; NOT fed into session risk or authoritative aggregation.
--   * Delivery is best-effort; the observer must never block the agent.
-- Timestamps are stored as RFC3339 strings (VARCHAR) to match the audit path
-- (tb_audit_events.intercepted_at) and to allow lexical range queries.
-- ============================================================

-- ============================================================
-- Native tool-call observations (two-point events: started + completed/error).
-- Visibility + audit trail only; every row is a claim-grade record produced by
-- the observed process. Retention is enforced by ObservationRetentionJob.
-- ============================================================
CREATE TABLE tb_tool_observation (
    event_id        VARCHAR(64)  NOT NULL,   -- unique id; INSERT-ignore dedup key
    call_id         VARCHAR(64)  NOT NULL,   -- links started to completed/error (LangChain run_id)
    phase           VARCHAR(16)  NOT NULL,   -- started | completed | error
    tenant_id       VARCHAR(64)  NOT NULL,
    app_id          VARCHAR(64)  NOT NULL,
    device_id       VARCHAR(128),            -- pod name; heuristic L1 correlation key
    session_id      VARCHAR(128),            -- thread_id, or <unscoped>
    trace_id        VARCHAR(64),             -- advisory key; display-only, never authoritative
    tool_name       VARCHAR(128),
    duration_ms     INTEGER,                 -- completed/error rows only
    args_repr       TEXT,                    -- bounded + masked preview (never the raw payload)
    result_repr     TEXT,                    -- bounded + masked preview
    error           VARCHAR(512),
    source          VARCHAR(32),             -- e.g. "langchain-native"
    package_version VARCHAR(32),             -- plugin version that emitted the event
    observed_at     VARCHAR(32)  NOT NULL,   -- RFC3339 UTC
    PRIMARY KEY (event_id),
    CHECK (phase IN ('started', 'completed', 'error'))
);

CREATE INDEX idx_tb_tool_observation_time
    ON tb_tool_observation (tenant_id, app_id, observed_at);

CREATE INDEX idx_tb_tool_observation_call
    ON tb_tool_observation (tenant_id, app_id, call_id);

-- ============================================================
-- Tool inventory / binding report (B1): one row per (tenant, app, tool).
-- Populated by the plugin's construction-time report; drives the coverage view.
-- risk_class/recommended_mode come from the rule classifier (advisory);
-- bound_mode is self-reported by the plugin ("A") and is a claim, not verified.
-- ============================================================
CREATE TABLE tb_tool_inventory (
    tenant_id           VARCHAR(64)  NOT NULL,
    app_id              VARCHAR(64)  NOT NULL,
    tool_name           VARCHAR(128) NOT NULL,
    mcp_name            VARCHAR(128),            -- agent tool name -> MCP prefixed name
    risk_class          VARCHAR(16)  NOT NULL DEFAULT 'low',  -- low | medium | high (rule classifier)
    matched_rules       VARCHAR(512),            -- comma-separated rule hits (explainability)
    confidence          VARCHAR(16),             -- classifier confidence
    recommended_mode    VARCHAR(8),              -- A | B (from risk_class)
    rule_table_version  VARCHAR(32),             -- classifier rule table version
    package_version     VARCHAR(32),
    bound_mode          VARCHAR(8),              -- self-reported binding: A (Mode A instrumented)
    first_seen          VARCHAR(32)  NOT NULL,
    last_seen           VARCHAR(32)  NOT NULL,
    PRIMARY KEY (tenant_id, app_id, tool_name),
    CHECK (risk_class IN ('low', 'medium', 'high'))
);

CREATE INDEX idx_tb_tool_inventory_mode
    ON tb_tool_inventory (tenant_id, app_id, recommended_mode);

-- ============================================================
-- Integration liveness per (tenant, app, device). Two-source health so idle
-- agents are not mislabelled: live = fresh heartbeat OR recent event;
-- stale = had a signal but aged out; never-seen = no row.
-- dropped_total is client-claimed (advisory), surfaced for visibility only.
-- ============================================================
CREATE TABLE tb_obs_app_liveness (
    tenant_id       VARCHAR(64)  NOT NULL,
    app_id          VARCHAR(64)  NOT NULL,
    device_id       VARCHAR(128) NOT NULL,
    last_seen       VARCHAR(32),             -- last heartbeat ts (RFC3339 UTC)
    last_event_ts   VARCHAR(32),             -- last observation event ts
    dropped_total   INTEGER      NOT NULL DEFAULT 0,  -- client-reported dropped events
    package_version VARCHAR(32),
    mode            VARCHAR(16),             -- observe (P0 ships observe only)
    updated_at      VARCHAR(32),
    PRIMARY KEY (tenant_id, app_id, device_id)
);
