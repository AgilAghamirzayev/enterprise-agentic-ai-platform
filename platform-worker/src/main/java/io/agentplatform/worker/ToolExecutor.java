package io.agentplatform.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentplatform.connectors.ExternalTool;
import io.agentplatform.plan.ToolContract;
import io.agentplatform.store.PlatformStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.temporal.failure.ApplicationFailure;

import java.util.*;

/**
 * Tool names resolve only against local implementations or operator-installed, read-only
 * connectors.
 */
final class ToolExecutor {
    private static final Set<String> BUILT_INS =
            Set.of("uppercase", "word-count", "create-ticket", "document-search");
    private final PlatformStore store;
    private final ObjectMapper json;
    private final Map<String, List<ExternalTool>> connectors;
    private final MeterRegistry metrics;

    ToolExecutor(PlatformStore store, ObjectMapper json, List<ExternalTool> plugins) {
        this(store, json, plugins, null);
    }

    ToolExecutor(
            PlatformStore store,
            ObjectMapper json,
            List<ExternalTool> plugins,
            MeterRegistry metrics) {
        this.store = store;
        this.json = json;
        this.metrics = metrics;
        var entries = new HashMap<String, List<ExternalTool>>();
        for (var plugin : plugins) {
            if (BUILT_INS.contains(plugin.name()))
                throw new IllegalArgumentException("A tool shadows a built-in");
            entries.computeIfAbsent(plugin.name(), ignored -> new ArrayList<>()).add(plugin);
        }
        connectors = Map.copyOf(entries);
    }

    String read(StepContext ctx, String tool, JsonNode args) {
        String state = dispatch(ctx, tool, args, true);
        if (!state.equals("DONE")) deny();
        return store.step(ctx.tenant(), ctx.runId(), ctx.key()).orElseThrow().toString();
    }

    String dispatch(StepContext ctx, String tool, JsonNode args) {
        return dispatch(ctx, tool, args, false);
    }

    private String dispatch(StepContext ctx, String tool, JsonNode args, boolean synchronousRead) {
        if (!ctx.root().allowedTools().contains(tool)
                || !ctx.plan().allowedTools().contains(tool)
                || Collections.disjoint(ctx.roles(), Set.of("admin", "operator"))) deny();
        var connector = connector(ctx, tool);
        boolean write =
                tool.equals("create-ticket") || (connector != null && !connector.readOnly());
        var contract = contract(ctx, tool, connector);
        if (write && connector != null && contract == null) deny();
        if (contract != null) contract.validateInput(args);
        String effect = authorize(ctx, tool, args);
        if (!synchronousRead && store.step(ctx.tenant(), ctx.runId(), ctx.key()).isPresent())
            return "DONE";
        if (synchronousRead) {
            if (!effect.equals("ALLOW") || write) deny();
            if (store.step(ctx.tenant(), ctx.runId(), ctx.key()).isPresent()) return "DONE";
        }
        store.reserveTool(
                ctx.tenant(),
                ctx.runId(),
                ctx.key(),
                Math.min(ctx.root().limits().maxToolCalls(), ctx.plan().limits().maxToolCalls()));
        if (!tool.equals("create-ticket") && (write || effect.equals("REQUIRE_APPROVAL"))) {
            String decision = approval(ctx, tool, args);
            if (!decision.equals("APPROVED")) return decision;
        }
        if (connector != null) {
            String latest = authorize(ctx, tool, args);
            if ("REQUIRE_APPROVAL".equals(latest) && !"REQUIRE_APPROVAL".equals(effect) && !write) {
                if (synchronousRead) deny();
                String decision = approval(ctx, tool, args);
                if (!decision.equals("APPROVED")) return decision;
            }
            if (write) return invokeWrite(ctx, tool, args, connector, contract);
            return invokeRead(ctx, tool, args, connector, contract);
        }
        if (tool.equals("create-ticket")) {
            if (!args.isObject() || args.isEmpty() || args.toString().length() > 8192) invalid();
            String decision = approval(ctx, tool, args);
            if (decision.equals("APPROVED")) {
                if (contract == null)
                    store.applyTicket(
                            ctx.tenant(), ctx.runId(), ctx.key(), args, ctx.role(), ctx.ttl());
                else
                    store.applyTicket(
                            ctx.tenant(),
                            ctx.runId(),
                            ctx.key(),
                            args,
                            ctx.role(),
                            ctx.ttl(),
                            contract::validateOutput);
                return "DONE";
            }
            return decision;
        }
        JsonNode result =
                switch (tool) {
                    case "uppercase" ->
                            json.getNodeFactory()
                                    .textNode(textArgument(args).toUpperCase(Locale.ROOT));
                    case "word-count" -> {
                        String text = textArgument(args).strip();
                        yield json.getNodeFactory()
                                .numberNode(text.isEmpty() ? 0 : text.split("(?U)\\s+").length);
                    }
                    case "document-search" -> {
                        var base = args.path("base").asText("");
                        var query = args.path("query").asText("");
                        if (!args.isObject()
                                || args.size() != 2
                                || !args.path("base").isTextual()
                                || !args.path("query").isTextual()
                                || query.isBlank()
                                || query.length() > 8000) invalid();
                        if (!ctx.node().knowledgeBases().contains(base)
                                && !base.equals(ctx.node().knowledgeBase())) deny();
                        yield json.valueToTree(
                                store.searchForRun(
                                        ctx.tenant(),
                                        ctx.runId(),
                                        ctx.key(),
                                        base,
                                        query,
                                        ctx.roles(),
                                        4));
                    }
                    default -> throw new IllegalStateException("Unknown tool");
                };
        checkResult(contract, result);
        store.saveToolStep(ctx.tenant(), ctx.runId(), ctx.key(), tool, args, result);
        return "DONE";
    }

