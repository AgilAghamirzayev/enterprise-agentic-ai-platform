package io.agentplatform.sdk;

import java.util.List;

/** Recent visible messages from the authenticated actor's current agent session. */
@FunctionalInterface
public interface MemoryGateway {
    List<Message> recent(int limit);

    record Message(String role, String content) {}
}
