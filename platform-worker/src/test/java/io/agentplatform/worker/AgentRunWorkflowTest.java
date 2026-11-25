package io.agentplatform.worker;

import static io.agentplatform.worker.RunActivities.*;

import static org.junit.jupiter.api.Assertions.*;

import io.agentplatform.plan.Plan;
import io.temporal.client.*;
import io.temporal.testing.*;
import io.temporal.worker.Worker;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

class AgentRunWorkflowTest {
    static final String QUEUE = "execution-test";
    static final AgentRun.RunRef REF = new AgentRun.RunRef("company", "run-123");
    static final Plan.Limits LIMITS = new Plan.Limits(5, 8192, 512, 60, 32, 3, 4, null);

    @Test
    void replaysParallelStepsAndApprovalHistoryWithoutRerunningActivities() throws Exception {
        var activities = new TestActivities();
        try (var env = TestWorkflowEnvironment.newInstance()) {
            var worker = env.newWorker(QUEUE);
            register(worker, activities);
            env.start();
            var wf = stub(env);
            var execution = WorkflowClient.start(wf::run, REF);
            assertTrue(activities.waiting.await(10, TimeUnit.SECONDS));
            activities.approved.set(true);
            wf.wake();
            WorkflowStub.fromTyped(wf).getResult(15, TimeUnit.SECONDS, Void.class);
            assertEquals("COMPLETED", activities.status.get());
            assertEquals(List.of("a", "b"), activities.joined);
            int before = activities.calls.get();
            WorkflowReplayer.replayWorkflowExecution(
                    env.getWorkflowExecutionHistory(execution), AgentRunWorkflow.class);
            assertEquals(before, activities.calls.get());
        }
    }

    @Test
    void remainsCompatibleWithPublishedV1History() throws Exception {
        try (var file = getClass().getResourceAsStream("/replay/agent-run-v1.json.gz");
                var stream = new java.util.zip.GZIPInputStream(Objects.requireNonNull(file))) {
            String history =
                    new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            WorkflowReplayer.replayWorkflowExecution(history, AgentRunWorkflow.class);
        }
    }

    @Test
    void persistedWorkflowFinishesOnReplacementWorkerAfterPollerLoss() throws Exception {
        var activities = new TestActivities();
        try (var temporal =
                new org.testcontainers.containers.GenericContainer<>("temporalio/temporal:1.5.1")
                        .withExposedPorts(7233)
                        .withCommand("server", "start-dev", "--headless", "--ip", "0.0.0.0")) {
            temporal.start();
            var options =
                    TestEnvironmentOptions.newBuilder()
                            .setUseExternalService(true)
                            .setTarget(temporal.getHost() + ":" + temporal.getMappedPort(7233))
                            .build();
            try (var env = TestWorkflowEnvironment.newInstance(options)) {
                var first =
                        env.newWorker(
                                QUEUE,
                                io.temporal.worker.WorkerOptions.newBuilder()
                                        .setIdentity("first-worker")
                                        .setStickyQueueScheduleToStartTimeout(
                                                java.time.Duration.ofMillis(200))
                                        .build());
                register(first, activities);
                env.start();
                var wf = stub(env);
                var execution = WorkflowClient.start(wf::run, REF);
                assertTrue(activities.waiting.await(10, TimeUnit.SECONDS));
                org.awaitility.Awaitility.await()
                        .atMost(java.time.Duration.ofSeconds(5))
                        .until(
                                () ->
                                        env
                                                .getWorkflowExecutionHistory(execution)
                                                .getEvents()
                                                .stream()
                                                .anyMatch(
                                                        event ->
                                                                event
                                                                        .hasTimerStartedEventAttributes()));
                env.getWorkerFactory().shutdownNow();
                env.getWorkerFactory().awaitTermination(10, TimeUnit.SECONDS);
                // The second factory has an empty workflow cache and replays the service's history.
                var replacement =
                        io.temporal.worker.WorkerFactory.newInstance(env.getWorkflowClient());
                register(
                        replacement.newWorker(
                                QUEUE,
                                io.temporal.worker.WorkerOptions.newBuilder()
                                        .setIdentity("replacement-worker")
                                        .build()),
                        activities);
                replacement.start();
                try {
                    activities.approved.set(true);
                    wf.wake();
                    try {
                        WorkflowStub.fromTyped(wf).getResult(10, TimeUnit.SECONDS, Void.class);
                    } catch (TimeoutException timeout) {
                        throw new AssertionError(env.getDiagnostics(), timeout);
                    }
                    assertEquals("COMPLETED", activities.status.get());
                    assertEquals(1, activities.executed.get("a").get());
                    assertEquals(1, activities.executed.get("b").get());
                } finally {
                    replacement.suspendPolling();
                    replacement.shutdownNow();
                }
            }
        }
    }

