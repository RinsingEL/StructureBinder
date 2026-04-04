package com.user.terra_script.server.http;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.sun.net.httpserver.HttpExchange;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class HttpUtil {
    private static final Gson GSON = new Gson();
    private static final Object LOG_LOCK = new Object();
    private static final String MCP_LOG_FILE = "terra_script_mcp_log.jsonl";

    private HttpUtil() {}

    public static String readBody(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        exchange.setAttribute("mcp_request_body", body);
        return body;
    }

    public static void sendResponse(HttpExchange exchange, int code, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        } finally {
            logExchange(exchange, code, response);
        }
    }

    public static boolean requireMethod(HttpExchange exchange, String method) throws IOException {
        if (!method.equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "Only " + method);
            return false;
        }
        return true;
    }

    public static void handleError(HttpExchange exchange, Exception e) throws IOException {
        e.printStackTrace();
        sendResponse(exchange, 500, "{\"error\": \"" + e.getMessage() + "\"}");
    }

    private static void logExchange(HttpExchange exchange, int code, String response) {
        try {
            JsonObject entry = new JsonObject();
            entry.addProperty("timestamp", System.currentTimeMillis());
            entry.addProperty("method", exchange.getRequestMethod());
            entry.addProperty("path", exchange.getRequestURI().getPath());
            String query = exchange.getRequestURI().getQuery();
            if (query != null && !query.isBlank()) entry.addProperty("query", query);
            Object body = exchange.getAttribute("mcp_request_body");
            entry.add("request", tryParseJson(body != null ? body.toString() : null));
            entry.addProperty("status", code);
            entry.add("response", tryParseJson(response));
            if (exchange.getRemoteAddress() != null) {
                entry.addProperty("remote", exchange.getRemoteAddress().toString());
            }
            entry.addProperty("source", "http");

            Path logPath = FMLPaths.GAMEDIR.get().resolve(MCP_LOG_FILE);
            String line = GSON.toJson(entry) + System.lineSeparator();
            synchronized (LOG_LOCK) {
                Files.writeString(logPath, line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static JsonElement tryParseJson(String text) {
        if (text == null || text.isBlank()) return JsonNull.INSTANCE;
        try { return JsonParser.parseString(text); } catch (Exception e) { return new JsonPrimitive(text); }
    }
}
