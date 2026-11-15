-- V37: add tb_audit_events.tool_name for databases created before it was
-- folded into V1. Decision-chain reads and audit ingest both select/insert
-- the column; a baseline that already has it must no-op.
-- MySQL 8 has no ADD COLUMN IF NOT EXISTS.

SET @ddl = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE tb_audit_events ADD COLUMN tool_name VARCHAR(128) NULL AFTER layer',
        'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'tb_audit_events'
      AND COLUMN_NAME = 'tool_name'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE tb_audit_events_archive ADD COLUMN tool_name VARCHAR(128) NULL AFTER layer',
        'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'tb_audit_events_archive'
      AND COLUMN_NAME = 'tool_name'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
