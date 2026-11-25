package io.agentplatform.server;

import io.temporal.api.enums.v1.TaskQueueType;
import io.temporal.api.taskqueue.v1.TaskQueue;
import io.temporal.api.workflowservice.v1.DescribeTaskQueueRequest;
import io.temporal.client.WorkflowClient;

import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/v1/status")
class StatusController {
    private final WorkflowClient temporal;
    private final HealthEndpoint health;

    StatusController(WorkflowClient temporal, HealthEndpoint health) {
        this.temporal = temporal;
        this.health = health;
    }

    record Status(String api, String temporal, String worker) {}

    @GetMapping
    Status status(Authentication auth) {
        Identity.from(auth).require("auditor", "operator", "developer");
        String api = health.health().getStatus().getCode();
        try {
            var response =
                    temporal.getWorkflowServiceStubs()
                            .blockingStub()
                            .withDeadlineAfter(2, TimeUnit.SECONDS)
                            .describeTaskQueue(
                                    DescribeTaskQueueRequest.newBuilder()
                                            .setNamespace(temporal.getOptions().getNamespace())
                                            .setTaskQueue(
                                                    TaskQueue.newBuilder().setName("agent-runtime"))
                                            .setTaskQueueType(
                                                    TaskQueueType.TASK_QUEUE_TYPE_WORKFLOW)
                                            .build());
            return new Status(
                    api, "UP", response.getPollersCount() > 0 ? "POLLING" : "NO_RECENT_POLLER");
        } catch (io.grpc.StatusRuntimeException failure) {
            return new Status(api, "UNAVAILABLE", "UNKNOWN");
        }
    }
}
