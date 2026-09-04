package com.rinsing.geomantia.systems.provider.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.realm_planning.WorldSurveySettingsConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/** Restores the next W/T/City decision from formal artifacts for the current server world. */
public final class ProviderPlanningDiscovery {
    private static final Set<String> CITY_ACTIONABLE = Set.of(
            "waiting_for_agent", "waiting_for_patch_review");
    private final Path debugRoot;
    private final Path surveySettingsPath;
    private final String worldSeed;
    private final int realmCount;

    public ProviderPlanningDiscovery(Path debugRoot, long worldSeed) {
        this(debugRoot, worldSeed, 3);
    }

    ProviderPlanningDiscovery(Path debugRoot, long worldSeed, int realmCount) {
        this.debugRoot = debugRoot.toAbsolutePath().normalize();
        this.surveySettingsPath = this.debugRoot.getParent().resolve("config").resolve("geomantia")
                .resolve("world_survey.json");
        this.worldSeed = Long.toString(worldSeed);
        this.realmCount = Math.max(1, Math.min(12, realmCount));
    }

    public PlanningStep nextStep() throws IOException {
        WorldSurveySettingsConfig surveySettings = WorldSurveySettingsConfig.loadOrCreate(surveySettingsPath);
        Optional<Path> newest = newestCurrentWorldRun(surveySettings);
        if (newest.isEmpty()) {
            String runId = "provider_" + Long.toUnsignedString(Long.parseLong(worldSeed), 16)
                    + "_r" + surveySettings.planningRadiusBlocks();
            JsonObject state = baseState(Stage.W, runId, "realm_w_refresh");
            state.addProperty("hostConfiguredPlanningRadiusBlocks", surveySettings.planningRadiusBlocks());
            state.addProperty("instruction", "Call W refresh once. The host owns and injects the complete survey range; do not provide range parameters.");
            return step(Stage.W, runId, "", "", "realm_w_refresh", state,
                    debugRoot.resolve(runId), List.of());
        }

        Path runDirectory = newest.get();
        String runId = runDirectory.getFileName().toString();
        if (!sealedWExists(runDirectory)) {
            JsonObject state = baseState(Stage.W, runId, "realm_w_refresh");
            state.addProperty("resumePolicy", "use_cache");
            return step(Stage.W, runId, "", "", "realm_w_refresh", state, runDirectory, List.of());
        }

        JsonArray profiles = readArray(runDirectory.resolve("realm_profiles.json"), "realmProfiles");
        if (profiles.isEmpty()) {
            JsonObject state = baseState(Stage.T1, runId, "realm_t1_prepare");
            state.addProperty("realmCount", realmCount);
            state.add("worldOverview", worldOverview(runDirectory));
            state.addProperty("instruction", "Design exactly " + realmCount
                    + " distinct realm profiles from the world overview and previews, then call realm_t1_prepare.");
            return step(Stage.T1, runId, "", "", "realm_t1_prepare", state, runDirectory,
                    existingImages(runDirectory, "world_patch_preview.png", "world_biome_preview.png"));
        }

        JsonArray selections = readArray(runDirectory.resolve("realm_coordinate_selections.json"),
                "realmCoordinateSelections");
        Set<String> selectedRealms = ids(selections, "realmId");
        for (JsonElement element : profiles) {
            if (!element.isJsonObject()) continue;
            JsonObject profile = element.getAsJsonObject();
            String realmId = string(profile, "realmId");
            if (!realmId.isBlank() && !selectedRealms.contains(realmId)) {
                JsonObject state = baseState(Stage.T2, runId, "patch_explorer_open");
                state.addProperty("realmId", realmId);
                state.add("realmProfile", profile.deepCopy());
                state.addProperty("instruction", "Open realm_t2 Patch Explorer for this realm, inspect candidates, "
                        + "freeze one selection, then submit it with realm_t2_select_coordinate. Do not use raw grid coordinates.");
                return step(Stage.T2, runId, realmId, "", "patch_explorer_open", state,
                        runDirectory, List.of());
            }
        }

        if (!Files.isRegularFile(runDirectory.resolve("t3_report.json"))
                || !Files.isRegularFile(runDirectory.resolve("realm_territory_map.json"))) {
            JsonObject state = baseState(Stage.T3, runId, "realm_t3_expand");
            state.add("realmProfiles", profiles.deepCopy());
            state.add("realmCoordinateSelections", selections.deepCopy());
            state.addProperty("instruction", "All realms completed T2. Call realm_t3_expand exactly once for the "
                    + "whole run; never expand one realm independently.");
            return step(Stage.T3, runId, "", "", "realm_t3_expand", state, runDirectory, List.of());
        }

        Set<String> registeredRealms = registeredCapitalRealms(runDirectory);
        for (JsonElement element : profiles) {
            if (!element.isJsonObject()) continue;
            JsonObject profile = element.getAsJsonObject();
            String realmId = string(profile, "realmId");
            if (registeredRealms.contains(realmId)) continue;
            JsonObject state = baseState(Stage.T4, runId, "realm_t4_patch_planning_create");
            state.addProperty("realmId", realmId);
            state.add("realmProfile", profile.deepCopy());
            JsonObject session = newestOpenT4Session(runDirectory, realmId);
            if (session != null) {
                state.add("openPlanningSession", session.deepCopy());
                state.addProperty("nextAction", t4NextAction(session));
            }
            state.addProperty("instruction", "Continue the existing T4 contract for this realm: create or resume its "
                    + "planning session, review Patch Explorer evidence, select the required capital and any justified "
                    + "non-capital city seeds, then finalize. Do not start or refresh the City queue.");
            return step(Stage.T4, runId, realmId, "", string(state, "nextAction"), state,
                    runDirectory, List.of());
        }

        JsonObject queue = readObject(runDirectory.resolve("automation/city_design_queue.json"));
        if (!registeredRealms.isEmpty() && (queue == null || queueItems(queue).size() < registeredCityCount(runDirectory))) {
            JsonObject state = baseState(Stage.QUEUE_REFRESH, runId, "city_design_queue_refresh");
            return step(Stage.QUEUE_REFRESH, runId, "", "", "city_design_queue_refresh", state,
                    runDirectory, List.of());
        }

        if (queue != null && queueHasUnfinishedCity(queue)) {
            String status = string(queue, "status");
            String cityId = string(queue, "currentCitySeedId");
            String nextAction = string(queue, "nextAction");
            boolean blueprintRevision = "needs_agent".equals(status)
                    && "city_submit_d4_blueprint".equals(nextAction);
            if ((CITY_ACTIONABLE.contains(status) || blueprintRevision)
                    && !cityId.isBlank() && !nextAction.isBlank()) {
                return step(Stage.CITY, runId, string(queueCurrent(queue), "realmId"), cityId,
                        nextAction, queue.deepCopy(), runDirectory, List.of());
            }
            JsonObject state = baseState(Stage.WAITING, runId, nextAction);
            state.add("cityDesignQueue", queue.deepCopy());
            return step(Stage.WAITING, runId, string(queueCurrent(queue), "realmId"), cityId,
                    nextAction, state, runDirectory, List.of());
        }

        JsonObject state = baseState(Stage.COMPLETE, runId, "");
        if (queue != null) state.add("cityDesignQueue", queue.deepCopy());
        return step(Stage.COMPLETE, runId, "", "", "", state, runDirectory, List.of());
    }

