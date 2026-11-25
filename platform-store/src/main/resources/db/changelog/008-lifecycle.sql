--liquibase formatted sql
--changeset agentplatform:023
ALTER TABLE document_heads ADD COLUMN retired_revision integer NOT NULL DEFAULT 0;
CREATE TABLE document_labels (
 tenant text NOT NULL,id uuid NOT NULL,source_uri text,
 classification text NOT NULL CHECK(classification IN ('PUBLIC','INTERNAL','CONFIDENTIAL','RESTRICTED')),
 PRIMARY KEY(tenant,id)
);
ALTER TABLE document_labels ENABLE ROW LEVEL SECURITY;
ALTER TABLE document_labels FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_boundary ON document_labels USING (tenant=nullif(current_setting('agent.tenant',true),''));
GRANT SELECT,INSERT ON document_labels TO agent_runtime;

--changeset agentplatform:024
CREATE TABLE retention_settings (
 tenant text PRIMARY KEY, run_days integer CHECK(run_days BETWEEN 1 AND 36500),
 audit_days integer CHECK(audit_days BETWEEN 1 AND 36500), updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
ALTER TABLE runs ADD COLUMN archived_at timestamptz;
CREATE INDEX retention_due ON runs(tenant,updated_at,id)
 WHERE archived_at IS NULL AND status IN ('SUCCEEDED','COMPLETED','FAILED','DENIED','CANCELLED','EXPIRED','TIMED_OUT','BUDGET_EXCEEDED');
CREATE TABLE archived_runs (
 tenant text NOT NULL,id uuid NOT NULL,input jsonb NOT NULL,result jsonb,
 PRIMARY KEY(tenant,id), FOREIGN KEY(tenant,id) REFERENCES runs
);
CREATE TABLE archived_steps (
 tenant text NOT NULL,run_id uuid NOT NULL,step_key text NOT NULL,output jsonb NOT NULL,
 PRIMARY KEY(tenant,run_id,step_key), FOREIGN KEY(tenant,run_id,step_key) REFERENCES run_steps
);
ALTER TABLE retention_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE retention_settings FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_boundary ON retention_settings USING(tenant=nullif(current_setting('agent.tenant',true),''));
ALTER TABLE archived_runs ENABLE ROW LEVEL SECURITY;
ALTER TABLE archived_runs FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_boundary ON archived_runs USING(tenant=nullif(current_setting('agent.tenant',true),''));
ALTER TABLE archived_steps ENABLE ROW LEVEL SECURITY;
ALTER TABLE archived_steps FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_boundary ON archived_steps USING(tenant=nullif(current_setting('agent.tenant',true),''));
CREATE VIEW run_payloads WITH (security_invoker=true) AS
 SELECT r.tenant,r.id,r.actor,r.roles,r.session_id,r.request_key,r.kind,r.name,r.version,
 CASE WHEN r.archived_at IS NULL THEN r.input ELSE a.input END input,
 CASE WHEN r.archived_at IS NULL THEN r.result ELSE a.result END result,
 r.status,r.cancel_requested,r.reserved_tokens,r.model_calls,r.created_at,r.updated_at,r.archived_at
 FROM runs r LEFT JOIN archived_runs a USING(tenant,id);
CREATE VIEW step_payloads WITH (security_invoker=true) AS
 SELECT s.tenant,s.run_id,s.step_key,coalesce(a.output,s.output) output,s.input_tokens,s.output_tokens,s.created_at
 FROM run_steps s LEFT JOIN archived_steps a USING(tenant,run_id,step_key);
GRANT SELECT,INSERT,UPDATE ON retention_settings TO agent_runtime;
GRANT SELECT ON archived_runs,archived_steps,run_payloads,step_payloads TO agent_runtime;

--changeset agentplatform:025 splitStatements:false
CREATE FUNCTION archive_due_runs(batch_size integer) RETURNS integer LANGUAGE plpgsql SECURITY DEFINER
SET search_path=pg_catalog,public AS $archive$
DECLARE scope text:=nullif(current_setting('agent.tenant',true),''); days integer; candidate uuid; total integer:=0;
BEGIN
 IF scope IS NULL OR batch_size IS NULL OR batch_size NOT BETWEEN 1 AND 100 THEN RAISE EXCEPTION 'invalid archive scope or batch'; END IF;
 SELECT run_days INTO days FROM retention_settings WHERE tenant=scope FOR SHARE;
 IF days IS NULL THEN RETURN 0; END IF;
 FOR candidate IN SELECT r.id FROM runs r
   WHERE r.tenant=scope AND r.archived_at IS NULL AND r.updated_at<now()-make_interval(days=>days)
    AND r.status IN ('SUCCEEDED','COMPLETED','FAILED','DENIED','CANCELLED','EXPIRED','TIMED_OUT','BUDGET_EXCEEDED')
    AND NOT EXISTS(SELECT FROM external_effects e WHERE e.tenant=r.tenant AND e.run_id=r.id AND e.outcome='DISPATCHED')
   ORDER BY r.updated_at,r.id LIMIT batch_size FOR UPDATE OF r SKIP LOCKED
 LOOP
   INSERT INTO archived_runs SELECT tenant,id,input,result FROM runs WHERE tenant=scope AND id=candidate;
   INSERT INTO archived_steps SELECT tenant,run_id,step_key,output FROM run_steps WHERE tenant=scope AND run_id=candidate;
   UPDATE runs SET input='null'::jsonb,result=NULL,archived_at=clock_timestamp() WHERE tenant=scope AND id=candidate;
   UPDATE run_steps SET output='null'::jsonb WHERE tenant=scope AND run_id=candidate;
   total:=total+1;
 END LOOP;
 RETURN total;
END $archive$;
--changeset agentplatform:026
REVOKE ALL ON FUNCTION archive_due_runs(integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION archive_due_runs(integer) TO agent_runtime;

--changeset agentplatform:027 splitStatements:false
CREATE FUNCTION audit_material(e audit_events) RETURNS text LANGUAGE sql STABLE SET search_path=pg_catalog,public AS $canonical$
 SELECT jsonb_build_array(1,e.tenant,e.chain_position,e.previous_hash,e.id,e.event_uuid,e.actor,
    e.run_id,e.event_type,e.resource_type,e.resource,e.trace_id,e.policy_version,e.payload_hash,extract(epoch FROM e.occurred_at))::text
$canonical$;
--changeset agentplatform:028 splitStatements:false
CREATE OR REPLACE FUNCTION audit_digest(e audit_events) RETURNS text LANGUAGE sql STABLE SET search_path=pg_catalog,public AS $digest$
 SELECT encode(sha256(convert_to(audit_material(e),'UTF8')),'hex')
$digest$;

--changeset agentplatform:029
-- Only the workspace routing identifier and the next poll time are visible to maintenance.
CREATE TABLE retention_routes (tenant text PRIMARY KEY, enabled boolean NOT NULL,next_check timestamptz NOT NULL);
CREATE INDEX retention_routes_due ON retention_routes(next_check,tenant) WHERE enabled;
GRANT SELECT,INSERT,UPDATE ON retention_routes TO agent_runtime;

--changeset agentplatform:030
-- External indexes own their vectors. PostgreSQL keeps canonical chunk text and ACLs for every provider.
ALTER TABLE chunks ALTER COLUMN embedding DROP NOT NULL;
