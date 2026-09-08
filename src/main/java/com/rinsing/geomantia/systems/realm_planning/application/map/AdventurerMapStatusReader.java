package com.rinsing.geomantia.systems.realm_planning.application.map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.realm_planning.application.map.AdventurerMapSnapshot.CityNode;
import com.rinsing.geomantia.systems.realm_planning.application.map.AdventurerMapSnapshot.CoarseMap;
import com.rinsing.geomantia.systems.realm_planning.application.access.PlanningAreaAccessConfig;
import com.rinsing.geomantia.systems.realm_planning.application.access.PlanningAreaAccessPolicy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** Builds a read-only map snapshot from the current save's persisted planning artifacts. */
public final class AdventurerMapStatusReader {
    private static final int MAX_MAP_SIDE = 96;
    private static Path cachedMapRun;
    private static String cachedMapFingerprint = "";
    private static CoarseMap cachedMap = CoarseMap.empty();

    private AdventurerMapStatusReader() {
    }

    public static AdventurerMapSnapshot read(Path debugRoot, String preferredRunId) throws IOException {
        return read(debugRoot, preferredRunId, 2048);
    }

    public static AdventurerMapSnapshot read(Path debugRoot, String preferredRunId,
                                              int initialActivityRadiusBlocks) throws IOException {
        PlanningAreaAccessConfig accessConfig = new PlanningAreaAccessConfig(true, initialActivityRadiusBlocks,
                PlanningAreaAccessConfig.DEFAULT_FIRST_CITY_DISTANCE_BLOCKS, Set.of("minecraft:overworld"));
        return read(debugRoot, preferredRunId, accessConfig);
    }

    public static AdventurerMapSnapshot read(Path debugRoot, String preferredRunId,
                                              PlanningAreaAccessConfig accessConfig) throws IOException {
        return read(debugRoot, preferredRunId, accessConfig, MapViewport.full());
    }

    public static AdventurerMapSnapshot read(Path debugRoot, String preferredRunId,
                                              PlanningAreaAccessConfig accessConfig,
                                              MapViewport viewport) throws IOException {
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
        CoarseMap coarseMap = coarseMap(root, run, accessConfig,
                viewport == null ? MapViewport.full() : viewport);
        List<CityNode> nodes = visibleCityNodes(
                cityNodes(run.resolve("city_seed_registry.json"), queueItems, currentCityId), coarseMap,
                viewport == null ? MapViewport.full() : viewport);

        return new AdventurerMapSnapshot(runId, wStatus, wPhase, wProgress,
                stageState.stage(), stageState.status(), currentRealmId, currentRealmName,
                currentCityId, cityStatus, completedCount, remainingCount,
                accessConfig.initialActivityRadiusBlocks(), coarseMap, nodes);
    }

