-- ============================================================
-- V3: Add approval_mode to tb_tool_registry.
--
-- Per-tool challenge approval binding:
--   'strict' (default) — session exemption requires the exact same
--                        tool args (session + tool + args_hash).
--   'lax'              — session exemption covers any args of the
--                        same tool (session + tool), so LLM-generated
--                        args jitter under the same prompt does not
--                        re-trigger a challenge within the TTL window.
-- ============================================================

ALTER TABLE tb_tool_registry
    ADD COLUMN approval_mode VARCHAR(8) NOT NULL DEFAULT 'strict';

ALTER TABLE tb_tool_registry
    ADD CONSTRAINT chk_tb_tool_registry_approval_mode
    CHECK (approval_mode IN ('strict', 'lax'));
