package com.rinsing.geomantia.systems.realm_planning.application.terrain;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Builds an advisory, generation-prior terrain preview over one realm's T3 owned cells. */
public final class RealmT4CoarseTerrainPreviewService {
    public static final String SCHEMA_VERSION = "realm_t4_coarse_terrain_evidence.v0.1";
    public static final String REQUIRED_NEXT_GATE = "city_d3_site_review";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int[][] CARDINAL = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private final Path debugRoot;

    public RealmT4CoarseTerrainPreviewService(Path debugRoot) {
        this.debugRoot = Objects.requireNonNull(debugRoot, "debugRoot").toAbsolutePath().normalize();
    }

    public Result ensure(String runId, String realmId, String dimensionId,
            TerrainPreviewProviderSelection selection) throws IOException {
        String safeRunId = safeId(runId, "runId");
        String safeRealmId = safeId(realmId, "realmId");
        String normalizedDimension = requireText(dimensionId, "dimensionId");
        Objects.requireNonNull(selection, "selection");

        Path runDirectory = debugRoot.resolve(safeRunId).normalize();
        if (!runDirectory.startsWith(debugRoot)) {
            throw new IllegalArgumentException("runId resolves outside debugRoot.");
        }
        Path contextPath = runDirectory.resolve("world_survey_context.json");
        Path territoryPath = runDirectory.resolve("realm_territory_map.json");
        JsonObject context = readObject(contextPath, "T4_TERRAIN_W_CONTEXT_MISSING");
        JsonObject territory = readObject(territoryPath, "T4_TERRAIN_TERRITORY_MISSING");
        if (!booleanValue(context, "sealed", false)) {
            throw new IllegalArgumentException("T4_TERRAIN_W_NOT_SEALED");
        }
        String contextDimension = stringValue(context, "dimensionId", "");
        if (!contextDimension.isBlank() && !normalizedDimension.equals(contextDimension)) {
            throw new IllegalArgumentException("T4_TERRAIN_DIMENSION_MISMATCH: expected "
                    + contextDimension + " but got " + normalizedDimension);
        }
        int cellStepBlocks = intValue(context, "cellStepBlocks", 0);
        if (cellStepBlocks <= 0) {
            throw new IllegalArgumentException("T4_TERRAIN_CELL_STEP_INVALID");
        }

        List<GridCell> ownedCells = ownedCells(territory, safeRealmId);
        if (ownedCells.isEmpty()) {
            throw new IllegalArgumentException("T4_TERRAIN_REALM_HAS_NO_OWNED_TERRITORY: " + safeRealmId);
        }
        String territoryIdentity = fileIdentity(territoryPath);
        String contextIdentity = fileIdentity(contextPath);
        String cacheKey = sourceIdentity(safeRunId, safeRealmId, normalizedDimension, cellStepBlocks,
                territoryIdentity, contextIdentity, selection);

        Path artifactDirectory = runDirectory.resolve("realm_t4_terrain_preview");
        Path evidencePath = artifactDirectory.resolve(safeRealmId + "_coarse_terrain_evidence.json");
        Path previewPath = artifactDirectory.resolve(safeRealmId + "_coarse_height_water_preview.png");
        Result cached = readCached(evidencePath, previewPath, cacheKey);
        if (cached != null) {
            return cached;
        }

        Map<String, SampleCell> sampledByGrid = new LinkedHashMap<>();
        int sampleOffset = Math.floorDiv(cellStepBlocks, 2);
        for (GridCell grid : ownedCells) {
            int blockX = Math.addExact(Math.multiplyExact(grid.gridX(), cellStepBlocks), sampleOffset);
            int blockZ = Math.addExact(Math.multiplyExact(grid.gridZ(), cellStepBlocks), sampleOffset);
            TerrainPreviewSample sample = Objects.requireNonNull(selection.sample(blockX, blockZ),
                    "Terrain preview provider returned null.");
            if (sample.blockX() != blockX || sample.blockZ() != blockZ) {
                throw new IllegalArgumentException("T4_TERRAIN_PROVIDER_COORDINATE_MISMATCH: requested "
                        + blockX + "," + blockZ + " but got " + sample.blockX() + "," + sample.blockZ());
            }
            sampledByGrid.put(key(grid.gridX(), grid.gridZ()), new SampleCell(grid.gridX(), grid.gridZ(),
                    sample.blockX(), sample.blockZ(), sample.elevation(), sample.water(), sample.biomeId(),
                    sample.terrainId(), sample.sourceBiomeId(), 0, 0.0, 0.0, 0.0, 0.0));
        }
        sampledByGrid = withNeighborhoodMetrics(sampledByGrid, cellStepBlocks);

        JsonObject evidence = evidence(safeRunId, safeRealmId, normalizedDimension, territory, territoryIdentity,
                contextIdentity, cacheKey, cellStepBlocks, sampleOffset, selection, sampledByGrid, previewPath,
                runDirectory);
        Files.createDirectories(artifactDirectory);
        render(sampledByGrid.values(), previewPath);
        writeJson(evidencePath, evidence);
        return new Result(evidencePath, previewPath, evidence.deepCopy(), false);
    }

