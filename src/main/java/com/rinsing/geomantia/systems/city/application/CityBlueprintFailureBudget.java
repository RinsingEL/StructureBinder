package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.infrastructure.json.CityJson;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Context-scoped budget for failed programmatic D4 compilations. */
public final class CityBlueprintFailureBudget {
    public static final String SCHEMA = "city_blueprint_failure_budget";
    public static final String FILE_NAME = "city_blueprint_failure_budget.json";
    public static final int MAX_FAILURE_COUNT = 5;

    private static final Map<Path, Object> LEDGER_LOCKS = new ConcurrentHashMap<>();

    public JsonObject initialize(Path debugRoot, String runId, String cityId, String contextId)
            throws IOException {
        Path path = path(debugRoot, runId, cityId);
        synchronized (lock(path)) {
            JsonObject current = read(path);
            if (matches(current, cityId, contextId)) return normalize(current);
            JsonObject created = empty(cityId, contextId);
            writeAtomic(path, created);
            return created.deepCopy();
        }
    }

    public JsonObject current(Path debugRoot, String runId, String cityId) throws IOException {
        Path contextPath = blueprintDirectory(debugRoot, runId, cityId)
                .resolve("city_blueprint_context.json");
        if (!Files.isRegularFile(contextPath)) {
            throw new IllegalArgumentException("CITY_BLUEPRINT_CONTEXT_NOT_FOUND");
        }
        JsonObject context = JsonParser.parseString(Files.readString(contextPath)).getAsJsonObject();
        String contextId = stringValue(context, "contextId", "");
        if (contextId.isBlank()) throw new IllegalArgumentException("CITY_BLUEPRINT_CONTEXT_INVALID");
        return initialize(debugRoot, runId, cityId, contextId);
    }

    public JsonObject rebindAfterAuthorCorrection(Path debugRoot, String runId, String cityId,
                                                 String previousContextId, String contextId) throws IOException {
        Path path = path(debugRoot, runId, cityId);
        synchronized (lock(path)) {
            JsonObject state = read(path);
            if (matches(state, cityId, contextId)) return normalize(state);
            if (!matches(state, cityId, previousContextId))
                throw new IllegalArgumentException("CITY_BLUEPRINT_RECOVERY_BUDGET_STALE");
            state.addProperty("previousContextId", previousContextId);
            state.addProperty("contextId", contextId);
            state.addProperty("status", exhausted(state) ? "exhausted" : "open");
            state.remove("succeededAt");
            state.addProperty("updatedAt", Instant.now().toString());
            normalize(state);
            writeAtomic(path, state);
            return state.deepCopy();
        }
    }

    public JsonObject recordFailure(Path debugRoot, String runId, String cityId,
                                    String reasonCode, String message) throws IOException {
        Path path = path(debugRoot, runId, cityId);
        synchronized (lock(path)) {
            JsonObject state = activeState(debugRoot, runId, cityId, path);
            if ("succeeded".equals(stringValue(state, "status", ""))) return normalize(state);
            int count = failureCount(state);
            if (count < MAX_FAILURE_COUNT) {
                count++;
                state.addProperty("failureCount", count);
                JsonObject failure = new JsonObject();
                failure.addProperty("failureNumber", count);
                failure.addProperty("reasonCode", reasonCode == null ? "" : reasonCode);
                failure.addProperty("message", message == null ? "" : message);
                failure.addProperty("recordedAt", Instant.now().toString());
                failures(state).add(failure);
            }
            state.addProperty("status", count >= MAX_FAILURE_COUNT ? "exhausted" : "open");
            state.addProperty("updatedAt", Instant.now().toString());
            normalize(state);
            writeAtomic(path, state);
            return state.deepCopy();
        }
    }

    public JsonObject recordSuccess(Path debugRoot, String runId, String cityId) throws IOException {
        Path path = path(debugRoot, runId, cityId);
        synchronized (lock(path)) {
            JsonObject state = activeState(debugRoot, runId, cityId, path);
            state.addProperty("status", "succeeded");
            state.addProperty("succeededAt", Instant.now().toString());
            state.addProperty("updatedAt", Instant.now().toString());
            normalize(state);
            writeAtomic(path, state);
            return state.deepCopy();
        }
    }

    public JsonObject reopenForRevision(Path debugRoot, String runId, String cityId) throws IOException {
        Path path = path(debugRoot, runId, cityId);
        synchronized (lock(path)) {
            JsonObject state = activeState(debugRoot, runId, cityId, path);
            if (!exhausted(state)) {
                state.addProperty("status", "open");
                state.remove("succeededAt");
                state.addProperty("updatedAt", Instant.now().toString());
            }
            normalize(state);
            writeAtomic(path, state);
            return state.deepCopy();
        }
    }

    public static void attach(JsonObject response, JsonObject state, Path debugRoot,
                              String runId, String cityId) {
        JsonObject normalized = normalize(state.deepCopy());
        response.addProperty("failureCount", failureCount(normalized));
        response.addProperty("maximumFailureCount", MAX_FAILURE_COUNT);
        response.addProperty("remainingFailureCount",
                Math.max(0, MAX_FAILURE_COUNT - failureCount(normalized)));
        response.addProperty("retryAllowed", retryAllowed(normalized));
        response.add("failureBudget", normalized);
        JsonObject artifacts = response.has("artifacts") && response.get("artifacts").isJsonObject()
                ? response.getAsJsonObject("artifacts") : new JsonObject();
        Path ledger = path(debugRoot, runId, cityId);
        artifacts.addProperty("cityBlueprintFailureBudget", debugRef(debugRoot, ledger));
        response.add("artifacts", artifacts);
    }

