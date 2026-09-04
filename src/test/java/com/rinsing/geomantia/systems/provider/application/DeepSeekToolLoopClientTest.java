package com.rinsing.geomantia.systems.provider.application;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonArray;
import com.rinsing.geomantia.systems.provider.application.ProviderConfigStore.Credentials;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepSeekToolLoopClientTest {
    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<JsonObject> secondRequest = new AtomicReference<>();
    private final AtomicInteger chatRequests = new AtomicInteger();
    private final AtomicReference<JsonObject> secondChatRequest = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/responses", exchange -> {
            JsonObject body = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8)).getAsJsonObject();
            if (requests.incrementAndGet() == 1) {
                assertTrue(body.get("instructions").getAsString().contains(
                        "CONNECTION is the only relation kind that creates a terrain-routed main road"));
                assertTrue(body.get("instructions").getAsString().contains(
                        "Never combine fields from these two families"));
                assertEquals("required", body.get("tool_choice").getAsString());
                assertEquals("none", body.getAsJsonObject("reasoning").get("effort").getAsString());
                assertEquals("city_design_queue_status",
                        body.getAsJsonArray("tools").get(0).getAsJsonObject().get("name").getAsString());
                JsonObject call = new JsonObject();
                call.addProperty("type", "function_call");
                call.addProperty("call_id", "fc_1");
                call.addProperty("name", "city_design_queue_status");
                call.addProperty("arguments", "{}");
                JsonArray output = new JsonArray();
                output.add(call);
                JsonObject response = new JsonObject();
                response.addProperty("status", "completed");
                response.add("output", output);
                reply(exchange, response.toString());
            } else {
                secondRequest.set(body);
                reply(exchange, """
                        {"status":"completed","output":[{"type":"message","role":"assistant",
                        "content":[{"type":"output_text","text":"Queue inspected."}]}]}
                        """);
            }
        });
        server.createContext("/chat/completions", exchange -> {
            JsonObject body = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8)).getAsJsonObject();
            if (chatRequests.incrementAndGet() == 1) {
                assertEquals("auto", body.get("tool_choice").getAsString());
                assertEquals("function", body.getAsJsonArray("tools").get(0).getAsJsonObject()
                        .get("type").getAsString());
                assertEquals("city_design_queue_status", body.getAsJsonArray("tools").get(0).getAsJsonObject()
                        .getAsJsonObject("function").get("name").getAsString());
                assertEquals("enabled", body.getAsJsonObject("thinking").get("type").getAsString());
                reply(exchange, """
                        {"choices":[{"message":{"role":"assistant","content":"",
                        "tool_calls":[{"id":"fc_chat_1","type":"function","function":
                        {"name":"city_design_queue_status","arguments":"{}"}}]}}]}
                        """);
            } else {
                secondChatRequest.set(body);
                reply(exchange, """
                        {"choices":[{"message":{"role":"assistant","content":"Queue inspected via chat."}}]}
                        """);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void executesFunctionAndReturnsItsOutputInStatelessHistory() {
        PlayerProviderConfig config = new PlayerProviderConfig(PlayerProviderConfig.CUSTOM, true,
                "http://127.0.0.1:" + server.getAddress().getPort(), "vision-model", 10);
        AtomicReference<String> called = new AtomicReference<>();

        var result = new DeepSeekToolLoopClient().run(config, new Credentials("test-key", "stored"),
                JsonParser.parseString("{\"status\":\"waiting_for_agent\"}").getAsJsonObject(),
                List.of("city_design_queue_status"), (name, arguments) -> {
                    called.set(name);
                    return new JsonPrimitive("{\"ok\":true,\"status\":\"completed\"}");
                });

        assertTrue(result.success());
        assertEquals(1, result.toolCalls());
        assertEquals("Queue inspected.", result.finalText());
        assertEquals("city_design_queue_status", called.get());
        assertTrue(secondRequest.get().getAsJsonArray("input").toString().contains("function_call_output"));
        assertTrue(secondRequest.get().getAsJsonArray("input").toString().contains("status\\\":\\\"completed"));
    }

    @Test
    void executesFunctionThroughChatCompletionsHistory() {
        PlayerProviderConfig config = new PlayerProviderConfig(PlayerProviderConfig.CUSTOM, true,
                "http://127.0.0.1:" + server.getAddress().getPort(), "glm-5.3-flash",
                PlayerProviderConfig.CHAT_COMPLETIONS, 10);
        AtomicReference<String> called = new AtomicReference<>();

        var result = new DeepSeekToolLoopClient().run(config, new Credentials("test-key", "stored"),
                JsonParser.parseString("{\"status\":\"waiting_for_agent\"}").getAsJsonObject(),
                List.of("city_design_queue_status"), (name, arguments) -> {
                    called.set(name);
                    return new JsonPrimitive("{\"ok\":true,\"status\":\"completed\"}");
                });

        assertTrue(result.success());
        assertEquals(1, result.toolCalls());
        assertEquals("Queue inspected via chat.", result.finalText());
        assertEquals("city_design_queue_status", called.get());
        assertTrue(secondChatRequest.get().getAsJsonArray("messages").toString().contains("tool_call_id"));
        assertTrue(secondChatRequest.get().getAsJsonArray("messages").toString()
                .contains("status\\\":\\\"completed"));
    }

    @Test
    void submitBlueprintSchemaExplainsTheOnlyMainRoadTrigger() {
        JsonObject tool = ProviderPlanningToolCatalog.definitions(List.of("city_submit_d4_blueprint"))
                .get(0).getAsJsonObject();
        assertTrue(tool.get("description").getAsString().contains(
                "CONNECTION is the only relation kind that generates a terrain-routed main road"));
        JsonObject relationKind = tool.getAsJsonObject("parameters").getAsJsonObject("properties")
                .getAsJsonObject("cityBlueprint").getAsJsonObject("properties")
                .getAsJsonObject("relations").getAsJsonObject("items").getAsJsonObject("properties")
                .getAsJsonObject("relationKind");
        assertTrue(relationKind.get("description").getAsString().contains(
                "ADJACENCY and HIERARCHY may organize compact districts but do not create a road"));
        JsonObject parameters = tool.getAsJsonObject("parameters").getAsJsonObject("properties")
                .getAsJsonObject("cityBlueprint").getAsJsonObject("properties")
                .getAsJsonObject("groups").getAsJsonObject("items").getAsJsonObject("properties")
                .getAsJsonObject("connectionPlan").getAsJsonObject("properties")
                .getAsJsonObject("parameters");
        assertTrue(parameters.get("description").getAsString().contains("Never mix them"));
        assertEquals(2, parameters.getAsJsonArray("anyOf").size());
        JsonObject compound = parameters.getAsJsonArray("anyOf").get(0).getAsJsonObject();
        JsonObject dualSide = parameters.getAsJsonArray("anyOf").get(1).getAsJsonObject();
        assertTrue(compound.getAsJsonObject("properties").has("clusterShape"));
        assertEquals(1, compound.getAsJsonObject("properties").size());
        assertTrue(dualSide.getAsJsonObject("properties").has("sideMode"));
        assertEquals(3, dualSide.getAsJsonObject("properties").size());
    }

    @Test
    void compactsD4ContextForProviderTransportWithoutChangingFormalEvidenceFields() {
        JsonObject response = JsonParser.parseString("""
                {"ok":true,"contextId":"sha256:test","cityBlueprintContext":{
                 "patchReviewEvidence":{"interestTypes":["plain"]},
                 "d3ReviewPackage":{"landformPatches":[
                    {"landformPatchId":"plain_1","landformType":"plain","areaBlocks":4096,
                     "memberCells":[{"cellX":1,"cellZ":2}],"neighborLandformPatchIds":["slope_1"],
                     "summaryFacts":["flat"],"metricsSummary":{"slopeP90":2.0}},
                    {"landformPatchId":"slope_1","landformType":"slope","areaBlocks":2048,
                     "memberCells":[{"cellX":3,"cellZ":4}]}]}}}
                """).getAsJsonObject();

        JsonObject delivered = DeepSeekToolLoopClient.modelToolOutput(
                "city_prepare_d4_blueprint_context", response).getAsJsonObject();

        assertEquals("sha256:test", delivered.get("contextId").getAsString());
        JsonObject deliveredContext = delivered.getAsJsonObject("cityBlueprintContext");
        JsonArray patches = deliveredContext.getAsJsonObject("d3ReviewPackage")
                .getAsJsonArray("landformPatches");
        assertEquals(1, patches.size());
        assertEquals("plain_1", patches.get(0).getAsJsonObject().get("landformPatchId").getAsString());
        assertFalse(patches.get(0).getAsJsonObject().has("memberCells"));
        assertTrue(patches.get(0).getAsJsonObject().has("metricsSummary"));
        assertEquals(2, deliveredContext.getAsJsonObject("agentDeliveryProjection")
                .get("sourcePatchCount").getAsInt());
        assertTrue(response.getAsJsonObject("cityBlueprintContext").getAsJsonObject("d3ReviewPackage")
                .getAsJsonArray("landformPatches")
                .get(0).getAsJsonObject().has("memberCells"));
    }

    private static void reply(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
