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
import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.StringJoiner;
import java.util.function.Consumer;

/** Runs a bounded stateless Responses API function-call loop against the existing planning tools. */
public final class DeepSeekToolLoopClient {
    private static final int MAX_ROUNDS = 40;
    private static final int MAX_TOOL_CALLS = 80;
    private static final int MAX_RESPONSE_CHARS = 8 * 1024 * 1024;
    private static final long MAX_INITIAL_IMAGE_BYTES = 8L * 1024 * 1024;
    private static final String INSTRUCTIONS = """
            You are the Geomantia in-game planning agent. Continue only the current host-locked run, realm and city.
            Use the available Geomantia tools and follow the returned nextAction and validation evidence. Never skip a
            required review, never invent artifact contents, never inspect source code or project documents,
            and never bypass a failure budget. Tool responses and images attached to them are your only runtime
            evidence. If another MCP agent advanced the state first, reload status and continue from the new state.
            W is performed once. Finish T1/T2 for every realm before calling T3 exactly once for the complete set.
            Finish the existing T4 requirements for every realm before using the existing City queue. Whenever
            selecting a site, use Patch Explorer open, show and select in order and rely on the attached preview.
            When resuming without prior tool history, reopen the active Patch Explorer or prepare the same D4 context
            again to obtain formal evidence; do not read raw run files.
            Stop without calling another tool when the queue is completed, waiting for generation, requires a
            human, or the returned error cannot be corrected from tool evidence.
            """;

    private final HttpClient httpClient;

