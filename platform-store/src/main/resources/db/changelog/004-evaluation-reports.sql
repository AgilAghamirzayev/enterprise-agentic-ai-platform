--liquibase formatted sql
--changeset agentplatform:007
CREATE TABLE tool_evidence (
  tenant text NOT NULL, run_id uuid NOT NULL, step_key text NOT NULL, tool text NOT NULL, argument_hash text NOT NULL,
  PRIMARY KEY (tenant,run_id,step_key), FOREIGN KEY (tenant,run_id,step_key) REFERENCES run_steps
);
CREATE INDEX tool_evidence_lookup ON tool_evidence(tenant,run_id,tool,argument_hash);
CREATE TABLE evaluation_reports (
  tenant text NOT NULL, id uuid NOT NULL, actor text NOT NULL, suite_kind text NOT NULL DEFAULT 'evaluation-suite'
    CHECK (suite_kind='evaluation-suite'), suite text NOT NULL, version text NOT NULL,
  baseline_id uuid, report jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(tenant,id), FOREIGN KEY(tenant,suite_kind,suite,version) REFERENCES definitions,
  FOREIGN KEY(tenant,baseline_id) REFERENCES evaluation_reports
);
CREATE INDEX evaluation_reports_recent ON evaluation_reports(tenant,created_at DESC);
ALTER TABLE tool_evidence ENABLE ROW LEVEL SECURITY;
ALTER TABLE tool_evidence FORCE ROW LEVEL SECURITY;
ALTER TABLE evaluation_reports ENABLE ROW LEVEL SECURITY;
ALTER TABLE evaluation_reports FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_boundary ON tool_evidence USING (tenant=nullif(current_setting('agent.tenant',true),''));
CREATE POLICY tenant_boundary ON evaluation_reports USING (tenant=nullif(current_setting('agent.tenant',true),''));
GRANT SELECT,INSERT ON tool_evidence,evaluation_reports TO agent_runtime;

--changeset agentplatform:008
CREATE TABLE retrieval_evidence (
  tenant text NOT NULL, run_id uuid NOT NULL, step_key text NOT NULL, document_id uuid NOT NULL, chunk_id integer NOT NULL,
  digest text NOT NULL, PRIMARY KEY (tenant,run_id,step_key,document_id,chunk_id),
  FOREIGN KEY (tenant,run_id) REFERENCES runs, FOREIGN KEY (tenant,document_id,chunk_id) REFERENCES chunks
);
CREATE INDEX retrieval_lookup ON retrieval_evidence(tenant,run_id,document_id,chunk_id);
ALTER TABLE retrieval_evidence ENABLE ROW LEVEL SECURITY;
ALTER TABLE retrieval_evidence FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_boundary ON retrieval_evidence USING (tenant=nullif(current_setting('agent.tenant',true),''));
GRANT SELECT,INSERT ON retrieval_evidence TO agent_runtime;

--changeset agentplatform:009
CREATE INDEX runs_actor_recent ON runs(tenant,actor,created_at DESC,id);
CREATE INDEX definition_versions_recent ON definitions(tenant,kind,name,created_at DESC);
