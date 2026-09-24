package com.rinsing.geomantia.harness.systems.provider.application;
import com.rinsing.geomantia.systems.provider.application.*;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class DynamicPlanningToolsTest {
    @Test void directProtocolsSendTheCurrentTasksSchemaAndExecuteItsCallback() throws Exception {
        for (String protocol : List.of(PlayerProviderConfig.RESPONSES, PlayerProviderConfig.CHAT_COMPLETIONS)) {
            var received = new AtomicReference<JsonObject>();
            var called = new AtomicReference<String>();
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/", exchange -> {
                received.set(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
                String response = protocol.equals(PlayerProviderConfig.RESPONSES)
                    ? "{\"status\":\"completed\",\"output\":[{\"type\":\"function_call\",\"call_id\":\"one\",\"name\":\"exampleaddon_publish\",\"arguments\":\"{}\"}]}"
                    : "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[{\"id\":\"one\",\"type\":\"function\",\"function\":{\"name\":\"exampleaddon_publish\",\"arguments\":\"{}\"}}]}}]}";
                byte[] bytes=response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
            });
            server.start();
            try {
                var executor = new PlanningToolExecutor() {
                    public JsonArray definitions(List<String> names) { return JsonParser.parseString("[{\"type\":\"function\",\"name\":\"exampleaddon_publish\",\"description\":\"Configure city\",\"parameters\":{\"type\":\"object\",\"properties\":{\"pool\":{\"type\":\"string\"}}}}]").getAsJsonArray(); }
                    public JsonElement execute(String name, JsonObject args) {
                        called.set(name);
                        return JsonParser.parseString("{\"ok\":true,\"hostDecisionCommitted\":true}");
                    }
                };
                var control = new PlanningTurnControl(executor);
                var config = new PlayerProviderConfig(PlayerProviderConfig.CUSTOM,true,
                        "http://127.0.0.1:"+server.getAddress().getPort(),"test-model",protocol,10);
                var result = new DeepSeekToolLoopClient().run(config,new ProviderConfigStore.Credentials("test-key","test"),
                        new JsonObject(),List.of("exampleaddon_publish"),control);
                assertTrue(result.success(),result.toString());
                assertEquals("exampleaddon_publish",called.get());
                var definition=received.get().getAsJsonArray("tools").get(0).getAsJsonObject();
                if (protocol.equals(PlayerProviderConfig.CHAT_COMPLETIONS)) definition=definition.getAsJsonObject("function");
                assertEquals("exampleaddon_publish",definition.get("name").getAsString());
                assertTrue(definition.getAsJsonObject("parameters").getAsJsonObject("properties").has("pool"));
            } finally { server.stop(0); }
        }
    }
}
