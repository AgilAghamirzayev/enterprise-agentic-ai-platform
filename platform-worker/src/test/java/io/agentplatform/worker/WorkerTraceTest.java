package io.agentplatform.worker;

import static org.junit.jupiter.api.Assertions.*;

import io.agentplatform.plan.DeploymentModels;
import io.agentplatform.spi.ModelRequest;
import io.agentplatform.spi.ModelResult;
import io.agentplatform.store.RunTraceContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.trace.SdkTracerProvider;

import org.junit.jupiter.api.Test;

import java.util.List;

class WorkerTraceTest {
    @Test
    void joinsApiTraceFromDurableRunAcrossWorkerAndProviderThreads() {
        String traceId = "0123456789abcdef0123456789abcdef";
        String parentId = "0011223344556677";
        try (var sdk = SdkTracerProvider.builder().build();
                var parent = RunTraceContext.restore("00-" + traceId + "-" + parentId + "-01")) {
            var tracer =
                    new OtelTracer(
                            sdk.get("worker-test"), new OtelCurrentTraceContext(), event -> {});
            var observations = ObservationRegistry.create();
            observations
                    .observationConfig()
                    .observationHandler(new DefaultTracingObservationHandler(tracer));
            var routes =
                    new ModelRoutes(
                            DeploymentModels.from("demo", "", ""),
                            req -> {
                                var scope = Span.current().getSpanContext();
                                assertEquals(traceId, scope.getTraceId());
                                assertNotEquals(parentId, scope.getSpanId());
                                assertTrue(scope.isSampled());
                                return new ModelResult("ok", 1, 1);
                            },
                            null,
                            List.of(),
                            new SimpleMeterRegistry(),
                            observations);
            Observation.createNotStarted("agent.step", observations)
                    .observe(
                            () -> {
                                assertEquals(traceId, Span.current().getSpanContext().getTraceId());
                                routes.complete(
                                        "echo",
                                        null,
                                        new ModelRequest("Help", "test", 10),
                                        id -> {});
                            });
        }
    }
}
