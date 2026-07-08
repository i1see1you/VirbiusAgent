-- Challenge approval queue: tracks high-risk tool call challenges requiring human approval.
-- Challenge records are primarily stored in Redis (for low-latency verify/poll),
-- this table provides persistent audit trail and dashboard query support.
CREATE TABLE IF NOT EXISTS tb_challenge_audit (
    id              BIGINT       PRIMARY KEY AUTO_INCREMENT,
    challenge_id    VARCHAR(32)  NOT NULL UNIQUE,
    tenant_id       VARCHAR(64)  NOT NULL DEFAULT 'default',
    session_id      VARCHAR(128) NOT NULL,
    tool_name       VARCHAR(128) NOT NULL,
    args_hash       VARCHAR(80)  NOT NULL,
    rule_id         VARCHAR(128),
    reason_code     VARCHAR(128),
    risk_score      INTEGER      NOT NULL DEFAULT 0,
    status          VARCHAR(16)  NOT NULL DEFAULT 'pending',  -- pending | approved | rejected | expired
    approved_by     VARCHAR(64),
    approved_at     TIMESTAMP    NULL,
    rejected_by     VARCHAR(64),
    rejected_at     TIMESTAMP    NULL,
    reject_reason   TEXT,
    token           VARCHAR(64),  -- one-time-use token (masked, for audit trail)
    token_expires_at TIMESTAMP   NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at      TIMESTAMP    NOT NULL,
    INDEX idx_challenge_tenant_status (tenant_id, status),
    INDEX idx_challenge_created (created_at),
    INDEX idx_challenge_tool (tool_name)
);

-- Rename cnt_captcha to cnt_challenge in tb_rule_metrics_1m (if not already renamed).
-- This migration is idempotent: checks if the old column exists before renaming.
-- Note: For fresh installs using V1__init_mysql.sql, the column is already named cnt_challenge.
-- For upgrades from V1 with cnt_captcha, this ALTER handles the rename.
-- The IF EXISTS check is done via a stored procedure to be MySQL-compatible.
DELIMITER //
DROP PROCEDURE IF EXISTS migrate_captcha_to_challenge//
CREATE PROCEDURE migrate_captcha_to_challenge()
BEGIN
    -- Check if cnt_captcha column exists in tb_rule_metrics_1m
    IF EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'tb_rule_metrics_1m'
        AND COLUMN_NAME = 'cnt_captcha'
    ) THEN
        ALTER TABLE tb_rule_metrics_1m
        CHANGE COLUMN cnt_captcha cnt_challenge INTEGER NOT NULL DEFAULT 0;
    END IF;
END//
DELIMITER ;
CALL migrate_captcha_to_challenge();
DROP PROCEDURE IF EXISTS migrate_captcha_to_challenge;
