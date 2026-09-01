package com.rinsing.geomantia.systems.city.application.queue;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.application.queue.CityDesignQueueConfig.OrderingMode;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.HashSet;
import java.util.Set;

/** Persistent upper queue that exposes exactly one City seed to the D3/D4 Agent at a time. */
public final class CityDesignQueue {
    public static final String SCHEMA = "city_design_queue.v0.1";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String WAITING_FOR_AGENT = "waiting_for_agent";
    private static final String WAITING_FOR_PATCH_REVIEW = "waiting_for_patch_review";
    private static final String PENDING = "pending";
    private static final String POST_D4_RUNNING = "post_d4_running";
    private static final String WAITING_FOR_GENERATION = "waiting_for_generation";
    private static final String NEEDS_AGENT = "needs_agent";

    private final Path debugRoot;
    private final Path configPath;

    public CityDesignQueue(Path debugRoot, Path configPath) {
        this.debugRoot = Objects.requireNonNull(debugRoot, "debugRoot").toAbsolutePath().normalize();
        this.configPath = Objects.requireNonNull(configPath, "configPath").toAbsolutePath().normalize();
    }

    public synchronized JsonObject refresh(String runId, String requestedOrderingMode) throws IOException {
        String safeRunId = requireId(runId, "runId");
        CityDesignQueueConfig config = CityDesignQueueConfig.loadOrCreate(configPath);
        OrderingMode mode = requestedOrderingMode == null || requestedOrderingMode.isBlank()
                ? config.orderingMode() : OrderingMode.fromContract(requestedOrderingMode);
        Path runDirectory = runDirectory(safeRunId);
        Path registryPath = runDirectory.resolve("city_seed_registry.json");
        if (!Files.isRegularFile(registryPath)) {
            throw new IllegalArgumentException("CITY_DESIGN_QUEUE_REGISTRY_NOT_FOUND: " + safeRunId);
        }
        JsonObject registry = readObject(registryPath);
        JsonArray seedsJson = registry.has("citySeeds") && registry.get("citySeeds").isJsonArray()
                ? registry.getAsJsonArray("citySeeds") : new JsonArray();
        List<Seed> seeds = parseSeeds(seedsJson, worldSurveyStep(runDirectory));
        sort(seeds, mode);

        JsonObject previous = readState(safeRunId);
        Map<String, JsonObject> previousItems = itemsById(previous);
        JsonArray items = new JsonArray();
        for (int index = 0; index < seeds.size(); index++) {
            Seed seed = seeds.get(index);
            JsonObject item = seed.asItem(index);
            JsonObject previousItem = previousItems.get(seed.citySeedId());
            if (previousItem != null) {
                item.addProperty("status", stringValue(previousItem, "status", PENDING));
                item.addProperty("reasonCode", stringValue(previousItem, "reasonCode", "PENDING"));
                if (previousItem.has("updatedAt")) item.add("updatedAt", previousItem.get("updatedAt").deepCopy());
            }
            applyPostD4State(runDirectory, item);
            items.add(item);
        }
        JsonObject state = baseState(safeRunId, mode, config.enabled(), items);
        normalize(state);
        writeState(safeRunId, state);
        return response(state);
    }

    public synchronized JsonObject status(String runId) throws IOException {
        String safeRunId = requireId(runId, "runId");
        JsonObject state = readState(safeRunId);
        if (state == null) return refresh(safeRunId, "");
        Path runDirectory = runDirectory(safeRunId);
        for (var element : state.getAsJsonArray("items")) {
            applyPostD4State(runDirectory, element.getAsJsonObject());
        }
        normalize(state);
        writeState(safeRunId, state);
        return response(state);
    }

