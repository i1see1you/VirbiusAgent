-- ============================================================
-- V14: Audit & trace integrity, retention, export (A3/A4/A5/A7).
--
-- A3: tb_agent_trace gains per-session hash chaining (chain_seq/prev_hash/
--     curr_hash) with per-session chain state; a daily anchor seals each day's
--     session chain heads under a Merkle root so retention can archive without
--     losing the ability to prove integrity of the retained period.
-- A4: Merkle inclusion proofs are computed over tb_audit_events (no schema
--     change needed; leaves = curr_hash ordered by audit_seq).
-- A5: tenant retention policy + archive mirrors (archive mode copies without
--     deleting; deletion stays a manual ops action after anchors are sealed).
-- A7: export job ledger (the export action's own audit trail).
-- ============================================================

-- A3: per-session chain fields on trace rows.
ALTER TABLE tb_agent_trace ADD COLUMN chain_seq INTEGER NOT NULL DEFAULT 0;
ALTER TABLE tb_agent_trace ADD COLUMN prev_hash VARCHAR(128) NOT NULL DEFAULT '';
ALTER TABLE tb_agent_trace ADD COLUMN curr_hash VARCHAR(128) NOT NULL DEFAULT '';

-- A3: per-session chain state (optimistic-lock CAS, mirrors tb_audit_chain_state).
CREATE TABLE tb_trace_chain_state (
    tenant_id  VARCHAR(64)  NOT NULL,
    session_id VARCHAR(128) NOT NULL,
    seq        BIGINT       NOT NULL DEFAULT 0,
    last_hash  VARCHAR(128) NOT NULL DEFAULT '',
    version    INTEGER      NOT NULL DEFAULT 0,
    updated_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, session_id)
);

-- A3: daily Merkle anchor over the day's session chain heads (per tenant).
-- Sealed once per day by DailyAnchorJob; retention archive relies on it.
CREATE TABLE tb_audit_daily_anchor (
    tenant_id      VARCHAR(64) NOT NULL,
    anchor_date    DATE        NOT NULL,
    root_hash      VARCHAR(128) NOT NULL,
    sessions       INTEGER     NOT NULL DEFAULT 0,
    events         INTEGER     NOT NULL DEFAULT 0,
    created_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, anchor_date)
);

-- A5: per-tenant retention policy. mode: 'archive' (copy to *_archive, keep
-- originals — default, safe for evaluation) | 'off'.
CREATE TABLE tb_tenant_retention_policy (
    tenant_id     VARCHAR(64) NOT NULL,
    table_name    VARCHAR(64) NOT NULL,
    retention_days INTEGER    NOT NULL DEFAULT 180,
    mode          VARCHAR(8)  NOT NULL DEFAULT 'archive',
    updated_at    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, table_name)
);

-- A5: archive mirrors (same shape as the source tables + archived_at).
CREATE TABLE tb_audit_events_archive LIKE tb_audit_events;
ALTER TABLE tb_audit_events_archive ADD COLUMN archived_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;

CREATE TABLE tb_agent_trace_archive LIKE tb_agent_trace;
ALTER TABLE tb_agent_trace_archive ADD COLUMN archived_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;

-- A7: export job ledger — every export request leaves a row here (requester,
-- filters, result hash). The ledger itself is the export action's audit trail.
CREATE TABLE tb_audit_export_job (
    job_id       VARCHAR(64)  PRIMARY KEY,
    tenant_id    VARCHAR(64)  NOT NULL,
    requester    VARCHAR(128) NOT NULL,
    filters_json TEXT         NOT NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'queued',   -- queued|running|done|failed
    file_path    VARCHAR(512),
    file_sha256  VARCHAR(128),
    file_bytes   BIGINT,
    error        VARCHAR(512),
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP    NULL
);
