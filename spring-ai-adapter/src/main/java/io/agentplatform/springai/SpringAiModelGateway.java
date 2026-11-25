package io.agentplatform.springai;

import io.agentplatform.spi.ModelChunk;
import io.agentplatform.spi.ModelProvider;
import io.agentplatform.spi.ModelRequest;
import io.agentplatform.spi.ModelResult;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Flow;

/** Adapts a deployment-configured, text-only Spring AI model. */
public final class SpringAiModelGateway implements ModelProvider {
    private final ChatModel model;

    private final String modelId;
    private final String providerId;
    private final Set<String> capabilities;

    public SpringAiModelGateway(ChatModel model) {
        this(model, null);
    }

    /** Model IDs are resolved from the operator's deployment inventory. */
    public SpringAiModelGateway(ChatModel model, String modelId) {
        this(model, modelId, "spring-ai", Set.of("text", "streaming", "structuredOutput"));
    }

    public SpringAiModelGateway(
            ChatModel model, String modelId, String providerId, Set<String> capabilities) {
        this.providerId = Objects.requireNonNull(providerId);
        this.capabilities = Set.copyOf(capabilities);
        this.model = Objects.requireNonNull(model);
        this.modelId = modelId;
    }

    @Override
    public String providerId() {
        return providerId;
    }

    @Override
    public Set<String> capabilities() {
        return capabilities;
    }

    private Prompt prompt(ModelRequest request) {
        var options =
                ToolCallingChatOptions.builder()
                        .internalToolExecutionEnabled(false)
                        .maxTokens(request.maxOutputTokens());
        if (modelId != null) {
            options.model(modelId);
        }
        if (request.temperature() != null) options.temperature(request.temperature());
        return new Prompt(
                List.of(new SystemMessage(request.system()), new UserMessage(request.input())),
                options.build());
    }

    @Override
    public ModelResult complete(ModelRequest request) {
        var response = model.call(prompt(request));
        if (response == null || response.hasToolCalls() || response.getResult() == null) {
            throw new IllegalStateException("Model returned an unsupported response");
        }
        String text = response.getResult().getOutput().getText();
        if (text == null || text.isBlank() || text.length() > 1_048_576) {
            throw new IllegalStateException("Model returned invalid text");
        }
        var usage = response.getMetadata().getUsage();
        Integer input = usage == null ? null : usage.getPromptTokens();
        Integer output = usage == null ? null : usage.getCompletionTokens();
        return new ModelResult(
                text,
                input != null && input > 0 ? input : request.estimatedInputTokens(),
                output != null && output > 0
                        ? output
                        : text.getBytes(StandardCharsets.UTF_8).length);
    }

    @Override
    public Flow.Publisher<ModelChunk> stream(ModelRequest request) {
        if (!capabilities.contains("streaming"))
            throw new IllegalStateException("Model does not support streaming");
        var chunks =
                Flux.defer(
                                () -> {
                                    var received = new java.util.concurrent.atomic.AtomicLong();
                                    return model.stream(prompt(request))
                                            .<ModelChunk>handle(
                                                    (response, sink) -> {
                                                        if (response.hasToolCalls()) {
                                                            sink.error(
                                                                    new IllegalStateException(
                                                                            "Unexpected tool"
                                                                                + " call"));
                                                            return;
                                                        }
                                                        var generation = response.getResult();
                                                        String delta =
                                                                generation == null
                                                                        ? null
                                                                        : generation
                                                                                .getOutput()
                                                                                .getText();
                                                        var usage =
                                                                response.getMetadata().getUsage();
                                                        int input =
                                                                usage == null
                                                                                || usage
                                                                                                .getPromptTokens()
                                                                                        == null
                                                                        ? 0
                                                                        : usage.getPromptTokens();
                                                        int output =
                                                                usage == null
                                                                                || usage
                                                                                                .getCompletionTokens()
                                                                                        == null
                                                                        ? 0
                                                                        : usage
                                                                                .getCompletionTokens();
                                                        if (received.addAndGet(
                                                                                delta == null
                                                                                        ? 0
                                                                                        : delta
                                                                                                .length())
                                                                        > 1_048_576
                                                                || output
                                                                        > request
                                                                                .maxOutputTokens()) {
                                                            sink.error(
                                                                    new IllegalStateException(
                                                                            "Stream exceeded its"
                                                                                + " output limit"));
                                                        } else if ((delta != null
                                                                        && !delta.isEmpty())
                                                                || input > 0
                                                                || output > 0) {
                                                            sink.next(
                                                                    new ModelChunk(
                                                                            delta == null
                                                                                    ? ""
                                                                                    : delta,
                                                                            input,
                                                                            output));
                                                        }
                                                    });
                                })
                        .timeout(java.time.Duration.ofSeconds(30));
        return JdkFlowAdapter.publisherToFlowPublisher(chunks);
    }
}