    public synchronized boolean registryCoversAllRealms(String runId) throws IOException {
        Path runDirectory = runDirectory(runId);
        Path profilesPath = runDirectory.resolve("realm_profiles.json");
        if (!Files.isRegularFile(profilesPath)) return true;
        var profileElement = JsonParser.parseString(Files.readString(profilesPath));
        JsonArray profiles = profileElement.isJsonArray() ? profileElement.getAsJsonArray()
                : profileElement.getAsJsonObject().has("realmProfiles")
                ? profileElement.getAsJsonObject().getAsJsonArray("realmProfiles") : new JsonArray();
        Set<String> expected = new HashSet<>();
        for (var element : profiles) {
            if (element.isJsonObject()) expected.add(stringValue(element.getAsJsonObject(), "realmId", ""));
        }
        expected.remove("");
        if (expected.isEmpty()) return true;
        JsonObject registry = readObject(runDirectory.resolve("city_seed_registry.json"));
        Set<String> capitals = new HashSet<>();
        for (var element : registry.getAsJsonArray("citySeeds")) {
            JsonObject seed = element.getAsJsonObject();
            if ("capital".equals(stringValue(seed, "role", ""))) {
                capitals.add(stringValue(seed, "realmId", ""));
            }
        }
        return capitals.containsAll(expected);
    }

    public synchronized void requireCurrentIfManaged(String runId, String citySeedId) throws IOException {
        CityDesignQueueConfig config = CityDesignQueueConfig.loadOrCreate(configPath);
        if (!config.enabled() || !Files.isRegularFile(runDirectory(runId).resolve("city_seed_registry.json"))) return;
        JsonObject state = readState(runId);
        if (state == null) state = refresh(runId, "");
        normalize(state);
        String current = stringValue(state, "currentCitySeedId", "");
        if (!citySeedId.equals(current)) {
            throw new IllegalArgumentException("CITY_DESIGN_QUEUE_OUT_OF_ORDER: current=" + current
                    + ", requested=" + citySeedId);
        }
        String status = stringValue(state, "status", "");
        if (!WAITING_FOR_AGENT.equals(status) && !WAITING_FOR_PATCH_REVIEW.equals(status)
                && !NEEDS_AGENT.equals(status)) {
            throw new IllegalArgumentException("CITY_DESIGN_QUEUE_CURRENT_NOT_ACCEPTING_D4: status=" + status);
        }
    }

    public synchronized void onAgentWorkflowState(String runId, String citySeedId, String status,
                                                   String reasonCode, String sessionId) throws IOException {
        CityDesignQueueConfig config = CityDesignQueueConfig.loadOrCreate(configPath);
        if (!config.enabled() || !Files.isRegularFile(runDirectory(runId).resolve("city_seed_registry.json"))) return;
        JsonObject state = readState(runId);
        if (state == null) state = refresh(runId, "");
        JsonObject item = findItem(state, citySeedId);
        if (item == null || !citySeedId.equals(stringValue(state, "currentCitySeedId", ""))) {
            throw new IllegalArgumentException("CITY_DESIGN_QUEUE_OUT_OF_ORDER: current="
                    + stringValue(state, "currentCitySeedId", "") + ", requested=" + citySeedId);
        }
        if (!WAITING_FOR_AGENT.equals(status) && !WAITING_FOR_PATCH_REVIEW.equals(status)) {
            throw new IllegalArgumentException("CITY_DESIGN_QUEUE_AGENT_STATUS_INVALID: " + status);
        }
        setItemStatus(item, status, reasonCode);
        if (sessionId == null || sessionId.isBlank()) item.remove("patchExplorerSessionId");
        else item.addProperty("patchExplorerSessionId", sessionId);
        normalize(state);
        writeState(runId, state);
    }

