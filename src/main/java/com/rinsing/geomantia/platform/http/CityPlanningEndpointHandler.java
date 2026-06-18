package com.rinsing.geomantia.platform.http;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.application.CityFunctionZoneBuilder;
import com.rinsing.geomantia.systems.city.application.CityLandformReviewBuilder;
import com.rinsing.geomantia.systems.city.application.CitySiteContextBuilder;
import com.rinsing.geomantia.systems.city.application.CitySiteContextBuilder.TerritoryCellRef;
import com.rinsing.geomantia.systems.city.domain.config.CityPlanningConfig;
import com.rinsing.geomantia.systems.city.domain.model.CityLandformReviewPackage;
import com.rinsing.geomantia.systems.city.domain.model.CitySiteContext;
import com.rinsing.geomantia.systems.city.domain.model.PatchGroupPlan;
import com.rinsing.geomantia.systems.city.infrastructure.json.CityJson;
import com.rinsing.geomantia.systems.city.infrastructure.preview.CityLandformReviewMapRenderer;
import com.rinsing.geomantia.systems.city.infrastructure.preview.FunctionZonePreviewRenderer;
import com.rinsing.geomantia.systems.gis.GisClassifierConfig;
import com.rinsing.geomantia.systems.gis.GisSampleConfig;
import com.rinsing.geomantia.systems.gis.adapter.minecraft.MinecraftPriorAtlasSampler;
import com.rinsing.geomantia.systems.gis.application.refresh.GisRefreshService;
import com.rinsing.geomantia.systems.gis.application.refresh.RefreshResult;
import com.rinsing.geomantia.systems.gis.domain.landform.LandformPatch;
import com.rinsing.geomantia.systems.gis.domain.region.AtlasRegionStore;
import net.minecraft.server.level.ServerLevel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class CityPlanningEndpointHandler {

    private CityPlanningEndpointHandler() {
    }

    static JsonObject handlePlanD2(Path debugRoot, String runId, String citySeedId,
                                    Integer requestedCellStepBlocks) throws IOException {
        Path runDir = debugRoot.resolve(runId);
        JsonObject seed = loadCitySeed(runDir, runId, citySeedId);
        RunMetadata metadata = loadRunMetadata(runDir, requestedCellStepBlocks, "");

        CityPlanningConfig config = CityPlanningConfig.defaults();
        CitySiteContextBuilder builder = new CitySiteContextBuilder(config);

        List<TerritoryCellRef> territoryCells = loadTerritoryCells(runDir, stringValue(seed, "realmId"));
        CitySiteContext ctx = buildSiteContext(builder, seed, metadata, territoryCells);

        JsonObject response = new JsonObject();
        response.addProperty("ok", true);
        response.add("citySiteContext", ctx.asJson());
        return response;
    }

    static JsonObject handlePlanD3(Path debugRoot, String runId, String citySeedId,
                                    Integer requestedCellStepBlocks, ServerLevel level) throws IOException {
        Path runDir = debugRoot.resolve(runId);
        JsonObject seed = loadCitySeed(runDir, runId, citySeedId);
        RunMetadata metadata = loadRunMetadata(runDir, requestedCellStepBlocks,
                level.dimension().location().toString());

        CityPlanningConfig config = CityPlanningConfig.defaults();
        CitySiteContextBuilder siteBuilder = new CitySiteContextBuilder(config);
        CityLandformReviewBuilder reviewBuilder = new CityLandformReviewBuilder(config);
        CityLandformReviewMapRenderer mapRenderer = new CityLandformReviewMapRenderer();

        List<TerritoryCellRef> territoryCells = loadTerritoryCells(runDir, stringValue(seed, "realmId"));
        CitySiteContext ctx = buildSiteContext(siteBuilder, seed, metadata, territoryCells);

        // Run local GIS refresh within city bounds to get terrain patches
        int cityRadiusBlocks = ctx.planningRadiusBlocks();
        int chunks = Math.max(1, cityRadiusBlocks / 16 + 2);
        int localCellStepBlocks = ctx.grid().cellStepBlocks();
        GisSampleConfig sampleConfig = GisSampleConfig.defaults().withCellStepBlocks(localCellStepBlocks);
        AtlasRegionStore store = new AtlasRegionStore(sampleConfig);
        GisRefreshService gisService = new GisRefreshService(sampleConfig, GisClassifierConfig.defaults(),
                store, new MinecraftPriorAtlasSampler(level));
        Path outputDirectory = runDir.resolve("city_d3_" + safeFileName(citySeedId));
        RefreshResult refreshResult = gisService.refresh(
                level.dimension().location().toString(),
                ctx.anchorBlock().x(), ctx.anchorBlock().z(), chunks,
                com.rinsing.geomantia.systems.gis.application.refresh.SampleMode.PRIOR,
                com.rinsing.geomantia.systems.gis.application.refresh.RefreshPriority.DEBUG,
                outputDirectory);

        List<LandformPatch> patches;
        if (refreshResult.patches() != null && !refreshResult.patches().isEmpty()) {
            patches = refreshResult.patches();
        } else if (refreshResult.region() != null) {
            patches = new ArrayList<>(refreshResult.region().patches());
        } else {
            patches = List.of();
        }

        CityLandformReviewPackage reviewPkg = refreshResult.region() != null
                ? reviewBuilder.build(ctx, refreshResult.region())
                : reviewBuilder.build(ctx, patches);
        Path reviewMapPath = mapRenderer.render(ctx, reviewPkg, patches, outputDirectory);
        String reviewMapRef = debugRef(debugRoot, reviewMapPath);
        reviewPkg = reviewPkg.withReviewMap(reviewMapRef, List.of(
                reviewMapRef,
                debugRef(debugRoot, outputDirectory)));
        Path packagePath = outputDirectory.resolve("city_landform_review_package.json");
        Files.writeString(packagePath, CityJson.GSON.toJson(reviewPkg.asJson()));

        JsonObject response = new JsonObject();
        response.addProperty("ok", true);
        response.addProperty("patchCount", patches.size());
        response.add("citySiteContext", ctx.asJson());
        response.add("landformReviewPackage", reviewPkg.asJson());
        JsonObject artifacts = new JsonObject();
        artifacts.addProperty("landformReviewMap", reviewMapRef);
        artifacts.addProperty("cityLandformReviewPackage", debugRef(debugRoot, packagePath));
        response.add("artifacts", artifacts);
        return response;
    }

    static JsonObject handlePlanD4(Path debugRoot, String runId, String citySeedId,
                                    JsonObject patchGroupPlanJson) throws IOException {
        Path runDir = debugRoot.resolve(runId);
        loadCitySeed(runDir, runId, citySeedId);
        Path d3Dir = runDir.resolve("city_d3_" + safeFileName(citySeedId));
        Path d3PackagePath = d3Dir.resolve("city_landform_review_package.json");
        if (!Files.exists(d3PackagePath)) {
            throw new IllegalArgumentException("D3 package not found. Run city_plan_d3 first: "
                    + debugRef(debugRoot, d3PackagePath));
        }

        CityLandformReviewPackage reviewPackage = CityLandformReviewPackage.fromJson(
                JsonParser.parseString(Files.readString(d3PackagePath)).getAsJsonObject());
        PatchGroupPlan patchGroupPlan = PatchGroupPlan.fromJson(patchGroupPlanJson);
        CityFunctionZoneBuilder.Result result = new CityFunctionZoneBuilder().build(reviewPackage, patchGroupPlan);

        Path outputDirectory = runDir.resolve("city_d4_" + safeFileName(citySeedId));
        Files.createDirectories(outputDirectory);
        Path patchGroupPlanPath = outputDirectory.resolve("patch_group_plan.json");
        Path zoneMapPath = outputDirectory.resolve("function_zone_map.json");
        Path statsPath = outputDirectory.resolve("function_zone_terrain_stats.json");
        Path qualityPath = outputDirectory.resolve("quality_report.json");
        Files.writeString(patchGroupPlanPath, CityJson.GSON.toJson(patchGroupPlan.asJson()));
        Files.writeString(zoneMapPath, CityJson.GSON.toJson(result.functionZoneMap().asJson()));

        JsonArray statsArray = new JsonArray();
        result.functionZoneTerrainStats().forEach(stats -> statsArray.add(stats.asJson()));
        Files.writeString(statsPath, CityJson.GSON.toJson(statsArray));
        Files.writeString(qualityPath, CityJson.GSON.toJson(result.qualityReport().asJson()));

        Path previewPath = new FunctionZonePreviewRenderer()
                .render(reviewPackage, result.functionZoneMap(), outputDirectory);

        JsonObject response = result.asJson();
        JsonObject artifacts = new JsonObject();
        artifacts.addProperty("patchGroupPlan", debugRef(debugRoot, patchGroupPlanPath));
        artifacts.addProperty("functionZoneMap", debugRef(debugRoot, zoneMapPath));
        artifacts.addProperty("functionZoneTerrainStats", debugRef(debugRoot, statsPath));
        artifacts.addProperty("functionZonePreview", debugRef(debugRoot, previewPath));
        artifacts.addProperty("qualityReport", debugRef(debugRoot, qualityPath));
        artifacts.addProperty("sourceD3Package", debugRef(debugRoot, d3PackagePath));
        response.add("artifacts", artifacts);
        return response;
    }

    private static CitySiteContext buildSiteContext(CitySiteContextBuilder builder, JsonObject seed,
                                                    RunMetadata metadata,
                                                    List<TerritoryCellRef> territoryCells) {
        String cityId = stringValue(seed, "citySeedId");
        String realmId = stringValue(seed, "realmId");
        String role = stringValue(seed, "role");
        String scale = stringValue(seed, "theoreticalScale");
        int anchorBlockX = blockCoord(seed, "x", metadata.cellStepBlocks());
        int anchorBlockZ = blockCoord(seed, "z", metadata.cellStepBlocks());
        int planningRadiusCells = intValue(seed, "planningRadiusCells", 64);
        String candidateId = stringValue(seed, "candidateId");
        if (candidateId.isBlank()) {
            candidateId = cityId;
        }

        return builder.build(
                cityId, realmId, metadata.dimensionId(),
                cityId, candidateId,
                anchorBlockX, anchorBlockZ,
                role, scale,
                planningRadiusCells, metadata.cellStepBlocks(),
                territoryCells);
    }

    private static JsonObject loadCitySeed(Path runDir, String runId, String citySeedId) throws IOException {
        Path registryPath = runDir.resolve("city_seed_registry.json");
        if (!Files.exists(registryPath)) {
            throw new IllegalArgumentException("city_seed_registry.json not found for run: " + runId);
        }
        String json = Files.readString(registryPath);
        JsonObject registry = JsonParser.parseString(json).getAsJsonObject();
        JsonArray seeds = registry.getAsJsonArray("citySeeds");
        if (seeds == null || seeds.isEmpty()) {
            throw new IllegalArgumentException("No city seeds found in registry for run: " + runId);
        }
        for (JsonElement elem : seeds) {
            JsonObject seed = elem.getAsJsonObject();
            if (citySeedId.equals(stringValue(seed, "citySeedId"))) {
                return seed;
            }
        }
        throw new IllegalArgumentException("City seed not found: " + citySeedId + " in run: " + runId);
    }

    private static RunMetadata loadRunMetadata(Path runDir, Integer requestedCellStepBlocks,
                                               String requestedDimensionId) throws IOException {
        int cellStepBlocks = requestedCellStepBlocks != null ? requestedCellStepBlocks : 4;
        String dimensionId = requestedDimensionId == null ? "" : requestedDimensionId.trim();

        Path manifestPath = runDir.resolve("world_survey_manifest.json");
        if (Files.exists(manifestPath)) {
            JsonObject manifest = JsonParser.parseString(Files.readString(manifestPath)).getAsJsonObject();
            if (manifest.has("config") && manifest.get("config").isJsonObject()) {
                JsonObject config = manifest.getAsJsonObject("config");
                if (requestedCellStepBlocks == null) {
                    cellStepBlocks = intValue(config, "cellStepBlocks", cellStepBlocks);
                }
                if (dimensionId.isBlank()) {
                    dimensionId = stringValue(config, "dimensionId");
                }
            }
        }

        if (cellStepBlocks <= 0) {
            throw new IllegalArgumentException("cellStepBlocks must be positive.");
        }
        if (dimensionId.isBlank()) {
            dimensionId = "minecraft:overworld";
        }
        return new RunMetadata(cellStepBlocks, dimensionId);
    }

    private static List<TerritoryCellRef> loadTerritoryCells(Path runDir, String realmId) throws IOException {
        if (realmId == null || realmId.isBlank()) {
            return List.of();
        }
        Path territoryPath = runDir.resolve("realm_territory_map.json");
        if (!Files.exists(territoryPath)) {
            return List.of();
        }

        JsonObject territoryMap = JsonParser.parseString(Files.readString(territoryPath)).getAsJsonObject();
        JsonArray cells = territoryMap.getAsJsonArray("territoryCells");
        if (cells == null || cells.isEmpty()) {
            return List.of();
        }

        List<TerritoryCellRef> result = new ArrayList<>();
        for (JsonElement elem : cells) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject cell = elem.getAsJsonObject();
            if (!realmId.equals(stringValue(cell, "realmId"))) {
                continue;
            }
            if (!"owned".equalsIgnoreCase(stringValue(cell, "status"))) {
                continue;
            }
            result.add(new TerritoryCellRef(intValue(cell, "gridX", 0), intValue(cell, "gridZ", 0)));
        }
        return result;
    }

    private static int blockCoord(JsonObject seed, String axis, int cellStepBlocks) {
        if (seed.has("anchorBlock") && seed.get("anchorBlock").isJsonObject()) {
            JsonObject anchorBlock = seed.getAsJsonObject("anchorBlock");
            if (anchorBlock.has(axis) && !anchorBlock.get(axis).isJsonNull()) {
                return anchorBlock.get(axis).getAsInt();
            }
        }
        if (seed.has("anchorGrid") && seed.get("anchorGrid").isJsonObject()) {
            JsonObject anchorGrid = seed.getAsJsonObject("anchorGrid");
            if (anchorGrid.has(axis) && !anchorGrid.get(axis).isJsonNull()) {
                return anchorGrid.get(axis).getAsInt() * cellStepBlocks;
            }
        }
        return 0;
    }

    private static String debugRef(Path debugRoot, Path path) {
        Path relative;
        try {
            relative = debugRoot.relativize(path);
        } catch (IllegalArgumentException ex) {
            relative = path;
        }
        return relative.toString().replace('\\', '/');
    }

    private static String safeFileName(String raw) {
        return raw.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String stringValue(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return "";
        return obj.get(key).getAsString();
    }

    private static int intValue(JsonObject obj, String key, int defaultValue) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return defaultValue;
        return obj.get(key).getAsInt();
    }

    private record RunMetadata(int cellStepBlocks, String dimensionId) {
    }
}
