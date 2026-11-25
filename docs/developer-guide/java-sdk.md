# Embed a Java agent

The SDK runs inside a Java 21 process. Use it to test a typed handler or embed one in an existing application. For durable, remotely submitted runs, publish a workflow definition through the platform API. Deploy a custom worker tool when a workflow needs to invoke company Java code.

## Run the example

From the repository root with JDK 21, build and run the example.

```sh
./mvnw -pl examples/hello-agent -am verify
java -jar examples/hello-agent/target/hello-agent-0.1.0.jar Ada
```

The last line is `Hello, Ada!`. The example supplies a deterministic `ModelGateway` bean and runs offline after the Maven dependencies are cached.

## Add a handler

Install the libraries once when working from a source checkout.

```sh
./mvnw -pl platform-bom,agent-spring-boot-starter,platform-testkit -am install
```

Add `io.agentplatform:agent-spring-boot-starter:0.1.0` to your Spring Boot application. For plain Java, depend on `io.agentplatform:platform-application:0.1.0` and construct the registry and engine directly. Import `io.agentplatform:platform-bom:0.1.0` to manage these library versions.

Place a handler in a package scanned by the application. Declare concrete input and output types so the starter can check them at registration. A handler that accepts several fields can take a record.

```java
@AiAgent(name = "summarize-ticket", version = "1.0.0")
public class TicketAgent implements Agent<String, String> {
    @Override
    public String execute(String ticket, AgentExecutionContext context) {
        var prompt = new ModelRequest("Summarize the ticket in one sentence.", ticket, 128);
        return context.models().complete(prompt).text();
    }
}
```

The annotation lives in `io.agentplatform.boot`, the handler contracts in `io.agentplatform.sdk`, and model contracts in `io.agentplatform.spi`. Inject `LocalExecutionEngine` and pass a tenant obtained from your application's authenticated session.

```java
var agent = new AgentDescriptor("summarize-ticket", "1.0.0");
var limits = new ExecutionLimits(1, 2048, Duration.ofSeconds(20));
var run = engine.start(tenantId, agent, ticketText, String.class, limits);
CompletionStage<String> response = run.result();
```

Each invocation fixes its agent version. Duplicate registrations cause startup to fail. Handler instances can serve several calls concurrently, so keep invocation data in method-local variables.

## Model integration

Provide a Spring AI `ChatModel` bean or configure `spring-ai-starter-model-openai` using its Spring Boot properties. With one eligible model the starter supplies `SpringAiModelGateway`. An application-provided `ModelGateway` can route calls. The adapter separates system instructions from user input, passes the requested completion limit to the provider, and disables internal tool execution.

For structured results, construct a `StructuredOutput<MyRecord>` once with a target record and validator. Call `contract.complete(context.models(), request)` in the handler. The converter supplies schema instructions and validates the JSON and the decoded value. Account for the schema instructions in the token budget.

The local engine reserves input and output allowance before the call, then reconciles the reservation against reported usage. Ambiguous failures keep their reservation. When the provider omits usage, its adapter estimates by UTF-8 byte length. The provider determines actual billing.

## Cancellation and load

`run.cancel()` completes the caller's future and interrupts an active handler. Long CPU loops should call `context.checkCancellation()`. Set network timeouts in custom gateways. An uncooperative Java handler can keep its slot until it returns. Requests get `ExecutionRejected` with reason `BUSY` when all slots are occupied. Set `agentplatform.local.max-concurrent-runs` to adjust the default of 16.

Use `platform-testkit` for deterministic tests. Its default fake echoes input. Its callback constructor accepts a scripted response per request.

## Version prompts and models for durable runs

A tenant developer publishes an immutable system prompt with `POST /api/v1/prompts`. Send a JSON object such as `{"name":"triage","version":"1.0.0","text":"Classify the support ticket."}`. A tenant administrator can publish a model profile at `POST /api/v1/model-profiles` with `{"name":"support","version":"1.0.0","model":"fast","fallbackModel":"accurate"}`. These example model IDs must exist in the deployment inventory. A duplicate name and version returns HTTP 409.

Refer to both versions in an agent or workflow node:

```yaml
start: classify
nodes:
  - id: classify
    type: AGENT
    promptRef: triage:1.0.0
    modelProfileRef: support:1.0.0
    next: finish
  - id: finish
    type: END
```

Publication resolves both references in the authenticated tenant. The worker retrieves their immutable definitions when executing the node. A provider transport failure can trigger one configured fallback. Both attempts require a separate call, token and cost reservation. Unknown model IDs, malformed results and policy denials fail the step.

A deployment owner sets the same model inventory on server and worker. Use `PLATFORM_MODEL=openai`, `OPENAI_MODEL=fast` and `PLATFORM_ALLOWED_MODELS=accurate` for the example profile. The worker resolves IDs to Spring AI calls against the operator-configured endpoint. For a custom provider, supply a `ModelBinding` worker bean for an allowed ID and implement the `ModelGateway` SPI. The worker also supports a fully offline `PLATFORM_MODEL=demo` with primary ID `echo`.

## Read invocation-scoped resources

A Java handler can call `context.knowledge().search(query, limit)`, `context.memory().recent(limit)` and `context.tools().read(name, argumentsJson)`. Knowledge results carry document and chunk IDs, title, digest and text. Memory returns role and content in chronological order. The tool gateway accepts and returns JSON strings.

For a worker handler, configure `knowledgeBase` on its `javaRef` node. The worker fixes the knowledge base, tenant, actor, ACLs and session from the authenticated run. Search records evidence through the same store operation as declarative RAG. The tool must be read-only, installed for that tenant, and allowed by both the root plan and current plan. An operator role and the current tool policy still apply. Use a declarative TOOL node when the operation requires an approval wait or a write.

A handler can perform up to 64 resource reads per invocation, each search can return up to eight chunks, and a memory read can return up to 20 messages. Tool budgets also apply in the worker. Retained handles expire when a handler returns. Embedded runs deny unconfigured resource access. To test a handler, pass `ResourceFixtures.of(searches, messages, toolResults)` as the last argument to `LocalExecutionEngine.start`. The maps contain only the data and tool responses that invocation should see.

## Test agent behavior without infrastructure

`AgentTestRuntime` runs a typed agent through the same local registry, cancellation checks and budget guards as the starter. Configure resource fixtures for the interactions the test expects. Assert on one run's model calls, successful tool calls and denied calls. The following test denies a configured fixture before execution.

```java
var descriptor = new AgentDescriptor("lookup", "1.0.0");
var agent = new AgentDefinition<>(descriptor, String.class, String.class,
        (input, ctx) -> ctx.tools().read("lookup", input));
var call = new ResourceFixtures.Read("lookup", "{}");
var resources = ResourceFixtures.of(Map.of(), List.of(), Map.of(call, "fixture data"));
var result = AgentTestRuntime.builder()
        .resources(resources)
        .toolPolicy(read -> false)
        .build()
        .run(agent, "{}");

assertEquals(AgentTestRuntime.Status.DENIED, result.status());
assertEquals(List.of(call), result.deniedTools());
assertTrue(result.executedTools().isEmpty());
```

The Spring adapter implements `ModelProvider`, an SPI with `providerId()`, `capabilities()`, `generate(request)` and `stream(request)`. A streaming subscription uses Java's `Flow.Publisher<ModelChunk>`. Each chunk carries a text delta and the cumulative usage supplied on that event. Cancel the subscription to stop upstream generation. The adapter rejects native tool-call chunks, over-limit reported output, oversized text and streams idle for 30 seconds. Worker activities persist complete responses so durable retries have a single step result.
