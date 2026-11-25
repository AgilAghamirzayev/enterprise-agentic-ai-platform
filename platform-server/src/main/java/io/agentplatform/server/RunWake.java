package io.agentplatform.server;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;

import io.micrometer.core.instrument.MeterRegistry;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.workflowservice.v1.SignalWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.SignalWorkflowExecutionResponse;
import io.temporal.client.WorkflowClient;

import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Optional notification after a database commit. The durable worker timer also checks the decision.
 */
@Component
final class RunWake {
    private final WorkflowClient temporal;
    private final MeterRegistry meters;
    private final Semaphore inFlight = new Semaphore(128);

    RunWake(WorkflowClient temporal, MeterRegistry meters) {
        this.temporal = temporal;
        this.meters = meters;
    }

    void notifyWorker(String runId) {
        if (!inFlight.tryAcquire()) {
            deferred();
            return;
        }
        try {
            var request =
                    SignalWorkflowExecutionRequest.newBuilder()
                            .setNamespace(temporal.getOptions().getNamespace())
                            .setWorkflowExecution(
                                    WorkflowExecution.newBuilder().setWorkflowId(runId))
                            .setRequestId(UUID.randomUUID().toString())
                            .setSignalName("wake")
                            .build();
            var future =
                    temporal.getWorkflowServiceStubs()
                            .futureStub()
                            .withDeadlineAfter(1, TimeUnit.SECONDS)
                            .signalWorkflowExecution(request);
            Futures.addCallback(
                    future,
                    new FutureCallback<SignalWorkflowExecutionResponse>() {
                        public void onSuccess(SignalWorkflowExecutionResponse response) {
                            inFlight.release();
                        }

                        public void onFailure(Throwable unavailable) {
                            inFlight.release();
                            deferred();
                        }
                    },
                    Runnable::run);
        } catch (RuntimeException unavailable) {
            inFlight.release();
            deferred();
        }
    }

    private void deferred() {
        meters.counter("platform.wake.deferred").increment();
    }
}
