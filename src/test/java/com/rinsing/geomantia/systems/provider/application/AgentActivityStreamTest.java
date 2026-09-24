package com.rinsing.geomantia.systems.provider.application;
import com.rinsing.geomantia.harness.systems.provider.application.*;

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

}
