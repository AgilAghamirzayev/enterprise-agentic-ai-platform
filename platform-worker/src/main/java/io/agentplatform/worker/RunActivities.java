package io.agentplatform.worker;

import static io.agentplatform.worker.AgentRun.RunRef;

import io.agentplatform.plan.Plan;
import io.temporal.activity.ActivityInterface;

import java.util.List;

@ActivityInterface
public interface RunActivities {
    /**
     * Only execution topology enters history. User input, prompts, documents and results stay in
     * the store.
     */
    record Definition(String kind, String ref) {}

    record Route(
            String id,
            Plan.Type type,
            String next,
            String onTrue,
            String onFalse,
            String onApproved,
            String onRejected,
            List<String> branches,
            int seconds,
            Definition child) {}

    record Graph(String start, List<Route> routes, Plan.Limits limits, List<String> allowedTools) {
        public Graph {
            allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
        }
    }

    record Step(
            RunRef run,
            Definition definition,
            String node,
            String key,
            String inputKey,
            long deadline,
            Plan.Limits ceiling) {}

    void begin(RunRef run);

    Graph graph(RunRef run, Definition definition);

    void check(RunRef run);

    void proposeTool(Step step);

    String execute(Step step);

    void join(RunRef run, String key, List<String> branches, List<String> outputs);

    void finish(RunRef run, String status, String resultKey);
}
