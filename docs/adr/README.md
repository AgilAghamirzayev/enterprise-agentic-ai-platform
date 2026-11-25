# Architecture decisions

These decisions apply to the Java SDK and the self-hosted runtime. The last column describes an operational consequence or trade-off.

| ADR | Decision | Consequence |
| --- | --- | --- |
| 001 | Java 21 is the minimum language level. | Records represent immutable references. Virtual threads support the local executor and SSE clients. |
| 002 | Spring AI sits behind the model gateway. | Agent SDK contracts use platform types. Deployments can replace the gateway or use a deterministic test provider. |
| 003 | Temporal owns durable control flow. | Workflow code issues activity calls and durable timers. Database access, inference and remote I/O run in activities. |
| 004 | Develop in one Maven reactor. | Server and worker have separate entry points and can scale independently. |
| 005 | Keep core contracts framework-independent. | ArchUnit checks dependencies from domain, SPI, SDK and application packages. |
| 006 | PostgreSQL stores definitions, execution projections, evidence and audit records. | A transactional start command bridges the API transaction and the Temporal start call. |
| 007 | pgvector shares the PostgreSQL tenant boundary. | Retrieval applies tenant and role filters. The default token-hash vectorizer provides a reproducible lexical baseline. |
| 008 | Publish versioned declarative definitions. | The compiler rejects malformed graphs. Runs and sub-workflows use explicit versions. |
| 009 | Worker tool execution passes through one enforcement path. | Tool names, bindings, arguments, budgets and approvals are checked before execution. |
| 010 | Deterministic policy checks govern agent actions. | A model proposal must satisfy the configured allowlist. An approval records the concrete action. |
| 011 | REST initiates runs and reads results. SSE streams progress. | Clients retain the latest event ID and reconnect after stream expiry. |
| 012 | Resolve remote MCP and HTTP tool endpoints from deployment-owned bindings. | User-supplied documents and workflow input cannot select a network destination. |
| 013 | Published versions are immutable. | Use a new version for a behavior change and retain the old version while runs depend on it. |
| 014 | Emit low-cardinality metrics through Micrometer and support OTLP export on the API. | Correlate a specific run through its audit timeline. |
| 015 | License the platform under Apache 2.0. | Distribution also requires the notices and license terms of included dependencies. |
| 016 | Run only deployment-owned Java code and registered tool bindings. | Adding a Java implementation requires rebuilding the worker or embedding application. |
| 017 | Model and external tool calls have explicit replay semantics. | A downstream system must honor idempotency for safely repeated writes. Preserve ambiguous outcomes for reconciliation. |

## Ownership and recovery

PostgreSQL stores versioned plans and runtime observations. Temporal schedules and resumes workflow execution. The server creates the run and its pending start in one database transaction. Dispatch uses the run ID as the Temporal workflow ID. The worker loads the pinned definition and writes a result for each completed step. This division lets an operator inspect a queued run even when Temporal is temporarily unavailable.

Registration in the embedded SDK is scoped to a process. The public runtime executes declarative plans using its worker deployment. Arbitrary handler classes cannot be uploaded through the HTTP API. Add an application-specific tool through a trusted worker build or register a fixed outbound connection.

The same source tree contains the operator UI and the data plane. A cluster can keep Studio internal and expose only the API to service identities. See the [operator guide](../operator-guide/kubernetes.md) and [SDK guide](../developer-guide/java-sdk.md).
