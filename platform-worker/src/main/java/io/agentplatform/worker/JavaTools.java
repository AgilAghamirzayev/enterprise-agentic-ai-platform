package io.agentplatform.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentplatform.boot.PlatformTool;
import io.agentplatform.connectors.ExternalTool;
import io.agentplatform.sdk.AgentTool;

import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.util.Set;

final class JavaTools {
    private JavaTools() {}

    static <I, O> ExternalTool adapt(AgentTool<I, O> impl, ObjectMapper json) {
        var binding =
                AnnotatedElementUtils.findMergedAnnotation(
                        AopUtils.getTargetClass(impl), PlatformTool.class);
        if (binding == null
                || !binding.name().matches("[a-z][a-z0-9-]{0,62}")
                || !binding.version().matches("[0-9]+[.][0-9]+[.][0-9]+"))
            throw new IllegalArgumentException(
                    "Java tool requires a valid @PlatformTool name and version");
        var tenants = Set.of(binding.tenants());
        if (tenants.isEmpty() || tenants.stream().anyMatch(String::isBlank))
            throw new IllegalArgumentException("Java tool requires explicit tenants");
        return new ExternalTool() {
            public String name() {
                return binding.name();
            }

            public String version() {
                return binding.version();
            }

            public boolean readOnly() {
                return binding.readOnly();
            }

            public boolean allowsTenant(String tenant) {
                return tenants.contains(tenant);
            }

            public JsonNode invoke(JsonNode args) {
                if (!readOnly()) throw new IllegalStateException("Write requires approval");
                return call(args, null);
            }

            public JsonNode invokeApproved(JsonNode args, String key) {
                if (key == null || key.isBlank())
                    throw new IllegalArgumentException("Missing idempotency key");
                return call(args, key);
            }

            private JsonNode call(JsonNode args, String key) {
                return json.valueToTree(
                        impl.execute(json.convertValue(args, impl.inputType()), key));
            }
        };
    }
}