    private Optional<Path> newestCurrentWorldRun(WorldSurveySettingsConfig surveySettings) throws IOException {
        if (!Files.isDirectory(debugRoot)) return Optional.empty();
        try (Stream<Path> paths = Files.list(debugRoot)) {
            return paths.filter(Files::isDirectory).filter(path -> matchesWorld(path, surveySettings))
                    .max(Comparator.comparing(this::createdAt));
        }
    }

    private boolean matchesWorld(Path runDirectory, WorldSurveySettingsConfig surveySettings) {
        JsonObject context = readObject(runDirectory.resolve("world_survey_context.json"));
        JsonObject scanBounds = object(context, "scanBounds");
        return context != null && worldSeed.equals(string(context, "worldSeed"))
                && integer(scanBounds, "centerBlockX") == 0
                && integer(scanBounds, "centerBlockZ") == 0
                && integer(scanBounds, "planningRadiusBlocks") == surveySettings.planningRadiusBlocks();
    }

    private Instant createdAt(Path runDirectory) {
        JsonObject manifest = readObject(runDirectory.resolve("world_survey_manifest.json"));
        try {
            String value = string(manifest, "createdAt");
            if (!value.isBlank()) return Instant.parse(value);
        } catch (RuntimeException ignored) {
        }
        try {
            return Files.getLastModifiedTime(runDirectory).toInstant();
        } catch (IOException ignored) {
            return Instant.EPOCH;
        }
    }