    private ExternalTool connector(StepContext ctx, String tool) {
        if (BUILT_INS.contains(tool)) return null;
        String ref = reference(ctx, tool);
        var matches =
                connectors.getOrDefault(tool, List.of()).stream()
                        .filter(toolImpl -> toolImpl.allowsTenant(ctx.tenant()))
                        .filter(
                                toolImpl ->
                                        ref == null || ref.equals(tool + ":" + toolImpl.version()))
                        .limit(2)
                        .toList();
        if (matches.size() != 1) deny();
        return matches.getFirst();
    }

    private static String reference(StepContext ctx, String name) {
        return ctx.node().toolRef() == null
                ? ctx.node().toolBindings().get(name)
                : ctx.node().toolRef();
    }

    private String invokeRead(
            StepContext ctx,
            String tool,
            JsonNode args,
            ExternalTool connector,
            ToolContract contract) {
        JsonNode result = connector.invoke(args);
        checkResult(contract, result);
        store.saveToolStep(ctx.tenant(), ctx.runId(), ctx.key(), tool, args, result);
        return "DONE";
    }

    private String invokeWrite(
            StepContext ctx,
            String tool,
            JsonNode args,
            ExternalTool connector,
            ToolContract contract) {
        if (!store.claimExternalWrite(
                ctx.tenant(), ctx.runId(), ctx.key(), tool, args, ctx.role())) {
            if (store.step(ctx.tenant(), ctx.runId(), ctx.key()).isPresent()) return "DONE";
            throw ApplicationFailure.newNonRetryableFailure(
                    "External outcome requires reconciliation", "AMBIGUOUS_WRITE");
        }
        try {
            var result = connector.invokeApproved(args, ctx.runId() + "/" + ctx.key());
            checkResult(contract, result);
            store.completeExternalWrite(ctx.tenant(), ctx.runId(), ctx.key(), tool, args, result);
            return "DONE";
        } catch (RuntimeException failure) {
            throw ApplicationFailure.newNonRetryableFailure(
                    "External outcome requires reconciliation", "AMBIGUOUS_WRITE");
        }
    }

