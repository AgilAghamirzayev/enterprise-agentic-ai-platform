# Agent traces in Langfuse

The API persists the admission span's W3C trace context in the run transaction. The worker restores that context for each Temporal activity. A model attempt produces one generation, including attempts that fail before a fallback succeeds. The generation records the routed model and provider. Successful calls report the token counts supplied by the model adapter. The demo provider supplies local estimates.

## Run locally

Run `./scripts/verify-langfuse.sh` with Java 21 and Docker. The first run pulls the pinned images and migrates the separate Langfuse database and ClickHouse tables. Persistent Docker volumes keep the project and its traces between starts. To restart an existing build, use `./scripts/langfuse.sh up -d --wait`.

Open [Langfuse](http://localhost:13001) and sign in as `admin@platform.local`. The initialization script generates the password as `LF_ADMIN_PASSWORD` in `$XDG_STATE_HOME/agent-platform/langfuse/local.conf`, using `~/.local/state` when `XDG_STATE_HOME` is unset. The same private file holds the local project and storage credentials. Keep it together with the volumes across restarts.

Publish an agent in Studio, start it in **Playground** and open **View trace in Langfuse** in the execution detail. Ingestion is asynchronous. The trace name identifies the definition, the metadata contains the run UUID and the generation reports model usage. A paused workflow can add observations later when execution resumes. A sampled run's link can appear before its first observations arrive.

## Operate the exporter

The optional Compose configuration samples every run for local verification. The base configuration uses zero sampling. In a deployed environment, use `tracing.samplingProbability` for the expected traffic and set `tracing.endpoint` to a private collector's OTLP/HTTP trace endpoint. Set `tracing.langfuseProjectUrl` to the browser-facing HTTPS project URL. Langfuse enforces project access separately from Studio. A project receives traces from every workspace routed to that collector. Use a separate deployment and project for each required observability access boundary.

Mount the [collector configuration](../../deployments/langfuse/collector.yaml) and provide `LANGFUSE_ENDPOINT` (ending in `/api/public/otel`), `LANGFUSE_PUBLIC_KEY` and `LANGFUSE_SECRET_KEY` from the deployment secret store. Use HTTPS for external Langfuse endpoints and restrict access to the collector receiver. Only the local Langfuse web service has a published host port.

The collector exports marked execution spans, keeps selected metadata and drops span events and error messages. Raw prompts, responses, documents and credentials are excluded. The collector batches exports, retries transient failures and has a bounded in-memory queue. A prolonged outage or collector restart can lose queued traces. Execution history and audit records remain in the platform.

The verification publishes a synthetic agent from Studio and checks that its completed run and generation share a trace ID in Langfuse's observations API. It checks parentage, usage and omission of the synthetic input, then signs in and opens the generation in the Langfuse web interface.

[Langfuse OpenTelemetry ingestion](https://langfuse.com/integrations/native/opentelemetry) · [Headless project initialization](https://langfuse.com/self-hosting/administration/headless-initialization)
