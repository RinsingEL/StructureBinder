package com.rinsing.geomantia.harness.systems.provider.application;
import com.rinsing.geomantia.harness.systems.provider.application.*;

import org.junit.jupiter.api.Test;
import java.net.URI;
import static org.junit.jupiter.api.Assertions.*;

class ProviderRequestHeadersTest {
    @Test void sameConversationKeepsAffinityAcrossEndpointsButDifferentConversationsDoNot() {
        String session = ProviderRequestHeaders.session("city/conversation-1");
        assertEquals(session, ProviderRequestHeaders.session("city/conversation-1"));
        assertNotEquals(session, ProviderRequestHeaders.session("city/conversation-2"));
        assertNotEquals(ProviderRequestHeaders.session(null), ProviderRequestHeaders.session(null));
        for (String endpoint : new String[]{"models", "chat/completions", "responses"}) {
            var request = ProviderRequestHeaders.request(URI.create("https://opencode.ai/zen/go/v1/" + endpoint), session).GET().build();
            assertEquals(session, request.headers().firstValue("x-opencode-session").orElseThrow());
            assertEquals("Geomantia/0.1.0", request.headers().firstValue("User-Agent").orElseThrow());
        }
    }

    @Test void affinityDoesNotLeakToOtherProvidersOrLookalikeDomains() {
        for (String url : new String[]{"https://api.deepseek.com/v1", "https://opencode.ai.example.com/v1", "https://example.com/opencode.ai", "http://localhost/v1"}) {
            var request = ProviderRequestHeaders.request(URI.create(url), "session").GET().build();
            assertTrue(request.headers().firstValue("x-opencode-session").isEmpty());
        }
    }
}