    private ToolContract contract(StepContext ctx, String name, ExternalTool installed) {
        String reference = reference(ctx, name);
        if (reference == null) return null;
        String version = installed == null ? "1.0.0" : installed.version();
        if (!reference.equals(name + ":" + version)) deny();
        return ToolContract.from(
                store.definition(ctx.tenant(), "tool", name, version).required("spec"));
    }

    private String authorize(StepContext ctx, String tool, JsonNode args) {
        var policies = new LinkedHashSet<>(ctx.root().policyRefs());
        policies.addAll(ctx.plan().policyRefs());
        String effect =
                store.authorizeTool(
                                ctx.tenant(),
                                ctx.runId(),
                                ctx.key(),
                                tool,
                                args,
                                List.copyOf(policies))
                        .path("effect")
                        .asText();
        if (metrics != null)
            metrics.counter(
                            "tool.authorizations",
                            "outcome",
                            effect.equals("ALLOW")
                                    ? "allow"
                                    : effect.equals("REQUIRE_APPROVAL") ? "approval" : "deny")
                    .increment();
        if (!effect.equals("ALLOW") && !effect.equals("REQUIRE_APPROVAL")) deny();
        return effect;
    }

    String authorizeDelegate(StepContext ctx) {
        String name = ctx.node().tool();
        if (!ctx.root().allowedTools().contains(name)
                || !ctx.plan().allowedTools().contains(name)
                || Collections.disjoint(ctx.roles(), Set.of("admin", "operator"))) deny();
        String ref = ctx.node().toolRef();
        var spec =
                store.definition(ctx.tenant(), "tool", name, ref.split(":", 2)[1]).required("spec");
        var target = spec.required("target");
        var handler = ctx.plan().node(ctx.node().next());
        boolean agent = target.required("kind").asText().equals("agent");
        if (!target.required("ref")
                .asText()
                .equals(agent ? handler.agentRef() : handler.workflowRef())) deny();
        ToolContract.from(spec).validateInput(ctx.input());
        var effect = authorize(ctx, name, ctx.input());
        store.reserveTool(
                ctx.tenant(),
                ctx.runId(),
                ctx.key(),
                Math.min(ctx.root().limits().maxToolCalls(), ctx.plan().limits().maxToolCalls()));
        if (effect.equals("REQUIRE_APPROVAL")) {
            var outcome = approval(ctx, name, ctx.input());
            if (!outcome.equals("APPROVED")) return outcome;
        }
        store.saveStep(ctx.tenant(), ctx.runId(), ctx.key(), ctx.input(), 0, 0);
        return "DONE";
    }

    void receipt(StepContext ctx) {
        String key = ctx.key().substring(0, ctx.key().lastIndexOf('/') + 1) + ctx.node().field();
        var args = store.step(ctx.tenant(), ctx.runId(), key).orElseThrow();
        var ref = ctx.node().toolRef().split(":", 2);
        var spec = store.definition(ctx.tenant(), "tool", ref[0], ref[1]).required("spec");
        checkResult(ToolContract.from(spec), ctx.input());
        store.saveToolStep(ctx.tenant(), ctx.runId(), ctx.key(), ref[0], args, ctx.input());
    }

    private static void checkResult(ToolContract contract, JsonNode result) {
        if (result == null || result.toString().length() > 65536) invalid();
        if (contract != null) contract.validateOutput(result);
    }

    String approval(StepContext ctx, String action, JsonNode args) {
        return store.requestApproval(
                        ctx.tenant(), ctx.runId(), ctx.key(), action, args, ctx.role(), ctx.ttl())
                .path("status")
                .asText();
    }

    private static String textArgument(JsonNode args) {
        JsonNode value = args.isObject() && args.size() == 1 ? args.get("text") : args;
        if (value == null || !value.isTextual() || value.textValue().length() > 16384) invalid();
        return value.textValue();
    }

    static void deny() {
        throw ApplicationFailure.newNonRetryableFailure("Tool permission denied", "DENIED");
    }

    static void invalid() {
        throw ApplicationFailure.newNonRetryableFailure("Invalid step arguments", "INVALID_INPUT");
    }
}