    public static boolean exhausted(JsonObject state) {
        return failureCount(state) >= MAX_FAILURE_COUNT;
    }

    public static boolean succeeded(JsonObject state) {
        return "succeeded".equals(stringValue(state, "status", ""));
    }

    public static boolean retryAllowed(JsonObject state) {
        return !succeeded(state) && !exhausted(state);
    }

    public static int failureCount(JsonObject state) {
        return intValue(state, "failureCount", 0);
    }

    private JsonObject activeState(Path debugRoot, String runId, String cityId, Path path) throws IOException {
        Path contextPath = blueprintDirectory(debugRoot, runId, cityId)
                .resolve("city_blueprint_context.json");
        if (!Files.isRegularFile(contextPath)) {
            throw new IllegalArgumentException("CITY_BLUEPRINT_CONTEXT_NOT_FOUND");
        }
        JsonObject context = JsonParser.parseString(Files.readString(contextPath)).getAsJsonObject();
        String contextId = stringValue(context, "contextId", "");
        if (contextId.isBlank()) throw new IllegalArgumentException("CITY_BLUEPRINT_CONTEXT_INVALID");
        JsonObject state = read(path);
        if (!matches(state, cityId, contextId)) state = empty(cityId, contextId);
        return state;
    }

    private static JsonObject empty(String cityId, String contextId) {
        JsonObject state = new JsonObject();
        state.addProperty("schema", SCHEMA);
        state.addProperty("cityId", cityId);
        state.addProperty("contextId", contextId);
        state.addProperty("failureCount", 0);
        state.addProperty("maximumFailureCount", MAX_FAILURE_COUNT);
        state.addProperty("remainingFailureCount", MAX_FAILURE_COUNT);
        state.addProperty("status", "open");
        state.addProperty("retryAllowed", true);
        state.add("failures", new JsonArray());
        state.addProperty("createdAt", Instant.now().toString());
        state.addProperty("updatedAt", Instant.now().toString());
        return state;
    }

    private static JsonObject normalize(JsonObject state) {
        int count = Math.max(0, Math.min(MAX_FAILURE_COUNT, failureCount(state)));
        state.addProperty("schema", SCHEMA);
        state.addProperty("failureCount", count);
        state.addProperty("maximumFailureCount", MAX_FAILURE_COUNT);
        state.addProperty("remainingFailureCount", MAX_FAILURE_COUNT - count);
        if (!state.has("failures") || !state.get("failures").isJsonArray()) {
            state.add("failures", new JsonArray());
        }
        if (count >= MAX_FAILURE_COUNT && !"succeeded".equals(stringValue(state, "status", ""))) {
            state.addProperty("status", "exhausted");
        }
        state.addProperty("retryAllowed", retryAllowed(state));
        return state;
    }

    private static boolean matches(JsonObject state, String cityId, String contextId) {
        return state != null && SCHEMA.equals(stringValue(state, "schema", ""))
                && cityId.equals(stringValue(state, "cityId", ""))
                && contextId.equals(stringValue(state, "contextId", ""));
    }

    private static JsonArray failures(JsonObject state) {
        if (!state.has("failures") || !state.get("failures").isJsonArray()) {
            state.add("failures", new JsonArray());
        }
        return state.getAsJsonArray("failures");
    }

    private static Object lock(Path path) {
        return LEDGER_LOCKS.computeIfAbsent(path.toAbsolutePath().normalize(), ignored -> new Object());
    }

    private static JsonObject read(Path path) throws IOException {
        if (!Files.isRegularFile(path)) return null;
        try {
            return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("CITY_BLUEPRINT_FAILURE_BUDGET_INVALID: " + exception.getMessage(),
                    exception);
        }
    }

    private static Path path(Path debugRoot, String runId, String cityId) {
        return blueprintDirectory(debugRoot, runId, cityId).resolve(FILE_NAME);
    }

    private static Path blueprintDirectory(Path debugRoot, String runId, String cityId) {
        Path root = debugRoot.toAbsolutePath().normalize();
        Path runDir = root.resolve(runId).normalize();
        if (!runDir.startsWith(root) || !Files.isDirectory(runDir)) {
            throw new IllegalArgumentException("CITY_BLUEPRINT_RUN_NOT_FOUND: " + runId);
        }
        return CityTestRunLayout.open(runDir, cityId).stepDirectory(CityTestRunLayout.BLUEPRINT);
    }

    private static void writeAtomic(Path path, JsonObject value) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), "." + path.getFileName(), ".tmp");
        try {
            Files.writeString(temporary, CityJson.GSON.toJson(value));
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String debugRef(Path debugRoot, Path path) {
        return debugRoot.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize())
                .toString().replace('\\', '/');
    }

    private static String stringValue(JsonObject object, String key, String fallback) {
        return object != null && object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsString() : fallback;
    }

    private static int intValue(JsonObject object, String key, int fallback) {
        return object != null && object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsInt() : fallback;
    }
}
