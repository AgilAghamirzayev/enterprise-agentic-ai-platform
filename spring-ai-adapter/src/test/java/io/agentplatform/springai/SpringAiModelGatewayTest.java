package io.agentplatform.springai;

import io.agentplatform.spi.ModelRequest;
import io.agentplatform.spi.ModelResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import static org.junit.jupiter.api.Assertions.*;

class SpringAiModelGatewayTest {
    private static final ModelRequest REQUEST = new ModelRequest("Summarize", "user text", 8);

    @Test
    void limitsOutputAndDisablesProviderToolExecution() {
        var gateway = new SpringAiModelGateway(prompt -> {
            assertEquals(8, prompt.getOptions().getMaxTokens());
            assertEquals(false, ((ToolCallingChatOptions) prompt.getOptions()).getInternalToolExecutionEnabled());
            assertEquals(REQUEST.system(), prompt.getSystemMessage().getText());
            assertEquals(REQUEST.input(), prompt.getUserMessage().getText());
            return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
        });
        assertEquals(new ModelResult("ok", REQUEST.estimatedInputTokens(), 2), gateway.complete(REQUEST));
    }

    record Label(String category, int confidence) {}

    @Test
    void validatesStructuredResponsesAndRejectsMalformedOrInvalidData() {
        var format = new StructuredOutput<>(Label.class, v -> "order".equals(v.category()) && v.confidence() <= 100);
        var parsed = format.complete(request -> {
            assertTrue(request.system().contains("confidence"));
            return new ModelResult("{\"category\":\"order\",\"confidence\":85}", 1, 1);
        }, REQUEST);
        assertEquals(new Label("order", 85), parsed);
        for (String invalid : List.of("not JSON", "{}", "{\"category\":\"order\"}",
                "{\"category\":\"order\",\"confidence\":101}", "{\"category\":\"order\",\"confidence\":\"45\"}")) {
            assertThrows(IllegalStateException.class, () -> format.complete(request -> new ModelResult(invalid, 1, 1), REQUEST));
        }
    }
}
