package com.rinsing.geomantia.systems.provider.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.provider.application.ProviderConfigStore.Credentials;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

/** Low-level multimodal client for the configured OpenAI-compatible protocol. */
public final class MultimodalProviderClient {
    private static final int MAX_IMAGE_BYTES = 16 * 1024 * 1024;
    private static final int MAX_PROMPT_CHARS = 64 * 1024;
    private final HttpClient httpClient;

    public MultimodalProviderClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build());
    }

    MultimodalProviderClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public InvocationResult analyze(PlayerProviderConfig config, Credentials credentials,
                                    byte[] imageBytes, String mimeType, String prompt) {
        if (!config.enabled()) return new InvocationResult(false, "PROVIDER_DISABLED", "");
        if (!credentials.present()) return new InvocationResult(false, "PROVIDER_API_KEY_MISSING", "");
        if (imageBytes == null || imageBytes.length == 0 || imageBytes.length > MAX_IMAGE_BYTES) {
            return new InvocationResult(false, "PROVIDER_IMAGE_SIZE_INVALID", "");
        }
        String safeMimeType = normalizeMimeType(mimeType);
        if (safeMimeType.isBlank()) return new InvocationResult(false, "PROVIDER_IMAGE_FORMAT_INVALID", "");
        String safePrompt = prompt == null ? "" : prompt.trim();
        if (safePrompt.isBlank() || safePrompt.length() > MAX_PROMPT_CHARS) {
            return new InvocationResult(false, "PROVIDER_PROMPT_INVALID", "");
        }
        try {
            PlayerProviderConfig value = config.validated();
            HttpRequest request = HttpRequest.newBuilder(endpoint(value))
                    .timeout(Duration.ofSeconds(value.timeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + credentials.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(body(value, safeMimeType, imageBytes, safePrompt)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return new InvocationResult(false, "PROVIDER_HTTP_" + response.statusCode(), "");
            }
            String output = outputText(response.body(), value.apiProtocol());
            return output.isBlank()
                    ? new InvocationResult(false, "PROVIDER_EMPTY_RESPONSE", "")
                    : new InvocationResult(true, "", output);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new InvocationResult(false, "PROVIDER_INVOCATION_INTERRUPTED", "");
        } catch (IOException | RuntimeException exception) {
            return new InvocationResult(false, "PROVIDER_INVOCATION_FAILED", "");
        }
    }

    private static URI endpoint(PlayerProviderConfig config) {
        String path = PlayerProviderConfig.CHAT_COMPLETIONS.equals(config.apiProtocol())
                ? "/chat/completions" : "/responses";
        return URI.create(config.baseUrl().replaceAll("/+$", "") + path);
    }

    private static String body(PlayerProviderConfig config, String mimeType, byte[] imageBytes, String prompt) {
        return PlayerProviderConfig.CHAT_COMPLETIONS.equals(config.apiProtocol())
                ? chatCompletionsBody(config.model(), mimeType, imageBytes, prompt)
                : responsesBody(config.model(), mimeType, imageBytes, prompt);
    }

    private static String responsesBody(String model, String mimeType, byte[] imageBytes, String prompt) {
        JsonObject text = new JsonObject();
        text.addProperty("type", "input_text");
        text.addProperty("text", prompt);
        JsonObject image = new JsonObject();
        image.addProperty("type", "input_image");
        image.addProperty("image_url", "data:" + mimeType + ";base64,"
                + Base64.getEncoder().encodeToString(imageBytes));
        image.addProperty("detail", "low");
        JsonArray content = new JsonArray();
        content.add(text);
        content.add(image);
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.add("content", content);
        JsonArray input = new JsonArray();
        input.add(message);
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.add("input", input);
        body.addProperty("max_output_tokens", 8192);
        return body.toString();
    }

    private static String chatCompletionsBody(String model, String mimeType, byte[] imageBytes, String prompt) {
        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", prompt);
        JsonObject imageUrl = new JsonObject();
        imageUrl.addProperty("url", "data:" + mimeType + ";base64,"
                + Base64.getEncoder().encodeToString(imageBytes));
        JsonObject image = new JsonObject();
        image.addProperty("type", "image_url");
        image.add("image_url", imageUrl);
        JsonArray content = new JsonArray();
        content.add(text);
        content.add(image);
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.add("content", content);
        JsonArray messages = new JsonArray();
        messages.add(message);
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.add("messages", messages);
        body.addProperty("max_tokens", 8192);
        return body.toString();
    }

    private static String outputText(String body, String apiProtocol) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        if (PlayerProviderConfig.CHAT_COMPLETIONS.equals(apiProtocol)) {
            if (!root.has("choices") || !root.get("choices").isJsonArray()
                    || root.getAsJsonArray("choices").isEmpty()) return "";
            JsonObject choice = root.getAsJsonArray("choices").get(0).getAsJsonObject();
            if (!choice.has("message") || !choice.get("message").isJsonObject()) return "";
            JsonObject message = choice.getAsJsonObject("message");
            return message.has("content") && message.get("content").isJsonPrimitive()
                    ? message.get("content").getAsString().trim() : "";
        }
        if (root.has("output_text") && root.get("output_text").isJsonPrimitive()) {
            return root.get("output_text").getAsString().trim();
        }
        if (!root.has("output") || !root.get("output").isJsonArray()) return "";
        StringBuilder result = new StringBuilder();
        for (JsonElement outputElement : root.getAsJsonArray("output")) {
            if (!outputElement.isJsonObject()) continue;
            JsonObject output = outputElement.getAsJsonObject();
            if (!output.has("content") || !output.get("content").isJsonArray()) continue;
            for (JsonElement contentElement : output.getAsJsonArray("content")) {
                if (!contentElement.isJsonObject()) continue;
                JsonObject content = contentElement.getAsJsonObject();
                if (content.has("text") && content.get("text").isJsonPrimitive()) {
                    if (!result.isEmpty()) result.append('\n');
                    result.append(content.get("text").getAsString());
                }
            }
        }
        return result.toString().trim();
    }

    private static String normalizeMimeType(String value) {
        if (value == null) return "";
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "image/png" -> "image/png";
            case "image/jpeg", "image/jpg" -> "image/jpeg";
            case "image/webp" -> "image/webp";
            case "image/gif" -> "image/gif";
            default -> "";
        };
    }

    public record InvocationResult(boolean success, String errorCode, String outputText) {
        public InvocationResult {
            errorCode = errorCode == null ? "" : errorCode;
            outputText = outputText == null ? "" : outputText;
        }
    }
}
