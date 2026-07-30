-- ============================================================
-- V5: Challenge approval records (approved / rejected)
-- ============================================================

CREATE TABLE tb_challenge_approvals (
    id              BIGINT       AUTO_INCREMENT PRIMARY KEY,
    challenge_id    VARCHAR(64)  NOT NULL,
    tenant_id       VARCHAR(64)  NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'approved',
    tool_name       VARCHAR(256) DEFAULT NULL,
    args_hash       VARCHAR(80)  DEFAULT NULL,
    session_id      VARCHAR(64)  DEFAULT NULL,
    rule_id         VARCHAR(64)  DEFAULT NULL,
    reason_code     VARCHAR(64)  DEFAULT NULL,
    risk_score      INT          DEFAULT 0,
    approval_mode   VARCHAR(8)   DEFAULT NULL,
    created_at      BIGINT       DEFAULT NULL,
    expires_at      BIGINT       DEFAULT NULL,
    approved_by     VARCHAR(64)  DEFAULT NULL,
    approved_at     BIGINT       DEFAULT NULL,
    rejected_by     VARCHAR(64)  DEFAULT NULL,
    rejected_at     BIGINT       DEFAULT NULL,
    comment         TEXT         DEFAULT NULL,
    created_ts      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_challenge_approvals_tenant_status (tenant_id, status),
    UNIQUE KEY uk_challenge_approvals_id (challenge_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
