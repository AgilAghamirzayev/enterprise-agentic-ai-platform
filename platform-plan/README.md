# Workflow definitions

Publish a plan as the `spec` of an agent or workflow version. The control API accepts a JSON object or a string containing YAML. The worker loads the immutable version recorded on the run. References to other published definitions take the form `name:version`.

This plan routes high-risk requests to a reviewer. The `create-ticket` tool also requires approval for the exact ticket arguments. For a simple ticket flow, a single `TOOL` node is sufficient.

```yaml
start: check-risk
invokeRoles: [operator]
allowedTools: [create-ticket]
limits:
  timeoutSeconds: 1800
  maxSteps: 10
  maxToolCalls: 1
nodes:
  - id: check-risk
    type: CONDITION
    expression: 'riskScore > 70'
    onTrue: review
    onFalse: end
  - id: review
    type: APPROVAL
    role: approver
    timeoutSeconds: 600
    onApproved: ticket
    onRejected: end
  - id: ticket
    type: TOOL
    tool: create-ticket
    next: end
  - id: end
    type: END
```

| Node | Configuration and output |
| --- | --- |
| AGENT | An inline `prompt` or pinned `promptRef` produces text. An optional `modelProfileRef` selects a published model profile. An optional `knowledgeBase` adds authorized source chunks and returns an object with `answer` and `citations`. |
| AGENT with agentRef | Invokes another published agent with its own invocation permissions and returns its final value. |
| AGENT with javaRef | Invokes a typed, deployment-installed Java handler. The target worker must register the handler and explicitly bind its version to the tenant. |
| TOOL | Invokes the named tool with the preceding output. Define it in `allowedTools`. The initial node receives the original run input. |
| TOOL_CALLING | Asks the configured model for a JSON object with `tool` and `arguments`. The worker persists the validated proposal, checks tenant policy and dispatches the exact recorded arguments. |
| TRANSFORM | Selects a required top-level `field` from the preceding JSON object. |
| CONDITION | Routes to `onTrue` or `onFalse`. Supply `field` with `equalsValue`, or a comparison such as `riskScore >= 70`. Operators are `==`, `!=`, `>`, `<`, `>=` and `<=`. Ordering requires numeric values. Missing fields and incompatible types take the false branch. |
| SUPERVISOR | Asks the model to choose an ID in `branches`. The specialist receives the supervisor's input. An out-of-list choice ends the run with a denial. |
| PARALLEL and JOIN | Executes up to eight independent branch nodes with identical input. Each branch points directly to the same `JOIN`. The next node receives an object keyed by branch ID. A branch may invoke a pinned agent containing multiple steps. |
| SUB_WORKFLOW | Runs the pinned `workflowRef` in the current execution. Step keys include the parent path. |
| APPROVAL | Waits for the configured `role` to decide, then follows `onApproved` or `onRejected`. Expiration ends the run. |
| WAIT | Pauses for `seconds` using a durable Temporal timer. |
| END | Returns the preceding output or the original input for an empty operation. |

Regular steps use `next` for the successor. A plan may have multiple terminal nodes. Publication rejects cycles, duplicate IDs, missing targets and unreachable nodes. The compiler accepts at most 128 nodes. Check a plan in Java with `PlanCompiler.compile(source)`.

## Execution limits and policy

The default run limits are 64 steps, 12 model calls, 16 tool calls, a delegation depth of 3, 16,384 reserved tokens, 1,024 output tokens per call and a 300-second workflow deadline. For a run cost cap, set `limits.maxCostUsd` and configure model prices. Model attempts, including fallback, reserve budget before inference. Read-only tool retries reuse their step key. Ticket creation consumes the exact matching human approval and writes the ticket and execution evidence in one transaction.

By default, operators, developers and users may invoke a plan. Set `invokeRoles` to restrict a version. Tenant administrators may invoke any version in their tenant. The worker checks these roles again for each delegated definition. Tool calls also require an operator or administrator role, bindings in the root and current plans and an enabled tenant tool policy. A tenant policy can deny a previously allowed tool while its action is awaiting approval.

The demo model echoes input. To exercise model-selected tools locally, pass `{"tool":"uppercase","arguments":{"text":"hello"}}`. For a supervisor, pass `{"route":"billing"}` and include `billing` in its branches.

## Installed Java handlers

Package the handler as part of the worker application and register its annotated Spring bean. Set `PLATFORM_JAVA_AGENTS=acme=greeting:1.0.0` on the worker and publish an `AGENT` node with `javaRef: greeting:1.0.0`. Multiple bindings are comma-separated. A handler receives the authenticated tenant, run UUID, a cancellation check and the run-scoped model gateway. Record application-specific external writes through a tool with an explicit idempotency contract. Handler execution can repeat after worker loss.

Worker traces use `PLATFORM_TRACE_SAMPLE` and `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`. The step, model request, tool execution and knowledge retrieval observations omit prompts and payloads. Run IDs and step keys are trace attributes. Metric labels use bounded categories.

## Reconciling an interrupted run

PostgreSQL stores the run result. Temporal stores orchestration history. The worker retries its final database projection if PostgreSQL is unavailable. The server also leases up to 16 acknowledged, nonterminal run IDs at a time and describes the corresponding workflows in the configured Temporal namespace. Active workflows continue normally. If an execution closed upstream and lacks a terminal business result, the server records `FAILED` with diagnostic code `RECONCILIATION_REQUIRED`. An acknowledged run with missing history receives a one-hour grace period and a namespace check. An unavailable Temporal service defers the check.

Inspect the run timeline, recorded tool evidence and the destination's idempotency record before deciding to submit another request. Reconnect a run event stream with the last received event ID in `Last-Event-ID`. Keep the API and worker pointed at the same Temporal namespace across restarts.
