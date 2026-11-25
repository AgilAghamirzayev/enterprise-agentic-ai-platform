package io.agentplatform.spi;

import java.util.Set;
import java.util.concurrent.Flow;

/** Provider adapter SPI. Subscriptions obey downstream demand and cancellation. */
public interface ModelProvider extends ModelGateway {
    default ModelResult generate(ModelRequest request) {
        return complete(request);
    }

    String providerId();

    Set<String> capabilities();

    Flow.Publisher<ModelChunk> stream(ModelRequest request);
}
