package io.agentplatform.worker;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentplatform.connectors.ConfiguredTools;
import io.agentplatform.connectors.ExternalTool;
import io.agentplatform.plan.DeploymentModels;
import io.agentplatform.spi.ModelBinding;
import io.agentplatform.spi.ModelGateway;
import io.agentplatform.store.PlatformStore;
import io.agentplatform.store.StoreConfiguration;
import io.agentplatform.testkit.FakeModelGateway;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.stream.Stream;

@Import(StoreConfiguration.class)
@SpringBootApplication
public class WorkerMain {
    public static void main(String[] args) {
        SpringApplication.run(WorkerMain.class, args);
    }

    @Bean
    io.micrometer.observation.ObservationFilter runTraceMetadata() {
        return context -> {
            if (context.getHighCardinalityKeyValue("langfuse.observation.type") == null)
                return context;
            // Children finish first, so walk back to the activity that restored the durable
            // context.
            for (var parent = context.getParentObservation();
                    parent != null;
                    parent = parent.getContextView().getParentObservation()) {
                var source = parent.getContextView();
                if (source.getHighCardinalityKeyValue("langfuse.trace.metadata.runId") == null)
                    continue;
                for (var key :
                        java.util.List.of(
                                "langfuse.trace.metadata.runId",
                                "langfuse.trace.name",
                                "langfuse.version")) {
                    var value = source.getHighCardinalityKeyValue(key);
                    if (value != null) context.addHighCardinalityKeyValue(value);
                }
                break;
            }
            return context;
        };
    }

    @Bean
    @ConditionalOnProperty(name = "platform.model", havingValue = "demo", matchIfMissing = true)
    ModelGateway demoModel() {
        return new FakeModelGateway();
    }

    @Bean
    ModelRoutes modelRoutes(
            ObjectProvider<ModelGateway> primary,
            ObjectProvider<ChatModel> chat,
            List<ModelBinding> bindings,
            MeterRegistry meters,
            io.micrometer.observation.ObservationRegistry observations,
            @Value("${PLATFORM_MODEL:demo}") String mode,
            @Value("${OPENAI_MODEL:gpt-4o-mini}") String model,
            @Value("${PLATFORM_ALLOWED_MODELS:}") String additional) {
        var inventory = DeploymentModels.from(mode, model, additional);
        var configured = CatalogModels.create(inventory, System::getenv, observations);
        return new ModelRoutes(
                inventory,
                primary.getIfUnique(),
                chat.getIfUnique(),
                Stream.concat(bindings.stream(), configured.stream()).toList(),
                meters,
                observations);
    }

    @Bean
    JavaHandlers javaHandlers(
            io.agentplatform.application.AgentRegistry registry,
            PlatformStore store,
            ObjectMapper mapper,
            @Value("${PLATFORM_JAVA_AGENTS:}") String bindings) {
        return new JavaHandlers(registry, store, mapper, bindings);
    }

    @Bean
    StoredActivities runActivities(
            PlatformStore store,
            ObjectMapper mapper,
            ModelRoutes models,
            List<ExternalTool> plugins,
            List<io.agentplatform.sdk.AgentTool<?, ?>> javaTools,
            MeterRegistry metrics,
            JavaHandlers handlers,
            io.micrometer.observation.ObservationRegistry observations) {
        var connectors =
                Stream.of(
                                ConfiguredTools.load(mapper).stream(),
                                plugins.stream(),
                                javaTools.stream().map(tool -> JavaTools.adapt(tool, mapper)))
                        .flatMap(stream -> stream)
                        .toList();
        return new StoredActivities(
                store, mapper, models, connectors, metrics, handlers, observations);
    }
}
