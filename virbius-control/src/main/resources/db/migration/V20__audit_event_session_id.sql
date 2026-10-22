-- V20: persist session_id on audit events.
--
-- The session id has always ridden the Kafka payload (AuditWriter), but only
-- trace_id was persisted. Persisting it lets the console's decision-chain
-- timeline, alerts and decision BOM join audit events at session scope
-- without re-introducing an engine-side trace double-write.
-- The column is NOT part of the audit hash-chain input (HashChainOrchestrator
-- .computeHash is unchanged), so existing chains stay valid. Historical rows
-- keep NULL session_id (the payload cannot be replayed retroactively).

ALTER TABLE tb_audit_events ADD COLUMN session_id VARCHAR(128);
ALTER TABLE tb_audit_events_archive ADD COLUMN session_id VARCHAR(128);

CREATE INDEX idx_audit_events_tenant_session
    ON tb_audit_events (tenant_id, session_id, intercepted_at);
