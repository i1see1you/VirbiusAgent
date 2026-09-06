CREATE TABLE IF NOT EXISTS tb_operator_user (
    user_id         VARCHAR(36)  NOT NULL,
    username        VARCHAR(128) NOT NULL,
    password_hash   VARCHAR(255) NOT NULL,
    role            VARCHAR(32)  NOT NULL,
    tenant_id       VARCHAR(64)  NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'active',
    created_at      TIMESTAMP    NOT NULL,
    PRIMARY KEY (user_id),
    UNIQUE (username),
    CHECK (role IN ('tenant_viewer', 'tenant_admin', 'platform_admin')),
    CHECK (status IN ('active', 'disabled'))
);

CREATE TABLE IF NOT EXISTS tb_auth_code (
    code_hash    VARCHAR(64)  NOT NULL,
    user_id      VARCHAR(36)  NOT NULL,
    return_uri   VARCHAR(512) NOT NULL,
    expires_at   TIMESTAMP    NOT NULL,
    consumed_at  TIMESTAMP,
    PRIMARY KEY (code_hash)
);
