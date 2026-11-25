--liquibase formatted sql
--changeset agentplatform:001
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE definitions (
  tenant text NOT NULL, kind text NOT NULL, name text NOT NULL, version text NOT NULL,
  spec jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant,kind,name,version)
);
CREATE TABLE drafts (LIKE definitions INCLUDING ALL);

CREATE TABLE runs (
  tenant text NOT NULL, id uuid NOT NULL, actor text NOT NULL, roles jsonb NOT NULL,
  session_id text, request_key text NOT NULL, kind text NOT NULL, name text NOT NULL, version text NOT NULL,
  input jsonb NOT NULL, result jsonb, status text NOT NULL DEFAULT 'QUEUED', cancel_requested boolean NOT NULL DEFAULT false,
  reserved_tokens bigint NOT NULL DEFAULT 0 CHECK (reserved_tokens >= 0),
  model_calls integer NOT NULL DEFAULT 0 CHECK (model_calls >= 0),
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant,id), UNIQUE(tenant,actor,request_key),
  FOREIGN KEY (tenant,kind,name,version) REFERENCES definitions
);
CREATE INDEX runs_recent ON runs (tenant,created_at DESC,id);

-- The dispatcher reads routing identifiers only. Payloads remain behind RLS.
CREATE TABLE start_commands (
  run_id uuid PRIMARY KEY, tenant text NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (tenant,run_id) REFERENCES runs
);
ALTER TABLE start_commands ADD COLUMN dispatched_at timestamptz;
CREATE INDEX start_commands_fifo ON start_commands (created_at,run_id) WHERE dispatched_at IS NULL;

CREATE TABLE tool_reservations (
  tenant text NOT NULL, run_id uuid NOT NULL, step_key text NOT NULL,
  PRIMARY KEY (tenant,run_id,step_key), FOREIGN KEY (tenant,run_id) REFERENCES runs
);

CREATE TABLE run_steps (
  tenant text NOT NULL, run_id uuid NOT NULL, step_key text NOT NULL, output jsonb NOT NULL,
  input_tokens integer NOT NULL CHECK (input_tokens >= 0), output_tokens integer NOT NULL CHECK (output_tokens >= 0),
  created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY (tenant,run_id,step_key),
  FOREIGN KEY (tenant,run_id) REFERENCES runs
);

CREATE TABLE approvals (
  tenant text NOT NULL, id uuid NOT NULL, run_id uuid NOT NULL, step_key text NOT NULL,
  tool text NOT NULL, args jsonb NOT NULL, action_hash text NOT NULL, required_role text NOT NULL,
  expires_at timestamptz NOT NULL, status text NOT NULL DEFAULT 'PENDING',
  decided_by text, decided_at timestamptz, PRIMARY KEY (tenant,id), UNIQUE(tenant,run_id,step_key),
  FOREIGN KEY (tenant,run_id) REFERENCES runs
);
CREATE INDEX approvals_recent ON approvals (tenant,expires_at DESC);
CREATE TABLE audit_events (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, tenant text NOT NULL, actor text NOT NULL,
  run_id uuid, event_type text NOT NULL, resource text NOT NULL,
  occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  FOREIGN KEY (tenant,run_id) REFERENCES runs
);
CREATE INDEX audit_timeline ON audit_events (tenant,run_id,id);
CREATE INDEX audit_recent ON audit_events (tenant,id DESC);
CREATE TABLE documents (
  tenant text NOT NULL, id uuid NOT NULL, base text NOT NULL, title text NOT NULL, roles jsonb NOT NULL,
  digest text NOT NULL, embedding_model text NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant,id)
);
CREATE INDEX documents_base ON documents (tenant,base);
CREATE TABLE chunks (
  tenant text NOT NULL, document_id uuid NOT NULL, chunk_id integer NOT NULL, content text NOT NULL,
  embedding vector NOT NULL, lexemes tsvector GENERATED ALWAYS AS (to_tsvector('simple',content)) STORED,
  PRIMARY KEY (tenant,document_id,chunk_id), FOREIGN KEY (tenant,document_id) REFERENCES documents
);
CREATE INDEX chunks_lexemes ON chunks USING gin(lexemes);
CREATE TABLE messages (
  tenant text NOT NULL, actor text NOT NULL, agent text NOT NULL, session_id text NOT NULL,
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  run_id uuid NOT NULL, step_key text NOT NULL, role text NOT NULL, content text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(tenant,run_id,step_key,role),
  FOREIGN KEY (tenant,run_id) REFERENCES runs
);
CREATE INDEX messages_session ON messages (tenant,actor,agent,session_id,id DESC);
CREATE TABLE evaluations (
  tenant text NOT NULL, id uuid NOT NULL, run_id uuid NOT NULL, actor text NOT NULL, expected jsonb NOT NULL,
  passed boolean NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant,id), FOREIGN KEY (tenant,run_id) REFERENCES runs
);

--changeset agentplatform:002 splitStatements:false
DO $migration$
DECLARE rel text;
BEGIN
  FOREACH rel IN ARRAY ARRAY['definitions','drafts','runs','run_steps','approvals','audit_events','documents','chunks','messages','evaluations','tool_reservations']
  LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', rel);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', rel);
    EXECUTE format('CREATE POLICY tenant_boundary ON %I USING (tenant = nullif(current_setting(''agent.tenant'', true),'''')) WITH CHECK (tenant = nullif(current_setting(''agent.tenant'', true),''''))', rel);
  END LOOP;
END $migration$;

--changeset agentplatform:003
GRANT USAGE ON SCHEMA public TO agent_runtime;
GRANT SELECT,INSERT ON definitions,run_steps,audit_events,documents,chunks,messages,evaluations TO agent_runtime;
GRANT SELECT,INSERT,UPDATE ON runs,approvals TO agent_runtime;
GRANT SELECT,INSERT,UPDATE ON drafts TO agent_runtime;
GRANT SELECT,INSERT,UPDATE ON start_commands TO agent_runtime;
GRANT SELECT,INSERT ON tool_reservations TO agent_runtime;
GRANT USAGE ON SEQUENCE audit_events_id_seq,messages_id_seq TO agent_runtime;
