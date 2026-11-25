package io.agentplatform.testkit;

import io.agentplatform.spi.ModelGateway;
import io.agentplatform.spi.ModelRequest;
import io.agentplatform.spi.ModelResult;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Function;

/** One UTF-8 byte is one token in this deterministic test provider. */
public final class FakeModelGateway implements ModelGateway {
    private final Function<ModelRequest, String> script;

    public FakeModelGateway() {
        this(ModelRequest::input);
    }

    public FakeModelGateway(Function<ModelRequest, String> script) {
        this.script = Objects.requireNonNull(script);
    }

    @Override
    public ModelResult complete(ModelRequest request) {
        var text = script.apply(request);
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        int end = Math.min(bytes.length, request.maxOutputTokens());
        while (end < bytes.length && end > 0 && (bytes[end] & 0xc0) == 0x80) {
            end--;
        }
        return new ModelResult(new String(bytes, 0, end, StandardCharsets.UTF_8), request.estimatedInputTokens(), end);
    }
}
