package io.agentplatform.worker;

import static io.agentplatform.worker.RunActivities.*;

import io.agentplatform.plan.Plan;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.*;
import io.temporal.workflow.*;

import java.time.Duration;
import java.util.*;

/**
 * A deterministic DAG interpreter. Workflow timers and signals wake the same persisted execution.
 */
@io.temporal.spring.boot.WorkflowImpl(taskQueues = "agent-runtime")
public final class AgentRunWorkflow implements AgentRun {
    private static final ActivityOptions OPTIONS =
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setScheduleToCloseTimeout(Duration.ofMinutes(2))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
                    .build();
    private final RunActivities activities = Workflow.newActivityStub(RunActivities.class, OPTIONS);
    // A new inference attempt consumes a new reservation. Completed outputs are reused on
    // redelivery.
    private final RunActivities invocation =
            Workflow.newActivityStub(
                    RunActivities.class,
                    OPTIONS.toBuilder()
                            .setRetryOptions(
                                    RetryOptions.newBuilder().setMaximumAttempts(2).build())
                            .build());
    private static final Set<String> TERMINAL =
            Set.of("DENIED", "CANCELLED", "EXPIRED", "TIMED_OUT", "BUDGET_EXCEEDED");
    // Terminal projection must catch up once PostgreSQL recovers, even after a prolonged outage.
    private final RunActivities projection =
            Workflow.newActivityStub(
                    RunActivities.class,
                    ActivityOptions.newBuilder()
                            .setStartToCloseTimeout(Duration.ofSeconds(30))
                            .setRetryOptions(
                                    RetryOptions.newBuilder()
                                            .setMaximumInterval(Duration.ofMinutes(1))
                                            .build())
                            .build());
    private RunRef run;

    private int signals;
    private int parallelSlots;

    @Override
    public void run(RunRef ref) {
        run = ref;
        String status = "COMPLETED";
        String output = "";
        try {
            activities.begin(ref);
            Graph graph = activities.graph(ref, null);
            var limits = graph.limits();
            long deadline = Workflow.currentTimeMillis() + limits.timeoutSeconds() * 1000L;
            var budget = new Budget(null, graph.limits(), deadline, limits.maxDepth());
            output = walk(graph, null, "", "", budget);
            checkpoint(budget);
        } catch (CanceledFailure cancelled) {
            status = "CANCELLED";
        } catch (RuntimeException failure) {
            status = terminalStatus(failure);
        }
        String terminal = status;
        String result = output;
        Workflow.newDetachedCancellationScope(() -> projection.finish(ref, terminal, result)).run();
    }

    private String walk(
            Graph graph, Definition definition, String prefix, String input, Budget budget) {
        Map<String, Route> routes = new HashMap<>();
        for (Route route : graph.routes()) routes.put(route.id(), route);
        String id = graph.start();
        while (true) {
            Route route = routes.get(id);
            Step step =
                    new Step(
                            run,
                            definition,
                            id,
                            prefix + id,
                            input,
                            budget.deadline,
                            budget.limits);
            takeStep(budget);
            switch (route.type()) {
                case LIMIT -> stop("BUDGET_EXCEEDED");
                case MODEL_DECISION -> {
                    id =
                            invocation.execute(step).equals("answer")
                                    ? route.onTrue()
                                    : route.onFalse();
                    input = step.key();
                }
                case SELECTED_TOOL, TOOL_GATE -> {
                    String outcome = decision(step, budget);
                    if (!outcome.equals("DONE"))
                        stop(outcome.equals("EXPIRED") ? "EXPIRED" : "DENIED");
                    input = step.key();
                    id = route.next();
                }
                case END -> {
                    return input;
                }
                case JOIN -> id = route.next();
                case WAIT -> {
                    long until = Workflow.currentTimeMillis() + route.seconds() * 1000L;
                    while (Workflow.currentTimeMillis() < until)
                        waitForUpdate(until, 60000, budget);
                    id = route.next();
                }
                case APPROVAL -> {
                    String decision = decision(step, budget);
                    if (decision.equals("EXPIRED")) stop("EXPIRED");
                    id = decision.equals("APPROVED") ? route.onApproved() : route.onRejected();
                }
                case CONDITION ->
                        id =
                                activities.execute(step).equals("true")
                                        ? route.onTrue()
                                        : route.onFalse();
                case SUPERVISOR -> {
                    id = invocation.execute(step);
                    if (!route.branches().contains(id)) stop("DENIED");
                }
                case PARALLEL -> {
                    parallelSlots += route.branches().size();
                    if (parallelSlots > budget.limits.maxParallelAgents()) stop("BUDGET_EXCEEDED");
                    List<Promise<String>> pending = new ArrayList<>();
                    for (String branch : route.branches()) {
                        Route child = routes.get(branch);
                        String branchInput = input;
                        pending.add(
                                Async.function(
                                        () -> {
                                            takeStep(budget);
                                            return operation(
                                                    child,
                                                    definition,
                                                    prefix,
                                                    branchInput,
                                                    graph,
                                                    budget);
                                        }));
                    }
                    List<String> outputs = pending.stream().map(Promise::get).toList();
                    parallelSlots -= pending.size();
                    activities.join(run, step.key(), route.branches(), outputs);
                    input = step.key();
                    id = route.next();
                }
                case AGENT, TOOL_CALLING, TOOL, TOOL_RECEIPT, TRANSFORM, SUB_WORKFLOW -> {
                    input = operation(route, definition, prefix, input, graph, budget);
                    id = route.next();
                }
            }
        }
    }

