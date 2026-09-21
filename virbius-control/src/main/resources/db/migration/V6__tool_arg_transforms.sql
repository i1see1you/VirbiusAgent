-- Tool catalog arg transforms (restrict / redact / truncate JSON).
ALTER TABLE tb_tool_registry ADD COLUMN arg_transforms TEXT;
