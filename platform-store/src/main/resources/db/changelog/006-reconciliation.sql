--liquibase formatted sql
--changeset agentplatform:011
ALTER TABLE start_commands ADD COLUMN closed boolean NOT NULL DEFAULT false;
ALTER TABLE start_commands ADD COLUMN next_check timestamptz NOT NULL DEFAULT now();
CREATE INDEX start_commands_due ON start_commands(next_check,run_id) WHERE dispatched_at IS NOT NULL AND NOT closed;

--changeset agentplatform:012
CREATE TABLE memory_sources (
  tenant text NOT NULL, run_id uuid NOT NULL, document_id uuid NOT NULL,
  PRIMARY KEY(tenant,run_id,document_id),
  FOREIGN KEY(tenant,run_id) REFERENCES runs, FOREIGN KEY(tenant,document_id) REFERENCES documents
);
ALTER TABLE memory_sources ENABLE ROW LEVEL SECURITY;
ALTER TABLE memory_sources FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_boundary ON memory_sources USING (tenant=nullif(current_setting('agent.tenant',true),''));
GRANT SELECT,INSERT ON memory_sources TO agent_runtime;

--changeset agentplatform:013
ALTER TABLE document_heads ADD COLUMN owner_id text;
CREATE INDEX audit_document_publisher ON audit_events(tenant,resource,id) WHERE event_type='document.indexed';