    public DeepSeekToolLoopClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build());
    }

    DeepSeekToolLoopClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public LoopResult run(PlayerProviderConfig config, Credentials credentials,
                          JsonObject initialState, List<String> allowedTools,
                          ToolExecutor toolExecutor) {
        return run(config, credentials, initialState, List.of(), allowedTools, toolExecutor, ignored -> { });
    }

    public LoopResult run(PlayerProviderConfig config, Credentials credentials,
                          JsonObject initialState, List<Path> initialImages, List<String> allowedTools,
                          ToolExecutor toolExecutor) {
        return run(config, credentials, initialState, initialImages, allowedTools, toolExecutor, ignored -> { });
    }

    public LoopResult run(PlayerProviderConfig config, Credentials credentials,
                          JsonObject initialState, List<Path> initialImages, List<String> allowedTools,
                          ToolExecutor toolExecutor, Consumer<AgentActivityEvent> activityListener) {
        if (!config.enabled()) return LoopResult.failure("PROVIDER_DISABLED", 0, "");
        if (!credentials.present()) return LoopResult.failure("PROVIDER_API_KEY_MISSING", 0, "");
        if (initialState == null || allowedTools == null || allowedTools.isEmpty() || toolExecutor == null) {
            return LoopResult.failure("PROVIDER_AGENT_INPUT_INVALID", 0, "");
        }
        try {
            PlayerProviderConfig value = config.validated();
            JsonArray input = new JsonArray();
            JsonObject message = new JsonObject();
            message.addProperty("role", "user");
            JsonArray content = new JsonArray();
            JsonObject text = new JsonObject();
            text.addProperty("type", "input_text");
            text.addProperty("text", "Continue this planning state:\n" + initialState);
            content.add(text);
            int attached = 0;
            for (Path imagePath : initialImages == null ? List.<Path>of() : initialImages) {
                if (attached >= 4 || imagePath == null || !Files.isRegularFile(imagePath)
                        || Files.size(imagePath) > MAX_INITIAL_IMAGE_BYTES) continue;
                JsonObject image = new JsonObject();
                image.addProperty("type", "input_image");
                image.addProperty("image_url", "data:image/png;base64,"
                        + Base64.getEncoder().encodeToString(Files.readAllBytes(imagePath)));
                image.addProperty("detail", "low");
                content.add(image);
                attached++;
            }
            message.add("content", content);
            input.add(message);
            int toolCalls = 0;
            String finalText = "";
            for (int round = 0; round < MAX_ROUNDS; round++) {
                JsonObject response = request(value, credentials, input, allowedTools, toolCalls == 0);
                JsonArray output = response.has("output") && response.get("output").isJsonArray()
                        ? response.getAsJsonArray("output") : new JsonArray();
                JsonArray calls = new JsonArray();
                for (JsonElement element : output) {
                    if (!element.isJsonObject()) continue;
                    JsonObject item = element.getAsJsonObject();
                    String type = string(item, "type");
                    if ("message".equals(type) || "reasoning".equals(type)
                            || "function_call".equals(type)) {
                        input.add(item.deepCopy());
                    }
                    if ("function_call".equals(type)) calls.add(item);
                    if ("message".equals(type)) {
                        finalText = outputText(item);
                        emit(activityListener, "model", compactVisibleText(finalText));
                    }
                    if ("reasoning".equals(type)) {
                        emit(activityListener, "model", compactVisibleText(reasoningSummary(item)));
                    }
                }
                if (calls.isEmpty()) {
                    return new LoopResult(true, "completed", "", toolCalls, finalText);
                }
                for (JsonElement element : calls) {
                    if (++toolCalls > MAX_TOOL_CALLS) {
                        return LoopResult.failure("PROVIDER_AGENT_TOOL_LIMIT", toolCalls - 1, finalText);
                    }
                    JsonObject call = element.getAsJsonObject();
                    String callId = string(call, "call_id");
                    String toolName = string(call, "name");
                    if (callId.isBlank() || toolName.isBlank()) {
                        return LoopResult.failure("PROVIDER_AGENT_TOOL_CALL_INVALID", toolCalls - 1, finalText);
                    }
                    JsonObject arguments = parseArguments(string(call, "arguments"));
                    emit(activityListener, "tool", summarizeToolCall(toolName, arguments));
                    JsonElement toolOutput;
                    if (!allowedTools.contains(toolName)) {
                        toolOutput = errorOutput("PROVIDER_AGENT_TOOL_NOT_ALLOWED", toolName);
                    } else {
                        try {
                            toolOutput = toolExecutor.execute(toolName, arguments.deepCopy());
                            if (toolOutput == null || toolOutput.isJsonNull()) {
                                toolOutput = errorOutput("PROVIDER_AGENT_TOOL_EMPTY_OUTPUT", toolName);
                            }
                        } catch (Exception exception) {
                            toolOutput = errorOutput("PROVIDER_AGENT_TOOL_FAILED", toolName);
                        }
                    }
                    emit(activityListener, "result", summarizeToolResult(toolName, toolOutput));
                    JsonObject result = new JsonObject();
                    result.addProperty("type", "function_call_output");
                    result.addProperty("call_id", callId);
                    result.add("output", toolOutput.deepCopy());
                    compactPriorImages(input);
                    input.add(result);
                }
            }
            return LoopResult.failure("PROVIDER_AGENT_ROUND_LIMIT", toolCalls, finalText);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return LoopResult.failure("PROVIDER_AGENT_INTERRUPTED", 0, "");
        } catch (IOException | RuntimeException exception) {
            return LoopResult.failure("PROVIDER_AGENT_REQUEST_FAILED", 0, "");
        }
    }

    private static void emit(Consumer<AgentActivityEvent> listener, String kind, String message) {
        if (listener == null || message == null || message.isBlank()) return;
        listener.accept(new AgentActivityEvent(Instant.now().toString(), kind, message));
    }

    private static String compactVisibleText(String value) {
        if (value == null) return "";
        String compact = value.replaceAll("\\s+", " ").strip();
        if (compact.startsWith("{") || compact.startsWith("[")) return "模型返回了结构化内容（已省略）";
        return compact.length() <= 600 ? compact : compact.substring(0, 599) + "…";
    }

    private static String reasoningSummary(JsonObject item) {
        if (!item.has("summary") || !item.get("summary").isJsonArray()) return "";
        StringJoiner result = new StringJoiner(" ");
        for (JsonElement element : item.getAsJsonArray("summary")) {
            if (!element.isJsonObject()) continue;
            String text = string(element.getAsJsonObject(), "text");
            if (!text.isBlank()) result.add(text);
        }
        return result.toString();
    }

    private static String summarizeToolCall(String toolName, JsonObject arguments) {
        StringJoiner values = new StringJoiner("，");
        for (String key : List.of("realmId", "citySeedId", "scopeType", "scopeId", "candidateId",
                "sessionId", "planningSessionId", "patchSelectionRef", "pageSize")) {
            if (!arguments.has(key) || !arguments.get(key).isJsonPrimitive()) continue;
            String value = arguments.get(key).getAsString();
            if (value.length() > 80) value = value.substring(0, 79) + "…";
            values.add(key + "=" + value);
        }
        String details = values.toString();
        return "调用 " + toolName + (details.isBlank() ? "" : "（" + details + "）");
    }

    private static String summarizeToolResult(String toolName, JsonElement output) {
        JsonObject value = structuredObject(output);
        if (value == null) return toolName + " 已返回结果";
        StringJoiner summary = new StringJoiner("，");
        for (String key : List.of("ok", "status", "stage", "nextAction", "errorCode", "candidateId",
                "patchSelectionRef", "sessionId", "planningSessionId")) {
            if (!value.has(key) || !value.get(key).isJsonPrimitive()) continue;
            String field = value.get(key).getAsString();
            if (field.length() > 100) field = field.substring(0, 99) + "…";
            summary.add(key + "=" + field);
        }
        String details = summary.toString();
        return toolName + (details.isBlank() ? " 已返回结果" : " → " + details);
    }

    private static JsonObject structuredObject(JsonElement output) {
        if (output == null || output.isJsonNull()) return null;
        if (output.isJsonObject()) return output.getAsJsonObject();
        if (output.isJsonPrimitive() && output.getAsJsonPrimitive().isString()) {
            return parseObject(output.getAsString());
        }
        if (output.isJsonArray()) {
            for (JsonElement part : output.getAsJsonArray()) {
                if (!part.isJsonObject()) continue;
                JsonObject object = part.getAsJsonObject();
                if ("input_text".equals(string(object, "type"))) {
                    JsonObject parsed = parseObject(string(object, "text"));
                    if (parsed != null) return parsed;
                }
            }
        }
        return null;
    }

    private static JsonObject parseObject(String value) {
        try {
            JsonElement parsed = JsonParser.parseString(value);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private JsonObject request(PlayerProviderConfig config, Credentials credentials, JsonArray input,
                               List<String> allowedTools, boolean requireTool)
            throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("model", config.model());
        body.addProperty("instructions", INSTRUCTIONS);
        body.add("input", input.deepCopy());
        body.add("tools", ProviderPlanningToolCatalog.definitions(allowedTools));
        body.addProperty("tool_choice", requireTool ? "required" : "auto");
        JsonObject reasoning = new JsonObject();
        reasoning.addProperty("effort", requireTool ? "none" : "high");
        body.add("reasoning", reasoning);
        body.addProperty("max_output_tokens", 32768);
        HttpRequest request = HttpRequest.newBuilder(endpoint(config))
                .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + credentials.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Provider HTTP " + response.statusCode());
        }
        if (response.body().length() > MAX_RESPONSE_CHARS) throw new IOException("Provider response too large");
        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        if (!"completed".equals(string(root, "status"))) {
            throw new IOException("Provider response incomplete");
        }
        return root;
    }

    private static JsonObject parseArguments(String value) {
        if (value == null || value.isBlank() || value.length() > 2 * 1024 * 1024) {
            throw new IllegalArgumentException("Invalid tool arguments");
        }
        JsonElement element = JsonParser.parseString(value);
        if (!element.isJsonObject()) throw new IllegalArgumentException("Tool arguments must be an object");
        return element.getAsJsonObject();
    }

    private static JsonElement errorOutput(String code, String toolName) {
        JsonObject error = new JsonObject();
        error.addProperty("ok", false);
        error.addProperty("errorCode", code);
        if (toolName != null && !toolName.isBlank()) error.addProperty("tool", toolName);
        return error;
    }

    private static void compactPriorImages(JsonArray input) {
        for (JsonElement element : input) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            if (!"function_call_output".equals(string(item, "type"))
                    || !item.has("output") || !item.get("output").isJsonArray()) continue;
            JsonArray compact = new JsonArray();
            boolean removedImage = false;
            for (JsonElement partElement : item.getAsJsonArray("output")) {
                if (partElement.isJsonObject()
                        && "input_image".equals(string(partElement.getAsJsonObject(), "type"))) {
                    removedImage = true;
                } else {
                    compact.add(partElement.deepCopy());
                }
            }
            if (removedImage) {
                JsonObject marker = new JsonObject();
                marker.addProperty("type", "input_text");
                marker.addProperty("text", "[Earlier planning preview already inspected; use newer tool evidence.]");
                compact.add(marker);
                item.add("output", compact);
            }
        }
    }

    private static String outputText(JsonObject message) {
        if (!message.has("content") || !message.get("content").isJsonArray()) return "";
        StringBuilder text = new StringBuilder();
        for (JsonElement element : message.getAsJsonArray("content")) {
            if (!element.isJsonObject()) continue;
            JsonObject part = element.getAsJsonObject();
            if (part.has("text") && part.get("text").isJsonPrimitive()) {
                if (!text.isEmpty()) text.append('\n');
                text.append(part.get("text").getAsString());
            }
        }
        return text.toString().trim();
    }

    private static URI endpoint(PlayerProviderConfig config) {
        return URI.create(config.baseUrl().replaceAll("/+$", "") + "/responses");
    }

    private static String string(JsonObject object, String key) {
        return object != null && object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsString() : "";
    }

    @FunctionalInterface
    public interface ToolExecutor {
        JsonElement execute(String toolName, JsonObject arguments) throws Exception;
    }

    public record LoopResult(boolean success, String state, String errorCode, int toolCalls,
                             String finalText) {
        public LoopResult {
            state = state == null ? "" : state;
            errorCode = errorCode == null ? "" : errorCode;
            finalText = finalText == null ? "" : finalText;
        }

        static LoopResult failure(String errorCode, int toolCalls, String finalText) {
            return new LoopResult(false, "error", errorCode, toolCalls, finalText);
        }
    }
}
