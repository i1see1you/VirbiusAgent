-- V36: tb_agent_trace.tool_args_hash holds the full upstream tool_args digest
-- ("sha256:" + 64 hex = 71 chars) but the column was VARCHAR(64). Combined
-- with INSERT IGNORE (at-least-once ingest, duplicate-tolerance) MySQL
-- downgraded the strict truncation error to a warning, silently clipping the
-- value — the stored trace row then failed TraceChainVerifier recomputation
-- (curr_hash mismatch) because the hash was computed over the untruncated
-- digest. Widen to 128 to match the other hash columns (prev_hash / curr_hash).
ALTER TABLE tb_agent_trace MODIFY tool_args_hash VARCHAR(128);