    private String operation(
            Route route,
            Definition definition,
            String prefix,
            String input,
            Graph parent,
            Budget budget) {
        String key = prefix + route.id();
        if (route.child() != null) {
            if (budget.remainingDepth == 0) stop("BUDGET_EXCEEDED");
            Graph child = activities.graph(run, route.child());
            if (!parent.allowedTools().containsAll(child.allowedTools())) stop("DENIED");
            var inherited = budget.limits.restrict(child.limits());
            var childBudget =
                    new Budget(
                            budget,
                            inherited,
                            Math.min(
                                    budget.deadline,
                                    Workflow.currentTimeMillis()
                                            + child.limits().timeoutSeconds() * 1000L),
                            Math.min(budget.remainingDepth - 1, child.limits().maxDepth()));
            return walk(child, route.child(), key + "/", input, childBudget);
        }
        Step step =
                new Step(run, definition, route.id(), key, input, budget.deadline, budget.limits);
        if (route.type() == Plan.Type.TOOL_CALLING) invocation.proposeTool(step);
        if (route.type() == Plan.Type.TOOL || route.type() == Plan.Type.TOOL_CALLING) {
            String disposition = decision(step, budget);
            if (!disposition.equals("DONE"))
                stop(disposition.equals("EXPIRED") ? "EXPIRED" : "DENIED");
        } else {
            invocation.execute(step);
        }
        return key;
    }

    private String decision(Step step, Budget budget) {
        String result = activities.execute(step);
        long interval = 1000;
        while (result.equals("PENDING")) {
            waitForUpdate(budget.deadline, interval, budget);
            interval = Math.min(interval * 2, 300000);
            result = activities.execute(step);
        }
        return result;
    }

    private static final class Budget {
        final Budget parent;
        final Plan.Limits limits;
        final long deadline;
        final int remainingDepth;
        int consumed;

        Budget(Budget parent, Plan.Limits limits, long deadline, int remainingDepth) {
            this.parent = parent;
            this.limits = limits;
            this.deadline = deadline;
            this.remainingDepth = remainingDepth;
        }
    }

    private void takeStep(Budget budget) {
        for (Budget scope = budget; scope != null; scope = scope.parent)
            if (++scope.consumed > scope.limits.maxSteps()) stop("BUDGET_EXCEEDED");
        checkpoint(budget);
    }

    private void checkpoint(Budget budget) {
        if (Workflow.currentTimeMillis() >= budget.deadline) stop("TIMED_OUT");
        activities.check(run);
    }

    private void waitForUpdate(long until, long interval, Budget budget) {
        int generation = signals;
        long millis =
                Math.min(interval, Math.min(until, budget.deadline) - Workflow.currentTimeMillis());
        if (millis > 0) Workflow.await(Duration.ofMillis(millis), () -> signals != generation);
        checkpoint(budget);
    }

    @Override
    public void wake() {
        signals++;
    }

    private static String terminalStatus(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof CanceledFailure) return "CANCELLED";
            if (cause instanceof TimeoutFailure) return "TIMED_OUT";
            if (cause instanceof ApplicationFailure app && TERMINAL.contains(app.getType()))
                return app.getType();
        }
        return "FAILED";
    }

    static void stop(String status) {
        throw ApplicationFailure.newNonRetryableFailure(status, status);
    }
}
