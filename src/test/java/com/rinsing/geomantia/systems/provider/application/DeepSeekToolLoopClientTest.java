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
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepSeekToolLoopClientTest {
    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<JsonObject> secondRequest = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/responses", exchange -> {
            JsonObject body = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8)).getAsJsonObject();
            if (requests.incrementAndGet() == 1) {
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

    private static void reply(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
