package com.rinsing.geomantia.harness.systems.provider.application;
import com.rinsing.geomantia.systems.provider.application.*;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.harness.systems.provider.application.ProviderConfigStore.Credentials;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class ProviderConnectionTester {
    private static final int PROBE_MAX_OUTPUT_TOKENS = 16;
    private static final String PROBE_IMAGE =
            "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAAAXNSR0IArs4c6QAAAARnQU1BAACxjwv8YQUAAAAJcEhZcwAADsMAAA7DAcdvqGQAAAAdSURBVDhPY0iZ+vY/JZgBXYBUPGrAqAGjBgwWAwBvsOUfyEymUQAAAABJRU5ErkJggg==";

    private final HttpClient httpClient;

    public ProviderConnectionTester() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build());
    }

    ProviderConnectionTester(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public TestResult test(PlayerProviderConfig config, Credentials credentials) {
        if (!credentials.present()) return new TestResult("missing_key", false, false, "PROVIDER_API_KEY_MISSING");
        try {
            PlayerProviderConfig value = config.validated();
            String probeSession = ProviderRequestHeaders.session(null);
            HttpResponse<String> modelsResponse = httpClient.send(ProviderRequestHeaders.request(endpoint(value, "models"), probeSession)
                            .timeout(Duration.ofSeconds(value.timeoutSeconds()))
                            .header("Accept", "application/json")
                            .header("Authorization", "Bearer " + credentials.apiKey())
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (modelsResponse.statusCode() / 100 != 2) {
                return new TestResult("error", false, false,
                        httpFailure("PROVIDER_HTTP_", modelsResponse, credentials));
            }
            if (!containsModel(modelsResponse.body(), value.model())) {
                return new TestResult("model_missing", true, false, "PROVIDER_MODEL_NOT_AVAILABLE");
            }

            String completionPath = PlayerProviderConfig.CHAT_COMPLETIONS.equals(value.apiProtocol())
                    ? "chat/completions" : "responses";
            HttpResponse<String> visionResponse = httpClient.send(ProviderRequestHeaders.request(endpoint(value, completionPath), probeSession)
                            .timeout(Duration.ofSeconds(value.timeoutSeconds()))
                            .header("Content-Type", "application/json")
                            .header("Authorization", "Bearer " + credentials.apiKey())
                            .POST(HttpRequest.BodyPublishers.ofString(visionProbeBody(value)))
                            .build(), HttpResponse.BodyHandlers.ofString());
            if (visionResponse.statusCode() / 100 == 2) {
                return new TestResult("connected_multimodal", true, true, "PROVIDER_MULTIMODAL_READY");
            }
            // Reaching /models does not establish text-only capability. A rejected image request
            // can mean authentication, quota, protocol or parameter failure instead.
            return new TestResult("error", true, false,
                    httpFailure("PROVIDER_VISION_HTTP_", visionResponse, credentials));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new TestResult("error", false, false, "PROVIDER_TEST_INTERRUPTED");
        } catch (IOException | RuntimeException exception) {
            return new TestResult("error", false, false, "PROVIDER_CONNECTION_FAILED: " + exception.getClass().getSimpleName());
        }
    }

    private static String httpFailure(String prefix, HttpResponse<String> response, Credentials credentials) {
        String detail = response.body() == null ? "" : response.body();
        try {
            JsonObject root = JsonParser.parseString(detail).getAsJsonObject();
            JsonElement error = root.has("error") ? root.get("error") : root;
            if (error.isJsonObject()) {
                JsonObject fields = error.getAsJsonObject();
                StringBuilder summary = new StringBuilder();
                for (String key : new String[]{"code", "type", "message", "detail"}) {
                    if (fields.has(key) && fields.get(key).isJsonPrimitive()) {
                        if (!summary.isEmpty()) summary.append("; ");
                        summary.append(key).append("=").append(fields.get(key).getAsString());
                    }
                }
                if (!summary.isEmpty()) detail = summary.toString();
            } else if (error.isJsonPrimitive()) detail = error.getAsString();
        } catch (RuntimeException ignored) { /* Plain-text and gateway errors are useful too. */ }
        detail = detail.replace(credentials.apiKey(), "[redacted]").replaceAll("[\\p{Cntrl}§]+", " ").trim();
        if (detail.length() > 1000) detail = detail.substring(0, 1000) + "…";
        return prefix + response.statusCode() + (detail.isBlank() ? "" : ": " + detail);
    }

    private static boolean containsModel(String body, String model) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            if (!root.has("data") || !root.get("data").isJsonArray()) return false;
            for (JsonElement element : root.getAsJsonArray("data")) {
                if (element.isJsonObject() && element.getAsJsonObject().has("id")
                        && model.equals(element.getAsJsonObject().get("id").getAsString())) return true;
            }
            return false;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static URI endpoint(PlayerProviderConfig config, String path) {
        String base = config.baseUrl().replaceAll("/+$", "");
        return URI.create(base + '/' + path);
    }

    private static String visionProbeBody(PlayerProviderConfig config) {
        if (PlayerProviderConfig.CHAT_COMPLETIONS.equals(config.apiProtocol())) {
            return chatCompletionsVisionProbeBody(config.model());
        }
        return responsesVisionProbeBody(config.model());
    }

    private static String responsesVisionProbeBody(String model) {
        JsonObject image = new JsonObject();
        image.addProperty("type", "input_image");
        image.addProperty("image_url", PROBE_IMAGE);
        image.addProperty("detail", "low");
        JsonObject text = new JsonObject();
        text.addProperty("type", "input_text");
        text.addProperty("text", "Reply only with OK.");
        com.google.gson.JsonArray content = new com.google.gson.JsonArray();
        content.add(text);
        content.add(image);
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.add("content", content);
        com.google.gson.JsonArray input = new com.google.gson.JsonArray();
        input.add(message);
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.add("input", input);
        body.addProperty("max_output_tokens", PROBE_MAX_OUTPUT_TOKENS);
        return body.toString();
    }

    private static String chatCompletionsVisionProbeBody(String model) {
        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", "Reply only with OK.");
        JsonObject imageUrl = new JsonObject();
        imageUrl.addProperty("url", PROBE_IMAGE);
        JsonObject image = new JsonObject();
        image.addProperty("type", "image_url");
        image.add("image_url", imageUrl);
        com.google.gson.JsonArray content = new com.google.gson.JsonArray();
        content.add(text);
        content.add(image);
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.add("content", content);
        com.google.gson.JsonArray messages = new com.google.gson.JsonArray();
        messages.add(message);
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.add("messages", messages);
        body.addProperty("max_tokens", PROBE_MAX_OUTPUT_TOKENS);
        return body.toString();
    }

    public record TestResult(String state, boolean connected, boolean multimodal, String message) {
    }
}
