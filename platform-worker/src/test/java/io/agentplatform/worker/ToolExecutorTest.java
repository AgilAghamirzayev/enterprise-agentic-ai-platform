package io.agentplatform.worker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;

import io.agentplatform.connectors.ExternalTool;
import io.agentplatform.plan.PlanCompiler;
import io.agentplatform.store.PlatformStore;
import io.temporal.failure.ApplicationFailure;

import org.junit.jupiter.api.*;

import java.util.*;

class ToolExecutorTest {
    final ObjectMapper mapper = new ObjectMapper();
    final PlatformStore store = mock(PlatformStore.class);
    final ExternalTool plugin = mock(ExternalTool.class);
    ToolExecutor gateway;

    @BeforeEach
    void setup() {
        when(plugin.name()).thenReturn("lookup");
        when(plugin.readOnly()).thenReturn(true);
        when(plugin.allowsTenant("acme")).thenReturn(true);
        when(store.authorizeTool(
                        anyString(), anyString(), anyString(), anyString(), any(), anyList()))
                .thenReturn(mapper.createObjectNode().put("effect", "ALLOW"));
        gateway = new ToolExecutor(store, mapper, List.of(plugin));
    }

    @Test
    void bothTenantBindingAndRunRoleRequiredEvenWhenModelRequestsTool() {
        var args = mapper.createObjectNode().put("id", "42");
        assertThrows(ApplicationFailure.class, () -> gateway.dispatch(ctx("user"), "lookup", args));
        when(plugin.allowsTenant("acme")).thenReturn(false);
        assertThrows(
                ApplicationFailure.class, () -> gateway.dispatch(ctx("operator"), "lookup", args));
        verify(plugin, never()).invoke(any());
    }

    @Test
    void unsafeOrUndeclaredToolsAreRejected() {
        var args = mapper.createObjectNode().put("id", "42");
        when(plugin.readOnly()).thenReturn(false);
        assertThrows(
                ApplicationFailure.class, () -> gateway.dispatch(ctx("operator"), "lookup", args));
        assertThrows(
                ApplicationFailure.class, () -> gateway.dispatch(ctx("admin"), "payment", args));
        verify(plugin, never()).invoke(any());
    }

    @Test
    void mutableDenyStopsAlreadyRequestedActionAndRechecksEveryPoll() {
        var ctx = ctx("operator");
        var args = mapper.createObjectNode().put("text", "Review my ticket");
        when(store.requestApproval("acme", "run", "act", "create-ticket", args, "approver", 300))
                .thenReturn(mapper.createObjectNode().put("status", "PENDING"));
        assertEquals("PENDING", gateway.dispatch(ctx, "create-ticket", args));
        when(store.authorizeTool(
                        eq("acme"),
                        anyString(),
                        anyString(),
                        eq("create-ticket"),
                        any(),
                        anyList()))
                .thenReturn(mapper.createObjectNode().put("effect", "DENY"));
        assertThrows(ApplicationFailure.class, () -> gateway.dispatch(ctx, "create-ticket", args));
        verify(store, never())
                .applyTicket(anyString(), anyString(), anyString(), any(), anyString(), anyLong());
    }

    @Test
    void connectorWithApprovalPolicyWaitsAndSubsequentCallUsesExactArgs() {
        var ctx = ctx("operator");
        var args = mapper.createObjectNode().put("id", "42");
        when(store.authorizeTool(
                        eq("acme"), anyString(), anyString(), eq("lookup"), any(), anyList()))
                .thenReturn(mapper.createObjectNode().put("effect", "REQUIRE_APPROVAL"));
        when(store.requestApproval("acme", "run", "act", "lookup", args, "approver", 300))
                .thenReturn(
                        mapper.createObjectNode().put("status", "PENDING"),
                        mapper.createObjectNode().put("status", "APPROVED"));
        when(plugin.invoke(args)).thenReturn(mapper.createObjectNode().put("found", true));
        assertEquals("PENDING", gateway.dispatch(ctx, "lookup", args));
        verify(plugin, never()).invoke(any());
        assertEquals("DONE", gateway.dispatch(ctx, "lookup", args));
        verify(plugin).invoke(args);
    }

    @Test
    void synchronousJavaReadsRejectWritesAndApprovalPoliciesBeforeCallingOrOpeningApproval() {
        var ctx = ctx("operator");
        var args = mapper.createObjectNode().put("id", "42");
        assertThrows(ApplicationFailure.class, () -> gateway.read(ctx, "create-ticket", args));
        when(store.authorizeTool(
                        eq("acme"), anyString(), anyString(), eq("lookup"), any(), anyList()))
                .thenReturn(mapper.createObjectNode().put("effect", "REQUIRE_APPROVAL"));
        assertThrows(ApplicationFailure.class, () -> gateway.read(ctx, "lookup", args));
        verify(plugin, never()).invoke(any());
        verify(store, never())
                .requestApproval(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        anyString(),
                        anyLong());
    }

