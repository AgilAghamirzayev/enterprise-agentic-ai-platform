# Enterprise Agentic AI Platform documentation

Start with a local run to see the entire lifecycle. The default echo model works without a provider account. The embedded Java SDK also runs on its own if your application only needs an in-process agent.

| What do you want to do? | Start here |
| --- | --- |
| Run a Java agent in your application | [Build the Java example and register a handler](developer-guide/java-sdk.md) |
| Publish an agent, see its output and approve a tool call | [Studio walkthrough](tutorials/studio.md) |
| Call the platform from another application | [HTTP walkthrough with a runnable client](tutorials/http-api.md) |
| Understand how a run survives a worker restart | [Execution lifecycle and troubleshooting](architecture/execution.md) |
| Add branching, parallel work, a supervisor or a scheduled event | [Workflow patterns](developer-guide/workflow-patterns.md) and [plan reference](../platform-plan/README.md) |
| Give an agent access to documents | [Knowledge ingestion, permissions and reindexing](operator-guide/knowledge-ingestion.md) |
| Connect an HTTP or MCP tool | [Workspace and connector contracts](operator-guide/workspaces-and-tools.md) and [MCP example](../examples/mcp-integration/README.md) |
| Compare agent versions on a dataset | [Evaluation suites and reports](developer-guide/evaluations.md) |
| Inspect a model call and its token usage | [Langfuse setup and verification](operator-guide/langfuse.md) |
| Configure a provider, OIDC, quotas or a Kubernetes deployment | [Operator guide](operator-guide/kubernetes.md) |
| Build SDK archives, Helm packages or SBOMs | [Release guide](operator-guide/release.md) |

The [architecture diagram](architecture/platform-architecture.svg) shows the platform layers. The [decision log](adr/README.md) explains their responsibilities. The [design comparison in Azerbaijani](architecture/design-comparison.az.md) records what has been implemented and where deployment-specific verification is still needed.
