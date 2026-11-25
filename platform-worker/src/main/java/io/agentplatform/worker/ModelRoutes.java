package io.agentplatform.worker;

import io.agentplatform.plan.DeploymentModels;
import io.agentplatform.spi.ModelBinding;
import io.agentplatform.spi.ModelGateway;
import io.agentplatform.spi.ModelRequest;
import io.agentplatform.spi.ModelResult;
import io.agentplatform.springai.SpringAiModelGateway;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.web.client.ResourceAccessException;

import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

final class ModelRoutes {
    private final DeploymentModels inventory;
    private final Map<String, ModelGateway> gateways;

    private static final io.micrometer.context.ContextSnapshotFactory SNAPSHOTS =
            io.micrometer.context.ContextSnapshotFactory.builder().build();
    private final MeterRegistry meters;
    private final ObservationRegistry observations;

    ModelRoutes(
            DeploymentModels inventory,
            ModelGateway primary,
            ChatModel chat,
            List<ModelBinding> custom) {
        this(
                inventory,
                primary,
                chat,
                custom,
                io.micrometer.core.instrument.Metrics.globalRegistry,
                ObservationRegistry.NOOP);
    }

    ModelRoutes(
            DeploymentModels inventory,
            ModelGateway primary,
            ChatModel chat,
            List<ModelBinding> custom,
            MeterRegistry meters,
            ObservationRegistry observations) {
        this.meters = meters;
        this.observations = observations;
        this.inventory = inventory;
        var registered = new HashMap<String, ModelGateway>();
        for (var binding : custom) {
            if (!inventory.allowed().contains(binding.modelId())
                    || registered.putIfAbsent(binding.modelId(), binding.gateway()) != null) {
                throw new IllegalArgumentException(
                        "Duplicate or unlisted deployment model binding");
            }
        }
        if (primary != null) registered.putIfAbsent(inventory.primary(), primary);
        for (var id : inventory.allowed()) {
            if (!registered.containsKey(id)) {
                if (chat == null)
                    throw new IllegalArgumentException("Model binding is unavailable for " + id);
                registered.put(id, new SpringAiModelGateway(chat, id));
            }
        }
        gateways = Map.copyOf(registered);
    }

    String primary() {
        return inventory.primary();
    }

    String provider(String id) {
        return inventory.providerFor(id);
    }

    String policyDigest(String id) {
        return inventory.policyDigest(id);
    }

    String upstreamModel(String id) {
        return inventory.upstreamModel(id);
    }

    ModelResult complete(
            String selected, String fallback, ModelRequest request, Consumer<String> reserve) {
        var primary = new PinnedModelRoute(selected, request, java.time.Duration.ofSeconds(60));
        var targets =
                fallback == null
                        ? List.of(primary)
                        : List.of(
                                primary,
                                new PinnedModelRoute(fallback, request, primary.timeout()));
        return complete(targets, (id, effective) -> reserve.accept(id));
    }

    ModelResult complete(
            List<PinnedModelRoute> targets,
            java.util.function.BiConsumer<String, ModelRequest> reserve) {
        if (targets.isEmpty() || targets.size() > 5)
            throw new IllegalArgumentException("Invalid model route count");
        var primary = targets.getFirst();
        var distinct = new java.util.HashSet<String>();
        for (var target : targets) {
            if (!distinct.add(target.model())
                    || !target.timeout().isPositive()
                    || target.timeout().compareTo(java.time.Duration.ofSeconds(120)) > 0)
                throw new IllegalArgumentException("Duplicate model or invalid timeout");
            inventory.validate(primary.model(), target == primary ? null : target.model());
        }
        long deadline = System.nanoTime() + primary.timeout().toNanos();
        for (int i = 0; i < targets.size(); i++) {
            if (System.nanoTime() >= deadline) throw new IllegalStateException("Model timeout");
            var route = targets.get(i);
            reserve.accept(route.model(), route.request());
            try {
                var response =
                        attempt(
                                route.model(),
                                route.request(),
                                Math.min(deadline, System.nanoTime() + route.timeout().toNanos()));
                if (response.outputTokens() > route.request().maxOutputTokens())
                    AgentRunWorkflow.stop("BUDGET_EXCEEDED");
                return response;
            } catch (TransientAiException
                    | ResourceAccessException
                    | UncheckedIOException unavailable) {
                if (i + 1 == targets.size()) throw unavailable;
            }
        }
        throw new IllegalStateException("Missing inference result");
    }

    private ModelResult attempt(String alias, ModelRequest request, long deadline) {
        String provider = provider(alias);
        meters.counter("model.requests", "provider", provider).increment();
        return Observation.createNotStarted("model.request.duration", observations)
                .lowCardinalityKeyValue("provider", provider)
                .highCardinalityKeyValue("langfuse.observation.type", "generation")
                .highCardinalityKeyValue("gen_ai.provider.name", provider)
                .highCardinalityKeyValue("gen_ai.request.model", upstreamModel(alias))
                .observe(
                        () -> {
                            var work =
                                    new java.util.concurrent.FutureTask<>(
                                            () -> gateways.get(alias).complete(request));
                            Thread.startVirtualThread(
                                    SNAPSHOTS
                                            .captureAll()
                                            .wrap(
                                                    io.opentelemetry.context.Context.current()
                                                            .wrap(work)));
                            try {
                                var result =
                                        work.get(
                                                Math.max(0, deadline - System.nanoTime()),
                                                java.util.concurrent.TimeUnit.NANOSECONDS);
                                var span = io.opentelemetry.api.trace.Span.current();
                                span.setAttribute(
                                        "gen_ai.usage.input_tokens", (long) result.inputTokens());
                                span.setAttribute(
                                        "gen_ai.usage.output_tokens", (long) result.outputTokens());
                                return result;
                            } catch (java.util.concurrent.TimeoutException expired) {
                                throw new IllegalStateException("Model timeout", expired);
                            } catch (InterruptedException cancelled) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(
                                        "Model call interrupted", cancelled);
                            } catch (java.util.concurrent.ExecutionException failed) {
                                if (failed.getCause() instanceof RuntimeException cause)
                                    throw cause;
                                if (failed.getCause() instanceof Error error) throw error;
                                throw new IllegalStateException(
                                        "Model call failed", failed.getCause());
                            } finally {
                                work.cancel(true);
                            }
                        });
    }
}
