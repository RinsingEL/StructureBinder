package com.rinsing.geomantia.systems.provider.application;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AgentActivityStreamTest {
    @Test void longReasoningRetainsWhitespaceAndUnicodeAcrossPackets() {
        String text = "  first\n" + "城😀".repeat(900) + "\n end ";
        var events = new ArrayList<AgentActivityEvent>();
        AgentActivityEvent.emitText(events::add, "reasoning", text);
        assertEquals(text, events.stream().map(AgentActivityEvent::message).collect(java.util.stream.Collectors.joining()));
        assertTrue(events.stream().allMatch(e -> e.message().length() <= 600));
    }

    @Test void reasoningAndAssistantStreamAreDistinctAndFinalIsNotDuplicated() {
        var events = new ArrayList<AgentActivityEvent>();
        List<String> lines = new ArrayList<>();
        add(lines, "tool.progress", "_thinking", "this is an old content summary");
        add(lines, "tool.progress", "_geomantia_reasoning", "reasoning\n");
        add(lines, "assistant.delta", "", "answer ");
        add(lines, "assistant.delta", "", "continues");
        lines.add("event: assistant.completed"); lines.add("data: {\"content\":\"answer continues\"}");
        lines.add("event: run.completed"); lines.add("data: {}");
        var result = HermesAgentClient.streamSession(lines.stream(), events::add, null);
        assertEquals("completed", result.status());
        assertEquals(3, events.size());
        assertEquals("reasoning", events.get(0).kind());
        assertEquals("model_delta", events.get(1).kind());
        assertEquals("answer continues", events.get(1).message() + events.get(2).message());
    }

    private void add(List<String> lines, String event, String tool, String delta) {
        JsonObject data = new JsonObject(); data.addProperty("tool_name", tool); data.addProperty("delta", delta);
        lines.add("event: " + event); lines.add("data: " + data);
    }
}
