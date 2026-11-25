--liquibase formatted sql
--changeset agentplatform:005
CREATE TABLE quotas (
  tenant text NOT NULL, scope text NOT NULL, monthly_tokens bigint CHECK(monthly_tokens>=0),
  monthly_usd numeric(30,12) CHECK(monthly_usd>=0), updated_by text NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(tenant,scope)
);
CREATE TABLE usage_periods (
  tenant text NOT NULL, scope text NOT NULL, period date NOT NULL,
  tokens bigint NOT NULL, reserved_usd numeric(30,12) NOT NULL, unpriced bigint NOT NULL,
  PRIMARY KEY(tenant,scope,period)
);
CREATE TABLE model_attempts (
  tenant text NOT NULL, run_id uuid NOT NULL, step_key text NOT NULL,
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, reserved_tokens bigint NOT NULL,
  input_price numeric(18,6), output_price numeric(18,6), reserved_usd numeric(30,12),
  input_tokens integer, output_tokens integer, estimated_usd numeric(30,12),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(), FOREIGN KEY(tenant,run_id) REFERENCES runs
);
CREATE INDEX model_attempt_key ON model_attempts(tenant,run_id,step_key,id DESC);
ALTER TABLE quotas ENABLE ROW LEVEL SECURITY;
ALTER TABLE quotas FORCE ROW LEVEL SECURITY;
ALTER TABLE usage_periods ENABLE ROW LEVEL SECURITY;
ALTER TABLE usage_periods FORCE ROW LEVEL SECURITY;
ALTER TABLE model_attempts ENABLE ROW LEVEL SECURITY;
ALTER TABLE model_attempts FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_boundary ON quotas USING (tenant=nullif(current_setting('agent.tenant',true),''));
CREATE POLICY tenant_boundary ON usage_periods USING (tenant=nullif(current_setting('agent.tenant',true),''));
CREATE POLICY tenant_boundary ON model_attempts USING (tenant=nullif(current_setting('agent.tenant',true),''));
GRANT SELECT,INSERT,UPDATE ON quotas,usage_periods,model_attempts TO agent_runtime;
GRANT USAGE ON SEQUENCE model_attempts_id_seq TO agent_runtime;

--changeset agentplatform:006
ALTER TABLE model_attempts ADD COLUMN model_id text NOT NULL DEFAULT 'default';
