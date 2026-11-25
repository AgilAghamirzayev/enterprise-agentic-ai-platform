package io.agentplatform.worker;

import static io.agentplatform.worker.AgentRun.RunRef;

import com.fasterxml.jackson.databind.*;

import io.agentplatform.connectors.ExternalTool;
import io.agentplatform.plan.*;
import io.agentplatform.store.*;
import io.micrometer.core.instrument.MeterRegistry;
import io.temporal.failure.ApplicationFailure;
import io.temporal.spring.boot.ActivityImpl;

import java.util.List;
import java.util.function.Supplier;

@ActivityImpl(taskQueues = "agent-runtime")
public final class StoredActivities implements RunActivities {
    private final PlatformStore store;
    private final ObjectMapper json;
    private final ModelSteps models;
    private final ToolExecutor tools;
    private final JavaHandlers javaHandlers;
    private final io.micrometer.observation.ObservationRegistry observations;
    private final MeterRegistry metrics;

    StoredActivities(
            PlatformStore store,
            ObjectMapper json,
            ModelRoutes model,
            List<ExternalTool> connectors,
            MeterRegistry metrics,
            JavaHandlers javaHandlers,
            io.micrometer.observation.ObservationRegistry observations) {
        this.observations = observations;
        this.javaHandlers = javaHandlers;
        this.store = store;
        this.json = json;
        this.metrics = metrics;
        models = new ModelSteps(store, json, model, metrics, observations);
        tools = new ToolExecutor(store, json, connectors, metrics);
    }

    @Override
    public void begin(RunRef ref) {
        store.begin(ref.tenant(), ref.runId());
    }

    @Override
    public Graph graph(RunRef ref, Definition definition) {
        return guarded(
                () -> {
                    var run = active(ref);
                    var plan = definition == null ? rootPlan(run) : published(ref, definition);
                    var roles =
                            java.util.stream.StreamSupport.stream(
                                            run.path("roles").spliterator(), false)
                                    .map(JsonNode::asText)
                                    .toList();
                    if (!plan.permitsInvocation(roles)) AgentRunWorkflow.stop("DENIED");
                    var routes = plan.nodes().stream().map(StoredActivities::route).toList();
                    return new Graph(plan.start(), routes, plan.limits(), plan.allowedTools());
                });
    }

    private static Route route(Plan.Node node) {
        Definition child = null;
        if (node.type() == Plan.Type.SUB_WORKFLOW)
            child = new Definition("workflow", node.workflowRef());
        else if (node.agentRef() != null && node.type() == Plan.Type.AGENT)
            child = new Definition("agent", node.agentRef());
        return new Route(
                node.id(),
                node.type(),
                node.next(),
                node.onTrue(),
                node.onFalse(),
                node.onApproved(),
                node.onRejected(),
                node.branches(),
                node.seconds() == null ? 0 : node.seconds(),
                child);
    }

    private Plan published(RunRef ref, Definition definition) {
        String[] coordinates = definition.ref().split(":", 2);
        JsonNode metadata =
                store.definition(ref.tenant(), definition.kind(), coordinates[0], coordinates[1]);
        return PlanCompiler.parse(metadata.get("spec").toString());
    }

    private Plan rootPlan(JsonNode run) {
        return PlanCompiler.parse(run.get("plan").toString());
    }

    @Override
    public void check(RunRef ref) {
        checkActive(store.runControl(ref.tenant(), ref.runId()));
    }

    private JsonNode active(RunRef ref) {
        JsonNode run = store.run(ref.tenant(), ref.runId());
        checkActive(run);
        return run;
    }

    private static void checkActive(JsonNode run) {
        if (run.path("cancelRequested").asBoolean()) AgentRunWorkflow.stop("CANCELLED");
        if (!run.path("status").asText().equals("RUNNING"))
            throw ApplicationFailure.newNonRetryableFailure("Run inactive", "INACTIVE");
    }

    @Override
    public void proposeTool(Step step) {
        observed(
                step,
                ctx -> {
                    models.propose(ctx);
                    return null;
                });
    }

    @Override
    public String execute(Step step) {
        return observed(step, this::execute);
    }

    private <T> T observed(Step step, java.util.function.Function<StepContext, T> operation) {
        return guarded(
                () -> {
                    var ctx = context(step);
                    try (var parent =
                            RunTraceContext.restore(ctx.run().path("traceparent").asText(null))) {
                        return io.micrometer.observation.Observation.createNotStarted(
                                        "agent.step", observations)
                                .lowCardinalityKeyValue("type", ctx.node().type().name())
                                .highCardinalityKeyValue(
                                        "langfuse.observation.type",
                                        ctx.node().type() == Plan.Type.AGENT ? "agent" : "span")
                                .highCardinalityKeyValue(
                                        "langfuse.trace.name",
                                        ctx.run().path("kind").asText()
                                                + "/"
                                                + ctx.run().path("name").asText())
                                .highCardinalityKeyValue(
                                        "langfuse.version", ctx.run().path("version").asText())
                                .highCardinalityKeyValue(
                                        "langfuse.trace.metadata.runId", ctx.runId())
                                .highCardinalityKeyValue("agent.run.id", ctx.runId())
                                .highCardinalityKeyValue("agent.step.key", ctx.key())
                                .observe(() -> operation.apply(ctx));
                    }
                });
    }

