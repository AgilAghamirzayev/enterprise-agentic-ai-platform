# Operate the platform on Kubernetes

The Helm chart deploys the API, Temporal worker and Studio independently. PostgreSQL, Temporal, an OIDC issuer and an optional OTLP collector run outside this chart. The two Services are cluster-internal.

## Provision dependencies

1. Install PostgreSQL with pgvector. Install the vector extension as a database administrator. Create a login role named `agent_runtime` with `NOSUPERUSER NOBYPASSRLS`. Create a distinct migration owner with permission to create tables and sequences in the application schema. Revoke public schema creation from ordinary roles. The source checkout contains a working role bootstrap for a new development database in `deployments/postgres/init.sh`.
2. Create a Temporal namespace with retention appropriate for your workload. The API and worker must reach the same namespace and `agent-runtime` task queue. For TLS connections, ensure both Java images trust the issuing CA.
3. Configure an OIDC client and issuer. API access tokens must include the configured audience, a subject, a `tenant_id` and a string array named `roles`. Application roles include `user`, `operator`, `developer`, `approver`, `auditor` and tenant-scoped `admin`. Configure role assignment in the identity provider.
4. Mirror or build and push the three images to your own registry under a common prefix as `server`, `worker` and `studio`. Build the Java artifacts with Maven before invoking the root Dockerfile with `MODULE=platform-server` or `MODULE=platform-worker`. Build Studio with its directory as the build context.
5. Create the namespace and supply the chart's referenced Kubernetes Secrets using your normal secret manager. The runtime database Secret contains `username`, `password` and `jdbc-url`. The migration Secret contains `username` and `password`. The OpenAI-compatible provider Secret contains `api-key`. Restrict each resource to its consumers. The worker receives runtime and provider credentials. The server receives runtime and migration credentials.
6. Configure database TLS, storage encryption and backups for both PostgreSQL and Temporal. For PostgreSQL, supply a TLS-verifying JDBC URL and trust material that the driver can read. Keep raw credentials out of Helm values.

## Install

Create deployment-specific values using the names exposed by `deployments/helm/agent-platform/values.yaml`.

```yaml
imagePrefix: registry.company.example/ai-platform
imageTag: "0.1.0"
database:
  runtimeSecret: platform-db-runtime
  migrationSecret: platform-db-owner
temporal:
  target: temporal.company.example:7233
  namespace: agents
  tls: true
oidc:
  issuer: https://identity.company.example/realms/engineering
  audience: agent-platform
  studioClientId: agent-studio
  scope: openid profile
model:
  mode: openai
  baseUrl: https://models.company.example
  name: approved-chat
  secret: platform-llm
```

Check the manifests before deploying to the selected cluster.

```sh
helm lint --strict deployments/helm/agent-platform -f site-values.yaml
helm template agent deployments/helm/agent-platform -f site-values.yaml
helm upgrade --install agent deployments/helm/agent-platform \
  -n agents -f site-values.yaml --wait --timeout 10m
kubectl -n agents get deploy,svc -l app.kubernetes.io/instance=agent
kubectl -n agents port-forward svc/agent-agent-platform-studio 3000:3000
```

Register Studio as a public OIDC client using Authorization Code with PKCE. Set its redirect URI to the exact Studio origin with a trailing slash, such as `https://studio.company.example/`. Configure allowed web origins and the API token audience in the issuer. Set `oidc.studioClientId` and `oidc.scope` in chart values. A blank Studio client ID keeps manual bearer-token entry available.

Studio forwards API calls to its server Service. For ingress, terminate TLS, preserve authentication headers, disable response buffering on SSE routes and set request limits suitable for document ingestion. Restrict inbound access to approved clients. Limit backend egress to PostgreSQL, Temporal, issuer discovery/JWKS, the selected provider, approved tool services and the telemetry collector.

To test a deployment without incurring provider charges, set `model.mode: demo`. For fixed remote tools, mount an operator-managed Secret containing `connectors.json` via `connectors.secret`. Inject any per-tool `tokenEnv` through the cluster's workload secret integration. The file is read on worker startup. Restart workers after a binding change. Use a separate token per destination and restrict it to that destination's audience.

## Monitor and recover

The server exposes unauthenticated health probes under `/actuator/health`. Its Prometheus endpoint requires an auditor or administrator token. Set `tracing.endpoint` and `tracing.samplingProbability` to enable sampled API traces. Keep prompts, tool arguments, provider responses and user IDs out of metric labels.

The worker is a background process. Confirm workflow and activity pollers with Temporal's task queue inspection before opening traffic. Start a known test workflow and confirm that its persisted result and audit timeline appear in the API. Check approval paths using a separate approver identity.

When runs remain queued, inspect pending Temporal starts, frontend availability and pollers. The outbox identifies each start by run ID. A repeated dispatch can recognize the already-started workflow. When an external write times out, reconcile with the destination using its idempotency reference before retrying. Retain compatible workflow implementations for histories that can still replay.

Scale server and worker replicas independently. Size the combined JDBC pools against PostgreSQL connection limits and keep worker concurrency within provider and downstream quotas. Benchmark API and inference latency separately using representative tenant sizes. The architecture's service-level and recovery targets require environment-specific load and restore tests.

Take regular database backups and enable point-in-time recovery for PostgreSQL and the Temporal persistence backend. Restore into an isolated namespace, compare queued and terminal executions, validate pending approvals, and reconcile external effects before resuming dispatch. A Helm rollback changes application versions. Plan database rollback separately because Liquibase migrations and external side effects survive pod replacement.