    @Test
    void synchronousJavaReadsReturnOnlyRecordedApprovedReadResult() {
        var args = mapper.createObjectNode().put("id", "42");
        var output = mapper.createObjectNode().put("found", true);
        when(plugin.invoke(args)).thenReturn(output);
        when(store.step("acme", "run", "act")).thenReturn(Optional.empty(), Optional.of(output));
        assertEquals(output.toString(), gateway.read(ctx("operator"), "lookup", args));
        verify(store).saveToolStep("acme", "run", "act", "lookup", args, output);
    }

    private StepContext ctx(String role) {
        var plan =
                PlanCompiler.parse(
                        """
                        start: act
                        allowedTools: [create-ticket, lookup]
                        nodes:
                          - {id: act, type: TOOL_CALLING, next: done}
                          - {id: done, type: END}
                        """);
        var run = mapper.createObjectNode();
        run.putArray("roles").add(role);
        return new StepContext(
                new AgentRun.RunRef("acme", "run"),
                "act",
                run,
                plan,
                plan,
                plan.node("act"),
                mapper.nullNode());
    }

    @Test
    void approvedExternalWriteRequiresVersionAndSingleClaimEvenWhenOutcomeIsUnknown()
            throws Exception {
        var plan =
                PlanCompiler.parse(
                        """
                        start: act
                        nodes:
                          - {id: act,type: TOOL,toolRef: 'lookup:2.1.0',next: done}
                          - {id: done,type: END}
                        """);
        var base = ctx("operator");
        var ctx =
                new StepContext(
                        base.ref(), "act", base.run(), plan, plan, plan.node("act"), base.input());
        var args = mapper.createObjectNode().put("id", "42");
        var spec = mapper.createObjectNode();
        spec.set(
                "spec",
                mapper.readTree(
                        """
                        {"inputSchema":{"type":"object","required":["id"]},"outputSchema":{"type":"object"}}
                        """));
        when(plugin.readOnly()).thenReturn(false);
        when(plugin.version()).thenReturn("2.1.0");
        when(store.definition("acme", "tool", "lookup", "2.1.0")).thenReturn(spec);
        when(store.requestApproval("acme", "run", "act", "lookup", args, "approver", 300))
                .thenReturn(
                        mapper.createObjectNode().put("status", "PENDING"),
                        mapper.createObjectNode().put("status", "APPROVED"));
        when(store.claimExternalWrite("acme", "run", "act", "lookup", args, "approver"))
                .thenReturn(true, false);
        when(plugin.invokeApproved(args, "run/act"))
                .thenThrow(new IllegalStateException("Receipt unavailable"));
        assertEquals("PENDING", gateway.dispatch(ctx, "lookup", args));
        verify(plugin, never()).invokeApproved(any(), anyString());
        for (int attempt = 0; attempt < 2; attempt++) {
            var failure =
                    assertThrows(
                            ApplicationFailure.class, () -> gateway.dispatch(ctx, "lookup", args));
            assertEquals("AMBIGUOUS_WRITE", failure.getType());
        }
        verify(plugin, times(1)).invokeApproved(args, "run/act");
        when(plugin.version()).thenReturn("2.2.0");
        assertThrows(ApplicationFailure.class, () -> gateway.dispatch(ctx, "lookup", args));
    }

    @Test
    void databaseToolUsesOnlyItsBoundKnowledgeBaseAndAuthenticatedRoleSnapshot() {
        var plan =
                PlanCompiler.parse(
                        """
                        start: act
                        allowedTools: [document-search]
                        nodes:
                          - {id: act,type: TOOL,tool: document-search,knowledgeBases: [manual],next: done}
                          - {id: done,type: END}
                        """);
        var base = ctx("operator");
        var ctx =
                new StepContext(
                        base.ref(), "act", base.run(), plan, plan, plan.node("act"), base.input());
        var args = mapper.createObjectNode().put("base", "manual").put("query", "returns");
        assertEquals("DONE", gateway.dispatch(ctx, "document-search", args));
        verify(store)
                .searchForRun("acme", "run", "act", "manual", "returns", List.of("operator"), 4);
        var other = mapper.createObjectNode().put("base", "private").put("query", "returns");
        assertThrows(
                ApplicationFailure.class, () -> gateway.dispatch(ctx, "document-search", other));
        verify(store, never())
                .searchForRun(
                        anyString(),
                        anyString(),
                        anyString(),
                        eq("private"),
                        anyString(),
                        anyList(),
                        anyInt());
    }
}
