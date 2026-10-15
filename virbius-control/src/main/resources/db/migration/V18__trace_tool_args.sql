-- V18: persist tool_args on trace rows (MySQL/MariaDB mirror of db/schema.sql).
--
-- TraceEventIngestor writes the full tool arguments (tool_args) into
-- tb_agent_trace and DecisionBomService reads them back, but the column existed
-- only in the SQLite dev schema. On MySQL/MariaDB the trace INSERT referenced a
-- missing column and failed. The archive mirror (created via
-- CREATE TABLE ... LIKE at V14, before this column) needs the same column. Kept
-- in sync with db/schema.sql by SchemaDialectParityTest.
ALTER TABLE tb_agent_trace ADD COLUMN tool_args TEXT;

ALTER TABLE tb_agent_trace_archive ADD COLUMN tool_args TEXT;
