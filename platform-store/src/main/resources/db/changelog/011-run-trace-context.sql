--liquibase formatted sql
--changeset agentplatform:run-trace-context
ALTER TABLE runs ADD COLUMN traceparent varchar(55) CHECK (traceparent ~ '^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$');
CREATE OR REPLACE VIEW run_payloads WITH (security_invoker=true) AS
 SELECT r.tenant,r.id,r.actor,r.roles,r.session_id,r.request_key,r.kind,r.name,r.version,
 CASE WHEN r.archived_at IS NULL THEN r.input ELSE a.input END input,
 CASE WHEN r.archived_at IS NULL THEN r.result ELSE a.result END result,
 r.status,r.cancel_requested,r.reserved_tokens,r.model_calls,r.created_at,r.updated_at,r.archived_at,r.traceparent
 FROM runs r LEFT JOIN archived_runs a USING(tenant,id);