## Configure model routes

In Helm values use `model.name` for the primary model and `model.allowed` for a comma-separated list of additional model IDs. The chart applies that inventory to both Java workloads. Configure provider-side identifiers that remain available for the lifetime of pinned profiles. Removing an ID causes existing profiles using it to fail closed. Keep a compatible old worker deployment available when retiring model IDs.

Set the deployment's input and output token prices to upper bounds across all allowed models when enforcing monetary budgets. An attempted inference with an ambiguous transport failure may already have been billed. The worker reserves each fallback separately. Audit records identify the selected model without recording prompt text.

Set `pricing.inputPerMillion` and `pricing.outputPerMillion` together as decimal strings. Use `worker.javaAgents` to opt individual tenants into installed Java handlers. For semantic retrieval set `embeddings.mode: learned`, a provider model name, an immutable embedding revision and an embeddings Secret containing `api-key`. Both the server and worker need this credential for ingestion and search. The embedding endpoint must support 256 dimensions. Changing the embedding implementation or revision requires reindexing documents.

### Route across providers

Set `model.mode: catalog` and supply `model.catalogConfigMap` containing a file named `models.json`. Set `model.credentialsSecret` to a worker-only Secret whose keys match the environment references below. Outside Kubernetes, set `PLATFORM_MODEL=catalog` and `PLATFORM_MODEL_CATALOG` to the same file path on the server and worker. The API reads deployment metadata. Only the worker resolves the provider credentials.

```json
{
  "primary": "writer",
  "models": {
    "writer": {
      "provider": "anthropic",
      "model": "my-approved-claude-version",
      "credentialEnv": "PLATFORM_MODEL_KEY_CLAUDE",
      "capabilities": ["text", "structuredOutput"],
      "residency": "eu",
      "confidentiality": "internal"
    },
    "reviewer": {
      "provider": "gemini",
      "model": "my-approved-gemini-version",
      "credentialEnv": "PLATFORM_MODEL_KEY_GEMINI",
      "capabilities": ["text", "structuredOutput"],
      "residency": "eu",
      "confidentiality": "internal"
    }
  }
}
```

Replace model names with the pinned IDs enabled in your account. Supported provider values are `openai`, `anthropic`, `gemini` and `ollama`. The worker uses each provider's OpenAI-compatible chat endpoint. Set an optional `baseUrl` for an authorized private gateway or a regional origin. Check the contract, account and endpoint against the declared residency and confidentiality values before installation. Ollama defaults to `http://localhost:11434` and may omit the credential reference. In Kubernetes point it at a reachable service. Configure a verified HTTPS origin for any credentialed network service.

The server lists installed aliases at `GET /api/v1/model-inventory`. A tenant administrator can publish a profile with `model`, `parameters.temperature`, `parameters.maxOutputTokens`, `timeoutSeconds`, `capabilities` and either `fallbackModel` or `fallbackProfiles`. A profile can reference up to four previously published single-model profiles, for example `["review:1.0.0"]`. The worker tries them in order after transient provider failures. Each attempt reserves its own tokens, cost and call allowance, obeys its own output and time limits, and shares the primary wall-clock deadline. A fallback requires matching residency and confidentiality and every capability advertised by the primary. The worker checks the pinned model, provider and policy digest again on execution. Publish a new version after changing a model, endpoint or routing policy.

Capabilities describe the features the deployment has qualified. For `structuredOutput` the SDK can validate a typed response. A platform tool-calling step asks the model for a proposed action and routes it through tool policy. The Spring adapter disables provider-side tool execution. Keep provider credentials and private endpoints out of tenant profiles, prompts and workflow definitions. [Anthropic compatibility](https://platform.claude.com/docs/en/api/openai-sdk) · [Gemini compatibility](https://ai.google.dev/gemini-api/docs/openai) · [Ollama compatibility](https://docs.ollama.com/openai)

### Export worker and API metrics

Set `metrics.endpoint` to the collector's OTLP/HTTP metrics URL, including `/v1/metrics`, to enable push export from both Java processes. The equivalent environment variables are `PLATFORM_METRICS_OTLP_ENABLED=true` and `OTEL_EXPORTER_OTLP_METRICS_ENDPOINT`. Traces use the separate `tracing.endpoint` setting. Use a collector reachable only from the workloads or configure authenticated OTLP transport through your cluster.

Telemetry contract v1 uses Micrometer dotted names. A Prometheus exporter translates separators and adds unit and counter suffixes. Count provider attempts with `model.requests`, including failed attempts and fallbacks. Use `model.request.duration` for per-attempt latency, `model.input.tokens` and `model.output.tokens` for validated responses, and `execution.budget.exceeded` for rejected activity operations. Inspect `tool.execution.duration` and `knowledge.retrieval.duration` for step latency. Provider names and result classes have bounded cardinality. Use trace attributes and persisted audit records to inspect individual runs. After a process restart the tenant's durable usage and cost report remains available through the API.

The server stores the accepting HTTP span's W3C trace parent with the run. An idempotent retry keeps the first parent. A worker restores that context when starting a step and passes it to the provider thread. This joins inference, retrieval and tool spans to the originating request across dispatch delays and restarts. Runs created without an active trace and older persisted runs can start a new worker trace.