    private Result readCached(Path evidencePath, Path previewPath, String cacheKey) {
        if (!Files.isRegularFile(evidencePath) || !Files.isRegularFile(previewPath)) {
            return null;
        }
        try {
            if (Files.size(previewPath) <= 0L) {
                return null;
            }
            JsonObject evidence = readObject(evidencePath, "");
            if (!SCHEMA_VERSION.equals(stringValue(evidence, "schemaVersion", ""))
                    || !cacheKey.equals(stringValue(evidence, "sourceIdentity", ""))) {
                return null;
            }
            return new Result(evidencePath, previewPath, evidence.deepCopy(), true);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static List<GridCell> ownedCells(JsonObject territory, String realmId) {
        Map<String, GridCell> cells = new HashMap<>();
        for (JsonElement element : array(territory, "territoryCells")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject cell = element.getAsJsonObject();
            if (!realmId.equals(stringValue(cell, "realmId", ""))
                    || !"owned".equals(stringValue(cell, "status", ""))) {
                continue;
            }
            int gridX = intValue(cell, "gridX", 0);
            int gridZ = intValue(cell, "gridZ", 0);
            cells.put(key(gridX, gridZ), new GridCell(gridX, gridZ));
        }
        return cells.values().stream()
                .sorted(Comparator.comparingInt(GridCell::gridZ).thenComparingInt(GridCell::gridX))
                .toList();
    }

    private static Map<String, SampleCell> withNeighborhoodMetrics(Map<String, SampleCell> source, int step) {
        Map<String, SampleCell> result = new LinkedHashMap<>();
        for (SampleCell cell : source.values()) {
            int neighbors = 0;
            double deltaSum = 0.0;
            double deltaMax = 0.0;
            double minHeight = cell.elevation();
            double maxHeight = cell.elevation();
            for (int[] direction : CARDINAL) {
                SampleCell neighbor = source.get(key(cell.gridX() + direction[0], cell.gridZ() + direction[1]));
                if (neighbor == null) {
                    continue;
                }
                double delta = Math.abs(neighbor.elevation() - cell.elevation());
                neighbors++;
                deltaSum += delta;
                deltaMax = Math.max(deltaMax, delta);
                minHeight = Math.min(minHeight, neighbor.elevation());
                maxHeight = Math.max(maxHeight, neighbor.elevation());
            }
            result.put(key(cell.gridX(), cell.gridZ()), new SampleCell(cell.gridX(), cell.gridZ(), cell.blockX(),
                    cell.blockZ(), cell.elevation(), cell.water(), cell.biomeId(), cell.terrainId(),
                    cell.sourceBiomeId(), neighbors,
                    neighbors == 0 ? 0.0 : deltaSum / neighbors, deltaMax,
                    step == 0 ? 0.0 : deltaMax / step, maxHeight - minHeight));
        }
        return result;
    }

    private static JsonObject evidence(String runId, String realmId, String dimensionId, JsonObject territory,
            String territoryIdentity, String contextIdentity, String sourceIdentity, int step, int sampleOffset,
            TerrainPreviewProviderSelection selection, Map<String, SampleCell> cells, Path previewPath,
            Path runDirectory) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        root.addProperty("runId", runId);
        root.addProperty("realmId", realmId);
        root.addProperty("dimensionId", dimensionId);
        root.addProperty("territoryMapId", stringValue(territory, "territoryMapId", ""));
        root.addProperty("territoryIdentity", territoryIdentity);
        root.addProperty("worldSurveyContextIdentity", contextIdentity);
        root.addProperty("sourceIdentity", sourceIdentity);
        root.addProperty("generatedAt", Instant.now().toString());
        root.addProperty("advisoryOnly", true);
        root.addProperty("requiredNextGate", REQUIRED_NEXT_GATE);

        JsonObject provider = new JsonObject();
        provider.addProperty("providerId", selection.providerId());
        provider.addProperty("sourceKind", selection.sourceKind());
        provider.addProperty("fastPath", selection.fastPath());
        provider.addProperty("fallbackReason", selection.fallbackReason());
        provider.addProperty("sourceFingerprint", selection.sourceFingerprint());
        provider.addProperty("samplingSemantics", selection.samplingSemantics());
        root.add("provider", provider);

        Bounds bounds = bounds(cells.values());
        JsonObject grid = new JsonObject();
        grid.addProperty("cellStepBlocks", step);
        grid.addProperty("sampleOffsetBlocks", sampleOffset);
        grid.addProperty("samplingPattern", "owned_cell_center_once");
        grid.addProperty("ownedCellCount", cells.size());
        grid.addProperty("sampleCount", cells.size());
        grid.addProperty("minGridX", bounds.minX());
        grid.addProperty("minGridZ", bounds.minZ());
        grid.addProperty("maxGridX", bounds.maxX());
        grid.addProperty("maxGridZ", bounds.maxZ());
        root.add("grid", grid);
        root.add("summary", summary(cells.values()));

        JsonArray items = new JsonArray();
        for (SampleCell cell : cells.values()) {
            items.add(cell.asJson());
        }
        root.add("cells", items);
        JsonObject artifacts = new JsonObject();
        artifacts.addProperty("heightWaterPreview", normalizeSeparators(runDirectory.relativize(previewPath).toString()));
        root.add("artifacts", artifacts);
        return root;
    }

    private static JsonObject summary(Iterable<SampleCell> cells) {
        List<Double> heights = new ArrayList<>();
        List<Double> maxDeltas = new ArrayList<>();
        List<Double> slopeProxies = new ArrayList<>();
        Map<String, Integer> biomeHistogram = new HashMap<>();
        Map<String, Integer> terrainHistogram = new HashMap<>();
        Map<String, Integer> sourceBiomeHistogram = new HashMap<>();
        int water = 0;
        double deltaSum = 0.0;
        int deltaCount = 0;
        for (SampleCell cell : cells) {
            heights.add(cell.elevation());
            if (cell.neighborCount() > 0) {
                maxDeltas.add(cell.neighborElevationDeltaMax());
                slopeProxies.add(cell.slopeProxy());
                deltaSum += cell.neighborElevationDeltaMean();
                deltaCount++;
            }
            if (cell.water()) {
                water++;
            }
            biomeHistogram.merge(cell.biomeId(), 1, Integer::sum);
            terrainHistogram.merge(cell.terrainId(), 1, Integer::sum);
            sourceBiomeHistogram.merge(cell.sourceBiomeId(), 1, Integer::sum);
        }
        heights.sort(Double::compareTo);
        maxDeltas.sort(Double::compareTo);
        slopeProxies.sort(Double::compareTo);
        JsonObject result = new JsonObject();
        result.addProperty("sampleCount", heights.size());
        result.addProperty("heightMin", heights.get(0));
        result.addProperty("heightP10", percentile(heights, 0.10));
        result.addProperty("heightP50", percentile(heights, 0.50));
        result.addProperty("heightP90", percentile(heights, 0.90));
        result.addProperty("heightMax", heights.get(heights.size() - 1));
        result.addProperty("robustRelief", percentile(heights, 0.90) - percentile(heights, 0.10));
        result.addProperty("waterFrac", water / (double) heights.size());
        result.addProperty("neighborElevationDeltaMean", deltaCount == 0 ? 0.0 : deltaSum / deltaCount);
        result.addProperty("neighborElevationDeltaP90", percentile(maxDeltas, 0.90));
        result.addProperty("slopeProxyP90", percentile(slopeProxies, 0.90));
        JsonObject biomes = new JsonObject();
        biomeHistogram.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
                .forEach(entry -> biomes.addProperty(entry.getKey(), entry.getValue()));
        result.add("biomeHistogram", biomes);
        result.add("terrainIdHistogram", histogram(terrainHistogram));
        result.add("sourceBiomeIdHistogram", histogram(sourceBiomeHistogram));
        return result;
    }

    private static JsonObject histogram(Map<String, Integer> counts) {
        JsonObject result = new JsonObject();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
                .forEach(entry -> result.addProperty(entry.getKey(), entry.getValue()));
        return result;
    }

    private static double percentile(List<Double> sorted, double fraction) {
        if (sorted.isEmpty()) {
            return 0.0;
        }
        int index = (int) Math.round((sorted.size() - 1) * fraction);
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    private static void render(Iterable<SampleCell> cells, Path output) throws IOException {
        List<SampleCell> samples = new ArrayList<>();
        cells.forEach(samples::add);
        Bounds bounds = bounds(samples);
        int widthCells = bounds.maxX() - bounds.minX() + 1;
        int heightCells = bounds.maxZ() - bounds.minZ() + 1;
        int scale = Math.max(1, Math.min(8, 1024 / Math.max(widthCells, heightCells)));
        BufferedImage image = new BufferedImage(Math.max(1, widthCells * scale),
                Math.max(1, heightCells * scale), BufferedImage.TYPE_INT_ARGB);
        List<Double> heights = samples.stream().map(SampleCell::elevation).sorted().toList();
        double low = percentile(heights, 0.10);
        double high = percentile(heights, 0.90);
        double range = Math.max(1.0, high - low);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(20, 22, 24, 255));
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            for (SampleCell cell : samples) {
                if (cell.water()) {
                    graphics.setColor(new Color(46, 126, 184));
                } else {
                    double normalized = Math.max(0.0, Math.min(1.0, (cell.elevation() - low) / range));
                    int shade = 42 + (int) Math.round(normalized * 198.0);
                    graphics.setColor(new Color(shade, shade, shade));
                }
                graphics.fillRect((cell.gridX() - bounds.minX()) * scale,
                        (cell.gridZ() - bounds.minZ()) * scale, scale, scale);
            }
        } finally {
            graphics.dispose();
        }
        Files.createDirectories(output.getParent());
        if (!ImageIO.write(image, "png", output.toFile())) {
            throw new IOException("No PNG writer is available.");
        }
    }