    @Test
    void expiredApprovalAndCancellationPreventFollowingToolWork() {
        for (String reason : List.of("EXPIRED", "CANCELLED")) {
            var activities = new TestActivities();
            activities.blocked = reason;
            try (var env = TestWorkflowEnvironment.newInstance()) {
                register(env.newWorker(QUEUE), activities);
                env.start();
                stub(env).run(REF);
                assertEquals(reason, activities.status.get());
                assertFalse(activities.executed.containsKey("a"));
            }
        }
    }

    private static AgentRun stub(TestWorkflowEnvironment env) {
        return env.getWorkflowClient()
                .newWorkflowStub(
                        AgentRun.class,
                        WorkflowOptions.newBuilder()
                                .setTaskQueue(QUEUE)
                                .setWorkflowId(REF.runId())
                                .build());
    }

    private static void register(Worker worker, RunActivities activities) {
        worker.registerWorkflowImplementationTypes(AgentRunWorkflow.class);
        worker.registerActivitiesImplementations(activities);
    }

    static class TestActivities implements RunActivities {
        final CountDownLatch waiting = new CountDownLatch(1);
        final AtomicBoolean approved = new AtomicBoolean();
        final AtomicReference<String> status = new AtomicReference<>();
        final AtomicInteger calls = new AtomicInteger();
        final Map<String, AtomicInteger> executed = new ConcurrentHashMap<>();
        volatile List<String> joined;
        volatile String blocked;

        @Override
        public void begin(AgentRun.RunRef ref) {}

        @Override
        public Graph graph(AgentRun.RunRef ref, Definition def) {
            return new Graph(
                    "gate",
                    List.of(
                            new Route(
                                    "gate",
                                    Plan.Type.APPROVAL,
                                    null,
                                    null,
                                    null,
                                    "fork",
                                    "end",
                                    List.of(),
                                    0,
                                    null),
                            new Route(
                                    "fork",
                                    Plan.Type.PARALLEL,
                                    "join",
                                    null,
                                    null,
                                    null,
                                    null,
                                    List.of("a", "b"),
                                    0,
                                    null),
                            route("a", Plan.Type.AGENT, "join"),
                            route("b", Plan.Type.AGENT, "join"),
                            route("join", Plan.Type.JOIN, "end"),
                            route("end", Plan.Type.END, null)),
                    LIMITS,
                    List.of());
        }

        private static Route route(String id, Plan.Type type, String next) {
            return new Route(id, type, next, null, null, null, null, List.of(), 0, null);
        }

        @Override
        public void check(AgentRun.RunRef ref) {
            if ("CANCELLED".equals(blocked)) AgentRunWorkflow.stop("CANCELLED");
        }

        @Override
        public String execute(Step step) {
            calls.incrementAndGet();
            if (step.node().equals("gate")) {
                waiting.countDown();
                if (blocked != null) return blocked;
                return approved.get() ? "APPROVED" : "PENDING";
            }
            executed.computeIfAbsent(step.node(), unused -> new AtomicInteger()).incrementAndGet();
            return "DONE";
        }

        @Override
        public void proposeTool(Step step) {}

        @Override
        public void join(
                AgentRun.RunRef run, String key, List<String> branches, List<String> outputs) {
            joined = outputs;
        }

        @Override
        public void finish(AgentRun.RunRef run, String terminal, String key) {
            status.set(terminal);
        }
    }
}
