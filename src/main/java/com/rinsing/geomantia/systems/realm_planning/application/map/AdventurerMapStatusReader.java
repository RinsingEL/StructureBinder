package com.rinsing.geomantia.systems.realm_planning.application.map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.realm_planning.application.map.AdventurerMapSnapshot.CityNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Builds a read-only map snapshot from the current save's persisted planning artifacts. */
public final class AdventurerMapStatusReader {
    private AdventurerMapStatusReader() {
    }

    public static AdventurerMapSnapshot read(Path debugRoot, String preferredRunId) throws IOException {
        return read(debugRoot, preferredRunId, 2048);
    }

    public static AdventurerMapSnapshot read(Path debugRoot, String preferredRunId,
                                              int initialActivityRadiusBlocks) throws IOException {
        Path root = debugRoot.toAbsolutePath().normalize();
        Optional<Path> runDirectory = resolveRunDirectory(root, preferredRunId);
        if (runDirectory.isEmpty()) {
            return AdventurerMapSnapshot.empty();
        }
        Path run = runDirectory.get();
        String runId = run.getFileName().toString();

        JsonObject progress = readObjectIfPresent(run.resolve("world_survey_progress.json"));
        boolean wCompleted = Files.isRegularFile(run.resolve("w_manifest.json"))
                || Files.isRegularFile(run.resolve("world_survey_context.json"));
        String wStatus = wCompleted ? "completed" : stringValue(progress, "status", "not_started");
        String wPhase = wCompleted ? "sealed" : stringValue(progress, "phase", "");
        double wProgress = wCompleted ? 100.0D : doubleValue(progress, "phaseProgressPercent", 0.0D);

        StageState stageState = stageState(run, wCompleted);
        Map<String, String> realmNames = realmNames(run.resolve("realm_profiles.json"));
        JsonObject queue = readObjectIfPresent(run.resolve("automation").resolve("city_design_queue.json"));
        String currentCityId = stringValue(queue, "currentCitySeedId", "");
        Map<String, JsonObject> queueItems = queueItems(queue);
        JsonObject currentItem = queueItems.get(currentCityId);
        String currentRealmId = stringValue(currentItem, "realmId", "");
        String currentRealmName = realmNames.getOrDefault(currentRealmId, currentRealmId);
        String cityStatus = stringValue(queue, "status", currentCityId.isBlank() ? "not_started" : "pending");
        int completedCount = intValue(queue, "completedCount", 0);
        int remainingCount = intValue(queue, "remainingCount", 0);
        List<CityNode> nodes = cityNodes(run.resolve("city_seed_registry.json"), queueItems, currentCityId);

        return new AdventurerMapSnapshot(runId, wStatus, wPhase, wProgress,
                stageState.stage(), stageState.status(), currentRealmId, currentRealmName,
                currentCityId, cityStatus, completedCount, remainingCount,
                initialActivityRadiusBlocks, nodes);
    }

    private static Optional<Path> resolveRunDirectory(Path root, String preferredRunId) throws IOException {
        if (preferredRunId != null && !preferredRunId.isBlank()) {
            Path preferred = root.resolve(preferredRunId).normalize();
            if (preferred.startsWith(root) && Files.isDirectory(preferred)) {
                return Optional.of(preferred);
            }
        }
        if (!Files.isDirectory(root)) {
            return Optional.empty();
        }
        try (var directories = Files.list(root)) {
            return directories.filter(Files::isDirectory)
                    .filter(AdventurerMapStatusReader::looksLikePlanningRun)
                    .max(Comparator.comparing(AdventurerMapStatusReader::lastModified));
        }
    }

    private static boolean looksLikePlanningRun(Path path) {
        return Files.isRegularFile(path.resolve("world_survey_progress.json"))
                || Files.isRegularFile(path.resolve("world_survey_manifest.json"))
                || Files.isRegularFile(path.resolve("world_survey_context.json"))
                || Files.isRegularFile(path.resolve("city_seed_registry.json"));
    }