    private static boolean sealedWExists(Path runDirectory) {
        JsonObject context = readObject(runDirectory.resolve("world_survey_context.json"));
        return context != null && bool(context, "sealed")
                && Files.isRegularFile(runDirectory.resolve("world_patch_map.json"));
    }

    private JsonObject baseState(Stage stage, String runId, String nextAction) {
        JsonObject state = new JsonObject();
        state.addProperty("schema", "provider_planning_step.v0.1");
        state.addProperty("stage", stage.contractName);
        state.addProperty("runId", runId);
        state.addProperty("nextAction", nextAction);
        state.addProperty("realmCount", realmCount);
        return state;
    }

    private static JsonObject worldOverview(Path runDirectory) {
        JsonObject context = readObject(runDirectory.resolve("world_survey_context.json"));
        JsonObject manifest = readObject(runDirectory.resolve("w_manifest.json"));
        JsonObject summary = new JsonObject();
        if (context != null) {
            for (String key : List.of("dimensionId", "worldBorderSizeBlocks", "cellStepBlocks", "gridOriginBlock",
                    "gridSize", "scanBounds", "surveyStats")) copy(context, summary, key);
        }
        if (manifest != null && manifest.has("continents") && manifest.get("continents").isJsonArray()) {
            List<JsonObject> continents = new ArrayList<>();
            for (JsonElement element : manifest.getAsJsonArray("continents")) {
                if (element.isJsonObject()) continents.add(element.getAsJsonObject());
            }
            continents.sort(Comparator.comparingLong(value -> -longValue(value, "areaCells")));
            JsonArray largest = new JsonArray();
            for (int index = 0; index < Math.min(12, continents.size()); index++) {
                largest.add(continents.get(index).deepCopy());
            }
            summary.add("largestContinents", largest);
        }
        return summary;
    }

    private static JsonObject newestOpenT4Session(Path runDirectory, String realmId) {
        try (Stream<Path> paths = Files.list(runDirectory)) {
            return paths.filter(path -> path.getFileName().toString().startsWith("realm_t4_patch_planning_"))
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .map(ProviderPlanningDiscovery::readObject)
                    .filter(value -> value != null && realmId.equals(string(value, "realmId"))
                            && "open".equals(string(value, "status")))
                    .max(Comparator.comparing(value -> string(value, "updatedAt")))
                    .orElse(null);
        } catch (IOException ignored) {
            return null;
        }
    }

    private static String t4NextAction(JsonObject session) {
        int seeds = array(session, "citySeeds").size();
        return seeds == 0 ? "patch_explorer_open" : "patch_explorer_open";
    }

    private static Set<String> registeredCapitalRealms(Path runDirectory) {
        JsonObject registry = readObject(runDirectory.resolve("city_seed_registry.json"));
        Set<String> result = new HashSet<>();
        if (registry == null) return result;
        for (JsonElement element : array(registry, "citySeeds")) {
            if (!element.isJsonObject()) continue;
            JsonObject seed = element.getAsJsonObject();
            if ("capital".equals(string(seed, "role"))) result.add(string(seed, "realmId"));
        }
        result.remove("");
        return result;
    }

    private static int registeredCityCount(Path runDirectory) {
        JsonObject registry = readObject(runDirectory.resolve("city_seed_registry.json"));
        return registry == null ? 0 : array(registry, "citySeeds").size();
    }

    private static boolean queueHasUnfinishedCity(JsonObject queue) {
        for (JsonElement element : queueItems(queue)) {
            if (element.isJsonObject() && !"waiting_for_generation".equals(
                    string(element.getAsJsonObject(), "status"))) return true;
        }
        return false;
    }

