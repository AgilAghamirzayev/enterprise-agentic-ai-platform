package io.agentplatform.plan;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

class BlueprintTest {
    @Test
    void javaHistoryAndKnowledgeDeclarationsMustBeEnforceableByTheInstalledHandler() {
        String source =
                """
                start: invoke
                nodes:
                  - {id: invoke,type: AGENT,javaRef: 'handler:1.0.0',knowledgeBases: [docs],next: end}
                  - {id: end,type: END}
                """;
        assertEquals("docs", PlanCompiler.parse(source).node("invoke").knowledgeBase());
        for (var memory :
                List.of(
                        "{type: NONE,maxMessages: 0}",
                        "{type: EPISODIC,maxMessages: 20}",
                        "{type: CONVERSATION,maxMessages: 8}"))
            assertThrows(
                    IllegalArgumentException.class,
                    () -> PlanCompiler.parse("memory: " + memory + "\n" + source));
        assertDoesNotThrow(
                () ->
                        PlanCompiler.parse(
                                "memory: {type: CONVERSATION,maxMessages: 20}\n" + source));
        assertThrows(
                IllegalArgumentException.class,
                () -> PlanCompiler.parse(source.replace("[docs]", "[docs, other]")));
    }

    @Test
    void invoiceDslMatchesThePublishedYamlGraph() {
        var yaml =
                """
                apiVersion: platform.ai/v1alpha1
                kind: Workflow
                metadata: { name: invoices, version: 1.0.0 }
                spec:
                  start: extract
                  nodes:
                    - {id: extract,type: AGENT, agentRef: 'extractor:1.0.0', next: validate}
                    - {id: validate,type: TOOL, toolRef: 'validator:1.0.0',next: risk}
                    - {id: risk,type: CONDITION,expression: 'riskScore > 70',onTrue: review,onFalse: write}
                    - {id: review,type: APPROVAL,role: ACCOUNTANT,timeout: 24h,onApproved: write,onRejected: rejected}
                    - {id: write,type: TOOL,toolRef: 'writer:1.0.0',next: done}
                    - {id: done,type: END}
                    - {id: rejected,type: END}
                """;
        String java =
                WorkflowBuilder.start("extract")
                        .agent("extract", "extractor:1.0.0", "validate")
                        .tool("validate", "validator:1.0.0", "risk")
                        .condition("risk", "riskScore > 70", "review", "write")
                        .approval("review", "ACCOUNTANT", Duration.ofDays(1), "write", "rejected")
                        .tool("write", "writer:1.0.0", "done")
                        .end("done")
                        .end("rejected")
                        .compile();
        assertEquals(java, PlanCompiler.compile(yaml));
    }

    @Test
    void sourceAgentPinsReferencesAndProducesABoundedDecisionGraph() {
        String yaml =
                """
                type: TOOL_CALLING
                model: {profile: chat}
                tools: [lookup, create-ticket]
                knowledgeBases: [manual]
                execution: {maxIterations: 2,timeout: 120s,maxModelCalls: 15,maxToolCalls: 20}
                budget: {maxTokens: 20000,maxCostUsd: 0.50}
                prompt: {templateRef: support, version: 3}
                policyRef: support-policy
                memory: {type: EPISODIC,maxMessages: 20}
                """;
        assertThrows(IllegalArgumentException.class, () -> PlanCompiler.compile(yaml));
        String json = PlanCompiler.compile(yaml, (kind, name) -> name + ":2.0.0");
        assertEquals(json, PlanCompiler.compile(json));
        var plan = PlanCompiler.parse(json);
        assertEquals(2, plan.limits().maxIterations());
        assertEquals(120, plan.limits().timeoutSeconds());
        assertEquals("support:3.0.0", plan.node("invoke").promptRef());
        assertEquals("lookup:2.0.0", plan.node("invoke-call-1").toolBindings().get("lookup"));
        assertEquals(List.of("support-policy:2.0.0"), plan.policyRefs());
        assertEquals(Plan.Type.MODEL_DECISION, plan.node("invoke-decide-2").type());
        assertEquals("invoke-limit", plan.node("invoke-call-2").next());
        assertEquals(Plan.Type.LIMIT, plan.node("invoke-limit").type());
    }

    @Test
    void contractsRejectUnknownVocabularyAndInvalidArgumentsOrResults() throws Exception {
        var json = new ObjectMapper();
        var schema =
                json.readTree(
                        """
                        {"inputSchema":{"type":"object","properties":{"id":{"type":"string"}},"required":["id"],"additionalProperties":false},
                         "outputSchema":{"type":"integer","minimum":0}}
                        """);
        var c = ToolContract.from(schema);
        c.validateInput(json.createObjectNode().put("id", "7"));
        c.validateOutput(json.getNodeFactory().numberNode(0));
        assertThrows(
                IllegalArgumentException.class, () -> c.validateInput(json.createObjectNode()));
        assertThrows(
                IllegalArgumentException.class,
                () -> c.validateOutput(json.getNodeFactory().numberNode(-1)));
        ((com.fasterxml.jackson.databind.node.ObjectNode) schema.get("inputSchema"))
                .put("unknownKeyword", true);
        assertThrows(IllegalArgumentException.class, () -> ToolContract.from(schema));
    }

    @Test
    void agentAsToolExpandsWithExactApprovalInputDelegatedTargetAndReceipt() throws Exception {
        var json = new ObjectMapper();
        var compiled =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        json.readTree(
                                PlanCompiler.compile(
                                        """
                                        type: TOOL_CALLING
                                        tools: ['study:1.0.0']
                                        execution: {maxIterations: 1}
                                        """));
        RegistryTools.bind(
                compiled,
                ref ->
                        json.createObjectNode()
                                .set(
                                        "target",
                                        json.createObjectNode()
                                                .put("kind", "agent")
                                                .put("ref", "analyst:2.0.0")));
        var plan = PlanCompiler.parse(compiled.toString());
        var gate =
                plan.nodes().stream()
                        .filter(n -> n.type() == Plan.Type.TOOL_GATE)
                        .findFirst()
                        .orElseThrow();
        assertEquals("study:1.0.0", gate.toolRef());
        var delegate = plan.node(gate.next());
        assertEquals("analyst:2.0.0", delegate.agentRef());
        assertEquals(gate.id(), plan.node(delegate.next()).field());
        compiled.put("start", delegate.id());
        assertThrows(IllegalArgumentException.class, () -> PlanCompiler.parse(compiled.toString()));
    }
}