    private static FileTime lastModified(Path path) {
        try {
            Path progress = path.resolve("world_survey_progress.json");
            return Files.getLastModifiedTime(Files.exists(progress) ? progress : path);
        } catch (IOException ignored) {
            return FileTime.fromMillis(0L);
        }
    }

    private static StageState stageState(Path run, boolean wCompleted) {
        if (Files.isRegularFile(run.resolve("t4_report.json"))
                || Files.isRegularFile(run.resolve("city_seed_registry.json"))) {
            return new StageState("T4", "completed");
        }
        if (Files.isRegularFile(run.resolve("t3_report.json"))) {
            return new StageState("T4", "pending");
        }
        if (Files.isRegularFile(run.resolve("t2_report.json"))) {
            return new StageState("T3", "pending");
        }
        if (Files.isRegularFile(run.resolve("t1_manifest.json"))) {
            return new StageState("T2", "pending");
        }
        if (wCompleted) {
            return new StageState("T1", "pending");
        }
        return new StageState("", "not_started");
    }

    private static Map<String, String> realmNames(Path path) throws IOException {
        JsonElement root = readElementIfPresent(path);
        JsonArray profiles = root != null && root.isJsonArray() ? root.getAsJsonArray()
                : root != null && root.isJsonObject() && root.getAsJsonObject().has("realmProfiles")
                ? root.getAsJsonObject().getAsJsonArray("realmProfiles") : new JsonArray();
        Map<String, String> names = new HashMap<>();
        for (JsonElement element : profiles) {
            if (!element.isJsonObject()) continue;
            JsonObject profile = element.getAsJsonObject();
            String realmId = stringValue(profile, "realmId", "");
            if (!realmId.isBlank()) names.put(realmId, stringValue(profile, "name", realmId));
        }
        return names;
    }

    private static Map<String, JsonObject> queueItems(JsonObject queue) {
        Map<String, JsonObject> items = new HashMap<>();
        if (queue == null || !queue.has("items") || !queue.get("items").isJsonArray()) return items;
        for (JsonElement element : queue.getAsJsonArray("items")) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            String cityId = stringValue(item, "citySeedId", "");
            if (!cityId.isBlank()) items.put(cityId, item);
        }
        return items;
    }

    private static List<CityNode> cityNodes(Path registryPath, Map<String, JsonObject> queueItems,
                                             String currentCityId) throws IOException {
        JsonObject registry = readObjectIfPresent(registryPath);
        if (registry == null || !registry.has("citySeeds") || !registry.get("citySeeds").isJsonArray()) {
            return List.of();
        }
        List<CityNode> nodes = new ArrayList<>();
        for (JsonElement element : registry.getAsJsonArray("citySeeds")) {
            if (!element.isJsonObject()) continue;
            JsonObject seed = element.getAsJsonObject();
            String cityId = stringValue(seed, "citySeedId", "");
            if (cityId.isBlank()) continue;
            JsonObject anchor = objectValue(seed, "anchorBlock");
            JsonObject item = queueItems.get(cityId);
            nodes.add(new CityNode(cityId, stringValue(seed, "realmId", ""),
                    stringValue(seed, "role", "city"), intValue(anchor, "x", 0), intValue(anchor, "z", 0),
                    stringValue(item, "status", "planned"), cityId.equals(currentCityId)));
        }
        return List.copyOf(nodes);
    }

    private static JsonObject readObjectIfPresent(Path path) throws IOException {
        JsonElement value = readElementIfPresent(path);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static JsonElement readElementIfPresent(Path path) throws IOException {
        return Files.isRegularFile(path) ? JsonParser.parseString(Files.readString(path)) : null;
    }

    private static JsonObject objectValue(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonObject()
                ? object.getAsJsonObject(key) : new JsonObject();
    }

    private static String stringValue(JsonObject object, String key, String fallback) {
        return object != null && object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsString() : fallback;
    }

    private static int intValue(JsonObject object, String key, int fallback) {
        return object != null && object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsInt() : fallback;
    }

    private static double doubleValue(JsonObject object, String key, double fallback) {
        return object != null && object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsDouble() : fallback;
    }

    private record StageState(String stage, String status) {
    }
}
