-- ============================================================
-- Audit insert-only hardening (A0, tamper *prevention*).
--
-- The hash chain provides tamper *detection*; these grants provide tamper
-- *prevention* for the application DB account: audit/trace/challenge tables
-- become append-only at the privilege layer, so neither a compromised agent
-- nor a normal operator account can UPDATE/DELETE evidence.
--
-- Apply MANUALLY in production after provisioning (not part of Flyway, since
-- the application account name is deployment-specific). Adjust the account
-- name before running. A separate admin/ops account retains full privileges
-- for retention/archival jobs (which must DELETE only rows outside the
-- retention window and only after the daily Merkle anchor is sealed).
--
-- MySQL / MariaDB:
--   mysql -u root -p < deploy/scripts/audit-insert-only-grants.sql
-- ============================================================

-- Replace 'virbius_app'@'%' with the actual application account
-- (application-prod.yml → VIRBIUS_JDBC_USER).
SET @app_user = 'virbius_app';
SET @app_host = '%';

-- Append-only evidence tables.
REVOKE UPDATE, DELETE ON virbius.tb_audit_events        FROM 'virbius_app'@'%';
REVOKE UPDATE, DELETE ON virbius.tb_agent_trace         FROM 'virbius_app'@'%';
REVOKE UPDATE, DELETE ON virbius.tb_challenge_approvals FROM 'virbius_app'@'%';
REVOKE UPDATE, DELETE ON virbius.tb_audit_chain_state   FROM 'virbius_app'@'%';

-- Note: tb_audit_chain_state is written by the MySQL optimistic-lock fallback
-- path of HashChainOrchestrator. If your deployment relies on that fallback
-- (Redis unavailable), keep INSERT+UPDATE on tb_audit_chain_state for the
-- application account and revoke only DELETE:
--   GRANT UPDATE ON virbius.tb_audit_chain_state TO 'virbius_app'@'%';

FLUSH PRIVILEGES;
