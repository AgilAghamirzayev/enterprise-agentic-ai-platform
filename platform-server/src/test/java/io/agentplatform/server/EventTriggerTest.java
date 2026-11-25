package io.agentplatform.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentplatform.store.PlatformStore;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

class EventTriggerTest {
    private final ObjectMapper json = new ObjectMapper();
    private final PlatformStore store = mock(PlatformStore.class);
    private final EventTriggerController controller =
            new EventTriggerController(
                    store,
                    json,
                    new RunSubmission(store, io.micrometer.observation.ObservationRegistry.NOOP));
    private final String version = "1.0.0";
    private final UsernamePasswordAuthenticationToken principal =
            new UsernamePasswordAuthenticationToken(
                    "clock", "test", List.of(new SimpleGrantedAuthority("ROLE_operator")));
    private final EventTriggerController.Event event =
            new EventTriggerController.Event(
                    "1.0",
                    "2026-01-01",
                    "urn:daily-clock",
                    "report.due",
                    null,
                    json.getNodeFactory().textNode("Summarize."));

    private void binding(String subject) throws Exception {
        var trigger =
                json.readTree(
                        """
                        {"spec":{"source":"urn:daily-clock","type":"report.due","workflow":{"name":"reports","version":"1.0.0"}}}
                        """);
        ((com.fasterxml.jackson.databind.node.ObjectNode) trigger.get("spec"))
                .put("subject", subject);
        when(store.definition("local", "trigger", "daily", version)).thenReturn(trigger);
    }

    @Test
    void repeatDeliveryUsesOneStableIdempotencyKeyAndSameInvocationChecks() throws Exception {
        binding("clock");
        when(store.definition("local", "workflow", "reports", version))
                .thenReturn(
                        json.readTree(
                                """
                                {"spec":{"start":"done","nodes":[{"id":"done","type":"END"}]}}
                                """));
        var run = json.readTree("{\"id\":\"run-42\",\"status\":\"QUEUED\"}");
        when(store.startRun(
                        anyString(),
                        anyString(),
                        anyList(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        isNull()))
                .thenReturn(run);
        assertEquals(
                202,
                controller.deliver(principal, "daily", version, event).getStatusCode().value());
        controller.deliver(principal, "daily", version, event);
        var keys = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(store, times(2))
                .startRun(
                        eq("local"),
                        eq("clock"),
                        eq(List.of("operator")),
                        keys.capture(),
                        eq("workflow"),
                        eq("reports"),
                        eq(version),
                        eq(event.data()),
                        isNull());
        assertEquals(keys.getAllValues().getFirst(), keys.getAllValues().getLast());
    }

    @Test
    void rejectsEventFromWrongAuthenticatedProducerBeforeStart() throws Exception {
        binding("other-producer");
        assertThrows(
                ResponseStatusException.class,
                () -> controller.deliver(principal, "daily", version, event));
        verify(store, never())
                .startRun(
                        anyString(),
                        anyString(),
                        anyList(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        any());
    }
}
