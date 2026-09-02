package com.rinsing.geomantia.systems.provider.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Safe Provider adapter over the same localhost HTTP handlers used by the Node MCP server. */
public final class ProviderPlanningToolGateway implements DeepSeekToolLoopClient.ToolExecutor {
    private static final int MAX_ARGUMENT_CHARS = 2 * 1024 * 1024;
    private static final int MAX_RESPONSE_CHARS = 6 * 1024 * 1024;
    private static final long MAX_IMAGE_BYTES = 8L * 1024 * 1024;
    private static final int MAX_IMAGES = 4;
    private static final Map<String, Endpoint> ENDPOINTS = endpoints();

    private final HttpClient httpClient;
    private final URI apiBase;
    private final Path serverDirectory;
    private final Path debugRoot;
    private final String runId;
    private final String citySeedId;
    private final ManagedCityPlanningSources managedSources;

    public ProviderPlanningToolGateway(int apiPort, Path serverDirectory, String runId, String citySeedId) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), apiPort,
                serverDirectory, runId, citySeedId);
    }

    ProviderPlanningToolGateway(HttpClient httpClient, int apiPort, Path serverDirectory,
                                String runId, String citySeedId) {
        this.httpClient = httpClient;
        this.apiBase = URI.create("http://127.0.0.1:" + apiPort);
        this.serverDirectory = serverDirectory.toAbsolutePath().normalize();
        this.debugRoot = this.serverDirectory.resolve("realm_debug").normalize();
        this.runId = requireIdentity(runId, "runId");
        this.citySeedId = requireIdentity(citySeedId, "citySeedId");
        this.managedSources = new ManagedCityPlanningSources(this.serverDirectory);
    }

    public static List<String> allowedTools() {
        return List.copyOf(ENDPOINTS.keySet());
    }

    @Override
    public JsonElement execute(String toolName, JsonObject suppliedArguments) throws Exception {
        Endpoint endpoint = ENDPOINTS.get(toolName);
        if (endpoint == null) return error("PROVIDER_AGENT_TOOL_NOT_ALLOWED", toolName);
        JsonObject arguments = suppliedArguments == null ? new JsonObject() : suppliedArguments.deepCopy();
        injectIdentity(arguments, "runId", runId);
        if (endpoint.cityScoped()) injectIdentity(arguments, "citySeedId", citySeedId);
        if ("patch_explorer_open".equals(toolName)) {
            injectIdentity(arguments, "scopeId", citySeedId);
            injectIdentity(arguments, "scopeType", "city_d4");
        }
        if ("city_prepare_d4_blueprint_context".equals(toolName)) managedSources.resolve().applyTo(arguments);
        if (arguments.toString().length() > MAX_ARGUMENT_CHARS) {
            return error("PROVIDER_AGENT_TOOL_ARGUMENTS_TOO_LARGE", toolName);
        }
        HttpRequest request = HttpRequest.newBuilder(apiBase.resolve(endpoint.path()))
                .timeout(endpoint.longRunning() ? Duration.ofMinutes(5) : Duration.ofSeconds(45))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(arguments.toString()))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        String body = response.body() == null ? "" : response.body();
        if (body.length() > MAX_RESPONSE_CHARS) {
            return error("PROVIDER_AGENT_TOOL_RESPONSE_TOO_LARGE", toolName);
        }
        JsonElement result;
        try {
            result = JsonParser.parseString(body);
        } catch (RuntimeException exception) {
            result = error("PROVIDER_AGENT_TOOL_INVALID_JSON", toolName);
        }
        if (response.statusCode() / 100 != 2) {
            JsonObject wrapper = new JsonObject();
            wrapper.addProperty("ok", false);
            wrapper.addProperty("httpStatus", response.statusCode());
            wrapper.addProperty("tool", toolName);
            wrapper.add("response", result);
            result = wrapper;
        }
        return withImages(result);
    }

    private JsonElement withImages(JsonElement result) {
        Set<Path> candidates = new LinkedHashSet<>();
        collectImagePaths(result, candidates);
        if (candidates.isEmpty()) return new JsonPrimitive(result.toString());
        JsonArray output = new JsonArray();
        JsonObject text = new JsonObject();
        text.addProperty("type", "input_text");
        text.addProperty("text", result.toString());
        output.add(text);
        int count = 0;
        for (Path path : candidates) {
            if (count >= MAX_IMAGES) break;
            try {
                if (!Files.isRegularFile(path) || Files.size(path) > MAX_IMAGE_BYTES) continue;
                JsonObject image = new JsonObject();
                image.addProperty("type", "input_image");
                image.addProperty("image_url", "data:image/png;base64,"
                        + Base64.getEncoder().encodeToString(Files.readAllBytes(path)));
                image.addProperty("detail", "low");
                output.add(image);
                count++;
            } catch (IOException ignored) {
                // Text evidence remains available when a preview disappears between response and read.
            }
        }
        return count == 0 ? new JsonPrimitive(result.toString()) : output;
    }

    private void collectImagePaths(JsonElement element, Set<Path> output) {
        if (element == null || element.isJsonNull() || output.size() >= MAX_IMAGES) return;
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            String value = element.getAsString().trim();
            if (!value.toLowerCase(java.util.Locale.ROOT).endsWith(".png")) return;
            try {
                Path requested = Path.of(value);
                List<Path> possibilities = new ArrayList<>();
                if (requested.isAbsolute()) possibilities.add(requested.normalize());
                else {
                    possibilities.add(debugRoot.resolve(requested).normalize());
                    possibilities.add(debugRoot.resolve(runId).resolve(requested).normalize());
                    possibilities.add(serverDirectory.resolve(requested).normalize());
                }
                for (Path path : possibilities) {
                    if (path.startsWith(serverDirectory) && Files.isRegularFile(path)) {
                        output.add(path);
                        return;
                    }
                }
            } catch (RuntimeException ignored) {
                // Ignore malformed path-shaped response strings.
            }
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) collectImagePaths(child, output);
            return;
        }
        if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                collectImagePaths(entry.getValue(), output);
            }
        }
    }

    private static void injectIdentity(JsonObject arguments, String key, String expected) {
        if (arguments.has(key) && !arguments.get(key).isJsonNull()) {
            String supplied = arguments.get(key).getAsString();
            if (!expected.equals(supplied)) {
                throw new IllegalArgumentException("PROVIDER_AGENT_SCOPE_MISMATCH: " + key);
            }
        }
        arguments.addProperty(key, expected);
    }

    private static JsonObject error(String code, String toolName) {
        JsonObject result = new JsonObject();
        result.addProperty("ok", false);
        result.addProperty("errorCode", code);
        result.addProperty("tool", toolName);
        return result;
    }

    private static String requireIdentity(String value, String field) {
        if (value == null || value.isBlank() || !value.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }

    private static Map<String, Endpoint> endpoints() {
        Map<String, Endpoint> endpoints = new LinkedHashMap<>();
        endpoints.put("city_design_queue_status",
                new Endpoint("/realm/city/design_queue/status", false, false));
        endpoints.put("city_plan_d3", new Endpoint("/realm/city/plan_d3", true, true));
        endpoints.put("city_review_d3_site", new Endpoint("/realm/city/review_d3_site", true, false));
        endpoints.put("patch_explorer_open",
                new Endpoint("/realm/patch_explorer/open", true, true));
        endpoints.put("patch_explorer_show_candidates",
                new Endpoint("/realm/patch_explorer/show_candidates", false, true));
        endpoints.put("city_prepare_d4_blueprint_context",
                new Endpoint("/realm/city/prepare_d4_blueprint_context", true, false));
        endpoints.put("city_submit_d4_blueprint",
                new Endpoint("/realm/city/submit_d4_blueprint", true, false));
        endpoints.put("city_post_d4_auto_compile_status",
                new Endpoint("/realm/city/post_d4_auto_compile_status", true, false));
        endpoints.put("city_post_d4_auto_compile_retry",
                new Endpoint("/realm/city/post_d4_auto_compile_retry", true, false));
        return Collections.unmodifiableMap(endpoints);
    }

    private record Endpoint(String path, boolean cityScoped, boolean longRunning) {
    }
}