    private static synchronized CoarseMap coarseMap(Path debugRoot, Path run,
                                                    PlanningAreaAccessConfig accessConfig,
                                                    MapViewport viewport) throws IOException {
        Path featurePath = run.resolve("world_feature_grid.json");
        Path territoryPath = run.resolve("realm_territory_map.json");
        Path contextPath = run.resolve("world_survey_context.json");
        String fingerprint = fileFingerprint(featurePath) + '|' + fileFingerprint(territoryPath)
                + '|' + fileFingerprint(contextPath) + '|' + PlanningAreaAccessPolicy.sourceStamp(debugRoot)
                + '|' + accessConfig + '|' + viewport;
        if (run.equals(cachedMapRun) && fingerprint.equals(cachedMapFingerprint)) {
            return cachedMap;
        }

        JsonObject context = readObjectIfPresent(contextPath);
        String dimensionId = stringValue(context, "dimensionId", "minecraft:overworld");
        JsonObject featureGrid = readObjectIfPresent(featurePath);
        if (featureGrid == null || !featureGrid.has("cells") || !featureGrid.get("cells").isJsonArray()) {
            return cacheMap(run, fingerprint, new CoarseMap(dimensionId, 0, 0, 1,
                    0, 0, new byte[0], new byte[0], new byte[0], List.of()));
        }

        JsonArray cells = featureGrid.getAsJsonArray("cells");
        if (cells.isEmpty()) {
            return cacheMap(run, fingerprint, new CoarseMap(dimensionId, 0, 0, 1,
                    0, 0, new byte[0], new byte[0], new byte[0], List.of()));
        }

        int minGridX = Integer.MAX_VALUE;
        int minGridZ = Integer.MAX_VALUE;
        int maxGridX = Integer.MIN_VALUE;
        int maxGridZ = Integer.MIN_VALUE;
        for (JsonElement element : cells) {
            if (!element.isJsonObject()) continue;
            JsonObject cell = element.getAsJsonObject();
            int gridX = intValue(cell, "gridX", 0);
            int gridZ = intValue(cell, "gridZ", 0);
            minGridX = Math.min(minGridX, gridX);
            minGridZ = Math.min(minGridZ, gridZ);
            maxGridX = Math.max(maxGridX, gridX);
            maxGridZ = Math.max(maxGridZ, gridZ);
        }
        if (minGridX == Integer.MAX_VALUE) {
            return cacheMap(run, fingerprint, CoarseMap.empty());
        }

        int sourceCellSize = Math.max(1, intValue(featureGrid, "cellStepBlocks",
                intValue(context, "cellStepBlocks", 128)));
        if (viewport.bounded()) {
            minGridX = Math.max(minGridX, Math.floorDiv(viewport.centerBlockX() - viewport.radiusBlocks(),
                    sourceCellSize));
            minGridZ = Math.max(minGridZ, Math.floorDiv(viewport.centerBlockZ() - viewport.radiusBlocks(),
                    sourceCellSize));
            maxGridX = Math.min(maxGridX, Math.floorDiv(viewport.centerBlockX() + viewport.radiusBlocks() - 1,
                    sourceCellSize));
            maxGridZ = Math.min(maxGridZ, Math.floorDiv(viewport.centerBlockZ() + viewport.radiusBlocks() - 1,
                    sourceCellSize));
            if (minGridX > maxGridX || minGridZ > maxGridZ) {
                return cacheMap(run, fingerprint, new CoarseMap(dimensionId, 0, 0, 1,
                        0, 0, new byte[0], new byte[0], new byte[0], List.of()));
            }
        }

        int sourceWidth = maxGridX - minGridX + 1;
        int sourceHeight = maxGridZ - minGridZ + 1;
        int reduction = Math.max(1, (Math.max(sourceWidth, sourceHeight) + MAX_MAP_SIDE - 1) / MAX_MAP_SIDE);
        int width = (sourceWidth + reduction - 1) / reduction;
        int height = (sourceHeight + reduction - 1) / reduction;
        int outputCellSize = sourceCellSize * reduction;
        int pixelCount = width * height;
        int[][] terrainCounts = new int[pixelCount][12];
        byte[] terrainCodes = new byte[pixelCount];
        byte[] realmCodes = new byte[pixelCount];
        byte[] revealedCodes = new byte[pixelCount];

        TerritoryPalette territory = territoryPalette(territoryPath);
        for (JsonElement element : cells) {
            if (!element.isJsonObject()) continue;
            JsonObject cell = element.getAsJsonObject();
            int gridX = intValue(cell, "gridX", 0);
            int gridZ = intValue(cell, "gridZ", 0);
            if (gridX < minGridX || gridX > maxGridX || gridZ < minGridZ || gridZ > maxGridZ) continue;
            int column = (gridX - minGridX) / reduction;
            int row = (gridZ - minGridZ) / reduction;
            if (column < 0 || column >= width || row < 0 || row >= height) continue;
            int index = row * width + column;
            int terrainCode = terrainCode(cell);
            terrainCounts[index][terrainCode]++;
            String realmId = territory.cellRealms().get(cellKey(gridX, gridZ));
            Integer realmCode = realmId == null ? null : territory.realmCodes().get(realmId);
            if (realmCode != null && Byte.toUnsignedInt(realmCodes[index]) == 0) {
                realmCodes[index] = (byte) (int) realmCode;
            }
        }
        for (int index = 0; index < pixelCount; index++) {
            int selected = 0;
            for (int code = 1; code < terrainCounts[index].length; code++) {
                if (terrainCounts[index][code] > terrainCounts[index][selected]) selected = code;
            }
            terrainCodes[index] = (byte) selected;
        }

        PlanningAreaAccessPolicy accessPolicy = new PlanningAreaAccessPolicy(debugRoot, accessConfig);
        int minBlockX = minGridX * sourceCellSize;
        int minBlockZ = minGridZ * sourceCellSize;
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                double blockX = minBlockX + (column + 0.5D) * outputCellSize;
                double blockZ = minBlockZ + (row + 0.5D) * outputCellSize;
                if (accessPolicy.revealed(dimensionId, blockX, blockZ)) {
                    revealedCodes[row * width + column] = 1;
                }
            }
        }

        CoarseMap value = new CoarseMap(dimensionId, minBlockX, minBlockZ,
                outputCellSize, width, height, terrainCodes, realmCodes, revealedCodes, territory.realmIds());
        return cacheMap(run, fingerprint, value);
    }

    private static CoarseMap cacheMap(Path run, String fingerprint, CoarseMap value) {
        cachedMapRun = run;
        cachedMapFingerprint = fingerprint;
        cachedMap = value;
        return value;
    }

    private static TerritoryPalette territoryPalette(Path path) throws IOException {
        JsonObject territory = readObjectIfPresent(path);
        if (territory == null || !territory.has("territoryCells")
                || !territory.get("territoryCells").isJsonArray()) {
            return new TerritoryPalette(Map.of(), Map.of(), List.of());
        }
        Map<Long, String> cellRealms = new HashMap<>();
        TreeSet<String> sortedRealmIds = new TreeSet<>();
        for (JsonElement element : territory.getAsJsonArray("territoryCells")) {
            if (!element.isJsonObject()) continue;
            JsonObject cell = element.getAsJsonObject();
            String realmId = stringValue(cell, "realmId", "");
            if (realmId.isBlank()) continue;
            int gridX = intValue(cell, "gridX", 0);
            int gridZ = intValue(cell, "gridZ", 0);
            cellRealms.put(cellKey(gridX, gridZ), realmId);
            sortedRealmIds.add(realmId);
        }
        List<String> realmIds = new ArrayList<>(sortedRealmIds);
        if (realmIds.size() > 255) realmIds = new ArrayList<>(realmIds.subList(0, 255));
        Map<String, Integer> realmCodes = new LinkedHashMap<>();
        for (int index = 0; index < realmIds.size() && index < 255; index++) {
            realmCodes.put(realmIds.get(index), index + 1);
        }
        return new TerritoryPalette(Map.copyOf(cellRealms), Map.copyOf(realmCodes), List.copyOf(realmIds));
    }

    private static int terrainCode(JsonObject cell) {
        double waterFraction = doubleValue(cell, "waterFrac", 0.0D);
        String biome = dominantBiome(cell).toLowerCase(java.util.Locale.ROOT);
        if (waterFraction >= 0.5D || biome.contains("ocean") || biome.contains("river")) {
            return biome.contains("frozen") ? 2 : 1;
        }
        double height = doubleValue(cell, "heightP50", 64.0D);
        double relief = doubleValue(cell, "robustRelief", 0.0D);
        if (biome.contains("snow") || biome.contains("frozen") || biome.contains("ice")) return 9;
        if (biome.contains("badlands")) return 6;
        if (biome.contains("desert") || biome.contains("beach")) return 5;
        if (biome.contains("swamp") || biome.contains("mangrove")) return 8;
        if (height >= 105.0D || relief >= 42.0D || biome.contains("peak")
                || biome.contains("windswept") || biome.contains("mountain")) return 7;
        if (biome.contains("forest") || biome.contains("taiga") || biome.contains("jungle")) return 4;
        if (height >= 82.0D || relief >= 24.0D) return 10;
        return 3;
    }

    private static String dominantBiome(JsonObject cell) {
        if (cell == null || !cell.has("biomeHist") || !cell.get("biomeHist").isJsonObject()) return "";
        String selected = "";
        int count = -1;
        for (Map.Entry<String, JsonElement> entry : cell.getAsJsonObject("biomeHist").entrySet()) {
            int value = entry.getValue().getAsInt();
            if (value > count || value == count && entry.getKey().compareTo(selected) < 0) {
                selected = entry.getKey();
                count = value;
            }
        }
        return selected;
    }

    private static long cellKey(int gridX, int gridZ) {
        return ((long) gridX << 32) ^ (gridZ & 0xffffffffL);
    }

    private static String fileFingerprint(Path path) throws IOException {
        if (!Files.isRegularFile(path)) return "missing";
        return Files.size(path) + ":" + Files.getLastModifiedTime(path).toMillis();
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

    private static List<CityNode> visibleCityNodes(List<CityNode> nodes, CoarseMap map, MapViewport viewport) {
        if (!viewport.bounded()) return nodes;
        if (!map.available()) return List.of();
        return nodes.stream()
                .filter(node -> node.blockX() >= map.minBlockX() && node.blockX() < map.maxBlockX()
                        && node.blockZ() >= map.minBlockZ() && node.blockZ() < map.maxBlockZ())
                .toList();
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

    private record TerritoryPalette(Map<Long, String> cellRealms, Map<String, Integer> realmCodes,
                                    List<String> realmIds) {
    }

    public record MapViewport(int centerBlockX, int centerBlockZ, int radiusBlocks) {
        public MapViewport {
            radiusBlocks = Math.max(0, radiusBlocks);
        }

        public static MapViewport full() {
            return new MapViewport(0, 0, 0);
        }

        boolean bounded() {
            return radiusBlocks > 0;
        }
    }
}
