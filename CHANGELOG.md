# Changelog

## 0.1.0 (unreleased)

- Run versioned agents and workflows on Temporal with tenant-scoped PostgreSQL state, cancellation, durable approvals and an audit history.
- Build typed Java agents using the SDK, Spring Boot starter and local test runtime. Run a deployment-owned Java handler or a declarative workflow through the same worker.
- Publish prompts and model profiles, limit tokens, calls and estimated spend, and route an eligible fallback through a separately reserved attempt.
- Search tenant knowledge with pgvector, retain scoped conversation history and expose read-only connectors through the worker's tool policy.
- Operate the API, worker and Studio with Docker Compose or Helm. Prepare local SDK artifacts, application archives, SBOMs and checksums with the release script.
- Inspect sampled Studio executions in a self-hosted Langfuse project. The collector exports model usage and excludes prompt and response content.
