package io.agentplatform.connectors;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Set;

class ConnectorPolicyTest {
    final ObjectMapper json = new ObjectMapper();

    @Test
    void onlyFixedHttpsEndpointsCanBeInstalled() {
        for (String url :
                new String[] {
                    "http://example.org/mcp",
                    "https://user@example.org",
                    "https://example.org/?token=secret"
                }) assertThrows(IllegalArgumentException.class, () -> binding(url));
    }

    @Test
    void disallowsUnregisteredArgumentsAndForeignTenants() {
        var binding = binding("https://catalog.example.org/items");
        var connector = binding.connect(json);
        assertTrue(connector.allowsTenant("north"));
        assertFalse(connector.allowsTenant("south"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        binding.validate(
                                json.createObjectNode().put("url", "https://other.example")));
        assertThrows(
                IllegalArgumentException.class,
                () -> binding.validate(json.createObjectNode().putArray("id").add(1)));
        assertDoesNotThrow(() -> binding.validate(json.createObjectNode().put("id", "SKU-4")));
    }

    ConfiguredTools.Binding binding(String url) {
        return new ConfiguredTools.Binding(
                "catalog", "north", "rest", URI.create(url), null, null, Set.of("id"));
    }
}
