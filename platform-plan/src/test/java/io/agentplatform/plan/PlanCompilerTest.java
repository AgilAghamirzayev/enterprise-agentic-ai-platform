package io.agentplatform.plan;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PlanCompilerTest {
    private static final String VALID =
            """
            apiVersion: platform.ai/v1alpha1
            kind: Workflow
            metadata: {name: support, version: 1.0.0}
            spec:
              start: analyze
              nodes:
                - {id: analyze, type: AGENT, next: done}
                - {id: done, type: END}
            """;

    @Test
    void canonicalGraphRoundTrips() {
        var json = PlanCompiler.compile(VALID);
        assertEquals(json, PlanCompiler.compile(json));
        assertEquals(12, PlanCompiler.parse(json).limits().maxModelCalls());
    }

    @Test
    void planInvocationHonorsImmutableRoleBindings() {
        assertTrue(PlanCompiler.parse(VALID).permitsInvocation(java.util.List.of("user")));
        var restricted =
                PlanCompiler.parse(
                        VALID.replace(
                                "start: analyze", "invokeRoles: [finance]\n  start: analyze"));
        assertTrue(restricted.permitsInvocation(java.util.List.of("finance")));
        assertTrue(restricted.permitsInvocation(java.util.List.of("admin")));
        assertFalse(restricted.permitsInvocation(java.util.List.of("operator")));
    }

    @Test
    void routerUsesTypedDataComparisonsWithoutEvaluatingCode() throws Exception {
        String source =
                """
                start: risk
                nodes:
                  - {id: risk,type: CONDITION,expression: 'riskScore > 70',onTrue: high,onFalse: low}
                  - {id: high,type: END}
                  - {id: low,type: END}
                """;
        var condition = Condition.compile(PlanCompiler.parse(source).node("risk"));
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        assertTrue(condition.test(json.readTree("{\"riskScore\":71}")));
        assertFalse(condition.test(json.readTree("{\"riskScore\":\"71\"}")));
        assertFalse(condition.test(json.readTree("{}")));
        assertThrows(
                IllegalArgumentException.class,
                () -> PlanCompiler.parse(source.replace("riskScore > 70", "runtime.exec()")));
    }

    @Test
    void rejectsCyclesUnknownTargetsUnreachableNodesAndDuplicateIds() {
        for (String edge : new String[] {"analyze", "missing"})
            assertThrows(
                    IllegalArgumentException.class,
                    () -> PlanCompiler.compile(VALID.replace("next: done", "next: " + edge)));
        assertThrows(
                IllegalArgumentException.class,
                () -> PlanCompiler.compile(VALID.replace("start: analyze", "start: done")));
        assertThrows(
                IllegalArgumentException.class,
                () -> PlanCompiler.compile(VALID.replace("id: done", "id: analyze")));
    }

    @Test
    void rejectsFloatingReferencesUnboundToolsAndUnboundedLimits() {
        for (String replacement :
                new String[] {
                    "type: AGENT, agentRef: 'hello:latest'",
                    "type: TOOL, tool: create-ticket",
                    "type: TOOL_CALLING",
                    "type: WAIT, seconds: 0",
                    "type: BASH"
                })
            assertThrows(
                    IllegalArgumentException.class,
                    () -> PlanCompiler.compile(VALID.replace("type: AGENT", replacement)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        PlanCompiler.compile(
                                VALID.replace(
                                        "start: analyze",
                                        "limits: {maxDepth: 100}\n  start: analyze")));
    }

    @Test
    void rejectsDuplicateYamlKeysTrailingDocumentsAndUnknownProperties() {
        for (String invalid :
                new String[] {
                    VALID.replace("start: analyze", "start: analyze\n  start: done"),
                    VALID + "---\nx: 3",
                    VALID.replace("type: AGENT", "type: AGENT, evaluate: anything")
                })
            assertThrows(IllegalArgumentException.class, () -> PlanCompiler.compile(invalid));
    }
}
