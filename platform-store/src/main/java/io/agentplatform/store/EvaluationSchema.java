package io.agentplatform.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;

import java.util.HashSet;
import java.util.Set;

/** Offline structural subset of JSON Schema 2020-12 accepted from tenant-authored datasets. */
final class EvaluationSchema {
    private static final JsonSchemaFactory FACTORY =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    private static final Set<String> TYPES =
            Set.of("object", "array", "boolean", "number", "integer", "null", "string");
    private static final Set<String> KEYS =
            Set.of(
                    "type",
                    "properties",
                    "items",
                    "required",
                    "additionalProperties",
                    "enum",
                    "const");

    static JsonSchema compile(JsonNode schema) {
        validate(schema, 0);
        return FACTORY.getSchema(schema);
    }

    private static void validate(JsonNode schema, int depth) {
        if (depth > 12 || !schema.isObject() || !TYPES.contains(schema.path("type").asText()))
            throw new StoreException(400, "Invalid evaluation schema type or depth");
        schema.fieldNames()
                .forEachRemaining(
                        k -> {
                            if (!KEYS.contains(k))
                                throw new StoreException(
                                        400, "Unsupported evaluation schema keyword");
                        });
        if (schema.has("properties")) {
            if (!schema.get("properties").isObject()
                    || !"object".equals(schema.path("type").asText()))
                throw new StoreException(400, "properties requires an object schema");
            schema.get("properties").forEach(child -> validate(child, depth + 1));
        }
        if (schema.has("required")) {
            if (!schema.get("required").isArray() || !"object".equals(schema.path("type").asText()))
                throw new StoreException(400, "required must be an array on an object schema");
            var unique = new HashSet<String>();
            schema.get("required")
                    .forEach(
                            k -> {
                                if (!k.isTextual() || !unique.add(k.textValue()))
                                    throw new StoreException(400, "Invalid required field");
                            });
        }
        if (schema.has("additionalProperties") && !schema.get("additionalProperties").isBoolean())
            throw new StoreException(400, "additionalProperties must be boolean");
        if (schema.has("items")) {
            if (!"array".equals(schema.path("type").asText()))
                throw new StoreException(400, "items requires an array schema");
            validate(schema.get("items"), depth + 1);
        }
        if (schema.has("enum") && (!schema.get("enum").isArray() || schema.get("enum").isEmpty()))
            throw new StoreException(400, "enum must be a nonempty array");
    }
}