    private static JsonObject queueCurrent(JsonObject queue) {
        String id = string(queue, "currentCitySeedId");
        for (JsonElement element : queueItems(queue)) {
            if (element.isJsonObject() && id.equals(string(element.getAsJsonObject(), "citySeedId"))) {
                return element.getAsJsonObject();
            }
        }
        return null;
    }

    private static JsonArray queueItems(JsonObject queue) {
        return array(queue, "items");
    }

    private static PlanningStep step(Stage stage, String runId, String realmId, String cityId,
                                     String nextAction, JsonObject state, Path runDirectory, List<Path> images) {
        String identity = String.join("|", stage.contractName, runId, realmId, cityId, nextAction,
                string(state, "status"), string(state, "reasonCode"), string(state, "errorCode"),
                fileStamp(runDirectory.resolve("realm_profiles.json")),
                fileStamp(runDirectory.resolve("realm_coordinate_selections.json")),
                fileStamp(runDirectory.resolve("t3_report.json")),
                fileStamp(runDirectory.resolve("city_seed_registry.json")));
        return new PlanningStep(stage, runId, realmId, cityId, nextAction, state, runDirectory, images, identity);
    }

    private static String fileStamp(Path path) {
        try {
            FileTime time = Files.getLastModifiedTime(path);
            return Long.toString(time.toMillis());
        } catch (IOException ignored) {
            return "-";
        }
    }

    private static List<Path> existingImages(Path directory, String... names) {
        List<Path> result = new ArrayList<>();
        for (String name : names) {
            Path path = directory.resolve(name).normalize();
            if (path.startsWith(directory) && Files.isRegularFile(path)) result.add(path);
        }
        return List.copyOf(result);
    }

    private static JsonArray readArray(Path path, String objectKey) {
        try {
            if (!Files.isRegularFile(path)) return new JsonArray();
            JsonElement element = JsonParser.parseString(Files.readString(path));
            if (element.isJsonArray()) return element.getAsJsonArray();
            if (element.isJsonObject() && element.getAsJsonObject().has(objectKey)
                    && element.getAsJsonObject().get(objectKey).isJsonArray()) {
                return element.getAsJsonObject().getAsJsonArray(objectKey);
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return new JsonArray();
    }

    private static JsonObject readObject(Path path) {
        try {
            if (!Files.isRegularFile(path)) return null;
            JsonElement element = JsonParser.parseString(Files.readString(path));
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static Set<String> ids(JsonArray values, String key) {
        Set<String> result = new HashSet<>();
        for (JsonElement element : values) if (element.isJsonObject()) result.add(string(element.getAsJsonObject(), key));
        result.remove("");
        return result;
    }

    private static void copy(JsonObject from, JsonObject to, String key) {
        if (from != null && from.has(key)) to.add(key, from.get(key).deepCopy());
    }

    private static JsonArray array(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonArray()
                ? object.getAsJsonArray(key) : new JsonArray();
    }

    private static JsonObject object(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonObject()
                ? object.getAsJsonObject(key) : new JsonObject();
    }

    private static String string(JsonObject object, String key) {
        return object != null && object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsString() : "";
    }

    private static boolean bool(JsonObject object, String key) {
        return object != null && object.has(key) && !object.get(key).isJsonNull()
                && object.get(key).getAsBoolean();
    }

    private static long longValue(JsonObject object, String key) {
        return object != null && object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsLong() : 0L;
    }

    private static int integer(JsonObject object, String key) {
        return object != null && object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsInt() : Integer.MIN_VALUE;
    }

    public enum Stage {
        W("w"), T1("t1"), T2("t2"), T3("t3"), T4("t4"),
        QUEUE_REFRESH("city_queue_refresh"), CITY("city"), WAITING("waiting"), COMPLETE("complete");

        private final String contractName;

        Stage(String contractName) {
            this.contractName = contractName;
        }

        public boolean actionable() {
            return this != WAITING && this != COMPLETE;
        }
    }

    public record PlanningStep(Stage stage, String runId, String realmId, String citySeedId,
                               String nextAction, JsonObject state, Path runDirectory,
                               List<Path> initialImages, String semanticIdentity) {
        public PlanningStep {
            initialImages = List.copyOf(initialImages);
        }
    }
}
