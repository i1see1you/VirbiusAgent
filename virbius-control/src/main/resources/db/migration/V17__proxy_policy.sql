-- V17: per-tenant MCP proxy policy (MySQL/MariaDB mirror of db/schema.sql).
--
-- ProxyPolicyService reads/writes tb_proxy_policy (GET body / UPDATE / INSERT).
-- The table existed only in the SQLite dev schema (db/schema.sql), so on
-- MySQL/MariaDB prod the proxy-policy admin API failed with "table doesn't
-- exist". Kept in sync with db/schema.sql by SchemaDialectParityTest.
CREATE TABLE IF NOT EXISTS tb_proxy_policy (
    tenant_id   VARCHAR(64) PRIMARY KEY,
    body        TEXT        NOT NULL,
    updated_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