    public synchronized void onPostD4State(JsonObject postD4State) {
        String runId = stringValue(postD4State, "runId", "");
        String citySeedId = stringValue(postD4State, "citySeedId", "");
        if (runId.isBlank() || citySeedId.isBlank()) return;
        try {
            JsonObject state = readState(runId);
            if (state == null) {
                if (!Files.isRegularFile(runDirectory(runId).resolve("city_seed_registry.json"))) return;
                state = refresh(runId, "");
            }
            JsonObject item = findItem(state, citySeedId);
            if (item == null) return;
            applyPostD4Status(item, stringValue(postD4State, "status", ""),
                    stringValue(postD4State, "reasonCode", ""));
            normalize(state);
            writeState(runId, state);
        } catch (Exception ignored) {
            // Post-D4 state remains authoritative and status() reconciles it on the next request.
        }
    }

    private void applyPostD4State(Path runDirectory, JsonObject item) throws IOException {
        String citySeedId = stringValue(item, "citySeedId", "");
        Path path = runDirectory.resolve("automation").resolve("post_d4")
                .resolve(citySeedId.replaceAll("[^A-Za-z0-9._-]", "_") + ".json");
        if (!Files.isRegularFile(path)) return;
        JsonObject post = readObject(path);
        applyPostD4Status(item, stringValue(post, "status", ""), stringValue(post, "reasonCode", ""));
    }

    private static void applyPostD4Status(JsonObject item, String status, String reasonCode) {
        switch (status) {
            case "queued", "running" -> setItemStatus(item, POST_D4_RUNNING,
                    reasonCode.isBlank() ? "POST_D4_RUNNING" : reasonCode);
            case WAITING_FOR_GENERATION -> setItemStatus(item, WAITING_FOR_GENERATION,
                    reasonCode.isBlank() ? "WAITING_FOR_GENERATION" : reasonCode);
            case NEEDS_AGENT -> setItemStatus(item, NEEDS_AGENT,
                    reasonCode.isBlank() ? "POST_D4_NEEDS_AGENT" : reasonCode);
            default -> { }
        }
    }

    private static void normalize(JsonObject state) {
        JsonArray items = state.getAsJsonArray("items");
        JsonObject current = null;
        for (var element : items) {
            JsonObject item = element.getAsJsonObject();
            String status = stringValue(item, "status", PENDING);
            if (WAITING_FOR_GENERATION.equals(status)) continue;
            if (current == null) {
                current = item;
                String reasonCode = stringValue(item, "reasonCode", "");
                boolean agentPhase = WAITING_FOR_PATCH_REVIEW.equals(status)
                        || WAITING_FOR_AGENT.equals(status) && ("D3_SITE_REVIEW_REQUIRED".equals(reasonCode)
                        || "PATCH_REVIEW_COMPLETED".equals(reasonCode)
                        || "D4_CONTEXT_PREPARED".equals(reasonCode));
                if (!POST_D4_RUNNING.equals(status) && !NEEDS_AGENT.equals(status) && !agentPhase) {
                    setItemStatus(item, WAITING_FOR_AGENT, "NEXT_CITY_BY_PRIORITY");
                }
            } else if (!WAITING_FOR_GENERATION.equals(status)) {
                setItemStatus(item, PENDING, "WAITING_FOR_PREVIOUS_CITY");
            }
        }
        int completed = 0;
        for (var element : items) {
            if (WAITING_FOR_GENERATION.equals(stringValue(element.getAsJsonObject(), "status", ""))) completed++;
        }
        state.addProperty("completedCount", completed);
        state.addProperty("remainingCount", items.size() - completed);
        state.addProperty("updatedAt", Instant.now().toString());
        if (current == null) {
            state.addProperty("status", items.isEmpty() ? "empty" : "completed");
            state.remove("currentCitySeedId");
            state.addProperty("nextAction", "");
            return;
        }
        String currentStatus = stringValue(current, "status", WAITING_FOR_AGENT);
        state.addProperty("status", currentStatus);
        state.addProperty("currentCitySeedId", stringValue(current, "citySeedId", ""));
        state.addProperty("nextAction", switch (currentStatus) {
            case WAITING_FOR_AGENT -> switch (stringValue(current, "reasonCode", "")) {
                case "D3_SITE_REVIEW_REQUIRED" -> "city_review_d3_site";
                case "PATCH_REVIEW_COMPLETED" -> "city_prepare_d4_blueprint_context";
                case "D4_CONTEXT_PREPARED" -> "city_submit_d4_blueprint";
                default -> "city_plan_d3";
            };
            case WAITING_FOR_PATCH_REVIEW -> "patch_explorer_show_candidates";
            case POST_D4_RUNNING -> "city_post_d4_auto_compile_status";
            case NEEDS_AGENT -> "city_post_d4_auto_compile_status";
            default -> "";
        });
    }

