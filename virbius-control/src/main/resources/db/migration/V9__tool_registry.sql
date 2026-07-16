-- Tool Registry: independent tool metadata management.
-- Replaces ad-hoc tool config extraction from bind_scope=tool rules.
-- Each tool has a single canonical definition (risk_class, sandbox_type, etc.)

CREATE TABLE tb_tool_registry (
    tenant_id            VARCHAR(64)  NOT NULL,
    tool_name            VARCHAR(128) NOT NULL,
    risk_class           VARCHAR(16)  NOT NULL DEFAULT 'low',
    sandbox_type         VARCHAR(16)  NOT NULL DEFAULT 'none',
    timeout_ms           INTEGER      NOT NULL DEFAULT 30000,
    fast_path            BOOLEAN      NOT NULL DEFAULT FALSE,
    allowed_args_schema  TEXT,
    description          VARCHAR(255),
    created_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, tool_name),
    CHECK (risk_class IN ('low', 'medium', 'high', 'network')),
    CHECK (sandbox_type IN ('none', 'landlock', 'gvisor')),
    CHECK (timeout_ms >= 1000 AND timeout_ms <= 300000)
);

CREATE INDEX idx_tb_tool_registry_tenant
    ON tb_tool_registry (tenant_id);
