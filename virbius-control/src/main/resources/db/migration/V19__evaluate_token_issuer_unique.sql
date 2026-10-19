-- V19: single-active evaluate-token issuer (multi-replica control)
--
-- Problem: EvaluateTokenService.init() is a check-then-insert. Two control
-- replicas cold-starting with no active issuer each generated a key and both
-- inserts succeeded (random kids), leaving two "active" rows and an undefined
-- signer per replica.
--
-- Fix, storage-enforced (no timing window):
--   1. Repair legacy data: keep the earliest-created active issuer
--      (tie: smallest kid), retire the rest.
--   2. Add a stored generated column that maps the one allowed active row to
--      a constant and every retired row to its own kid; a unique index on it
--      rejects a second active row regardless of kid. The losing replica
--      catches DuplicateKeyException and loads the winner's key.

UPDATE tb_evaluate_token_issuers
SET status = 'retired', retired_at = COALESCE(retired_at, CURRENT_TIMESTAMP)
WHERE status = 'active'
  AND kid NOT IN (
      SELECT kid FROM (
          SELECT kid FROM tb_evaluate_token_issuers
          WHERE status = 'active'
          ORDER BY created_at ASC, kid ASC
          LIMIT 1
      ) keep_earliest
  );

ALTER TABLE tb_evaluate_token_issuers
    ADD COLUMN active_guard VARCHAR(64) GENERATED ALWAYS AS (
        CASE WHEN status = 'active' THEN '__SINGLETON__' ELSE kid END
    ) STORED;

CREATE UNIQUE INDEX uk_evaluate_token_issuers_active
    ON tb_evaluate_token_issuers (active_guard);
