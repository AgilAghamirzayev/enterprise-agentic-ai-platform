package io.agentplatform.worker;

import com.fasterxml.jackson.databind.*;

import io.agentplatform.application.AgentRegistry;
import io.agentplatform.domain.AgentDescriptor;
import io.agentplatform.sdk.*;
import io.agentplatform.spi.*;
import io.agentplatform.store.PlatformStore;
import io.temporal.failure.ApplicationFailure;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/**
 * Deployment-installed handlers must be replay-safe; only the current synchronous invocation owns
 * its gateway.
 */
final class JavaHandlers {
    private final AgentRegistry registry;
    private final PlatformStore store;
    private final ObjectMapper json;
    private final Set<String> bindings;

    JavaHandlers(AgentRegistry registry, PlatformStore store, ObjectMapper json, String allowlist) {
        this.registry = registry;
        this.store = store;
        this.json = json;
        this.bindings =
                allowlist.isBlank() ? Set.of() : Set.copyOf(Arrays.asList(allowlist.split(",")));
        for (String binding : bindings) {
            if (!binding.matches(
                    "[A-Za-z0-9_-]{1,80}=[a-z][a-z0-9-]{0,62}:[0-9]+[.][0-9]+[.][0-9]+"))
                throw new IllegalArgumentException("Invalid Java agent tenant binding");
            registry.require(descriptor(binding.substring(binding.indexOf('=') + 1)));
        }
    }

    void execute(StepContext step, ModelSteps models, ToolExecutor tools) {
        String ref = step.node().javaRef();
        if (!bindings.contains(step.tenant() + "=" + ref)) ToolExecutor.deny();
        var definition = registry.require(descriptor(ref));
        if (store.step(step.tenant(), step.runId(), step.key()).isPresent()) return;
        Object input = json.convertValue(step.input(), definition.inputType());
        var context = new Context(step, models, definition.descriptor(), tools);
        try {
            context.checkCancellation();
            Object output = definition.invoke(input, context);
            context.checkCancellation();
            JsonNode value = json.valueToTree(output);
            if (value.toString().length() > 65536) ToolExecutor.invalid();
            store.saveStep(step.tenant(), step.runId(), step.key(), value, 0, 0);
        } catch (ApplicationFailure | io.agentplatform.store.StoreException expected) {
            throw expected;
        } catch (RuntimeException handlerFailure) {
            throw ApplicationFailure.newNonRetryableFailure(
                    "Installed Java handler failed", "JAVA_AGENT_ERROR");
        } finally {
            context.open = false;
        }
    }

    private static AgentDescriptor descriptor(String ref) {
        var parts = ref.split(":", 2);
        return new AgentDescriptor(parts[0], parts[1]);
    }

    private final class Context implements AgentExecutionContext {
        private final StepContext step;
        private final ModelSteps models;
        private final AgentDescriptor descriptor;
        private final Thread owner = Thread.currentThread();
        private int calls;
        private int reads;
        private final InvocationResources resources;
        private boolean open = true;

        private Context(StepContext step, ModelSteps models, AgentDescriptor descriptor, ToolExecutor tools) {
            this.step = step;
            this.models = models;
            this.descriptor = descriptor;
            this.resources = new InvocationResources(this::search, this::history, (name, args) -> readTool(tools, name, args))
                    .guarded(this::reserveRead);
        }

        @Override public KnowledgeGateway knowledge() { return resources.knowledge(); }
        @Override public MemoryGateway memory() { return resources.memory(); }
        @Override public ToolGateway tools() { return resources.tools(); }

        private void reserveRead() {
            checkCancellation();
            if (++reads > 64) AgentRunWorkflow.stop("BUDGET_EXCEEDED");
        }

        private List<KnowledgeGateway.Chunk> search(String query, int limit) {
            String base = step.node().knowledgeBase();
            if (base == null) ToolExecutor.deny();
            return store.searchForRun(tenantId(), step.runId(), step.key() + "@knowledge-" + reads, base,
                            query, step.roles(), limit).stream()
                    .map(row -> new KnowledgeGateway.Chunk(row.required("documentId").asText(),
                            row.required("chunkId").asInt(), row.required("title").asText(),
                            row.required("digest").asText(), row.required("text").asText())).toList();
        }

        private List<MemoryGateway.Message> history(int limit) {
            return store.messagesForRun(tenantId(), step.runId(), limit).stream()
                    .map(row -> new MemoryGateway.Message(row.required("role").asText(), row.required("content").asText()))
                    .toList();
        }

        private String readTool(ToolExecutor tools, String tool, String arguments) {
            JsonNode args;
            try {
                args = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(arguments);
            } catch (java.io.IOException malformed) {
                throw new IllegalArgumentException("Invalid tool arguments");
            }
            if (args == null || args.isNull()) ToolExecutor.invalid();
            String key = step.key() + "@read-" + reads + "-" + digest(tool + "\n" + args);
            var nested = new StepContext(step.ref(), key, step.run(), step.root(), step.plan(), step.node(), args);
            return tools.read(nested, tool, args);
        }

        @Override
        public UUID runId() {
            return UUID.fromString(step.runId());
        }

        @Override
        public String tenantId() {
            return step.tenant();
        }

        @Override
        public AgentDescriptor agent() {
            return descriptor;
        }

        @Override
        public ModelGateway models() {
            return this::complete;
        }

        @Override
        public void checkCancellation() {
            if (!open || Thread.currentThread() != owner) ToolExecutor.deny();
            var run = store.runControl(step.tenant(), step.runId());
            if (run.path("cancelRequested").asBoolean()
                    || !"RUNNING".equals(run.path("status").asText()))
                AgentRunWorkflow.stop("CANCELLED");
        }

        private ModelResult complete(ModelRequest request) {
            checkCancellation();
            int maxOutput =
                    Math.min(
                            step.root().limits().maxOutputTokens(),
                            step.plan().limits().maxOutputTokens());
            if (request.maxOutputTokens() > maxOutput) AgentRunWorkflow.stop("BUDGET_EXCEEDED");
            String key = step.key() + "@model-" + (++calls);
            String digest = digest(json.valueToTree(request).toString());
            var cached = store.step(step.tenant(), step.runId(), key);
            if (cached.isPresent()) {
                var prior = cached.get();
                if (!digest.equals(prior.path("requestHash").asText()))
                    throw ApplicationFailure.newNonRetryableFailure(
                            "Java agent changed a recorded model invocation", "REPLAY_MISMATCH");
                return new ModelResult(
                        prior.path("text").asText(),
                        prior.path("inputTokens").intValue(),
                        prior.path("outputTokens").intValue());
            }
            ModelResult result = models.infer(step, key, request);
            var value =
                    json.createObjectNode()
                            .put("requestHash", digest)
                            .put("text", result.text())
                            .put("inputTokens", result.inputTokens())
                            .put("outputTokens", result.outputTokens());
            store.saveStep(
                    step.tenant(),
                    step.runId(),
                    key,
                    value,
                    result.inputTokens(),
                    result.outputTokens());
            return result;
        }
    }

    private static String digest(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
