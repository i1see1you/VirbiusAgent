-- Migrate tb_cumulative: move threshold/reason to cumulative rules (body.condition).
-- PoC: rebuild table (SQLite). Re-create cumulative rules with condition if needed.

CREATE TABLE IF NOT EXISTS tb_cumulative_new (
    tenant_id              VARCHAR(64) NOT NULL,
    cumulative_name        VARCHAR(128) NOT NULL,
    description            VARCHAR(512),
    dimension              VARCHAR(32) NOT NULL,
    window_kind            VARCHAR(32) NOT NULL,
    window_minutes         INTEGER,
    window_hours           INTEGER,
    timezone               VARCHAR(64),
    priority               INTEGER NOT NULL DEFAULT 0,
    status                 VARCHAR(16) NOT NULL DEFAULT 'active',
    created_at             TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, cumulative_name)
);

INSERT INTO tb_cumulative_new (
    tenant_id, cumulative_name, description, dimension, window_kind,
    window_minutes, window_hours, timezone, priority, status, created_at, updated_at)
SELECT tenant_id, cumulative_name, description, dimension, window_kind,
       window_minutes, window_hours, timezone, priority, status, created_at, updated_at
FROM tb_cumulative;

DROP TABLE tb_cumulative;
ALTER TABLE tb_cumulative_new RENAME TO tb_cumulative;

-- Example rule body after migration:
-- {"cumulative_name":"user_req_1h","condition":{"compare_op":"gte","threshold":120}}
