--liquibase formatted sql
--changeset agentplatform:004
CREATE TABLE tool_policies (
  tenant text NOT NULL, tool text NOT NULL, effect text NOT NULL CHECK (effect IN ('INHERIT','DENY','REQUIRE_APPROVAL')),
  required_role text NOT NULL, revision bigint NOT NULL, updated_by text NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(tenant,tool)
);
ALTER TABLE tool_policies ENABLE ROW LEVEL SECURITY;
ALTER TABLE tool_policies FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_boundary ON tool_policies USING (tenant=nullif(current_setting('agent.tenant',true),''))
  WITH CHECK (tenant=nullif(current_setting('agent.tenant',true),''));
GRANT SELECT,INSERT,UPDATE ON tool_policies TO agent_runtime;
ALTER TABLE approvals ADD COLUMN policy_revision bigint NOT NULL DEFAULT 0;

ALTER TABLE start_commands ENABLE ROW LEVEL SECURITY;
ALTER TABLE start_commands FORCE ROW LEVEL SECURITY;
CREATE POLICY dispatch_routing ON start_commands FOR SELECT USING (true);
CREATE POLICY enqueue_routing ON start_commands FOR INSERT WITH CHECK (tenant=nullif(current_setting('agent.tenant',true),''));
CREATE POLICY acknowledge_routing ON start_commands FOR UPDATE USING (tenant=nullif(current_setting('agent.tenant',true),''))
  WITH CHECK (tenant=nullif(current_setting('agent.tenant',true),''));
