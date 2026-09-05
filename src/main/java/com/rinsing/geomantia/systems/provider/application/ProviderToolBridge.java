package com.rinsing.geomantia.systems.provider.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** Loopback capability bridge: Hermes must call the same host-scoped executor as the embedded loop. */
final class ProviderToolBridge implements AutoCloseable {
    private final HttpServer server;
    private final String token = UUID.randomUUID().toString();
    private volatile Binding binding;
    ProviderToolBridge() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/execute", exchange -> {
            try {
                if (!token.equals(exchange.getRequestHeaders().getFirst("X-Geomantia-Bridge-Key"))) {
                    exchange.sendResponseHeaders(403, -1); return;
                }
                Binding current = binding;
                if ("GET".equals(exchange.getRequestMethod())) {
                    JsonArray definitions = new JsonArray();
                    for (JsonElement element : ProviderPlanningToolCatalog.definitions(current == null ? List.of() : current.tools)) {
                        JsonObject function = element.getAsJsonObject();
                        JsonObject tool = new JsonObject();
                        tool.add("name", function.get("name"));
                        tool.add("description", function.get("description"));
                        tool.add("inputSchema", function.get("parameters"));
                        definitions.add(tool);
                    }
                    JsonObject result = new JsonObject(); result.add("tools", definitions);
                    byte[] body = result.toString().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    return;
                }
                if (!"POST".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(405, -1); return; }
                byte[] bytes = exchange.getRequestBody().readNBytes(2 * 1024 * 1024 + 1);
                if (bytes.length > 2 * 1024 * 1024) { exchange.sendResponseHeaders(413, -1); return; }
                JsonObject request = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
                String name = request.get("name").getAsString();
                if (current == null || !current.tools.contains(name)) { exchange.sendResponseHeaders(403, -1); return; }
                JsonElement output = current.executor.execute(name, request.getAsJsonObject("arguments"));
                byte[] body = mcpResult(output).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (Exception exception) {
                byte[] body = mcpResult(error(exception.getMessage())).toString().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } finally { exchange.close(); }
        });
        server.start();
    }
    void bind(List<String> tools, DeepSeekToolLoopClient.ToolExecutor executor) { binding = new Binding(List.copyOf(tools), executor); }
    void unbind() { binding = null; }
    String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/execute"; }
    String token() { return token; }
    @Override public void close() { unbind(); server.stop(0); }
    private record Binding(List<String> tools, DeepSeekToolLoopClient.ToolExecutor executor) { }
    private static JsonObject error(String message) {
        JsonObject result = new JsonObject(); result.addProperty("ok", false); result.addProperty("error", message); return result;
    }
    static JsonObject mcpResult(JsonElement output) {
        JsonArray content = new JsonArray();
        if (output.isJsonArray()) {
            for (JsonElement value : output.getAsJsonArray()) {
                JsonObject part = value.getAsJsonObject();
                JsonObject converted = new JsonObject();
                if ("input_image".equals(part.get("type").getAsString())) {
                    String url = part.get("image_url").getAsString();
                    if (!url.startsWith("data:image/png;base64,")) continue;
                    converted.addProperty("type", "image"); converted.addProperty("mimeType", "image/png");
                    converted.addProperty("data", url.substring("data:image/png;base64,".length()));
                } else {
                    converted.addProperty("type", "text"); converted.add("text", part.get("text"));
                }
                content.add(converted);
            }
        } else {
            JsonObject text = new JsonObject(); text.addProperty("type", "text");
            text.addProperty("text", output.isJsonPrimitive() ? output.getAsString() : output.toString()); content.add(text);
        }
        JsonObject result = new JsonObject(); result.add("content", content);
        JsonObject payload = PlanningTurnControl.payload(output);
        // Validation ran successfully and returned a negative verdict, not a broken MCP transport.
        // PlanningTurnControl still owns repeated-rejection stopping; keep the entire negative report.
        boolean validationVerdict = payload.has("validationReport") && payload.get("validationReport").isJsonObject();
        result.addProperty("isError", !validationVerdict && !PlanningTurnControl.failure(payload).isBlank());
        return result;
    }
}
