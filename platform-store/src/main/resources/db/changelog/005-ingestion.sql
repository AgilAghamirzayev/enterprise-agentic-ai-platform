--liquibase formatted sql
--changeset agentplatform:010
ALTER TABLE documents ADD COLUMN document_key text;
ALTER TABLE documents ADD COLUMN revision integer NOT NULL DEFAULT 1;
CREATE UNIQUE INDEX document_revision ON documents(tenant,base,document_key,revision) WHERE document_key IS NOT NULL;
CREATE TABLE document_heads (
  tenant text NOT NULL, base text NOT NULL, document_key text NOT NULL,
  last_revision integer NOT NULL, active_revision integer NOT NULL DEFAULT 0, active_id uuid,
  PRIMARY KEY(tenant,base,document_key), FOREIGN KEY(tenant,active_id) REFERENCES documents
);
CREATE TABLE document_artifacts (
  tenant text NOT NULL, digest text NOT NULL, content text NOT NULL,
  PRIMARY KEY(tenant,digest)
);
CREATE TABLE ingestion_jobs (
  tenant text NOT NULL, id uuid NOT NULL, actor text NOT NULL, request_key text NOT NULL,
  base text NOT NULL, document_key text NOT NULL, revision integer NOT NULL, title text NOT NULL, roles jsonb NOT NULL,
  digest text NOT NULL, status text NOT NULL DEFAULT 'QUEUED', generation integer NOT NULL DEFAULT 1,
  document_id uuid, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(tenant,id), UNIQUE(tenant,actor,request_key), UNIQUE(tenant,base,document_key,revision),
  FOREIGN KEY(tenant,digest) REFERENCES document_artifacts, FOREIGN KEY(tenant,document_id) REFERENCES documents,
  FOREIGN KEY(tenant,base,document_key) REFERENCES document_heads
);
CREATE TABLE ingestion_commands (
  tenant text NOT NULL, job_id uuid NOT NULL, generation integer NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(), dispatched_at timestamptz,
  PRIMARY KEY(tenant,job_id,generation), FOREIGN KEY(tenant,job_id) REFERENCES ingestion_jobs
);
CREATE INDEX ingestion_pending ON ingestion_commands(created_at,job_id) WHERE dispatched_at IS NULL;
ALTER TABLE document_heads ENABLE ROW LEVEL SECURITY;
ALTER TABLE document_heads FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_boundary ON document_heads USING (tenant=nullif(current_setting('agent.tenant',true),''));
ALTER TABLE document_artifacts ENABLE ROW LEVEL SECURITY;
ALTER TABLE document_artifacts FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_boundary ON document_artifacts USING (tenant=nullif(current_setting('agent.tenant',true),''));
ALTER TABLE ingestion_jobs ENABLE ROW LEVEL SECURITY;
ALTER TABLE ingestion_jobs FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_boundary ON ingestion_jobs USING (tenant=nullif(current_setting('agent.tenant',true),''));
ALTER TABLE ingestion_commands ENABLE ROW LEVEL SECURITY;
ALTER TABLE ingestion_commands FORCE ROW LEVEL SECURITY;
CREATE POLICY dispatch_routing ON ingestion_commands FOR SELECT USING (true);
CREATE POLICY enqueue_routing ON ingestion_commands FOR INSERT WITH CHECK (tenant=nullif(current_setting('agent.tenant',true),''));
CREATE POLICY acknowledge_routing ON ingestion_commands FOR UPDATE USING (tenant=nullif(current_setting('agent.tenant',true),''));
GRANT SELECT,INSERT ON document_artifacts TO agent_runtime;
GRANT SELECT,INSERT,UPDATE ON document_heads,ingestion_jobs,ingestion_commands TO agent_runtime;