    private String execute(StepContext ctx) {
        var n = ctx.node();
        return switch (n.type()) {
            case MODEL_DECISION -> models.decide(ctx);
            case TOOL_GATE -> tools.authorizeDelegate(ctx);
            case TOOL_RECEIPT -> {
                tools.receipt(ctx);
                yield "DONE";
            }
            case SELECTED_TOOL ->
                    invokeTool(
                            ctx,
                            ctx.input().required("tool").asText(),
                            ctx.input().required("arguments"));
            case APPROVAL -> tools.approval(ctx, "review", ctx.input());
            case TOOL -> invokeTool(ctx, n.tool(), ctx.input());
            case TOOL_CALLING -> {
                var proposal =
                        store.step(ctx.tenant(), ctx.runId(), ctx.key() + "@proposal")
                                .orElseThrow();
                yield invokeTool(ctx, proposal.path("tool").asText(), proposal.get("arguments"));
            }
            case CONDITION -> Boolean.toString(Condition.compile(n).test(ctx.input()));
            case SUPERVISOR -> models.route(ctx);
            case TRANSFORM -> {
                if (!ctx.input().has(n.field())) ToolExecutor.invalid();
                store.saveStep(
                        ctx.tenant(), ctx.runId(), ctx.key(), ctx.input().get(n.field()), 0, 0);
                yield "DONE";
            }
            case AGENT -> {
                if (n.javaRef() != null) javaHandlers.execute(ctx, models, tools);
                else if (store.step(ctx.tenant(), ctx.runId(), ctx.key()).isEmpty())
                    models.generate(ctx);
                yield "DONE";
            }
            default ->
                    throw ApplicationFailure.newNonRetryableFailure(
                            "Invalid operation", "INVALID_INPUT");
        };
    }

    private String invokeTool(StepContext ctx, String tool, JsonNode arguments) {
        return io.micrometer.observation.Observation.createNotStarted(
                        "tool.execution.duration", observations)
                .highCardinalityKeyValue("langfuse.observation.type", "tool")
                .highCardinalityKeyValue("tool.name", tool)
                .observe(() -> tools.dispatch(ctx, tool, arguments));
    }

    private StepContext context(Step step) {
        if (step.deadline() > 0 && System.currentTimeMillis() >= step.deadline())
            AgentRunWorkflow.stop("TIMED_OUT");
        var run = active(step.run());
        var root = rootPlan(run);
        var plan = step.definition() == null ? root : published(step.run(), step.definition());
        root = authority(step, root, plan);
        var input = input(step.run(), run, step.inputKey());
        return new StepContext(
                step.run(), step.key(), run, root, plan, plan.node(step.node()), input);
    }

    private Plan authority(Step step, Plan root, Plan leaf) {
        boolean javaHandler = leaf.node(step.node()).javaRef() != null;
        if (javaHandler && !root.memory().permitsJavaHistory()) ToolExecutor.deny();
        var scope = root;
        var policies = new java.util.LinkedHashSet<>(root.policyRefs());
        var memory = root.memory();
        // The durable step key records each delegation node id in invocation order.
        // Resolve its immutable references locally so old workflow histories need no new activity.
        var path = step.key().split("/");
        for (int i = 0; i < path.length - 1; i++) {
            var node = scope.node(path[i]);
            var delegated = route(node).child();
            if (delegated == null) ToolExecutor.deny();
            scope =
                    i == path.length - 2 && delegated.equals(step.definition())
                            ? leaf
                            : published(step.run(), delegated);
            if (javaHandler && !scope.memory().permitsJavaHistory()) ToolExecutor.deny();
            policies.addAll(scope.policyRefs());
            var childMemory = scope.memory();
            memory =
                    new Plan.Memory(
                            memory.type().equals("NONE") ? "NONE" : childMemory.type(),
                            Math.min(memory.maxMessages(), childMemory.maxMessages()));
        }
        var limits =
                step.ceiling() == null ? root.limits() : root.limits().restrict(step.ceiling());
        return new Plan(
                root.start(),
                root.nodes(),
                root.allowedTools(),
                limits,
                root.invokeRoles(),
                List.copyOf(policies),
                memory,
                root.description());
    }

    private JsonNode input(RunRef ref, JsonNode run, String key) {
        if (key.isEmpty()) return run.get("input");
        return store.step(ref.tenant(), ref.runId(), key)
                .orElseThrow(
                        () ->
                                ApplicationFailure.newNonRetryableFailure(
                                        "Missing step output", "MISSING_ARTIFACT"));
    }

    @Override
    public void join(RunRef ref, String key, List<String> branches, List<String> outputs) {
        guarded(
                () -> {
                    var run = active(ref);
                    var joined = json.createObjectNode();
                    for (int i = 0; i < branches.size(); i++)
                        joined.set(branches.get(i), input(ref, run, outputs.get(i)));
                    store.saveStep(ref.tenant(), ref.runId(), key, joined, 0, 0);
                    return null;
                });
    }

    @Override
    public void finish(RunRef ref, String status, String resultKey) {
        var run = store.run(ref.tenant(), ref.runId());
        boolean success = "COMPLETED".equals(status) && !run.path("cancelRequested").asBoolean();
        var output = success ? input(ref, run, resultKey) : null;
        store.finish(ref.tenant(), ref.runId(), status, output);
    }

    private <T> T guarded(Supplier<T> action) {
        try {
            return action.get();
        } catch (ApplicationFailure rejected) {
            if ("BUDGET_EXCEEDED".equals(rejected.getType()))
                metrics.counter("execution.budget.exceeded").increment();
            throw rejected;
        } catch (StoreException e) {
            if (e.status() == 429) metrics.counter("execution.budget.exceeded").increment();
            String code =
                    switch (e.status()) {
                        case 429 -> "BUDGET_EXCEEDED";
                        case 403, 404 -> "DENIED";
                        default -> "STORE_CONFLICT";
                    };
            throw ApplicationFailure.newNonRetryableFailure("Store rejected operation", code);
        } catch (IllegalArgumentException e) {
            throw ApplicationFailure.newNonRetryableFailure(
                    "Invalid step arguments", "INVALID_INPUT");
        }
    }
}
