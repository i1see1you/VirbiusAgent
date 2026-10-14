-- ============================================================
-- V13: Agent trace result preview.
--
-- The MCP proxy has always emitted `tool_result_preview` on tool_result steps
-- (bounded, masked preview of the tool output). It was silently dropped at
-- ingest; persist it so post-hoc traceability (and Decision BOM reconstruction)
-- can show what the tool actually returned.
-- ============================================================
ALTER TABLE tb_agent_trace ADD COLUMN tool_result_preview TEXT DEFAULT NULL;
