package io.agentplatform.worker;

import io.agentplatform.store.PlatformStore.IngestionRef;
import io.temporal.activity.ActivityInterface;

@ActivityInterface(namePrefix = "Knowledge")
public interface KnowledgeActivities {
    void index(IngestionRef job);

    void fail(IngestionRef job);
}
