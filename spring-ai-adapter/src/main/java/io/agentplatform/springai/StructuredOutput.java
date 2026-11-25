package io.agentplatform.springai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.json.JsonMapper;

import io.agentplatform.spi.ModelGateway;
import io.agentplatform.spi.ModelRequest;

import org.springframework.ai.converter.BeanOutputConverter;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * Reusable response contract for a deployment-owned DTO with mandatory fields and a business
 * validator.
 */
public final class StructuredOutput<T> {
    private static final JsonMapper MAPPER =
            JsonMapper.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                    .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                    .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
                    .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .build();
    private final ObjectReader reader;
    private final String format;
    private final Predicate<T> valid;

    public StructuredOutput(Class<T> type, Predicate<T> valid) {
        this.reader = MAPPER.readerFor(type);
        this.format = new BeanOutputConverter<>(type).getFormat();
        this.valid = Objects.requireNonNull(valid);
    }

    public T complete(ModelGateway gateway, ModelRequest request) {
        var instruction =
                new ModelRequest(
                        request.system() + "\n" + format,
                        request.input(),
                        request.maxOutputTokens(),
                        request.temperature());
        var result = gateway.complete(instruction);
        try {
            T value = reader.readValue(result.text());
            if (value != null && valid.test(value)) {
                return value;
            }
        } catch (JsonProcessingException ignored) {
            // Parser messages may include the raw provider response.
        }
        throw new IllegalStateException("Model output failed validation");
    }
}
