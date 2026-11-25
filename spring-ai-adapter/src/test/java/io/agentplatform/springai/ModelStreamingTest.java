package io.agentplatform.springai;

import static org.junit.jupiter.api.Assertions.*;

import io.agentplatform.spi.ModelRequest;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;

import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

class ModelStreamingTest {
    @Test
    void deliversActualDeltasAndPropagatesCancellationToProvider() {
        var cancelled = new AtomicBoolean();
        ChatModel model =
                new ChatModel() {
                    public ChatResponse call(Prompt prompt) {
                        throw new AssertionError("Expected stream");
                    }

                    public Flux<ChatResponse> stream(Prompt prompt) {
                        assertEquals(0.2, prompt.getOptions().getTemperature());
                        return Flux.just("one", " two")
                                .map(
                                        delta ->
                                                new ChatResponse(
                                                        List.of(
                                                                new Generation(
                                                                        new AssistantMessage(
                                                                                delta)))))
                                .concatWith(Flux.never())
                                .doOnCancel(() -> cancelled.set(true));
                    }
                };
        var provider =
                new SpringAiModelGateway(model, "local-v1", "ollama", Set.of("text", "streaming"));
        var request = new ModelRequest("Summarize", "test input", 8, 0.2);
        var chunks =
                JdkFlowAdapter.flowPublisherToFlux(provider.stream(request))
                        .take(2)
                        .collectList()
                        .block();
        assertEquals(List.of("one", " two"), chunks.stream().map(c -> c.text()).toList());
        assertTrue(cancelled.get());
        assertEquals("ollama", provider.providerId());
        assertThrows(
                IllegalStateException.class,
                () ->
                        new SpringAiModelGateway(model, "local", "ollama", Set.of("text"))
                                .stream(request));
    }
}
