package io.agentplatform.application;

import io.agentplatform.domain.AgentDescriptor;
import io.agentplatform.domain.ExecutionLimits;
import io.agentplatform.sdk.Agent;
import io.agentplatform.sdk.AgentDefinition;
import io.agentplatform.sdk.AgentExecutionContext;
import io.agentplatform.spi.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LocalExecutionEngineTest {
    private static final AgentDescriptor ID = new AgentDescriptor("hello-agent", "1.0.0");
    private static final ModelRequest REQUEST = new ModelRequest("return", "hello", 10);
    private static final ModelGateway MODEL = request -> new ModelResult("ok", 3, 1);
    private static final ExecutionLimits LIMITS = new ExecutionLimits(1, 100, Duration.ofSeconds(2));

    private static LocalExecutionEngine create(Agent<String, String> agent, ModelGateway gateway) {
        var definition = new AgentDefinition<>(ID, String.class, String.class, agent);
        return new LocalExecutionEngine(new AgentRegistry(List.of(definition)), gateway, 1);
    }

    private static ExecutionRejected.Reason failure(LocalRun<?> run) {
        var ex = assertThrows(CompletionException.class, () -> run.result().toCompletableFuture().join());
        return assertInstanceOf(ExecutionRejected.class, ex.getCause()).reason();
    }

    @Test
    void rejectsInvalidTypesAndVersionsBeforeCallingAgent() {
        var engine = create((input, context) -> fail("must not invoke handler"), MODEL);
        assertThrows(IllegalArgumentException.class, () -> engine.start("a", ID, 45, String.class, LIMITS));
        assertThrows(IllegalArgumentException.class, () -> engine.start("a", ID, "x", Integer.class, LIMITS));
        var unknown = new AgentDescriptor(ID.name(), "2.0.0");
        assertThrows(IllegalArgumentException.class, () -> engine.start("a", unknown, "x", String.class, LIMITS));
    }

    @Test
    void reservesInputAndOutputBeforeCallingModel() {
        var engine = create((input, ctx) -> ctx.models().complete(REQUEST).text(), request -> fail("provider was invoked"));
        var limits = new ExecutionLimits(1, REQUEST.estimatedInputTokens() + REQUEST.maxOutputTokens() - 1, Duration.ofSeconds(1));
        assertEquals(ExecutionRejected.Reason.BUDGET_EXCEEDED, failure(engine.start("a", ID, "input", String.class, limits)));
    }

    @Test
    void enforcesCallCountEvenWhenHandlerCatchesRejection() {
        var invoked = new AtomicInteger();
        var engine = create((input, ctx) -> {
            ctx.models().complete(REQUEST);
            assertThrows(ExecutionRejected.class, () -> ctx.models().complete(REQUEST));
            return "ignored";
        }, request -> {
            invoked.incrementAndGet();
            return MODEL.complete(request);
        });
        assertEquals(ExecutionRejected.Reason.BUDGET_EXCEEDED, failure(engine.start("a", ID, "input", String.class, LIMITS)));
        assertEquals(1, invoked.get());
    }

    @Test
    void overReportingProviderTripsBudget() {
        var engine = create((input, ctx) -> ctx.models().complete(REQUEST).text(), request -> new ModelResult("ok", 101, 1));
        assertEquals(ExecutionRejected.Reason.BUDGET_EXCEEDED, failure(engine.start("a", ID, "input", String.class, LIMITS)));
    }

    @Test
    void retainedContextCannotInvokeModelAfterCompletion() {
        var retained = new AtomicReference<AgentExecutionContext>();
        var engine = create((input, ctx) -> {
            retained.set(ctx);
            return input;
        }, request -> fail("provider was invoked"));
        var run = engine.start("tenant-a", ID, "input", String.class, LIMITS);
        assertEquals("input", run.result().toCompletableFuture().join());
        assertEquals("tenant-a", retained.get().tenantId());
        assertEquals(run.id(), retained.get().runId());
        assertThrows(ExecutionRejected.class, () -> retained.get().models().complete(REQUEST));
        assertFalse(run.cancel());
    }

    @Test
    void rejectsOverloadAndInterruptsActiveRunOnCancel() throws Exception {
        var started = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        var engine = create((input, ctx) -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException expected) {
                Thread.currentThread().interrupt();
            } finally {
                exited.countDown();
            }
            return input;
        }, MODEL);
        var run = engine.start("a", ID, "input", String.class, LIMITS);
        assertTrue(started.await(1, TimeUnit.SECONDS));
        var busy = assertThrows(ExecutionRejected.class, () -> engine.start("b", ID, "input", String.class, LIMITS));
        assertEquals(ExecutionRejected.Reason.BUSY, busy.reason());
        assertTrue(run.cancel());
        assertEquals(ExecutionRejected.Reason.CANCELLED, failure(run));
        assertTrue(exited.await(1, TimeUnit.SECONDS));
        assertFalse(run.cancel());
    }

    @Test
    void deadlineReleasesCallerDuringBlockingInvocation() {
        var engine = create((input, ctx) -> {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return input;
        }, MODEL);
        var limits = new ExecutionLimits(0, 0, Duration.ofMillis(50));
        assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertEquals(ExecutionRejected.Reason.TIMEOUT, failure(engine.start("a", ID, "x", String.class, limits))));
    }
}