    private static void setItemStatus(JsonObject item, String status, String reasonCode) {
        item.addProperty("status", status);
        item.addProperty("reasonCode", reasonCode);
        item.addProperty("updatedAt", Instant.now().toString());
    }

    private JsonObject baseState(String runId, OrderingMode mode, boolean enabled, JsonArray items) {
        JsonObject state = new JsonObject();
        state.addProperty("schema", SCHEMA);
        state.addProperty("runId", runId);
        state.addProperty("enabled", enabled);
        state.addProperty("orderingMode", mode.contractName());
        state.addProperty("createdAt", Instant.now().toString());
        state.add("items", items);
        return state;
    }

    private JsonObject response(JsonObject state) {
        JsonObject response = state.deepCopy();
        response.addProperty("ok", true);
        response.addProperty("statePath", debugRoot.relativize(statePath(stringValue(state, "runId", "")))
                .toString().replace('\\', '/'));
        String currentId = stringValue(state, "currentCitySeedId", "");
        JsonObject current = currentId.isBlank() ? null : findItem(state, currentId);
        if (current != null) response.add("currentCity", current.deepCopy());
        return response;
    }

    private static List<Seed> parseSeeds(JsonArray values, int worldSurveyStep) {
        List<Seed> seeds = new ArrayList<>();
        for (var element : values) {
            if (!element.isJsonObject()) continue;
            JsonObject value = element.getAsJsonObject();
            String id = requireId(stringValue(value, "citySeedId", ""), "citySeedId");
            String realmId = requireId(stringValue(value, "realmId", ""), "realmId");
            JsonObject anchorBlock = object(value, "anchorBlock");
            JsonObject anchorGrid = object(value, "anchorGrid");
            int blockX = anchorBlock.has("x") ? anchorBlock.get("x").getAsInt()
                    : intValue(anchorGrid, "x", 0) * worldSurveyStep;
            int blockZ = anchorBlock.has("z") ? anchorBlock.get("z").getAsInt()
                    : intValue(anchorGrid, "z", 0) * worldSurveyStep;
            seeds.add(new Seed(id, realmId, stringValue(value, "role", "city"), blockX, blockZ));
        }
        return seeds;
    }

    private static void sort(List<Seed> seeds, OrderingMode mode) {
        if (mode == OrderingMode.GLOBAL_RADIAL) {
            seeds.sort(Comparator.comparingLong(Seed::originDistanceSquared)
                    .thenComparing(Seed::realmId).thenComparing(Seed::citySeedId));
            return;
        }
        Map<String, Seed> capitals = new HashMap<>();
        for (Seed seed : seeds) {
            Seed existing = capitals.get(seed.realmId());
            if (existing == null || "capital".equals(seed.role()) && !"capital".equals(existing.role())
                    || "capital".equals(seed.role()) == "capital".equals(existing.role())
                    && seed.originDistanceSquared() < existing.originDistanceSquared()) {
                capitals.put(seed.realmId(), seed);
            }
        }
        List<String> realms = capitals.entrySet().stream()
                .sorted(Map.Entry.<String, Seed>comparingByValue(Comparator.comparingLong(Seed::originDistanceSquared))
                        .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey).toList();
        Map<String, Integer> realmRanks = new HashMap<>();
        for (int index = 0; index < realms.size(); index++) realmRanks.put(realms.get(index), index);
        seeds.sort(Comparator.comparingInt((Seed seed) -> realmRanks.getOrDefault(seed.realmId(), Integer.MAX_VALUE))
                .thenComparingLong(seed -> seed.distanceSquaredTo(capitals.get(seed.realmId())))
                .thenComparing(Seed::citySeedId));
    }

