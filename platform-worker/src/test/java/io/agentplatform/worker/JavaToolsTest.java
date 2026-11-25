package io.agentplatform.worker;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentplatform.boot.PlatformTool;
import io.agentplatform.sdk.AgentTool;

import org.junit.jupiter.api.Test;

class JavaToolsTest {
    record Note(String text) {}

    @PlatformTool(
            name = "save-note",
            version = "1.2.0",
            tenants = {"north"},
            readOnly = false)
    static class Notes implements AgentTool<Note, String> {
        public Class<Note> inputType() {
            return Note.class;
        }

        public String execute(Note note, String key) {
            return key + "/" + note.text();
        }
    }

    @Test
    void typedAdapterReceivesApprovedInputAndDurableKey() {
        var json = new ObjectMapper();
        var tool = JavaTools.adapt(new Notes(), json);
        var args = json.createObjectNode().put("text", "reviewed");
        assertEquals("1.2.0", tool.version());
        assertTrue(tool.allowsTenant("north"));
        assertFalse(tool.allowsTenant("south"));
        assertThrows(IllegalStateException.class, () -> tool.invoke(args));
        assertEquals("one/reviewed", tool.invokeApproved(args, "one").asText());
    }
}
