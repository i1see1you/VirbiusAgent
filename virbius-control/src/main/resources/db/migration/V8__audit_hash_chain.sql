-- Audit hash chain integrity: per-tenant tamper-evident chain.

ALTER TABLE tb_audit_events
    ADD COLUMN audit_seq   BIGINT       NOT NULL DEFAULT 0,
    ADD COLUMN prev_hash   VARCHAR(128) NOT NULL DEFAULT '',
    ADD COLUMN curr_hash   VARCHAR(128) NOT NULL DEFAULT '';

CREATE INDEX idx_audit_events_tenant_seq ON tb_audit_events (tenant_id, audit_seq);

CREATE TABLE tb_audit_chain_state (
    tenant_id   VARCHAR(64)  PRIMARY KEY,
    seq         BIGINT       NOT NULL DEFAULT 0,
    last_hash   VARCHAR(128) NOT NULL DEFAULT '',
    version     INT          NOT NULL DEFAULT 0,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
