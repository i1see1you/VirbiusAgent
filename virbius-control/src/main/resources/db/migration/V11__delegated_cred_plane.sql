-- Delegated credential plane (v2.5-min, A+B1): tenant-scoped static
-- credentials injected by the MCP proxy at egress time. Single table; the
-- proxy consumes only active rows through the Redis snapshot published on
-- change (key virbius:delegated:snapshot:{tenant}). No registry / sequence
-- tables: revocation latency = snapshot poll interval (D1-3).
CREATE TABLE tb_delegated_credentials (
    tenant_id     VARCHAR(64)  NOT NULL,
    handle        VARCHAR(64)  NOT NULL,
    host          VARCHAR(255) NOT NULL,
    header_name   VARCHAR(64)  NOT NULL,
    header_prefix VARCHAR(32)  NOT NULL DEFAULT '',
    ciphertext    TEXT         NOT NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'active',
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, handle),
    UNIQUE KEY uk_delegated_host (tenant_id, host)
);
