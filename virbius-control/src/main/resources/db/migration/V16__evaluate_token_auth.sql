-- V16: evaluate-token authentication (P0-min, control single-path issuance).
--
-- New tables:
--   tb_evaluate_token_issuers  trust anchor: signing keys allowed to mint evaluate tokens
--   tb_evaluate_tokens         issuance ledger: every minted short token (jti-level revocation)
-- Altered:
--   tb_license_revocations     + agent_aid (denormalized for direct publish), + expires_at (temporary revocation)
--   tb_audit_events / _archive + agent_aid, app_id, subject_source, token_iss (verified subject attribution)

CREATE TABLE IF NOT EXISTS tb_evaluate_token_issuers (
    kid                   VARCHAR(64)  PRIMARY KEY,
    key_type              VARCHAR(16)  NOT NULL DEFAULT 'control',
    public_key_pem        TEXT         NOT NULL,
    encrypted_private_key TEXT         NOT NULL,
    status                VARCHAR(16)  NOT NULL DEFAULT 'active',
    description           VARCHAR(255) NOT NULL DEFAULT '',
    created_at            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    retired_at            TIMESTAMP,
    CHECK (status IN ('active', 'retired'))
);

CREATE TABLE IF NOT EXISTS tb_evaluate_tokens (
    jti           VARCHAR(64)  PRIMARY KEY,
    subject       VARCHAR(256) NOT NULL,
    tenant_id     VARCHAR(64),
    app_id        VARCHAR(128),
    license_id    VARCHAR(64),
    sid           VARCHAR(128),
    issued_at     TIMESTAMP    NOT NULL,
    expires_at    TIMESTAMP    NOT NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'active',
    revoked_at    TIMESTAMP,
    revoke_reason VARCHAR(255),
    CHECK (status IN ('active', 'revoked'))
);

-- Plain CREATE INDEX: MySQL 8 rejects `CREATE INDEX IF NOT EXISTS` (MariaDB-only).
-- Flyway applies this migration exactly once, so the guard is unnecessary.
CREATE INDEX idx_tb_evaluate_tokens_subject
    ON tb_evaluate_tokens (subject, status);

CREATE INDEX idx_tb_evaluate_tokens_expiry
    ON tb_evaluate_tokens (expires_at);

ALTER TABLE tb_license_revocations ADD COLUMN agent_aid VARCHAR(256);
ALTER TABLE tb_license_revocations ADD COLUMN expires_at TIMESTAMP;

ALTER TABLE tb_audit_events ADD COLUMN agent_aid VARCHAR(256);
ALTER TABLE tb_audit_events ADD COLUMN app_id VARCHAR(128);
ALTER TABLE tb_audit_events ADD COLUMN subject_source VARCHAR(16);
ALTER TABLE tb_audit_events ADD COLUMN token_iss VARCHAR(64);

ALTER TABLE tb_audit_events_archive ADD COLUMN agent_aid VARCHAR(256);
ALTER TABLE tb_audit_events_archive ADD COLUMN app_id VARCHAR(128);
ALTER TABLE tb_audit_events_archive ADD COLUMN subject_source VARCHAR(16);
ALTER TABLE tb_audit_events_archive ADD COLUMN token_iss VARCHAR(64);
