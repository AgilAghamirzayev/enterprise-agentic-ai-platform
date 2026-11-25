--liquibase formatted sql
--changeset agentplatform:014
ALTER TABLE tool_policies ADD COLUMN rules jsonb;
CREATE TABLE step_policy_bindings (
  tenant text NOT NULL, run_id uuid NOT NULL, step_key text NOT NULL,
  tool text NOT NULL, args_hash text NOT NULL, policy_refs jsonb NOT NULL,
  PRIMARY KEY(tenant,run_id,step_key), FOREIGN KEY(tenant,run_id) REFERENCES runs
);
ALTER TABLE step_policy_bindings ENABLE ROW LEVEL SECURITY;
ALTER TABLE step_policy_bindings FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_boundary ON step_policy_bindings USING (tenant=nullif(current_setting('agent.tenant',true),''));
GRANT SELECT,INSERT ON step_policy_bindings TO agent_runtime;

--changeset agentplatform:015 splitStatements:false
CREATE FUNCTION reserve_parent_usage(child text, charged_tokens bigint, charged_usd numeric)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $function$
DECLARE parent_tenant text; u usage_periods%ROWTYPE; cap quotas%ROWTYPE;
BEGIN
  IF child IS DISTINCT FROM current_setting('agent.tenant',true) OR charged_tokens<0 OR charged_usd<0 OR child !~ '^[^~]+~[^~]+$' THEN
    RAISE EXCEPTION 'invalid workspace usage context';
  END IF;
  parent_tenant:=split_part(child,'~',1);
  PERFORM pg_advisory_xact_lock(hashtextextended('usage/'||parent_tenant,0));
  PERFORM set_config('agent.tenant',parent_tenant,true);
  INSERT INTO usage_periods(tenant,scope,period,tokens,reserved_usd,unpriced)
  VALUES(parent_tenant,'*',date_trunc('month',current_timestamp AT TIME ZONE 'UTC')::date,charged_tokens,coalesce(charged_usd,0),
         CASE WHEN charged_usd IS NULL THEN 1 ELSE 0 END)
  ON CONFLICT(tenant,scope,period) DO UPDATE SET tokens=usage_periods.tokens+excluded.tokens,
       reserved_usd=usage_periods.reserved_usd+excluded.reserved_usd,unpriced=usage_periods.unpriced+excluded.unpriced RETURNING * INTO u;
  SELECT * INTO cap FROM quotas WHERE tenant=parent_tenant AND scope='*';
  PERFORM set_config('agent.tenant',child,true);
  RETURN (cap.monthly_tokens IS NULL OR u.tokens<=cap.monthly_tokens)
        AND (cap.monthly_usd IS NULL OR (u.unpriced=0 AND u.reserved_usd<=cap.monthly_usd));
END $function$;
--changeset agentplatform:016
REVOKE ALL ON FUNCTION reserve_parent_usage(text,bigint,numeric) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION reserve_parent_usage(text,bigint,numeric) TO agent_runtime;

--changeset agentplatform:017
ALTER TABLE audit_events ADD COLUMN event_uuid uuid NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE audit_events ADD COLUMN resource_type text;
ALTER TABLE audit_events ADD COLUMN trace_id text;
ALTER TABLE audit_events ADD COLUMN policy_version text;
ALTER TABLE audit_events ADD COLUMN payload_hash text;
ALTER TABLE audit_events ADD COLUMN chain_position bigint;
ALTER TABLE audit_events ADD COLUMN previous_hash text;
ALTER TABLE audit_events ADD COLUMN event_hash text;
CREATE UNIQUE INDEX audit_chain_order ON audit_events(tenant,chain_position) WHERE chain_position IS NOT NULL;
CREATE TABLE audit_heads (tenant text PRIMARY KEY, position bigint NOT NULL, digest text NOT NULL);
ALTER TABLE audit_heads ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_heads FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_boundary ON audit_heads USING (tenant=nullif(current_setting('agent.tenant',true),''));
GRANT SELECT ON audit_heads TO agent_runtime;

--changeset agentplatform:018 splitStatements:false
CREATE FUNCTION audit_digest(e audit_events) RETURNS text LANGUAGE sql STABLE SET search_path=pg_catalog,public AS $digest$
  SELECT encode(sha256(convert_to(jsonb_build_array(1,e.tenant,e.chain_position,e.previous_hash,e.id,e.event_uuid,e.actor,
    e.run_id,e.event_type,e.resource_type,e.resource,e.trace_id,e.policy_version,e.payload_hash,extract(epoch FROM e.occurred_at))::text,'UTF8')),'hex')
$digest$;
--changeset agentplatform:019 splitStatements:false
CREATE FUNCTION append_audit_link() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $link$
DECLARE h audit_heads%ROWTYPE;
BEGIN
  IF NEW.tenant IS DISTINCT FROM current_setting('agent.tenant',true) THEN RAISE EXCEPTION 'invalid audit scope'; END IF;
  INSERT INTO audit_heads(tenant,position,digest) VALUES(NEW.tenant,0,repeat('0',64)) ON CONFLICT DO NOTHING;
  SELECT * INTO STRICT h FROM audit_heads WHERE tenant=NEW.tenant FOR UPDATE;
  NEW.chain_position:=h.position+1;
  NEW.previous_hash:=h.digest;
  NEW.resource_type:=coalesce(NEW.resource_type,split_part(NEW.event_type,'.',1));
  NEW.event_hash:=audit_digest(NEW);
  UPDATE audit_heads SET position=NEW.chain_position,digest=NEW.event_hash WHERE tenant=NEW.tenant;
  RETURN NEW;
END $link$;
--changeset agentplatform:020
CREATE TRIGGER audit_chain_before BEFORE INSERT ON audit_events FOR EACH ROW EXECUTE FUNCTION append_audit_link();

--changeset agentplatform:021
ALTER TABLE approvals ADD COLUMN created_at timestamptz;
ALTER TABLE approvals ALTER COLUMN created_at SET DEFAULT clock_timestamp();
ALTER TABLE messages ADD COLUMN search_tokens tsvector GENERATED ALWAYS AS (to_tsvector('simple',content)) STORED;
CREATE INDEX memory_episode_search ON messages USING gin(search_tokens);

--changeset agentplatform:022
CREATE TABLE external_effects (
 tenant text NOT NULL,run_id uuid NOT NULL,step_key text NOT NULL,tool text NOT NULL,args_hash text NOT NULL,
 action_hash text NOT NULL,outcome text NOT NULL DEFAULT 'DISPATCHED',receipt jsonb,attributed_to text,
 dispatched_at timestamptz NOT NULL DEFAULT clock_timestamp(),resolved_at timestamptz,
 PRIMARY KEY(tenant,run_id,step_key),FOREIGN KEY(tenant,run_id,step_key) REFERENCES approvals(tenant,run_id,step_key)
);
CREATE INDEX effects_outstanding ON external_effects(tenant,dispatched_at) WHERE outcome='DISPATCHED';
ALTER TABLE external_effects ENABLE ROW LEVEL SECURITY;
ALTER TABLE external_effects FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_boundary ON external_effects USING (tenant=nullif(current_setting('agent.tenant',true),''));
GRANT SELECT,INSERT ON external_effects TO agent_runtime;
GRANT UPDATE(outcome,receipt,attributed_to,resolved_at) ON external_effects TO agent_runtime;
