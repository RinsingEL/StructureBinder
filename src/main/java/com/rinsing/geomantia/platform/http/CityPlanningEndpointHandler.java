package com.rinsing.geomantia.platform.http;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.application.CityFunctionZoneBuilder;
import com.rinsing.geomantia.systems.city.application.CityLandformReviewBuilder;
import com.rinsing.geomantia.systems.city.application.CityRoadBoundaryPlanner;
import com.rinsing.geomantia.systems.city.application.CitySiteContextBuilder;
import com.rinsing.geomantia.systems.city.application.CitySiteContextBuilder.TerritoryCellRef;
import com.rinsing.geomantia.systems.city.application.CityStructureD6Planner;
import com.rinsing.geomantia.systems.city.application.CityStructureD7Executor;
import com.rinsing.geomantia.systems.city.domain.config.CityPlanningConfig;
import com.rinsing.geomantia.systems.city.domain.model.BoundaryIntent;
import com.rinsing.geomantia.systems.city.domain.model.BuildOperationPlan;
import com.rinsing.geomantia.systems.city.domain.model.BuildableAreaMap;
import com.rinsing.geomantia.systems.city.domain.model.CityLandformReviewPackage;
import com.rinsing.geomantia.systems.city.domain.model.CityQualityReport;
import com.rinsing.geomantia.systems.city.domain.model.CitySiteContext;
import com.rinsing.geomantia.systems.city.domain.model.FunctionZoneMap;
import com.rinsing.geomantia.systems.city.domain.model.FunctionZoneTerrainStats;
import com.rinsing.geomantia.systems.city.domain.model.PatchGroupPlan;
import com.rinsing.geomantia.systems.city.domain.model.RoadIntent;
import com.rinsing.geomantia.systems.city.domain.model.WorldMutationReport;
import com.rinsing.geomantia.systems.city.infrastructure.json.CityJson;
import com.rinsing.geomantia.systems.city.infrastructure.preview.CityBuildabilityPreviewRenderer;
import com.rinsing.geomantia.systems.city.infrastructure.preview.CityPlanningPreviewRenderer;
import com.rinsing.geomantia.systems.city.infrastructure.preview.CityLandformReviewMapRenderer;
import com.rinsing.geomantia.systems.city.infrastructure.preview.CityStructurePreviewRenderer;
import com.rinsing.geomantia.systems.city.infrastructure.preview.FunctionZonePreviewRenderer;
import com.rinsing.geomantia.systems.city.infrastructure.world.MinecraftStructurePlacementBackend;
import com.rinsing.geomantia.systems.city.infrastructure.world.WorldEditMutationBackend;
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

    static JsonObject handlePlanD5(Path debugRoot, String runId, String citySeedId) throws IOException {
        Path runDir = debugRoot.resolve(runId);
        JsonObject seed = loadCitySeed(runDir, runId, citySeedId);
        RunMetadata metadata = loadRunMetadata(runDir, null, "");
        CitySiteContext ctx = buildSiteContext(
                new CitySiteContextBuilder(CityPlanningConfig.defaults()),
                seed,
                metadata,
                loadTerritoryCells(runDir, stringValue(seed, "realmId")));

        Path d3Dir = runDir.resolve("city_d3_" + safeFileName(citySeedId));
        Path d4Dir = runDir.resolve("city_d4_" + safeFileName(citySeedId));
        Path d3PackagePath = d3Dir.resolve("city_landform_review_package.json");
        Path zoneMapPath = d4Dir.resolve("function_zone_map.json");
        Path statsPath = d4Dir.resolve("function_zone_terrain_stats.json");
        if (!Files.exists(d3PackagePath)) {
            throw new IllegalArgumentException("D3 package not found. Run city_plan_d3 first: "
                    + debugRef(debugRoot, d3PackagePath));
        }
        if (!Files.exists(zoneMapPath) || !Files.exists(statsPath)) {
            throw new IllegalArgumentException("D4 artifacts not found. Run city_plan_d4 first: "
                    + debugRef(debugRoot, d4Dir));
        }

        FunctionZoneMap zoneMap = FunctionZoneMap.fromJson(
                JsonParser.parseString(Files.readString(zoneMapPath)).getAsJsonObject());
        List<FunctionZoneTerrainStats> stats = terrainStatsFromJson(
                JsonParser.parseString(Files.readString(statsPath)).getAsJsonArray());
        CityRoadBoundaryPlanner.Result result = new CityRoadBoundaryPlanner().plan(ctx, zoneMap, stats);

        Path outputDirectory = runDir.resolve("city_d5_" + safeFileName(citySeedId));
        Files.createDirectories(outputDirectory);
        Path roadPath = outputDirectory.resolve("road_intent.json");
        Path boundaryPath = outputDirectory.resolve("boundary_intent.json");
        Path operationPath = outputDirectory.resolve("build_operation_plan.json");
        Path buildablePath = outputDirectory.resolve("buildable_area_map.json");
        Path qualityPath = outputDirectory.resolve("quality_report.json");
        Files.writeString(roadPath, CityJson.GSON.toJson(result.roadIntent().asJson()));
        Files.writeString(boundaryPath, CityJson.GSON.toJson(result.boundaryIntent().asJson()));
        Files.writeString(operationPath, CityJson.GSON.toJson(result.buildOperationPlan().asJson()));
        Files.writeString(buildablePath, CityJson.GSON.toJson(result.buildableAreaMap().asJson()));
        Files.writeString(qualityPath, CityJson.GSON.toJson(result.qualityReport().asJson()));

        Path previewPath = new CityPlanningPreviewRenderer()
                .render(zoneMap, result.roadIntent(), result.boundaryIntent(),
                        result.buildOperationPlan(), outputDirectory);
        Path buildabilityPreviewPath = new CityBuildabilityPreviewRenderer()
                .render(zoneMap, result.buildableAreaMap(), outputDirectory);

        JsonObject response = result.asJson();
        JsonObject artifacts = new JsonObject();
        artifacts.addProperty("roadIntent", debugRef(debugRoot, roadPath));
        artifacts.addProperty("boundaryIntent", debugRef(debugRoot, boundaryPath));
        artifacts.addProperty("buildOperationPlan", debugRef(debugRoot, operationPath));
        artifacts.addProperty("buildableAreaMap", debugRef(debugRoot, buildablePath));
        artifacts.addProperty("cityPlanningPreview", debugRef(debugRoot, previewPath));
        artifacts.addProperty("cityBuildabilityPreview", debugRef(debugRoot, buildabilityPreviewPath));
        artifacts.addProperty("qualityReport", debugRef(debugRoot, qualityPath));
        artifacts.addProperty("sourceD3Package", debugRef(debugRoot, d3PackagePath));
        artifacts.addProperty("sourceFunctionZoneMap", debugRef(debugRoot, zoneMapPath));
        artifacts.addProperty("sourceFunctionZoneTerrainStats", debugRef(debugRoot, statsPath));
        response.add("artifacts", artifacts);
        return response;
    }

    static JsonObject handleExecuteD5(Path debugRoot, Path serverRoot, String runId, String citySeedId,
                                      boolean confirmWorldMutation, ServerLevel level) throws IOException {
        if (!confirmWorldMutation) {
            throw new IllegalArgumentException("confirmWorldMutation=true is required for city_execute_d5.");
        }
        Path runDir = debugRoot.resolve(runId);
        loadCitySeed(runDir, runId, citySeedId);
        Path d5Dir = runDir.resolve("city_d5_" + safeFileName(citySeedId));
        Path operationPath = d5Dir.resolve("build_operation_plan.json");
        if (!Files.exists(operationPath)) {
            throw new IllegalArgumentException("D5 build_operation_plan.json not found. Run city_plan_d5 first: "
                    + debugRef(debugRoot, operationPath));
        }
        BuildOperationPlan plan = BuildOperationPlan.fromJson(
                JsonParser.parseString(Files.readString(operationPath)).getAsJsonObject());
        if (level == null) {
            throw new IllegalArgumentException("ServerLevel is required for city_execute_d5.");
        }
        WorldMutationReport report = new WorldEditMutationBackend().execute(level, plan, serverRoot);
        Files.createDirectories(d5Dir);
        Path reportPath = d5Dir.resolve("world_mutation_report.json");
        Files.writeString(reportPath, CityJson.GSON.toJson(report.asJson()));

        JsonObject response = new JsonObject();
        response.addProperty("ok", report.failedOperations() == 0);
        response.add("worldMutationReport", report.asJson());
        JsonObject artifacts = new JsonObject();
        artifacts.addProperty("buildOperationPlan", debugRef(debugRoot, operationPath));
        artifacts.addProperty("worldMutationReport", debugRef(debugRoot, reportPath));
        response.add("artifacts", artifacts);
        return response;
    }

    static JsonObject handlePlanD6(Path debugRoot, String runId, String citySeedId,
                                   JsonObject terraSenseProfileSource,
                                   JsonObject structureChoicePlan,
                                   JsonObject fixedPlacementSelectionPlan) throws IOException {
        Path runDir = debugRoot.resolve(runId);
        loadCitySeed(runDir, runId, citySeedId);
        CityInputs inputs = loadCityInputs(debugRoot, runDir, citySeedId);
        CityStructureD6Planner.Result result = new CityStructureD6Planner().plan(
                runDir, inputs.zoneMap(), inputs.buildableAreaMap(), terraSenseProfileSource,
                structureChoicePlan, fixedPlacementSelectionPlan);

        Path outputDirectory = runDir.resolve("city_d6_" + safeFileName(citySeedId));
        Files.createDirectories(outputDirectory);
        Path sourcePath = outputDirectory.resolve("terrasense_profile_source.json");
        Path catalogPath = outputDirectory.resolve("structure_profile_catalog.json");
        Path filteredPath = outputDirectory.resolve("filtered_structure_catalog.json");
        Path choicePath = outputDirectory.resolve("structure_choice_plan.json");
        Path candidatePath = outputDirectory.resolve("fixed_placement_candidate_set.json");
        Path selectionPath = outputDirectory.resolve("fixed_placement_selection_plan.json");
        Path plannedPath = outputDirectory.resolve("planned_fixed_placement_map.json");
        Path poolPath = outputDirectory.resolve("structure_pool_map.json");
        Path qualityPath = outputDirectory.resolve("quality_report.json");
        Files.writeString(sourcePath, CityJson.GSON.toJson(result.terraSenseProfileSource()));
        Files.writeString(catalogPath, CityJson.GSON.toJson(result.structureProfileCatalog()));
        Files.writeString(filteredPath, CityJson.GSON.toJson(result.filteredStructureCatalog()));
        Files.writeString(choicePath, CityJson.GSON.toJson(result.structureChoicePlan()));
        Files.writeString(candidatePath, CityJson.GSON.toJson(result.fixedPlacementCandidateSet()));
        Files.writeString(selectionPath, CityJson.GSON.toJson(result.fixedPlacementSelectionPlan()));
        Files.writeString(plannedPath, CityJson.GSON.toJson(result.plannedFixedPlacementMap()));
        Files.writeString(poolPath, CityJson.GSON.toJson(result.structurePoolMap()));
        Files.writeString(qualityPath, CityJson.GSON.toJson(result.qualityReport()));

        CityStructurePreviewRenderer.D6PreviewPaths previews = new CityStructurePreviewRenderer()
                .renderD6(inputs.zoneMap(), inputs.buildableAreaMap(), result.filteredStructureCatalog(),
                        result.fixedPlacementCandidateSet(), result.plannedFixedPlacementMap(), outputDirectory);

        JsonObject response = result.asJson();
        JsonObject artifacts = new JsonObject();
        artifacts.addProperty("terrasenseProfileSource", debugRef(debugRoot, sourcePath));
        artifacts.addProperty("structureProfileCatalog", debugRef(debugRoot, catalogPath));
        artifacts.addProperty("filteredStructureCatalog", debugRef(debugRoot, filteredPath));
        artifacts.addProperty("structureChoicePlan", debugRef(debugRoot, choicePath));
        artifacts.addProperty("fixedPlacementCandidateSet", debugRef(debugRoot, candidatePath));
        artifacts.addProperty("fixedPlacementSelectionPlan", debugRef(debugRoot, selectionPath));
        artifacts.addProperty("plannedFixedPlacementMap", debugRef(debugRoot, plannedPath));
        artifacts.addProperty("structurePoolMap", debugRef(debugRoot, poolPath));
        artifacts.addProperty("structureChoicePreview", debugRef(debugRoot, previews.structureChoicePreview()));
        artifacts.addProperty("fixedPlacementPreview", debugRef(debugRoot, previews.fixedPlacementPreview()));
        artifacts.addProperty("qualityReport", debugRef(debugRoot, qualityPath));
        artifacts.addProperty("sourceFunctionZoneMap", debugRef(debugRoot, inputs.zoneMapPath()));
        artifacts.addProperty("sourceBuildableAreaMap", debugRef(debugRoot, inputs.buildablePath()));
        response.add("artifacts", artifacts);
        return response;
    }

    static JsonObject handleExecuteD7(Path debugRoot, String runId, String citySeedId, long worldSeed,
                                      boolean executeStructurePlacement, MinecraftServerHolder serverHolder,
                                      ServerLevel level) throws IOException {
        Path runDir = debugRoot.resolve(runId);
        loadCitySeed(runDir, runId, citySeedId);
        CityInputs inputs = loadCityInputs(debugRoot, runDir, citySeedId);
        Path d6Dir = runDir.resolve("city_d6_" + safeFileName(citySeedId));
        Path plannedPath = d6Dir.resolve("planned_fixed_placement_map.json");
        Path poolPath = d6Dir.resolve("structure_pool_map.json");
        if (!Files.exists(plannedPath) || !Files.exists(poolPath)) {
            throw new IllegalArgumentException("D6 artifacts not found. Run city_plan_d6 first: "
                    + debugRef(debugRoot, d6Dir));
        }
        JsonObject plannedFixedMap = JsonParser.parseString(Files.readString(plannedPath)).getAsJsonObject();
        JsonObject structurePoolMap = JsonParser.parseString(Files.readString(poolPath)).getAsJsonObject();
        CityStructureD7Executor.PlacementBackend backend = serverHolder == null
                ? CityStructureD7Executor.PlacementBackend.traceOnly()
                : new MinecraftStructurePlacementBackend(serverHolder.server(), level, executeStructurePlacement);
        Path outputDirectory = runDir.resolve("city_d7_" + safeFileName(citySeedId));
        Path placedPath = outputDirectory.resolve("placed_structure_map.json");
        JsonObject previousPlacedMap = executeStructurePlacement && Files.exists(placedPath)
                ? JsonParser.parseString(Files.readString(placedPath)).getAsJsonObject()
                : null;
        CityStructureD7Executor.Result result = new CityStructureD7Executor().execute(
                inputs.zoneMap(), inputs.buildableAreaMap(), plannedFixedMap, structurePoolMap, worldSeed, backend,
                previousPlacedMap);

        Files.createDirectories(outputDirectory);
        Path startPath = outputDirectory.resolve("start_candidate_set.json");
        Path tracePath = outputDirectory.resolve("structure_generation_trace.json");
        Path qualityPath = outputDirectory.resolve("quality_report.json");
        Files.writeString(startPath, CityJson.GSON.toJson(result.startCandidateSets()));
        Files.writeString(placedPath, CityJson.GSON.toJson(result.placedStructureMap()));
        Files.writeString(tracePath, CityJson.GSON.toJson(result.structureGenerationTrace()));
        Files.writeString(qualityPath, CityJson.GSON.toJson(result.qualityReport()));

        CityStructurePreviewRenderer.D7PreviewPaths previews = new CityStructurePreviewRenderer()
                .renderD7(inputs.zoneMap(), inputs.buildableAreaMap(), result.startCandidateSets(),
                        result.placedStructureMap(), outputDirectory);

        JsonObject response = result.asJson();
        JsonObject artifacts = new JsonObject();
        artifacts.addProperty("startCandidateSet", debugRef(debugRoot, startPath));
        artifacts.addProperty("placedStructureMap", debugRef(debugRoot, placedPath));
        artifacts.addProperty("structureGenerationTrace", debugRef(debugRoot, tracePath));
        artifacts.addProperty("startCandidatePreview", debugRef(debugRoot, previews.startCandidatePreview()));
        artifacts.addProperty("placedStructurePreview", debugRef(debugRoot, previews.placedStructurePreview()));
        artifacts.addProperty("boundedPiecePreview", debugRef(debugRoot, previews.boundedPiecePreview()));
        artifacts.addProperty("qualityReport", debugRef(debugRoot, qualityPath));
        artifacts.addProperty("sourcePlannedFixedPlacementMap", debugRef(debugRoot, plannedPath));
        artifacts.addProperty("sourceStructurePoolMap", debugRef(debugRoot, poolPath));
        response.add("artifacts", artifacts);
        response.addProperty("structurePlacementExecuted", executeStructurePlacement);
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

    private static List<FunctionZoneTerrainStats> terrainStatsFromJson(JsonArray array) {
        List<FunctionZoneTerrainStats> result = new ArrayList<>();
        for (JsonElement elem : array) {
            result.add(FunctionZoneTerrainStats.fromJson(elem.getAsJsonObject()));
        }
        return result;
    }

    private static CityInputs loadCityInputs(Path debugRoot, Path runDir, String citySeedId) throws IOException {
        Path d4Dir = runDir.resolve("city_d4_" + safeFileName(citySeedId));
        Path d5Dir = runDir.resolve("city_d5_" + safeFileName(citySeedId));
        Path zoneMapPath = d4Dir.resolve("function_zone_map.json");
        Path statsPath = d4Dir.resolve("function_zone_terrain_stats.json");
        Path buildablePath = d5Dir.resolve("buildable_area_map.json");
        if (!Files.exists(zoneMapPath) || !Files.exists(statsPath)) {
            throw new IllegalArgumentException("D4 artifacts not found. Run city_plan_d4 first: "
                    + debugRef(debugRoot, d4Dir));
        }
        if (!Files.exists(buildablePath)) {
            throw new IllegalArgumentException("D5 buildable_area_map.json not found. Run city_plan_d5 first: "
                    + debugRef(debugRoot, buildablePath));
        }
        FunctionZoneMap zoneMap = FunctionZoneMap.fromJson(
                JsonParser.parseString(Files.readString(zoneMapPath)).getAsJsonObject());
        BuildableAreaMap buildableAreaMap = BuildableAreaMap.fromJson(
                JsonParser.parseString(Files.readString(buildablePath)).getAsJsonObject());
        return new CityInputs(zoneMap, buildableAreaMap, zoneMapPath, statsPath, buildablePath);
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

    private record CityInputs(FunctionZoneMap zoneMap, BuildableAreaMap buildableAreaMap,
                              Path zoneMapPath, Path terrainStatsPath, Path buildablePath) {
    }

    record MinecraftServerHolder(net.minecraft.server.MinecraftServer server) {
    }
}