    private static Bounds bounds(Iterable<? extends GridPosition> cells) {
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (GridPosition cell : cells) {
            minX = Math.min(minX, cell.gridX());
            minZ = Math.min(minZ, cell.gridZ());
            maxX = Math.max(maxX, cell.gridX());
            maxZ = Math.max(maxZ, cell.gridZ());
        }
        if (minX == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("At least one owned cell is required.");
        }
        return new Bounds(minX, minZ, maxX, maxZ);
    }

    private static String sourceIdentity(String runId, String realmId, String dimensionId, int step,
            String territoryIdentity, String contextIdentity, TerrainPreviewProviderSelection selection) {
        return "sha256:" + sha256(String.join("\n", SCHEMA_VERSION, runId, realmId, dimensionId,
                Integer.toString(step), territoryIdentity, contextIdentity, selection.providerId(),
                selection.sourceKind(), Boolean.toString(selection.fastPath()), selection.fallbackReason(),
                selection.sourceFingerprint(), selection.samplingSemantics()));
    }

    private static String fileIdentity(Path path) throws IOException {
        return "sha256:" + sha256(Files.readAllBytes(path));
    }

    private static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder result = new StringBuilder();
            for (byte item : digest) {
                result.append(String.format(Locale.ROOT, "%02x", item));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable.", ex);
        }
    }

    private static JsonObject readObject(Path path, String missingCode) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException((missingCode.isBlank() ? "T4_TERRAIN_ARTIFACT_MISSING" : missingCode)
                    + ": " + path);
        }
        JsonElement parsed = JsonParser.parseString(Files.readString(path));
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("T4_TERRAIN_ARTIFACT_INVALID: " + path);
        }
        return parsed.getAsJsonObject();
    }

    private static void writeJson(Path path, JsonElement value) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, GSON.toJson(value), StandardCharsets.UTF_8);
    }

    private static JsonArray array(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonArray() ? object.getAsJsonArray(key) : new JsonArray();
    }

    private static boolean booleanValue(JsonObject object, String key, boolean fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsBoolean() : fallback;
    }

    private static int intValue(JsonObject object, String key, int fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsInt() : fallback;
    }

    private static String stringValue(JsonObject object, String key, String fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : fallback;
    }

    private static String safeId(String value, String field) {
        String safe = requireText(value, field);
        if (!safe.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException(field + " contains unsupported characters.");
        }
        return safe;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return value.trim();
    }

    private static String key(int gridX, int gridZ) {
        return gridX + "," + gridZ;
    }

    private static String normalizeSeparators(String value) {
        return value.replace('\\', '/');
    }

    public record Result(Path evidencePath, Path previewPath, JsonObject evidence, boolean cacheHit) {
        public Result {
            evidencePath = Objects.requireNonNull(evidencePath, "evidencePath");
            previewPath = Objects.requireNonNull(previewPath, "previewPath");
            evidence = Objects.requireNonNull(evidence, "evidence").deepCopy();
        }
    }

    private interface GridPosition {
        int gridX();

        int gridZ();
    }

    private record GridCell(int gridX, int gridZ) implements GridPosition {
    }

    private record SampleCell(int gridX, int gridZ, int blockX, int blockZ, double elevation, boolean water,
                              String biomeId, String terrainId, String sourceBiomeId,
                              int neighborCount, double neighborElevationDeltaMean,
                              double neighborElevationDeltaMax, double slopeProxy, double localRelief)
            implements GridPosition {
        JsonObject asJson() {
            JsonObject result = new JsonObject();
            result.addProperty("gridX", gridX);
            result.addProperty("gridZ", gridZ);
            result.addProperty("blockX", blockX);
            result.addProperty("blockZ", blockZ);
            result.addProperty("elevation", elevation);
            result.addProperty("water", water);
            result.addProperty("biomeId", biomeId);
            result.addProperty("terrainId", terrainId);
            result.addProperty("sourceBiomeId", sourceBiomeId);
            result.addProperty("neighborCount", neighborCount);
            result.addProperty("neighborElevationDeltaMean", neighborElevationDeltaMean);
            result.addProperty("neighborElevationDeltaMax", neighborElevationDeltaMax);
            result.addProperty("slopeProxy", slopeProxy);
            result.addProperty("localRelief", localRelief);
            return result;
        }
    }

    private record Bounds(int minX, int minZ, int maxX, int maxZ) {
    }
}
