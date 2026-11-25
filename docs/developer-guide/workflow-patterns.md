# Bounded workflows and event delivery

## Publish a graph from Java or YAML

`WorkflowBuilder` creates the same graph as the YAML compiler. Give each node an ID and use an immutable version for every referenced agent, workflow or tool. Publish the returned JSON as the `spec` of a workflow draft, then validate and publish that draft.

```java
String spec = WorkflowBuilder.start("analyze")
        .agent("analyze", "invoice-reader:1.0.0", "risk")
        .condition("risk", "riskScore > 70", "review", "record")
        .approval("review", "ACCOUNTANT", Duration.ofHours(24), "record", "rejected")
        .tool("record", "record-invoice:1.0.0", "complete")
        .end("complete")
        .end("rejected")
        .compile();
```

The condition reads a typed top-level field from the previous step's JSON output. The approval resumes the same run after the assigned reviewer decides. A child that uses tools must fit within the parent's tool allowlist. Declare additional child permissions with `allowTools(...)`.

An installed Java agent can use one knowledge base and read up to 20 session messages. A graph containing a Java handler can keep the default memory settings or declare a conversation window of at least 20. The compiler also checks inherited memory restrictions before publishing a parent graph. Declarative model steps support smaller windows, disabled history and episodic retrieval.

## Run a tool-calling agent

The following agent looks up orders and can submit an approved support ticket. Publish the named tools, prompt, model profile and policy in its workspace first.

```yaml
apiVersion: platform.ai/v1alpha1
kind: Agent
metadata: {name: support, version: 1.0.0}
spec:
  type: TOOL_CALLING
  model: {profile: support-chat}
  prompt: {templateRef: support-answer, version: 3}
  tools: [lookup-order, create-ticket]
  knowledgeBases: [product-manual]
  execution: {maxIterations: 6, maxModelCalls: 10, maxToolCalls: 6, timeout: 120s}
  budget: {maxTokens: 20000, maxCostUsd: 0.50}
  policyRef: support-access
  memory: {type: CONVERSATION, maxMessages: 10}
```

Draft compilation resolves a bare registry name to its most recently published version. A numeric prompt version of `3` selects `3.0.0`. Inspect the canonical draft to review the pinned references. Every decision records its own output. The model can return `{"answer":"..."}` to finish or `{"tool":"lookup-order","arguments":{...}}` to call a tool. The next decision receives the original request and latest tool result. Exhausting the iteration limit records `BUDGET_EXCEEDED`. Iterative definitions support up to 60 decisions within the 128-node compiled graph limit.

## Register a versioned tool

Use an administrator in the target workspace to submit the following body to `POST /api/v1/tools/order-review/versions`. The optional target delegates to a published agent or workflow after the runtime checks the input, policy, budget and any approval. The run stores a successful receipt after checking the delegate's output.

```json
{
  "name": "order-review",
  "version": "1.0.0",
  "spec": {
    "inputSchema": {"type": "object", "properties": {"orderId": {"type": "string"}}, "required": ["orderId"], "additionalProperties": false},
    "outputSchema": {"type": "string", "maxLength": 4000},
    "target": {"kind": "agent", "ref": "order-reviewer:1.0.0"}
  }
}
```

For an installed Java, HTTP or MCP tool, omit `target` and publish its installed name and version. Java implementations implement `AgentTool<I,O>` and register as scanned Spring beans using `@PlatformTool(name=..., version=..., tenants={...}, readOnly=...)`. An approved write receives a stable idempotency key. A lost receipt leaves a dispatch record for reconciliation. Operator-confirmed external effects and local ticket writes retain their recorded outcome through retries.

The built-in `document-search:1.0.0` accepts `{"base":"product-manual","query":"returns"}` and uses the run's authenticated role snapshot to query PostgreSQL. Include the base in the node's `knowledgeBases`. The result contains up to four authorized matching chunks. A Java or YAML definition can use this tool wherever it can invoke a registered read.

## Plan and execute

The worker ships a trusted Java handler named `brief-planner:1.0.0`. It asks a model for up to three distinct operation IDs, verifies the entire proposal, then performs only those operations. The available operations extract facts, summarize and check the answer against the supplied material. Each inference runs through the same model gateway and budget. Results include the executed operation names and final output.

For your own bounded operation set, construct `PlannerExecutor` from a map of named instructions, a maximum step count and an output allowance. An unlisted operation or a duplicate invalidates the proposal before the first operation starts. The platform stores the selected operation names as operational evidence.

## Evaluate and revise

`brief-reviewer:1.0.0` generates a draft and asks a model to evaluate it against a fixed rubric. The evaluator must return an acceptance verdict or a request for revision with feedback. The handler permits two revisions. If the final review still requests work, the result records `accepted: false`, the last draft and feedback. A reviewer or binding policy can decide the next business action.

To use a different rubric or iteration limit, construct `EvaluatorOptimizer` and register it in a trusted worker build. Evaluate domain accuracy against human-labeled responses before relying on a model's own quality score.

Both handlers take a JSON string as input. Add the appropriate tenant binding to the worker, for example `PLATFORM_JAVA_AGENTS=north=brief-planner:1.0.0,north=brief-reviewer:1.0.0`. Publish the plan from `examples/planner-executor/definition.json` or `examples/evaluator-optimizer/definition.json` as a versioned workflow. Select a capable model and allow up to the declared six model calls. The SDK tests script model responses for repeatable offline behavior.

## Subscribe a pinned workflow to events

Register the workflow first. A tenant administrator can then publish a trigger with `POST /api/v1/triggers` and the following JSON:

```json
{
  "name": "daily",
  "version": "1.0.0",
  "source": "urn:daily-clock",
  "type": "report.due",
  "subject": "scheduled-reports",
  "workflow": {"name": "daily-brief", "version": "1.0.0"}
}
```

The subject is the producer's authenticated OIDC subject claim. An event source string on its own conveys no access. The producer must also have a role that can invoke the target workflow. The event body supplies the run input. The registered workflow fixes the executable target.

Deliver an event with `Content-Type: application/cloudevents+json` and a bearer token to `POST /api/v1/triggers/daily/versions/1.0.0/events`.

```json
{
  "specversion": "1.0",
  "id": "2026-11-25T08:00Z",
  "source": "urn:daily-clock",
  "type": "report.due",
  "data": "Prepare the daily brief."
}
```

The response is the ordinary HTTP 202 run receipt. When retrying a delivery, keep the same producer, event ID and data. The same tenant, actor, trigger version, source and ID resolve to the same idempotency key. Changing the input under that key produces a conflict.

## Schedule the producer

The Kubernetes CronJob in `examples/scheduled-event` obtains a short-lived service access token and sends one event at 08:00 UTC. Kubernetes owns calendar scheduling. Temporal owns the resulting durable workflow. Set the token endpoint, API URL, audience mapping, trigger source and schedule for your deployment. The client identity needs the configured OIDC subject, tenant and invocation role. Supply its client credentials via the referenced Secret.

Render the workload using `kubectl kustomize examples/scheduled-event`. With Kubernetes 1.27 or later, deploy the rendered workload in the producer's namespace. Kubernetes supplies the Job name as the event ID, so retries of a single Job share one occurrence identity. Concurrency policies govern the short event-publisher Job. The target workflow can outlive that Job.
