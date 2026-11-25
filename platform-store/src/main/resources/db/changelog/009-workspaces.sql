--liquibase formatted sql
--changeset agentplatform:workspace-directory
CREATE TABLE tenant_directory (
 tenant text PRIMARY KEY, display_name text NOT NULL
);
CREATE TABLE workspaces (
 tenant text NOT NULL REFERENCES tenant_directory, name text NOT NULL, display_name text NOT NULL,
 PRIMARY KEY(tenant,name), CHECK(name <> 'default')
);
CREATE TABLE workspace_memberships (
 tenant text NOT NULL, workspace text NOT NULL, subject text NOT NULL, roles jsonb NOT NULL,
 PRIMARY KEY(tenant, workspace, subject), FOREIGN KEY(tenant,workspace) REFERENCES workspaces,
 CHECK(jsonb_typeof(roles)='array')
);
CREATE INDEX workspace_members_by_subject ON workspace_memberships(tenant,subject,workspace);
ALTER TABLE tenant_directory ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_directory FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_boundary ON tenant_directory USING (tenant=nullif(current_setting('agent.tenant',true),''));
ALTER TABLE workspaces ENABLE ROW LEVEL SECURITY;
ALTER TABLE workspaces FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_boundary ON workspaces USING (tenant=nullif(current_setting('agent.tenant',true),''));
ALTER TABLE workspace_memberships ENABLE ROW LEVEL SECURITY;
ALTER TABLE workspace_memberships FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_boundary ON workspace_memberships USING (tenant=nullif(current_setting('agent.tenant',true),''));
GRANT SELECT, INSERT, UPDATE ON tenant_directory, workspace_memberships TO agent_runtime;
GRANT SELECT, INSERT ON workspaces TO agent_runtime;