    private int worldSurveyStep(Path runDirectory) throws IOException {
        Path contextPath = runDirectory.resolve("world_survey_context.json");
        if (!Files.isRegularFile(contextPath)) return 128;
        return intValue(readObject(contextPath), "cellStepBlocks", 128);
    }

    private JsonObject readState(String runId) throws IOException {
        Path path = statePath(runId);
        return Files.isRegularFile(path) ? readObject(path) : null;
    }

    private void writeState(String runId, JsonObject state) throws IOException {
        Path target = statePath(runId);
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".city_design_queue", ".tmp");
        try {
            Files.writeString(temporary, GSON.toJson(state));
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Path statePath(String runId) {
        return runDirectory(runId).resolve("automation").resolve("city_design_queue.json");
    }

    private Path runDirectory(String runId) {
        Path path = debugRoot.resolve(requireId(runId, "runId")).normalize();
        if (!path.startsWith(debugRoot)) throw new IllegalArgumentException("CITY_DESIGN_QUEUE_RUN_OUTSIDE_DEBUG_ROOT");
        return path;
    }

    private static Map<String, JsonObject> itemsById(JsonObject state) {
        Map<String, JsonObject> items = new LinkedHashMap<>();
        if (state == null || !state.has("items") || !state.get("items").isJsonArray()) return items;
        for (var element : state.getAsJsonArray("items")) {
            JsonObject item = element.getAsJsonObject();
            items.put(stringValue(item, "citySeedId", ""), item);
        }
        return items;
    }

    private static JsonObject findItem(JsonObject state, String citySeedId) {
        if (state == null || !state.has("items")) return null;
        for (var element : state.getAsJsonArray("items")) {
            JsonObject item = element.getAsJsonObject();
            if (citySeedId.equals(stringValue(item, "citySeedId", ""))) return item;
        }
        return null;
    }

    private static JsonObject readObject(Path path) throws IOException {
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }

    private static JsonObject object(JsonObject value, String key) {
        return value.has(key) && value.get(key).isJsonObject() ? value.getAsJsonObject(key) : new JsonObject();
    }

    private static String requireId(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private static String stringValue(JsonObject object, String key, String fallback) {
        return object != null && object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsString() : fallback;
    }

    private static int intValue(JsonObject object, String key, int fallback) {
        return object != null && object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsInt() : fallback;
    }

    private record Seed(String citySeedId, String realmId, String role, int blockX, int blockZ) {
        long originDistanceSquared() {
            return (long) blockX * blockX + (long) blockZ * blockZ;
        }

        long distanceSquaredTo(Seed other) {
            long dx = (long) blockX - other.blockX;
            long dz = (long) blockZ - other.blockZ;
            return dx * dx + dz * dz;
        }

        JsonObject asItem(int ordinal) {
            JsonObject item = new JsonObject();
            item.addProperty("ordinal", ordinal);
            item.addProperty("citySeedId", citySeedId);
            item.addProperty("realmId", realmId);
            item.addProperty("role", role);
            JsonObject anchor = new JsonObject();
            anchor.addProperty("x", blockX);
            anchor.addProperty("z", blockZ);
            item.add("anchorBlock", anchor);
            item.addProperty("originDistanceBlocks", Math.hypot(blockX, blockZ));
            item.addProperty("status", PENDING);
            item.addProperty("reasonCode", "PENDING");
            item.addProperty("updatedAt", Instant.now().toString());
            return item;
        }
    }
}
