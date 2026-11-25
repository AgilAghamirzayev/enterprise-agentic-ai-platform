package io.agentplatform.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;

import java.net.URI;
import java.util.Set;

/** Locally compiled tool schemas. The accepted vocabulary cannot load external resources. */
public record ToolContract(JsonSchema input, JsonSchema output) {
    private static final JsonSchemaFactory FACTORY =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    private static final JsonSchema META =
            FACTORY.getSchema(URI.create("https://json-schema.org/draft/2020-12/schema"));
    private static final Set<String> KEYWORDS =
            Set.of(
                    "type",
                    "title",
                    "description",
                    "properties",
                    "required",
                    "additionalProperties",
                    "items",
                    "minItems",
                    "maxItems",
                    "minLength",
                    "maxLength",
                    "minimum",
                    "maximum",
                    "enum",
                    "const",
                    "minProperties",
                    "maxProperties");

    public static ToolContract from(JsonNode spec) {
        if (!spec.isObject()
                || spec.size() != (spec.has("target") ? 3 : 2)
                || spec.toString().length() > 32768) invalid();
        if (spec.has("target")) {
            var target = spec.get("target");
            if (!target.isObject()
                    || target.size() != 2
                    || !Set.of("agent", "workflow").contains(target.path("kind").asText())
                    || !target.path("ref")
                            .asText()
                            .matches("[a-z][a-z0-9-]{0,62}:[0-9]+[.][0-9]+[.][0-9]+")) invalid();
        }
        return new ToolContract(
                compile(spec.required("inputSchema")), compile(spec.required("outputSchema")));
    }

    private static JsonSchema compile(JsonNode schema) {
        check(schema, 0);
        if (!META.validate(schema).isEmpty()) invalid();
        return FACTORY.getSchema(schema);
    }

    private static void check(JsonNode schema, int depth) {
        if (!schema.isObject() || !schema.has("type") || depth > 12) invalid();
        schema.fieldNames()
                .forEachRemaining(
                        key -> {
                            if (!KEYWORDS.contains(key)) invalid();
                        });
        if (schema.has("properties"))
            schema.get("properties").elements().forEachRemaining(child -> check(child, depth + 1));
        if (schema.has("items")) check(schema.get("items"), depth + 1);
        if (schema.has("additionalProperties") && !schema.get("additionalProperties").isBoolean())
            invalid();
    }

    public void validateInput(JsonNode value) {
        if (value == null || !input.validate(value).isEmpty())
            throw new IllegalArgumentException("Tool input violates its published schema");
    }

    public void validateOutput(JsonNode value) {
        if (value == null || !output.validate(value).isEmpty())
            throw new IllegalArgumentException("Tool output violates its published schema");
    }

    private static void invalid() {
        throw new IllegalArgumentException("Invalid tool schema");
    }
}
