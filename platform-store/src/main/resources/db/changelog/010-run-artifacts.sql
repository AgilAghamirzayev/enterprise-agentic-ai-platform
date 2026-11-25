--liquibase formatted sql
--changeset agentplatform:run-artifacts
CREATE TABLE run_artifacts (
 tenant text NOT NULL, run_id uuid NOT NULL, name text NOT NULL, media_type text NOT NULL,
 digest text NOT NULL, content bytea NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant,run_id,name), FOREIGN KEY(tenant,run_id) REFERENCES runs,
 CHECK (octet_length(content) BETWEEN 1 AND 524288)
);
ALTER TABLE run_artifacts ENABLE ROW LEVEL SECURITY;
ALTER TABLE run_artifacts FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_boundary ON run_artifacts USING (tenant=nullif(current_setting('agent.tenant',true),''));
GRANT SELECT,INSERT ON run_artifacts TO agent_runtime;
