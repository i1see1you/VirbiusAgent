-- Runtime License table for Agent identity management.
-- EdDSA (Ed25519) signed JWT tokens with revocation support.

CREATE TABLE tb_agent_licenses (
    license_id      VARCHAR(64)  NOT NULL,
    tenant_id       VARCHAR(64)  NOT NULL,
    app_id          VARCHAR(128) NOT NULL,
    allowed_tools   TEXT         NOT NULL DEFAULT '[]',
    allowed_scenes  TEXT         NOT NULL DEFAULT '[]',
    risk_quota      INTEGER      NOT NULL DEFAULT 60,
    tool_rate_limit INTEGER      NOT NULL DEFAULT 50,
    expiry          TIMESTAMP    NOT NULL,
    issued_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    status          VARCHAR(16)  NOT NULL DEFAULT 'active',
    signature       TEXT         NOT NULL,
    created_by      VARCHAR(64),
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at      TIMESTAMP,
    revoke_reason   VARCHAR(255),
    PRIMARY KEY (license_id),
    CHECK (status IN ('active', 'revoked', 'expired')),
    CHECK (risk_quota >= 0 AND risk_quota <= 100)
);

CREATE INDEX idx_tb_agent_licenses_tenant_app
    ON tb_agent_licenses (tenant_id, app_id, status);

CREATE INDEX idx_tb_agent_licenses_app
    ON tb_agent_licenses (app_id, status);

-- License signing key registry (Ed25519 key pairs).
-- Each tenant has its own signing key pair for License JWTs.
CREATE TABLE tb_license_keys (
    key_id          VARCHAR(64)  PRIMARY KEY,
    tenant_id       VARCHAR(64)  NOT NULL,
    public_key_pem  TEXT         NOT NULL,
    private_key_enc TEXT         NOT NULL,  -- encrypted with master key
    algorithm       VARCHAR(16)  NOT NULL DEFAULT 'EdDSA',
    status          VARCHAR(16)  NOT NULL DEFAULT 'active',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    rotated_at      TIMESTAMP,
    CHECK (status IN ('active', 'rotated', 'revoked'))
);

CREATE INDEX idx_tb_license_keys_tenant
    ON tb_license_keys (tenant_id, status);

-- License revocation log (for audit trail).
CREATE TABLE tb_license_revocations (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    license_id      VARCHAR(64)  NOT NULL,
    tenant_id       VARCHAR(64)  NOT NULL,
    app_id          VARCHAR(128) NOT NULL,
    revoked_by      VARCHAR(64),
    revoke_reason   VARCHAR(255),
    revoked_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);

CREATE INDEX idx_tb_license_revocations_license
    ON tb_license_revocations (license_id);
