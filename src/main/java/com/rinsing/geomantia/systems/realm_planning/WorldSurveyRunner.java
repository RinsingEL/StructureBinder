package com.rinsing.geomantia.systems.realm_planning;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.gis.GisClassifierConfig;
import com.rinsing.geomantia.systems.gis.GisSampleConfig;
import com.rinsing.geomantia.systems.gis.adapter.snapshot.AtlasRegionSnapshotIo;
import com.rinsing.geomantia.systems.gis.application.refresh.GisRefreshService;
import com.rinsing.geomantia.systems.gis.application.refresh.RefreshJob;
import com.rinsing.geomantia.systems.gis.application.refresh.RefreshPriority;
import com.rinsing.geomantia.systems.gis.application.refresh.RefreshResult;
import com.rinsing.geomantia.systems.gis.application.refresh.SampleMode;
import com.rinsing.geomantia.systems.gis.application.sample.AtlasSampler;
import com.rinsing.geomantia.systems.gis.application.sample.SampledCell;
import com.rinsing.geomantia.systems.gis.domain.cell.AtlasCell;
import com.rinsing.geomantia.systems.gis.domain.landform.LandformPatch;
import com.rinsing.geomantia.systems.gis.domain.region.AtlasRegion;
import com.rinsing.geomantia.systems.gis.domain.region.AtlasRegionStore;
import com.rinsing.geomantia.systems.gis.preview.AtlasJson;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

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
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class WorldSurveyRunner {
    public static final int DEFAULT_CELL_STEP_BLOCKS = 128;
    public static final int DEFAULT_PLANNING_RADIUS_BLOCKS = 8192;
    public static final int DEFAULT_LOCAL_SLOPE_RADIUS_BLOCKS = 8;
    private static final String SCHEMA_VERSION = RealmPlanningService.SCHEMA_VERSION;
    private static final int TILE_REFRESH_RADIUS_CHUNKS = 24;
    private static final Logger LOGGER = LogUtils.getLogger();

    private final Path debugRoot;
    private final GisClassifierConfig classifierConfig;

    public WorldSurveyRunner(Path debugRoot, GisClassifierConfig classifierConfig) {
        this.debugRoot = debugRoot;
        this.classifierConfig = classifierConfig;
    }

    public WorldSurveyResult run(Config config, AtlasSampler sampler) throws IOException {
        return run(config, sampler, ProgressListener.NONE);
    }

    public WorldSurveyResult run(Config config, AtlasSampler sampler, ProgressListener progressListener)
            throws IOException {
        long startedAt = System.nanoTime();
        Config normalized = config.normalized();
        Path runDirectory = debugRoot.resolve(normalized.runId);
        Path tileDirectory = runDirectory.resolve("tiles");
        Files.createDirectories(tileDirectory);

        GisSampleConfig sampleConfig = GisSampleConfig.defaults().withCellStepBlocks(normalized.cellStepBlocks);
        AtlasRegionSnapshotIo snapshotIo = new AtlasRegionSnapshotIo();
        AtlasRegionStore store = new AtlasRegionStore(sampleConfig);
        GisRefreshService service = new GisRefreshService(sampleConfig, classifierConfig, store, sampler);
        SurveyBounds bounds = SurveyBounds.from(normalized, sampleConfig.regionSizeBlocks());
        List<TilePlan> tiles = planTiles(normalized.dimensionId, normalized.sampleMode, bounds, sampleConfig.regionSizeBlocks());
        ProgressTracker progress = new ProgressTracker(runDirectory, normalized, tiles.size(),
                (long) bounds.gridWidth * bounds.gridHeight,
                microSampleBudgetPerCell(normalized.cellStepBlocks, normalized.microSampleStrideBlocks),
                progressListener == null ? ProgressListener.NONE : progressListener);
        List<TileManifest> manifests = new ArrayList<>();
        List<AtlasRegion> regions = new ArrayList<>();
        List<LandformPatch> patches = new ArrayList<>();
        int scanned = 0;
        int cached = 0;
        int failed = 0;
        String configHash = normalized.configHash(bounds);
        try {
            progress.start();
            for (TilePlan tile : tiles) {
                Path snapshotPath = tileDirectory.resolve(tile.cacheFileName());
                TileManifest tileManifest;
                long tileStartedAt = System.nanoTime();
                progress.beginTile(tile);
                if (normalized.resumePolicy.useCache() && Files.exists(snapshotPath)) {
                    try {
                        if (!cacheMatches(snapshotPath, configHash)) {
                            throw new IOException("Tile cache config hash mismatch.");
                        }
                        AtlasRegion cachedRegion = snapshotIo.read(snapshotPath, sampleConfig);
                        regions.add(cachedRegion);
                        patches.addAll(cachedRegion.patches());
                        cached++;
                        tileManifest = TileManifest.cached(tile, snapshotPath, configHash);
                    } catch (Exception ex) {
                        if (!normalized.resumePolicy.rescanCorruptCache()) {
                            failed++;
                            tileManifest = TileManifest.failed(tile, snapshotPath, ex.getMessage(), configHash);
                            manifests.add(tileManifest);
                            progress.finishTile(tile, "failed", ex.getMessage());
                            continue;
                        }
                        tileManifest = scanTile(service, snapshotIo, tile, snapshotPath, sampleConfig, configHash);
                        regions.add(tileManifest.region);
                        patches.addAll(tileManifest.region.patches());
                        scanned++;
                    }
                } else {
                    tileManifest = scanTile(service, snapshotIo, tile, snapshotPath, sampleConfig, configHash);
                    regions.add(tileManifest.region);
                    patches.addAll(tileManifest.region.patches());
                    scanned++;
                }
                long tileDurationMs = Math.max(0L, (System.nanoTime() - tileStartedAt) / 1_000_000L);
                manifests.add(tileManifest.withDuration(tileDurationMs).withoutRegion());
                progress.finishTile(tile, tileManifest.status, tileDurationMs + "ms");
            }

            regions.sort(Comparator.comparingInt(AtlasRegion::regionZ).thenComparingInt(AtlasRegion::regionX));
            patches.sort(Comparator.comparing(LandformPatch::patchId));
            Path featureGridPath = runDirectory.resolve("world_feature_grid.json");
            progress.beginMicroSampling();
            MicroSamplingResult micro = loadOrBuildFeatureGrid(normalized, sampler, regions, featureGridPath, configHash,
                    progress);
            long durationMs = Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
            boolean sealed = failed == 0 && regions.size() == tiles.size();
            long bytes = directorySize(runDirectory);
            Path manifestPath = runDirectory.resolve("world_survey_manifest.json");
            WorldSurveyResult result = new WorldSurveyResult(
                    normalized.runId,
                    "survey_" + normalized.runId,
                    normalized.dimensionId,
                    normalized.worldSeed,
                    normalized.worldBorderSizeBlocks,
                    normalized.centerBlockX,
                    normalized.centerBlockZ,
                    normalized.planningRadiusBlocks,
                    bounds.minBlockX,
                    bounds.minBlockZ,
                    bounds.maxBlockX,
                    bounds.maxBlockZ,
                    normalized.cellStepBlocks,
                    normalized.microSampleStrideBlocks,
                    normalized.localSlopeRadiusBlocks,
                    micro.implemented,
                    micro.sampleCount,
                    configHash,
                    bounds.minGridX * normalized.cellStepBlocks,
                    bounds.minGridZ * normalized.cellStepBlocks,
                    bounds.gridWidth,
                    bounds.gridHeight,
                    normalized.sampleMode,
                    regions,
                    patches,
                    micro.features,
                    runDirectory,
                    manifestPath,
                    durationMs,
                    tiles.size(),
                    scanned,
                    cached,
                    failed,
                    bytes,
                    sealed
            );
            writeManifest(result, manifests, normalized, bounds, manifestPath);
            if (!sealed) {
                throw new IOException("World survey did not seal: failedTileCount=" + failed);
            }
            progress.complete(durationMs);
            return result;
        } catch (IOException ex) {
            progress.fail(ex.getMessage());
            throw ex;
        } catch (RuntimeException ex) {
            progress.fail(ex.getMessage());
            throw ex;
        } finally {
            progress.close();
        }
    }

    public WorldSurveyResult loadSealedResult(String runId) throws IOException {
        String normalizedRunId = safeId(Objects.requireNonNull(runId, "runId").trim());
        if (normalizedRunId.isBlank()) {
            throw new IllegalArgumentException("runId is required.");
        }
        Path runDirectory = debugRoot.resolve(normalizedRunId);
        Path manifestPath = runDirectory.resolve("world_survey_manifest.json");
        if (!Files.exists(manifestPath)) {
            throw new IllegalArgumentException("World survey manifest is missing for runId: " + runId);
        }

        JsonObject manifest = JsonParser.parseString(Files.readString(manifestPath)).getAsJsonObject();
        if (!"sealed".equals(stringValue(manifest, "status", ""))) {
            throw new IllegalArgumentException("World survey must be sealed before Tag Audit can be restored: " + runId);
        }
        JsonObject configJson = objectValue(manifest, "config");
        JsonObject boundsJson = objectValue(manifest, "scanBounds");
        JsonObject gridJson = objectValue(manifest, "grid");
        JsonObject statsJson = objectValue(manifest, "stats");
        String configHash = stringValue(manifest, "configHash", stringValue(statsJson, "configHash", ""));
        GisSampleConfig sampleConfig = GisSampleConfig.defaults().withCellStepBlocks(intValue(configJson, "cellStepBlocks", DEFAULT_CELL_STEP_BLOCKS));
        AtlasRegionSnapshotIo snapshotIo = new AtlasRegionSnapshotIo();

        List<AtlasRegion> regions = new ArrayList<>();
        List<LandformPatch> patches = new ArrayList<>();
        JsonArray tiles = manifest.has("tiles") && manifest.get("tiles").isJsonArray()
                ? manifest.getAsJsonArray("tiles") : new JsonArray();
        for (var element : tiles) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject tile = element.getAsJsonObject();
            if (!"scanned".equals(stringValue(tile, "status", ""))
                    && !"cached".equals(stringValue(tile, "status", ""))) {
                continue;
            }
            String cachePath = stringValue(tile, "cachePath", "");
            if (cachePath.isBlank()) {
                throw new IllegalArgumentException("Tile cache path is missing for runId: " + runId);
            }
            Path snapshotPath = runDirectory.resolve(cachePath).normalize();
            if (!snapshotPath.startsWith(runDirectory.normalize()) || !Files.exists(snapshotPath)) {
                throw new IllegalArgumentException("Tile cache snapshot is missing: " + cachePath);
            }
            if (!configHash.isBlank() && !configHash.equals(stringValue(tile, "configHash", ""))) {
                throw new IllegalArgumentException("Tile cache config hash mismatch: " + cachePath);
            }
            AtlasRegion region = snapshotIo.read(snapshotPath, sampleConfig);
            regions.add(region);
            patches.addAll(region.patches());
        }
        if (regions.isEmpty()) {
            throw new IllegalArgumentException("No tile snapshots are available for runId: " + runId);
        }
        regions.sort(Comparator.comparingInt(AtlasRegion::regionZ).thenComparingInt(AtlasRegion::regionX));
        patches.sort(Comparator.comparing(LandformPatch::patchId));

        MicroSamplingResult micro = Files.exists(runDirectory.resolve("world_feature_grid.json"))
                ? readFeatureGrid(runDirectory.resolve("world_feature_grid.json"), configHash)
                : null;
        if (micro == null) {
            micro = new MicroSamplingResult(Map.of(), false, 0L);
        }

        SampleMode sampleMode = SampleMode.fromContractName(stringValue(configJson, "sampleMode", SampleMode.PRIOR.contractName()));
        return new WorldSurveyResult(
                stringValue(manifest, "runId", normalizedRunId),
                stringValue(manifest, "surveyId", "survey_" + normalizedRunId),
                stringValue(configJson, "dimensionId", "minecraft:overworld"),
                stringValue(configJson, "worldSeed", "unknown"),
                doubleValue(configJson, "worldBorderSizeBlocks", 0.0),
                intValue(configJson, "centerBlockX", 0),
                intValue(configJson, "centerBlockZ", 0),
                intValue(configJson, "planningRadiusBlocks", DEFAULT_PLANNING_RADIUS_BLOCKS),
                intValue(boundsJson, "minBlockX", 0),
                intValue(boundsJson, "minBlockZ", 0),
                intValue(boundsJson, "maxBlockX", 0),
                intValue(boundsJson, "maxBlockZ", 0),
                intValue(configJson, "cellStepBlocks", DEFAULT_CELL_STEP_BLOCKS),
                intValue(configJson, "microSampleStrideBlocks", RealmPlanningService.DEFAULT_MICRO_SAMPLE_STRIDE_BLOCKS),
                intValue(configJson, "localSlopeRadiusBlocks", DEFAULT_LOCAL_SLOPE_RADIUS_BLOCKS),
                micro.implemented,
                micro.sampleCount,
                configHash,
                intValue(gridJson, "originBlockX", 0),
                intValue(gridJson, "originBlockZ", 0),
                intValue(gridJson, "width", 0),
                intValue(gridJson, "height", 0),
                sampleMode,
                regions,
                patches,
                micro.features,
                runDirectory,
                manifestPath,
                longValue(statsJson, "durationMs", longValue(manifest, "durationMs", 0L)),
                intValue(statsJson, "tileCount", tiles.size()),
                intValue(statsJson, "scannedTileCount", 0),
                intValue(statsJson, "cachedTileCount", 0),
                intValue(statsJson, "failedTileCount", 0),
                longValue(statsJson, "artifactBytes", directorySize(runDirectory)),
                true
        );
    }

    private TileManifest scanTile(GisRefreshService service, AtlasRegionSnapshotIo snapshotIo, TilePlan tile,
            Path snapshotPath, GisSampleConfig sampleConfig, String configHash) throws IOException {
        int regionSizeBlocks = sampleConfig.regionSizeBlocks();
        int centerX = tile.regionX * regionSizeBlocks + regionSizeBlocks / 2;
        int centerZ = tile.regionZ * regionSizeBlocks + regionSizeBlocks / 2;
        RefreshJob job = new RefreshJob("w_tile_" + tile.regionX + "_" + tile.regionZ,
                tile.dimensionId, centerX, centerZ, TILE_REFRESH_RADIUS_CHUNKS,
                sampleConfig.dependencyMarginCells(), sampleConfig.cellStepBlocks(), tile.sampleMode,
                RefreshPriority.DEBUG, sampleConfig.budgetCellsPerBatch());
        AtlasRegion region = new AtlasRegion(tile.dimensionId, tile.regionX, tile.regionZ, sampleConfig);
        RefreshResult result = service.run(job, region, snapshotPath.getParent().resolve("debug_" + tile.regionX + "_" + tile.regionZ));
        snapshotIo.write(result.region(), snapshotPath);
        writeCacheMeta(snapshotPath, configHash);
        return TileManifest.scanned(tile, snapshotPath, result.region(), configHash);
    }

    private static MicroSamplingResult buildFeatureGrid(Config config, AtlasSampler sampler, List<AtlasRegion> regions,
            ProgressTracker progress) {
        if (config.microSampleStrideBlocks <= 0 || config.microSampleStrideBlocks >= config.cellStepBlocks) {
            return new MicroSamplingResult(Map.of(), false, 0L);
        }
        List<WorldFeatureCell> builtFeatures = regions.stream()
                .flatMap(region -> region.cells().stream())
                .parallel()
                .map(coarseCell -> {
                    List<MicroSample> samples = sampleCoarseCell(config, sampler, coarseCell);
                    if (samples.isEmpty()) {
                        progress.microCellCompleted(0L);
                        return null;
                    }
                    WorldFeatureCell feature = aggregateFeature(coarseCell.globalCellX(), coarseCell.globalCellZ(), samples);
                    progress.microCellCompleted(feature.microSampleCount());
                    return feature;
                })
                .filter(feature -> feature != null)
                .sorted(Comparator.comparingInt(WorldFeatureCell::gridZ).thenComparingInt(WorldFeatureCell::gridX))
                .toList();
        Map<String, WorldFeatureCell> features = new LinkedHashMap<>();
        long sampleCount = 0L;
        for (WorldFeatureCell feature : builtFeatures) {
            features.put(key(feature.gridX(), feature.gridZ()), feature);
            sampleCount += feature.microSampleCount();
        }
        return new MicroSamplingResult(features, !features.isEmpty(), sampleCount);
    }

    private static MicroSamplingResult loadOrBuildFeatureGrid(Config config, AtlasSampler sampler, List<AtlasRegion> regions,
            Path featureGridPath, String configHash, ProgressTracker progress) throws IOException {
        if (Files.exists(featureGridPath)) {
            try {
                MicroSamplingResult cached = readFeatureGrid(featureGridPath, configHash);
                if (cached != null) {
                    progress.microCacheHit(cached.features.size(), cached.sampleCount);
                    return cached;
                }
            } catch (Exception ignored) {
                // Corrupt feature cache falls back to recompute; tile cache validity is handled separately.
            }
        }
        MicroSamplingResult result = buildFeatureGrid(config, sampler, regions, progress);
        writeFeatureGrid(featureGridPath, config, result, configHash);
        return result;
    }

    private static void writeFeatureGrid(Path path, Config config, MicroSamplingResult result, String configHash)
            throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", SCHEMA_VERSION);
        json.addProperty("surveyId", "survey_" + config.runId);
        json.addProperty("runId", config.runId);
        json.addProperty("configHash", configHash);
        json.addProperty("cellStepBlocks", config.cellStepBlocks);
        json.addProperty("microSampleStrideBlocks", config.microSampleStrideBlocks);
        json.addProperty("metricSampleStrideBlocks", config.microSampleStrideBlocks);
        json.addProperty("localSlopeRadiusBlocks", config.localSlopeRadiusBlocks);
        json.addProperty("microSamplingImplemented", result.implemented);
        json.addProperty("microSampleCount", result.sampleCount);
        JsonArray cells = new JsonArray();
        for (WorldFeatureCell feature : result.features.values()) {
            cells.add(featureJson(feature));
        }
        json.add("cells", cells);
        Files.writeString(path, AtlasJson.GSON.toJson(json));
    }

    private static MicroSamplingResult readFeatureGrid(Path path, String configHash) throws IOException {
        JsonObject json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        if (!configHash.equals(json.has("configHash") ? json.get("configHash").getAsString() : "")) {
            return null;
        }
        Map<String, WorldFeatureCell> features = new LinkedHashMap<>();
        long sampleCount = 0L;
        JsonArray cells = json.has("cells") && json.get("cells").isJsonArray()
                ? json.getAsJsonArray("cells") : new JsonArray();
        for (var element : cells) {
            if (!element.isJsonObject()) {
                continue;
            }
            WorldFeatureCell feature = featureFromJson(element.getAsJsonObject());
            features.put(key(feature.gridX(), feature.gridZ()), feature);
            sampleCount += feature.microSampleCount();
        }
        boolean implemented = json.has("microSamplingImplemented")
                && json.get("microSamplingImplemented").getAsBoolean()
                && !features.isEmpty();
        return new MicroSamplingResult(features, implemented, sampleCount);
    }

    private static JsonObject featureJson(WorldFeatureCell feature) {
        JsonObject json = new JsonObject();
        json.addProperty("gridX", feature.gridX());
        json.addProperty("gridZ", feature.gridZ());
        json.addProperty("heightP10", feature.heightP10());
        json.addProperty("heightP50", feature.heightP50());
        json.addProperty("heightP90", feature.heightP90());
        json.addProperty("robustRelief", feature.robustRelief());
        json.addProperty("slopeMean", feature.slopeMean());
        json.addProperty("slopeP90", feature.slopeP90());
        json.addProperty("steepFrac", feature.steepFrac());
        json.addProperty("waterFrac", feature.waterFrac());
        json.addProperty("microSampleCount", feature.microSampleCount());
        JsonObject biomeHist = new JsonObject();
        for (Map.Entry<String, Integer> entry : feature.biomeHistogram().entrySet()) {
            biomeHist.addProperty(entry.getKey(), entry.getValue());
        }
        json.add("biomeHist", biomeHist);
        return json;
    }

    private static WorldFeatureCell featureFromJson(JsonObject json) {
        Map<String, Integer> biomeHist = new LinkedHashMap<>();
        if (json.has("biomeHist") && json.get("biomeHist").isJsonObject()) {
            for (Map.Entry<String, com.google.gson.JsonElement> entry : json.getAsJsonObject("biomeHist").entrySet()) {
                biomeHist.put(entry.getKey(), entry.getValue().getAsInt());
            }
        }
        return new WorldFeatureCell(
                json.get("gridX").getAsInt(),
                json.get("gridZ").getAsInt(),
                json.get("heightP10").getAsDouble(),
                json.get("heightP50").getAsDouble(),
                json.get("heightP90").getAsDouble(),
                json.get("robustRelief").getAsDouble(),
                json.get("slopeMean").getAsDouble(),
                json.get("slopeP90").getAsDouble(),
                json.get("steepFrac").getAsDouble(),
                json.get("waterFrac").getAsDouble(),
                json.get("microSampleCount").getAsInt(),
                biomeHist);
    }

    private static List<MicroSample> sampleCoarseCell(Config config, AtlasSampler sampler, AtlasCell coarseCell) {
        List<MicroSample> samples = new ArrayList<>();
        int stride = Math.max(1, config.microSampleStrideBlocks);
        int maxSamplesPerAxis = Math.max(1, config.cellStepBlocks / stride);
        for (int zIndex = 0; zIndex < maxSamplesPerAxis; zIndex++) {
            for (int xIndex = 0; xIndex < maxSamplesPerAxis; xIndex++) {
                int blockX = coarseCell.blockMinX() + Math.min(config.cellStepBlocks - 1, xIndex * stride + stride / 2);
                int blockZ = coarseCell.blockMinZ() + Math.min(config.cellStepBlocks - 1, zIndex * stride + stride / 2);
                SampledCell sample = sampleAt(config, sampler, blockX, blockZ);
                double slope = localSlope(config, sampler, blockX, blockZ, sample.elevation());
                samples.add(new MicroSample(sample.elevation(), slope, sample.water(), sample.biomeId()));
            }
        }
        return samples;
    }

    private static SampledCell sampleAt(Config config, AtlasSampler sampler, int blockX, int blockZ) {
        AtlasCell micro = new AtlasCell("micro", Math.floorDiv(blockX, config.cellStepBlocks),
                Math.floorDiv(blockZ, config.cellStepBlocks), 0, 0, blockX, blockZ);
        return sampler.sampleFeature(micro, config.sampleMode);
    }

    private static double localSlope(Config config, AtlasSampler sampler, int blockX, int blockZ, double centerElevation) {
        int radius = Math.max(1, config.localSlopeRadiusBlocks);
        double east = sampleElevationAt(config, sampler, blockX + radius, blockZ);
        double west = sampleElevationAt(config, sampler, blockX - radius, blockZ);
        double south = sampleElevationAt(config, sampler, blockX, blockZ + radius);
        double north = sampleElevationAt(config, sampler, blockX, blockZ - radius);
        double maxDelta = Math.max(Math.max(Math.abs(east - centerElevation), Math.abs(west - centerElevation)),
                Math.max(Math.abs(south - centerElevation), Math.abs(north - centerElevation)));
        return maxDelta;
    }

    private static double sampleElevationAt(Config config, AtlasSampler sampler, int blockX, int blockZ) {
        AtlasCell micro = new AtlasCell("micro", Math.floorDiv(blockX, config.cellStepBlocks),
                Math.floorDiv(blockZ, config.cellStepBlocks), 0, 0, blockX, blockZ);
        return sampler.sampleElevation(micro, config.sampleMode);
    }

    private static WorldFeatureCell aggregateFeature(int gridX, int gridZ, List<MicroSample> samples) {
        List<Double> heights = samples.stream().map(MicroSample::height).sorted().toList();
        List<Double> slopes = samples.stream().map(MicroSample::slope).sorted().toList();
        int water = 0;
        Map<String, Integer> biomeHist = new HashMap<>();
        double slopeSum = 0.0;
        for (MicroSample sample : samples) {
            if (sample.water) {
                water++;
            }
            slopeSum += sample.slope;
            biomeHist.put(sample.biomeId, biomeHist.getOrDefault(sample.biomeId, 0) + 1);
        }
        double p10 = percentile(heights, 0.10);
        double p50 = percentile(heights, 0.50);
        double p90 = percentile(heights, 0.90);
        double slopeP90 = percentile(slopes, 0.90);
        long steep = slopes.stream().filter(value -> value >= 14.0).count();
        return new WorldFeatureCell(gridX, gridZ, p10, p50, p90, p90 - p10,
                slopeSum / Math.max(1, samples.size()), slopeP90, steep / (double) samples.size(),
                water / (double) samples.size(), samples.size(), biomeHist);
    }

    private static double percentile(List<Double> sorted, double fraction) {
        if (sorted.isEmpty()) {
            return 0.0;
        }
        int index = (int) Math.round((sorted.size() - 1) * fraction);
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    private static List<TilePlan> planTiles(String dimensionId, SampleMode sampleMode, SurveyBounds bounds,
            int regionSizeBlocks) {
        int minRegionX = Math.floorDiv(bounds.minBlockX, regionSizeBlocks);
        int maxRegionX = Math.floorDiv(bounds.maxBlockX, regionSizeBlocks);
        int minRegionZ = Math.floorDiv(bounds.minBlockZ, regionSizeBlocks);
        int maxRegionZ = Math.floorDiv(bounds.maxBlockZ, regionSizeBlocks);
        List<TilePlan> tiles = new ArrayList<>();
        for (int regionZ = minRegionZ; regionZ <= maxRegionZ; regionZ++) {
            for (int regionX = minRegionX; regionX <= maxRegionX; regionX++) {
                tiles.add(new TilePlan(dimensionId, regionX, regionZ, sampleMode));
            }
        }
        return tiles;
    }

    private static void writeManifest(WorldSurveyResult result, List<TileManifest> tiles, Config config,
            SurveyBounds bounds, Path path) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", SCHEMA_VERSION);
        json.addProperty("surveyId", result.surveyId());
        json.addProperty("runId", result.runId());
        json.addProperty("status", result.sealed() ? "sealed" : "failed");
        json.addProperty("createdAt", Instant.now().toString());
        json.addProperty("durationMs", result.durationMs());
        json.addProperty("configHash", result.configHash());
        json.add("config", config.asJson());
        JsonObject scanBounds = new JsonObject();
        scanBounds.addProperty("minBlockX", result.scanMinBlockX());
        scanBounds.addProperty("minBlockZ", result.scanMinBlockZ());
        scanBounds.addProperty("maxBlockX", result.scanMaxBlockX());
        scanBounds.addProperty("maxBlockZ", result.scanMaxBlockZ());
        scanBounds.addProperty("diameterBlocksX", result.scanMaxBlockX() - result.scanMinBlockX() + 1);
        scanBounds.addProperty("diameterBlocksZ", result.scanMaxBlockZ() - result.scanMinBlockZ() + 1);
        json.add("scanBounds", scanBounds);
        JsonObject grid = new JsonObject();
        grid.addProperty("originBlockX", result.gridOriginBlockX());
        grid.addProperty("originBlockZ", result.gridOriginBlockZ());
        grid.addProperty("width", result.gridSizeWidth());
        grid.addProperty("height", result.gridSizeHeight());
        grid.addProperty("cellCount", result.gridSizeWidth() * result.gridSizeHeight());
        json.add("grid", grid);
        JsonObject stats = new JsonObject();
        long microSampleBudget = microSampleBudget(config, bounds);
        stats.addProperty("durationMs", result.durationMs());
        stats.addProperty("tileCount", result.tileCount());
        stats.addProperty("scannedTileCount", result.scannedTileCount());
        stats.addProperty("cachedTileCount", result.cachedTileCount());
        stats.addProperty("failedTileCount", result.failedTileCount());
        stats.addProperty("artifactBytes", result.artifactBytes());
        stats.addProperty("microSamplingImplemented", result.microSamplingImplemented());
        stats.addProperty("microSampleBudget", microSampleBudget);
        stats.addProperty("microSampleBudgetPerCell", microSampleBudgetPerCell(config.cellStepBlocks, config.microSampleStrideBlocks));
        stats.addProperty("microSampleCount", result.microSampleCount());
        stats.addProperty("metricSampleStrideBlocks", result.microSampleStrideBlocks());
        stats.addProperty("localSlopeRadiusBlocks", result.localSlopeRadiusBlocks());
        stats.addProperty("adaptiveSampling", false);
        stats.addProperty("configHash", result.configHash());
        stats.addProperty("averageTileDurationMs", averageTileDurationMs(tiles));
        stats.addProperty("maxTileDurationMs", maxTileDurationMs(tiles));
        json.add("stats", stats);
        JsonArray tileArray = new JsonArray();
        for (TileManifest tile : tiles) {
            tileArray.add(tile.asJson(path.getParent()));
        }
        json.add("tiles", tileArray);
        Files.createDirectories(path.getParent());
        Files.writeString(path, AtlasJson.GSON.toJson(json));
    }

    private static void writeCacheMeta(Path snapshotPath, String configHash) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("configHash", configHash);
        json.addProperty("createdAt", Instant.now().toString());
        Files.writeString(cacheMetaPath(snapshotPath), AtlasJson.GSON.toJson(json));
    }

    private static boolean cacheMatches(Path snapshotPath, String configHash) throws IOException {
        Path metaPath = cacheMetaPath(snapshotPath);
        if (!Files.exists(metaPath)) {
            return false;
        }
        JsonObject json = JsonParser.parseString(Files.readString(metaPath)).getAsJsonObject();
        return configHash.equals(json.has("configHash") ? json.get("configHash").getAsString() : "");
    }

    private static Path cacheMetaPath(Path snapshotPath) {
        return snapshotPath.resolveSibling(snapshotPath.getFileName() + ".meta.json");
    }

    private static double averageTileDurationMs(List<TileManifest> tiles) {
        long count = tiles.stream().filter(tile -> tile.durationMs >= 0L).count();
        if (count == 0L) {
            return 0.0;
        }
        long total = tiles.stream()
                .filter(tile -> tile.durationMs >= 0L)
                .mapToLong(tile -> tile.durationMs)
                .sum();
        return total / (double) count;
    }

    private static long maxTileDurationMs(List<TileManifest> tiles) {
        return tiles.stream()
                .filter(tile -> tile.durationMs >= 0L)
                .mapToLong(tile -> tile.durationMs)
                .max()
                .orElse(0L);
    }

    private static long directorySize(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return 0L;
        }
        try (var stream = Files.walk(directory)) {
            return stream.filter(Files::isRegularFile)
                    .mapToLong(path -> {
                        try {
                            return Files.size(path);
                        } catch (IOException ex) {
                            return 0L;
                        }
                    })
                    .sum();
        }
    }

    public record Config(
            String runId,
            String dimensionId,
            String worldSeed,
            double worldBorderSizeBlocks,
            int centerBlockX,
            int centerBlockZ,
            int planningRadiusBlocks,
            int cellStepBlocks,
            int microSampleStrideBlocks,
            int localSlopeRadiusBlocks,
            SampleMode sampleMode,
            ResumePolicy resumePolicy
    ) {
        Config normalized() {
            String id = runId == null || runId.isBlank()
                    ? "realm_w_" + Long.toUnsignedString(System.currentTimeMillis(), 36)
                            + "_" + UUID.randomUUID().toString().substring(0, 8)
                    : runId.trim();
            String dimension = dimensionId == null || dimensionId.isBlank() ? "minecraft:overworld" : dimensionId.trim();
            int radius = planningRadiusBlocks > 0 ? planningRadiusBlocks : DEFAULT_PLANNING_RADIUS_BLOCKS;
            int step = cellStepBlocks > 0 ? cellStepBlocks : DEFAULT_CELL_STEP_BLOCKS;
            int microStride = microSampleStrideBlocks > 0 ? microSampleStrideBlocks
                    : RealmPlanningService.DEFAULT_MICRO_SAMPLE_STRIDE_BLOCKS;
            int slopeRadius = localSlopeRadiusBlocks > 0 ? localSlopeRadiusBlocks : DEFAULT_LOCAL_SLOPE_RADIUS_BLOCKS;
            SampleMode mode = sampleMode == null ? SampleMode.PRIOR : sampleMode;
            ResumePolicy resume = resumePolicy == null ? ResumePolicy.USE_CACHE : resumePolicy;
            return new Config(safeId(id), dimension, worldSeed == null || worldSeed.isBlank() ? "unknown" : worldSeed,
                    worldBorderSizeBlocks, centerBlockX, centerBlockZ, radius, step, microStride, slopeRadius, mode, resume);
        }

        JsonObject asJson() {
            JsonObject json = new JsonObject();
            json.addProperty("runId", runId);
            json.addProperty("dimensionId", dimensionId);
            json.addProperty("worldSeed", worldSeed);
            json.addProperty("worldBorderSizeBlocks", worldBorderSizeBlocks);
            json.addProperty("centerBlockX", centerBlockX);
            json.addProperty("centerBlockZ", centerBlockZ);
            json.addProperty("planningRadiusBlocks", planningRadiusBlocks);
            json.addProperty("cellStepBlocks", cellStepBlocks);
            json.addProperty("microSampleStrideBlocks", microSampleStrideBlocks);
            json.addProperty("localSlopeRadiusBlocks", localSlopeRadiusBlocks);
            json.addProperty("microSamplingImplemented", microSampleStrideBlocks > 0 && microSampleStrideBlocks < cellStepBlocks);
            json.addProperty("microSampleBudgetPerCell", microSampleBudgetPerCell(cellStepBlocks, microSampleStrideBlocks));
            json.addProperty("adaptiveSampling", false);
            json.addProperty("sampleMode", sampleMode.contractName());
            json.addProperty("resumePolicy", resumePolicy.contractName());
            return json;
        }

        String configHash(SurveyBounds bounds) {
            String raw = String.join("|",
                    dimensionId,
                    worldSeed,
                    Integer.toString(centerBlockX),
                    Integer.toString(centerBlockZ),
                    Integer.toString(planningRadiusBlocks),
                    Integer.toString(cellStepBlocks),
                    Integer.toString(microSampleStrideBlocks),
                    Integer.toString(localSlopeRadiusBlocks),
                    sampleMode.contractName(),
                    Integer.toString(bounds.minBlockX),
                    Integer.toString(bounds.minBlockZ),
                    Integer.toString(bounds.maxBlockX),
                    Integer.toString(bounds.maxBlockZ));
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] bytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
                StringBuilder builder = new StringBuilder();
                for (byte b : bytes) {
                    builder.append(String.format(Locale.ROOT, "%02x", b));
                }
                return builder.toString();
            } catch (NoSuchAlgorithmException ex) {
                throw new IllegalStateException("SHA-256 digest unavailable.", ex);
            }
        }
    }

    public record ProgressUpdate(
            String runId,
            String status,
            String phase,
            String detail,
            long elapsedMs,
            double phaseProgressPercent,
            long estimatedRemainingMs,
            int processedTiles,
            int totalTiles,
            long completedMicroCells,
            long totalMicroCells
    ) {
    }

    @FunctionalInterface
    public interface ProgressListener {
        ProgressListener NONE = update -> {
        };

        void onProgress(ProgressUpdate update);
    }

    public enum ResumePolicy {
        USE_CACHE("use_cache"),
        RESCAN("rescan"),
        USE_CACHE_STRICT("use_cache_strict");

        private final String contractName;

        ResumePolicy(String contractName) {
            this.contractName = contractName;
        }

        public String contractName() {
            return contractName;
        }

        boolean useCache() {
            return this != RESCAN;
        }

        boolean rescanCorruptCache() {
            return this == USE_CACHE;
        }

        public static ResumePolicy fromContractName(String value) {
            if (value == null || value.isBlank()) {
                return USE_CACHE;
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            for (ResumePolicy policy : values()) {
                if (policy.contractName.equals(normalized)) {
                    return policy;
                }
            }
            throw new IllegalArgumentException("resumePolicy must be one of: use_cache, rescan, use_cache_strict.");
        }
    }

    private record SurveyBounds(int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ,
            int minGridX, int minGridZ, int maxGridX, int maxGridZ, int gridWidth, int gridHeight) {
        static SurveyBounds from(Config config, int regionSizeBlocks) {
            int minX = config.centerBlockX - config.planningRadiusBlocks;
            int minZ = config.centerBlockZ - config.planningRadiusBlocks;
            int maxX = config.centerBlockX + config.planningRadiusBlocks - 1;
            int maxZ = config.centerBlockZ + config.planningRadiusBlocks - 1;
            int minRegionX = Math.floorDiv(minX, regionSizeBlocks);
            int minRegionZ = Math.floorDiv(minZ, regionSizeBlocks);
            int maxRegionX = Math.floorDiv(maxX, regionSizeBlocks);
            int maxRegionZ = Math.floorDiv(maxZ, regionSizeBlocks);
            int alignedMinX = minRegionX * regionSizeBlocks;
            int alignedMinZ = minRegionZ * regionSizeBlocks;
            int alignedMaxX = (maxRegionX + 1) * regionSizeBlocks - 1;
            int alignedMaxZ = (maxRegionZ + 1) * regionSizeBlocks - 1;
            int minGridX = Math.floorDiv(alignedMinX, config.cellStepBlocks);
            int minGridZ = Math.floorDiv(alignedMinZ, config.cellStepBlocks);
            int maxGridX = Math.floorDiv(alignedMaxX, config.cellStepBlocks);
            int maxGridZ = Math.floorDiv(alignedMaxZ, config.cellStepBlocks);
            return new SurveyBounds(alignedMinX, alignedMinZ, alignedMaxX, alignedMaxZ,
                    minGridX, minGridZ, maxGridX, maxGridZ,
                    maxGridX - minGridX + 1, maxGridZ - minGridZ + 1);
        }
    }

    private static long microSampleBudget(Config config, SurveyBounds bounds) {
        return (long) bounds.gridWidth * bounds.gridHeight
                * microSampleBudgetPerCell(config.cellStepBlocks, config.microSampleStrideBlocks);
    }

    private static int microSampleBudgetPerCell(int cellStepBlocks, int microSampleStrideBlocks) {
        if (cellStepBlocks <= 0 || microSampleStrideBlocks <= 0 || microSampleStrideBlocks >= cellStepBlocks) {
            return 0;
        }
        int samplesPerAxis = Math.max(1, cellStepBlocks / microSampleStrideBlocks);
        return samplesPerAxis * samplesPerAxis;
    }

    private static final class ProgressTracker {
        private final Path runDirectory;
        private final Config config;
        private final int totalTiles;
        private final long totalMicroCells;
        private final long totalMicroSamples;
        private final ProgressListener progressListener;
        private final long startedAtNanos = System.nanoTime();
        private final long startedAtEpochMs = System.currentTimeMillis();
        private final AtomicInteger processedTiles = new AtomicInteger();
        private final AtomicInteger scannedTiles = new AtomicInteger();
        private final AtomicInteger cachedTiles = new AtomicInteger();
        private final AtomicInteger failedTiles = new AtomicInteger();
        private final AtomicLong completedMicroCells = new AtomicLong();
        private final AtomicLong completedMicroSamples = new AtomicLong();
        private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "geomantia-world-survey-progress");
            thread.setDaemon(true);
            return thread;
        });
        private String status = "queued";
        private String phase = "queued";
        private String detail = "";
        private TilePlan currentTile;
        private long phaseStartedAtNanos = startedAtNanos;
        private boolean closed;

        private ProgressTracker(Path runDirectory, Config config, int totalTiles, long totalMicroCells,
                int microSampleBudgetPerCell, ProgressListener progressListener) {
            this.runDirectory = runDirectory;
            this.config = config;
            this.totalTiles = totalTiles;
            this.totalMicroCells = totalMicroCells;
            this.totalMicroSamples = totalMicroCells * Math.max(0, microSampleBudgetPerCell);
            this.progressListener = progressListener;
        }

        synchronized void start() {
            status = "running";
            phase = "tile_scan";
            phaseStartedAtNanos = System.nanoTime();
            publish();
            heartbeat.scheduleAtFixedRate(this::publish, 1L, 1L, TimeUnit.SECONDS);
        }

        synchronized void beginTile(TilePlan tile) {
            currentTile = tile;
            phase = "tile_scan";
            detail = "";
        }

        synchronized void finishTile(TilePlan tile, String tileStatus, String tileDetail) {
            currentTile = tile;
            detail = tileDetail == null ? "" : tileDetail;
            processedTiles.incrementAndGet();
            if ("scanned".equals(tileStatus)) {
                scannedTiles.incrementAndGet();
            } else if ("cached".equals(tileStatus)) {
                cachedTiles.incrementAndGet();
            } else if ("failed".equals(tileStatus)) {
                failedTiles.incrementAndGet();
            }
            publish();
        }

        synchronized void beginMicroSampling() {
            phase = "micro_sampling";
            phaseStartedAtNanos = System.nanoTime();
            currentTile = null;
            detail = "";
            publish();
        }

        void microCellCompleted(long sampleCount) {
            completedMicroCells.incrementAndGet();
            completedMicroSamples.addAndGet(Math.max(0L, sampleCount));
        }

        synchronized void microCacheHit(int featureCount, long sampleCount) {
            completedMicroCells.set(totalMicroCells);
            completedMicroSamples.set(Math.max(0L, sampleCount));
            detail = "feature_grid_cache_hit cells=" + featureCount;
            publish();
        }

        synchronized void complete(long durationMs) {
            status = "completed";
            phase = "complete";
            detail = "durationMs=" + durationMs;
            publish();
        }

        synchronized void fail(String error) {
            status = "failed";
            detail = error == null ? "" : error;
            publish();
        }

        synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            heartbeat.shutdownNow();
            publish();
        }

        private synchronized void publish() {
            if (closed && "running".equals(status)) {
                return;
            }
            long elapsedMs = Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000L);
            long phaseElapsedMs = Math.max(1L, (System.nanoTime() - phaseStartedAtNanos) / 1_000_000L);
            int processed = processedTiles.get();
            long microCells = completedMicroCells.get();
            double tileSpeed = perSecond(processed, elapsedMs);
            double microCellSpeed = perSecond(microCells, phaseElapsedMs);
            double microSampleSpeed = perSecond(completedMicroSamples.get(), phaseElapsedMs);
            long etaMs = estimateRemainingMs(processed, microCells, elapsedMs, phaseElapsedMs);
            double phasePercent = phasePercent(processed, microCells);
            JsonObject json = new JsonObject();
            json.addProperty("schemaVersion", "realm_world_survey_progress.v1");
            json.addProperty("runId", config.runId);
            json.addProperty("status", status);
            json.addProperty("phase", phase);
            json.addProperty("detail", detail);
            json.addProperty("startedAt", Instant.ofEpochMilli(startedAtEpochMs).toString());
            json.addProperty("updatedAt", Instant.now().toString());
            json.addProperty("elapsedMs", elapsedMs);
            json.addProperty("phaseElapsedMs", phaseElapsedMs);
            json.addProperty("phaseProgressPercent", phasePercent);
            json.addProperty("estimatedRemainingMs", etaMs);
            json.addProperty("tileSpeedPerSecond", tileSpeed);
            json.addProperty("microCellsPerSecond", microCellSpeed);
            json.addProperty("microSamplesPerSecond", microSampleSpeed);
            JsonObject tiles = new JsonObject();
            tiles.addProperty("processed", processed);
            tiles.addProperty("total", totalTiles);
            tiles.addProperty("scanned", scannedTiles.get());
            tiles.addProperty("cached", cachedTiles.get());
            tiles.addProperty("failed", failedTiles.get());
            json.add("tiles", tiles);
            JsonObject micro = new JsonObject();
            micro.addProperty("completedCells", microCells);
            micro.addProperty("totalCells", totalMicroCells);
            micro.addProperty("completedSamples", completedMicroSamples.get());
            micro.addProperty("totalSamples", totalMicroSamples);
            json.add("microSampling", micro);
            if (currentTile != null) {
                JsonObject tile = new JsonObject();
                tile.addProperty("regionX", currentTile.regionX);
                tile.addProperty("regionZ", currentTile.regionZ);
                tile.addProperty("sampleMode", currentTile.sampleMode.contractName());
                json.add("currentTile", tile);
            }
            try {
                Files.writeString(runDirectory.resolve("world_survey_progress.json"), AtlasJson.GSON.toJson(json));
            } catch (IOException ex) {
                LOGGER.warn("Could not write W survey progress for runId={}: {}", config.runId, ex.getMessage());
            }
            LOGGER.info("W survey progress runId={} status={} phase={} tiles={}/{} microCells={}/{} tileSpeed={}/s microSpeed={}/s etaMs={}",
                    config.runId, status, phase, processed, totalTiles, microCells, totalMicroCells,
                    formatRate(tileSpeed), formatRate(microCellSpeed), etaMs);
            try {
                progressListener.onProgress(new ProgressUpdate(config.runId, status, phase, detail, elapsedMs,
                        phasePercent, etaMs, processed, totalTiles, microCells, totalMicroCells));
            } catch (RuntimeException ex) {
                LOGGER.warn("W survey progress listener failed for runId={}: {}", config.runId, ex.getMessage());
            }
        }

        private double phasePercent(int processed, long microCells) {
            if ("micro_sampling".equals(phase)) {
                return percent(microCells, totalMicroCells);
            }
            if ("complete".equals(phase)) {
                return 100.0;
            }
            return percent(processed, totalTiles);
        }

        private long estimateRemainingMs(int processed, long microCells, long elapsedMs, long phaseElapsedMs) {
            if ("complete".equals(phase) || "failed".equals(status)) {
                return 0L;
            }
            if ("micro_sampling".equals(phase)) {
                if (microCells <= 0L || totalMicroCells <= microCells) {
                    return microCells >= totalMicroCells ? 0L : -1L;
                }
                return Math.max(0L, Math.round((totalMicroCells - microCells)
                        * (phaseElapsedMs / (double) microCells)));
            }
            if (processed <= 0 || processed >= totalTiles) {
                return processed >= totalTiles ? -1L : -1L;
            }
            return Math.max(0L, Math.round((totalTiles - processed) * (elapsedMs / (double) processed)));
        }

        private static double perSecond(long completed, long elapsedMs) {
            return elapsedMs <= 0L ? 0.0 : completed * 1000.0 / elapsedMs;
        }

        private static double percent(long completed, long total) {
            return total <= 0L ? 100.0 : Math.min(100.0, completed * 100.0 / total);
        }

        private static String formatRate(double value) {
            return String.format(Locale.ROOT, "%.2f", value);
        }
    }

    private record TilePlan(String dimensionId, int regionX, int regionZ, SampleMode sampleMode) {
        String cacheFileName() {
            return "region_" + regionX + "_" + regionZ + ".json";
        }
    }

    private static final class TileManifest {
        final TilePlan tile;
        final Path snapshotPath;
        final String status;
        final String error;
        final String configHash;
        final long durationMs;
        final AtlasRegion region;

        TileManifest(TilePlan tile, Path snapshotPath, String status, String error, String configHash, AtlasRegion region) {
            this(tile, snapshotPath, status, error, configHash, -1L, region);
        }

        TileManifest(TilePlan tile, Path snapshotPath, String status, String error, String configHash,
                long durationMs, AtlasRegion region) {
            this.tile = tile;
            this.snapshotPath = snapshotPath;
            this.status = status;
            this.error = error == null ? "" : error;
            this.configHash = configHash == null ? "" : configHash;
            this.durationMs = durationMs;
            this.region = region;
        }

        static TileManifest scanned(TilePlan tile, Path snapshotPath, AtlasRegion region, String configHash) {
            return new TileManifest(tile, snapshotPath, "scanned", "", configHash, region);
        }

        static TileManifest cached(TilePlan tile, Path snapshotPath, String configHash) {
            return new TileManifest(tile, snapshotPath, "cached", "", configHash, null);
        }

        static TileManifest failed(TilePlan tile, Path snapshotPath, String error, String configHash) {
            return new TileManifest(tile, snapshotPath, "failed", error, configHash, null);
        }

        TileManifest withoutRegion() {
            return new TileManifest(tile, snapshotPath, status, error, configHash, durationMs, null);
        }

        TileManifest withDuration(long durationMs) {
            return new TileManifest(tile, snapshotPath, status, error, configHash, durationMs, region);
        }

        JsonObject asJson(Path root) {
            JsonObject json = new JsonObject();
            json.addProperty("regionX", tile.regionX);
            json.addProperty("regionZ", tile.regionZ);
            json.addProperty("status", status);
            json.addProperty("sampleMode", tile.sampleMode.contractName());
            json.addProperty("cachePath", root.relativize(snapshotPath).toString().replace('\\', '/'));
            json.addProperty("configHash", configHash);
            if (durationMs >= 0L) {
                json.addProperty("durationMs", durationMs);
            }
            if (!error.isBlank()) {
                json.addProperty("error", error);
            }
            return json;
        }
    }

    private record MicroSample(double height, double slope, boolean water, String biomeId) {
    }

    private record MicroSamplingResult(Map<String, WorldFeatureCell> features, boolean implemented, long sampleCount) {
    }

    private static String safeId(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]+", "_");
    }

    private static JsonObject objectValue(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonObject()
                ? object.getAsJsonObject(key) : new JsonObject();
    }

    private static int intValue(JsonObject object, String key, int defaultValue) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return defaultValue;
        }
        return object.get(key).getAsInt();
    }

    private static long longValue(JsonObject object, String key, long defaultValue) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return defaultValue;
        }
        return object.get(key).getAsLong();
    }

    private static double doubleValue(JsonObject object, String key, double defaultValue) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return defaultValue;
        }
        return object.get(key).getAsDouble();
    }

    private static String stringValue(JsonObject object, String key, String defaultValue) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return defaultValue;
        }
        return object.get(key).getAsString();
    }

    private static String key(int gridX, int gridZ) {
        return gridX + "," + gridZ;
    }
}
