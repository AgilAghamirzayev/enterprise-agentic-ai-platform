package io.agentplatform.worker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentplatform.application.AgentRegistry;
import io.agentplatform.domain.AgentDescriptor;
import io.agentplatform.plan.PlanCompiler;
import io.agentplatform.sdk.*;
import io.agentplatform.spi.*;
import io.agentplatform.store.PlatformStore;
import io.temporal.failure.ApplicationFailure;

import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

class JavaHandlersTest {
    private final ObjectMapper json = new ObjectMapper();
    private final PlatformStore store = mock(PlatformStore.class);
    private final ModelSteps models = mock(ModelSteps.class);
    private final String id = UUID.randomUUID().toString();
    private final AgentDescriptor ref = new AgentDescriptor("greeting", "1.0.0");
    private final AtomicReference<AgentExecutionContext> context = new AtomicReference<>();
    private final AgentDefinition<String, String> handler =
            new AgentDefinition<>(
                    ref,
                    String.class,
                    String.class,
                    (text, ctx) -> {
                        context.set(ctx);
                        return ctx.models().complete(new ModelRequest("Hello", text, 32)).text();
                    });
    private final JavaHandlers handlers =
            new JavaHandlers(
                    new AgentRegistry(List.of(handler)), store, json, "acme=greeting:1.0.0");
    private final StepContext step;

    JavaHandlersTest() {
        var plan =
                PlanCompiler.parse(
                        """
                        start: call
                        nodes:
                          - {id: call,type: AGENT,javaRef: 'greeting:1.0.0',next: end}
                          - {id: end,type: END}
                        """);
        var run = json.createObjectNode().put("status", "RUNNING");
        step =
                new StepContext(
                        new AgentRun.RunRef("acme", id),
                        "call",
                        run,
                        plan,
                        plan,
                        plan.node("call"),
                        json.getNodeFactory().textNode("reader"));
        when(store.runControl("acme", id)).thenReturn(run);
    }

    @Test
    void trustedJavaHandlerUsesGovernedModelAndContextExpiresAtReturn() {
        when(models.infer(eq(step), eq("call@model-1"), any(ModelRequest.class)))
                .thenReturn(new ModelResult("Welcome", 4, 1));
        handlers.execute(step, models, mock(ToolExecutor.class));
        verify(store).saveStep("acme", id, "call", json.getNodeFactory().textNode("Welcome"), 0, 0);
        assertEquals(UUID.fromString(id), context.get().runId());
        assertThrows(ApplicationFailure.class, context.get()::checkCancellation);
    }

    @Test
    void resourceReadsUseOnlyBoundTenantAndRunScopeAndRecordEvidence() throws Exception {
        var queryAgent = new AgentDefinition<>(ref, String.class, String.class, (input, ctx) -> {
            context.set(ctx);
            var doc = ctx.knowledge().search(input, 2).getFirst();
            var message = ctx.memory().recent(3).getFirst();
            return doc.documentId() + ":" + doc.chunkId() + ":" + message.content();
        });
        var service = new JavaHandlers(new AgentRegistry(List.of(queryAgent)), store, json, "acme=greeting:1.0.0");
        var plan = PlanCompiler.parse("""
                start: find
                nodes:
                  - {id: find, type: AGENT, javaRef: 'greeting:1.0.0', knowledgeBase: support, next: end}
                  - {id: end, type: END}
                """);
        var run = json.createObjectNode();
        run.putArray("roles").add("operator");
        var ctx = new StepContext(step.ref(), "find", run, plan, plan, plan.node("find"), step.input());
        when(store.searchForRun("acme", id, "find@knowledge-1", "support", "reader", List.of("operator"), 2))
                .thenReturn(List.of(json.readTree("""
                        {"documentId":"doc1","chunkId":1,"title":"Guide","digest":"sha","text":"evidence"}
                        """)));
        when(store.messagesForRun("acme", id, 3))
                .thenReturn(List.of(json.readTree("""
                        {"role":"user","content":"earlier"}
                        """)));
        service.execute(ctx, models, mock(ToolExecutor.class));
        verify(store).saveStep("acme", id, "find", json.getNodeFactory().textNode("doc1:1:earlier"), 0, 0);
        assertThrows(ApplicationFailure.class, () -> context.get().memory().recent(1));
    }

    @Test
    void rejectsMissingTenantBindingBeforeInvokingJava() {
        var other =
                new StepContext(
                        new AgentRun.RunRef("other", id),
                        step.key(),
                        step.run(),
                        step.root(),
                        step.plan(),
                        step.node(),
                        step.input());
        assertThrows(ApplicationFailure.class, () -> handlers.execute(other, models, mock(ToolExecutor.class)));
        verifyNoInteractions(models);
    }
}
