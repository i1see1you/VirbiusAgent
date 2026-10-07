-- P0 credential security: track the hash algorithm used for each API key.
-- legacy-sha256      pre-pepper rows (unkeyed SHA-256)
-- hmac-sha256-pepper peppered HMAC (VIRBIUS_API_KEY_PEPPER); rows are upgraded
--                    transparently on first authentication (upgrade-on-use).
ALTER TABLE tb_tenant_api_credential
    ADD COLUMN hash_algo VARCHAR(24) NOT NULL DEFAULT 'legacy-sha256';
