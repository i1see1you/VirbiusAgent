-- V21: global evaluation integration settings (MySQL/MariaDB mirror of db/schema.sql).
--
-- Single-row table (id = 1). Stores the control-plane side of the optional
-- VirbiusEvaluation integration: base URL, encrypted service token, promo switch.
-- The API token is AES-256-GCM encrypted (EvaluationSettingsCipher); GET never
-- returns it. Seeded once from VIRBIUS_EVALUATION_* env on first start, then the
-- DB row is authoritative (no runtime env precedence).
CREATE TABLE IF NOT EXISTS tb_evaluation_setting (
    id            TINYINT      NOT NULL PRIMARY KEY,
    enabled       BOOLEAN      NOT NULL,
    base_url      VARCHAR(512) NOT NULL,
    api_token_ct  TEXT         NULL,
    promo_enabled BOOLEAN      NOT NULL,
    promo_url     VARCHAR(512) NOT NULL,
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by    VARCHAR(128) NOT NULL
);
