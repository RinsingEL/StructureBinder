package com.rinsing.geomantia.harness.systems.provider.application;
import com.rinsing.geomantia.systems.provider.application.*;
import com.rinsing.geomantia.harness.systems.provider.application.*;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class HarnessBoundaryTest {
    @TempDir Path directory;

    @Test void designVerdictsDoNotTripTransportFailureButRepeatedRejectionStillStops() throws Exception {
        JsonObject rejected = JsonParser.parseString("{ok:false,validationReport:{valid:false,issues:[{reasonCode:'FIELD_MISSING'}]}}").getAsJsonObject();
        var control = new PlanningTurnControl((tool, args) -> rejected);
        for (int i = 0; i < 3; i++) {
            JsonObject result = ProviderToolBridge.mcpResult(control.execute("city_submit_d4_blueprint", new JsonObject()));
            assertFalse(result.get("isError").getAsBoolean());
            assertEquals(rejected, JsonParser.parseString(result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString()));
        }
        assertTrue(control.finished());
        assertTrue(control.result(3).errorCode().startsWith("PLANNING_REPEATED_REJECTION"));
        assertTrue(ProviderToolBridge.mcpResult(JsonParser.parseString("{ok:false,error:'ConnectException'}"))
                .get("isError").getAsBoolean());
    }

    @Test void sidecarUsesTheHostsCurrentDecisionSchemaNotTheRawMcpWorkflowSchema() throws Exception {
        try (var bridge = new ProviderToolBridge()) {
            bridge.bind(List.of("realm_t4_patch_planning_select_capital"), (tool, args) -> new JsonObject());
            var client = HttpClient.newHttpClient();
            var request = HttpRequest.newBuilder(URI.create(bridge.url())).header("X-Geomantia-Bridge-Key", bridge.token()).GET().build();
            JsonObject result = JsonParser.parseString(client.send(request, HttpResponse.BodyHandlers.ofString()).body()).getAsJsonObject();
            assertEquals(1, result.getAsJsonArray("tools").size());
            JsonObject schema = result.getAsJsonArray("tools").get(0).getAsJsonObject().getAsJsonObject("inputSchema");
            assertTrue(schema.getAsJsonObject("properties").has("candidateId"));
            assertFalse(schema.getAsJsonArray("required").toString().contains("patchSelectionRef"));
            assertEquals(403, client.send(HttpRequest.newBuilder(URI.create(bridge.url())).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }
    @Test void harnessInitialMessageContainsActualImages() throws Exception {
        Path image = directory.resolve("preview.png"); Files.write(image, new byte[]{1,2,3});
        JsonArray content = HarnessAgentClient.promptContent(new JsonObject(), List.of(image));
        assertEquals("data:image/png;base64,AQID", content.asList().stream().map(JsonElement::getAsJsonObject)
                .filter(p -> p.has("image_url")).findFirst().orElseThrow().get("image_url").getAsString());
    }

    @Test void harnessMissingImageFailsRatherThanSilentlyLosingEvidence() {
        assertThrows(java.io.IOException.class, () -> HarnessAgentClient.promptContent(new JsonObject(), List.of(directory.resolve("missing.png"))));
    }

    @Test void sidecarBridgeUsesHostExecutorAndChecksCapabilityAndWhitelist() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (var bridge = new ProviderToolBridge()) {
            bridge.bind(List.of("design"), (tool,args) -> { calls.incrementAndGet(); return new JsonPrimitive("{\"ok\":true}"); });
            var client = HttpClient.newHttpClient();
            var request = HttpRequest.newBuilder(URI.create(bridge.url())).header("X-Geomantia-Bridge-Key", bridge.token())
                    .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"design\",\"arguments\":{}}"));
            assertEquals(200, client.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(1, calls.get()); bridge.unbind();
            assertEquals(403, client.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(1, calls.get());
        }
    }
}
