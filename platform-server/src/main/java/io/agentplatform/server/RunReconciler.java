package io.agentplatform.server;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentplatform.store.PlatformStore;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.micrometer.core.instrument.MeterRegistry;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.api.workflowservice.v1.DescribeNamespaceRequest;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.client.WorkflowClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Component
final class RunReconciler {
    private static final Logger LOG = LoggerFactory.getLogger(RunReconciler.class);
    private static final Duration MISSING_GRACE = Duration.ofHours(1);
    private static final Set<WorkflowExecutionStatus> CLOSED =
            Set.of(
                    WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_COMPLETED,
                    WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_FAILED,
                    WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_TIMED_OUT,
                    WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_CANCELED,
                    WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_TERMINATED);
    private final PlatformStore store;
    private final WorkflowClient temporal;
    private final MeterRegistry meters;
    private final ObjectMapper json;

    RunReconciler(
            PlatformStore store, WorkflowClient temporal, MeterRegistry meters, ObjectMapper json) {
        this.store = store;
        this.temporal = temporal;
        this.meters = meters;
        this.json = json;
    }

    @Scheduled(fixedDelayString = "${platform.reconciliation.interval:15s}")
    void reconcile() {
        for (var command : store.dueReconciliations(16, 60)) {
            try {
                inspect(command);
            } catch (RuntimeException unavailable) {
                meters.counter("platform.reconciliation.deferred").increment();
                LOG.warn("Run reconciliation deferred for {}", command.runId());
            }
        }
    }

    private void inspect(PlatformStore.StartCommand command) {
        var ns = temporal.getOptions().getNamespace();
        var execution = WorkflowExecution.newBuilder().setWorkflowId(command.runId());
        var request =
                DescribeWorkflowExecutionRequest.newBuilder()
                        .setNamespace(ns)
                        .setExecution(execution)
                        .build();
        var service = temporal.getWorkflowServiceStubs().blockingStub();
        try {
            var info =
                    service.withDeadlineAfter(2, TimeUnit.SECONDS)
                            .describeWorkflowExecution(request)
                            .getWorkflowExecutionInfo();
            if ("AgentRunV1".equals(info.getType().getName())
                    && CLOSED.contains(info.getStatus())) {
                flag(command, info.getStatus().name());
            }
        } catch (StatusRuntimeException error) {
            if (error.getStatus().getCode() != Status.Code.NOT_FOUND) throw error;
            var run = store.run(command.tenant(), command.runId());
            if (Instant.parse(run.required("createdAt").asText())
                    .plus(MISSING_GRACE)
                    .isAfter(Instant.now())) return;
            // A missing namespace is an infrastructure issue. Leave the business state open in that
            // case.
            service.withDeadlineAfter(2, TimeUnit.SECONDS)
                    .describeNamespace(
                            DescribeNamespaceRequest.newBuilder().setNamespace(ns).build());
            flag(command, "HISTORY_UNAVAILABLE");
        }
    }

    private void flag(PlatformStore.StartCommand command, String upstream) {
        var diagnostic =
                json.createObjectNode()
                        .put("code", "RECONCILIATION_REQUIRED")
                        .put("temporalStatus", upstream)
                        .put(
                                "detail",
                                "Inspect recorded steps and external effects before starting"
                                        + " another run.");
        store.finish(command.tenant(), command.runId(), "FAILED", diagnostic);
        meters.counter("platform.reconciliation.flagged").increment();
    }

    @Configuration(proxyBeanMethods = false)
    static class Scheduling {
        @Bean
        ThreadPoolTaskScheduler taskScheduler() {
            var scheduler = new ThreadPoolTaskScheduler();
            scheduler.setPoolSize(3);
            scheduler.setThreadNamePrefix("platform-dispatch-");
            return scheduler;
        }
    }
}
