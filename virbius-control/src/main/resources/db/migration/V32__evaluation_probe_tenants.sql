-- V32: evaluation probe tenant allowlist (control-plane gate for REST probes).
--
-- Empty = only PLATFORM_ADMIN may submit probes; "*" = all tenants;
-- otherwise a comma-separated list of tenant ids (lowercase).
ALTER TABLE tb_evaluation_setting
    ADD COLUMN probe_tenants VARCHAR(1024) NOT NULL DEFAULT '';
