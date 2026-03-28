package com.user.terra_script.server.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.user.terra_script.server.http.HttpUtil;

import java.io.IOException;

public final class RuntimeDebugController {
    private static final Gson GSON = new Gson();

    public void handleRuntimeDebugLogs(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = body == null || body.isBlank()
                    ? new JsonObject()
                    : JsonParser.parseString(body).getAsJsonObject();
            String source = json.has("source") ? json.get("source").getAsString() : "gradle";
            int lines = json.has("contains")
                    ? (json.has("context_lines") ? json.get("context_lines").getAsInt() : (json.has("lines") ? json.get("lines").getAsInt() : 10))
                    : (json.has("lines") ? json.get("lines").getAsInt() : 80);
            String contains = json.has("contains") ? json.get("contains").getAsString() : null;
            int maxSegments = json.has("max_segments") ? json.get("max_segments").getAsInt() : 20;
            int maxTotalLines = json.has("max_total_lines") ? json.get("max_total_lines").getAsInt() : 500;

            JsonObject result = RuntimeConsoleDebugService.readLogs(source, lines, contains, maxSegments, maxTotalLines);
            result.addProperty("status", "ok");
            result.addProperty("step", "runtime_debug_logs");
            HttpUtil.sendResponse(exchange, 200, GSON.toJson(result));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }
}
