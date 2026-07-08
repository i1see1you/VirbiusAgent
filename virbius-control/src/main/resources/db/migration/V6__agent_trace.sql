-- Agent decision chain trace (input → reasoning → tool_call → tool_result → output)
-- Tracks the full decision chain per session, linked by trace_id + step_id.

CREATE TABLE tb_agent_trace (
    id               BIGINT       PRIMARY KEY AUTO_INCREMENT,
    trace_id         VARCHAR(128) NOT NULL,
    session_id       VARCHAR(128) NOT NULL,
    tenant_id        VARCHAR(64)  NOT NULL,
    step_id          VARCHAR(64)  NOT NULL,
    parent_step_id   VARCHAR(64),
    step_seq         INTEGER      NOT NULL DEFAULT 0,
    step_type        VARCHAR(32)  NOT NULL,
    layer            VARCHAR(16)  NOT NULL DEFAULT 'edge',
    scene            VARCHAR(64),
    user_id          VARCHAR(256),
    device_id        VARCHAR(256),
    input_role       VARCHAR(16),
    input_content_hash VARCHAR(64),
    tool_name        VARCHAR(128),
    tool_args_hash   VARCHAR(64),
    tool_decision    VARCHAR(16),
    rule_id          VARCHAR(128),
    reason_code      VARCHAR(64),
    risk_score       INTEGER      DEFAULT 0,
    tool_status      VARCHAR(16),
    tool_duration_ms INTEGER,
    content_ref      VARCHAR(256),
    content_size     INTEGER,
    content_sampled  INTEGER      DEFAULT 0,
    dlp_masked       INTEGER      DEFAULT 0,
    occurred_at      TIMESTAMP    NOT NULL,
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (trace_id, step_id)
);

CREATE INDEX idx_agent_trace_session ON tb_agent_trace (tenant_id, session_id, step_seq);
CREATE INDEX idx_agent_trace_trace   ON tb_agent_trace (tenant_id, trace_id, occurred_at);
CREATE INDEX idx_agent_trace_tool    ON tb_agent_trace (tenant_id, tool_name, occurred_at);
CREATE INDEX idx_agent_trace_type    ON tb_agent_trace (tenant_id, step_type, occurred_at);

-- Checkpoint table for trace stream ingestion (mirrors tb_audit_ingest_checkpoint)
CREATE TABLE IF NOT EXISTS tb_trace_ingest_checkpoint (
    stream_key      VARCHAR(128) PRIMARY KEY,
    last_entry_id   VARCHAR(64)  NOT NULL,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
