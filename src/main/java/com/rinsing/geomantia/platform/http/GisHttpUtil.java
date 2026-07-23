package com.rinsing.geomantia.platform.http;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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

final class GisHttpUtil {
    private static final Object LOG_LOCK = new Object();
    private static final String LOG_FILE = "geomantia_mcp_log.jsonl";
    private static final Gson LOG_GSON = new Gson();
    private static final Gson HTTP_GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .serializeNulls()
            .setPrettyPrinting()
            .create();

    private GisHttpUtil() {
    }

    static JsonObject readJsonObject(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        exchange.setAttribute("mcp_request_body", body);
        if (body.isBlank()) {
            return new JsonObject();
        }
        JsonElement parsed = JsonParser.parseString(body);
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("Request body must be a JSON object.");
        }
        return parsed.getAsJsonObject();
    }

    static boolean requireMethod(HttpExchange exchange, String method) throws IOException {
        if (method.equals(exchange.getRequestMethod())) {
            return true;
        }
        JsonObject response = new JsonObject();
        response.addProperty("ok", false);
        response.addProperty("error", "Only " + method + " is supported.");
        sendJson(exchange, 405, response);
        return false;
    }

    static void sendJson(HttpExchange exchange, int code, JsonElement response) throws IOException {
        String text = jsonText(response);
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        } finally {
            logExchange(exchange, code, text);
        }
    }

    static String jsonText(JsonElement response) {
        return HTTP_GSON.toJson(response == null ? JsonNull.INSTANCE : response);
    }

    static void sendError(HttpExchange exchange, int code, String message) throws IOException {
        JsonObject response = new JsonObject();
        response.addProperty("ok", false);
        response.addProperty("error", message == null || message.isBlank() ? "Unknown error" : message);
        sendJson(exchange, code, response);
    }

    private static void logExchange(HttpExchange exchange, int code, String response) {
        try {
            JsonObject entry = new JsonObject();
            entry.addProperty("timestamp", System.currentTimeMillis());
            entry.addProperty("method", exchange.getRequestMethod());
            entry.addProperty("path", exchange.getRequestURI().getPath());
            String query = exchange.getRequestURI().getQuery();
            if (query != null && !query.isBlank()) {
                entry.addProperty("query", query);
            }
            Object body = exchange.getAttribute("mcp_request_body");
            entry.add("request", tryParseJson(body == null ? "" : body.toString()));
            entry.addProperty("status", code);
            entry.add("response", tryParseJson(response));
            if (exchange.getRemoteAddress() != null) {
                entry.addProperty("remote", exchange.getRemoteAddress().toString());
            }
            entry.addProperty("source", "geomantia-http");

            Path logPath = FMLPaths.GAMEDIR.get().resolve(LOG_FILE);
            String line = LOG_GSON.toJson(entry) + System.lineSeparator();
            synchronized (LOG_LOCK) {
                Files.writeString(logPath, line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (Exception ignored) {
            // Logging must never affect the MCP/HTTP response path.
        }
    }

    private static JsonElement tryParseJson(String text) {
        if (text == null || text.isBlank()) {
            return JsonNull.INSTANCE;
        }
        try {
            return JsonParser.parseString(text);
        } catch (Exception ex) {
            return new JsonPrimitive(text);
        }
    }
}
