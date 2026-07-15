package com.rinsing.geomantia.platform.http;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.application.CityD4DesignLoopStatePlanner;
import com.rinsing.geomantia.systems.city.application.CityD4StagedPlanCompiler;
import com.rinsing.geomantia.systems.city.application.CityLandformReviewBuilder;
import com.rinsing.geomantia.systems.city.application.CityReservationMaskPlanner;
import com.rinsing.geomantia.systems.city.application.CitySiteContextBuilder;
import com.rinsing.geomantia.systems.city.application.CitySiteContextBuilder.TerritoryCellRef;
import com.rinsing.geomantia.systems.city.application.CityStructureAnchorPlanner;
import com.rinsing.geomantia.systems.city.application.CityStructureAnchorCandidatePlanner;
import com.rinsing.geomantia.systems.city.application.CityStructureArrayCandidatePlanner;
import com.rinsing.geomantia.systems.city.application.CityStructureArrayLayoutLoopPlanner;
import com.rinsing.geomantia.systems.city.application.CityStructureClusterGroupCandidatePlanner;
import com.rinsing.geomantia.systems.city.application.CityStructureEnvelopeFacts;
import com.rinsing.geomantia.systems.city.application.CityStructureEnvelopeProfiler;
import com.rinsing.geomantia.systems.city.application.CityStructureMaterializationPlanner;
import com.rinsing.geomantia.systems.city.application.CityStructureProfileCatalog;
import com.rinsing.geomantia.systems.city.application.CityStructureCatalogQueryService;
import com.rinsing.geomantia.systems.city.application.CityWallPlanner;
import com.rinsing.geomantia.systems.city.application.CityWallReservationPlanner;
import com.rinsing.geomantia.systems.city.application.CityWorkflowCandidateSelector;
import com.rinsing.geomantia.systems.city.application.CityWorkflowStepRunner;
import com.rinsing.geomantia.systems.city.application.dressing.CityDecorationProgramPlanner;
import com.rinsing.geomantia.systems.city.application.dressing.CityDecorationProgramContextResolver;
import com.rinsing.geomantia.systems.city.application.dressing.CityDecorationTerrainProbe;
import com.rinsing.geomantia.systems.city.application.dressing.CompiledDecorationProgram;
import com.rinsing.geomantia.systems.city.application.dressing.CompiledDecorationProgramCodec;
import com.rinsing.geomantia.systems.city.application.dressing.CompiledDecorationProgramPlan;
import com.rinsing.geomantia.systems.city.application.dressing.D3PatchDecorationProgramContextResolver;
import com.rinsing.geomantia.systems.city.application.dressing.DecorationProgramIntent;
import com.rinsing.geomantia.systems.city.application.dressing.DecorationProgramIntentCodec;
import com.rinsing.geomantia.systems.city.application.dressing.DecorationProgramIntentPlan;
import com.rinsing.geomantia.systems.city.application.dressing.DecorationSlot;
import com.rinsing.geomantia.systems.city.application.dressing.LandUseAreaDecorationProgramContextResolver;
import com.rinsing.geomantia.systems.city.application.landuse.LandUseAreaPlanCodec;
import com.rinsing.geomantia.systems.city.application.landuse.LandUsePlanningService;
import com.rinsing.geomantia.systems.city.application.landuse.LandUseTerrainFieldCodec;
import com.rinsing.geomantia.systems.city.application.landuse.LandUseTerrainFieldCompiler;
import com.rinsing.geomantia.systems.city.domain.config.CityPlanningConfig;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseTerrainField;
import com.rinsing.geomantia.systems.city.domain.landuse.rules.LandUseRuleCatalog;
import com.rinsing.geomantia.systems.city.domain.landuse.rules.LandUseSettings;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import com.rinsing.geomantia.systems.city.domain.model.BuildOperationPlan;
import com.rinsing.geomantia.systems.city.domain.model.CityLandformReviewPackage;
import com.rinsing.geomantia.systems.city.domain.model.CitySiteContext;
import com.rinsing.geomantia.systems.city.domain.model.WorldMutationReport;
import com.rinsing.geomantia.systems.city.infrastructure.json.CityJson;
import com.rinsing.geomantia.systems.city.infrastructure.preview.CityDecorationPreviewRenderer;
import com.rinsing.geomantia.systems.city.infrastructure.preview.CityLandUsePreviewRenderer;
import com.rinsing.geomantia.systems.city.infrastructure.preview.CityLandformReviewMapRenderer;
import com.rinsing.geomantia.systems.city.infrastructure.preview.CityStructureLandingPreviewRenderer;
import com.rinsing.geomantia.systems.city.infrastructure.preview.CityWallPreviewRenderer;
import com.rinsing.geomantia.systems.city.infrastructure.dressing.CityDecorationContentCatalog;
import com.rinsing.geomantia.systems.city.infrastructure.dressing.CityDecorationContentCatalogLoader;
import com.rinsing.geomantia.systems.city.infrastructure.dressing.CityDecorationDefaultCatalogBootstrap;
import com.rinsing.geomantia.systems.city.infrastructure.dressing.CityDecorationStyleProfileCatalog;
import com.rinsing.geomantia.systems.city.infrastructure.dressing.CityDecorationStyleProfileCatalogLoader;
import com.rinsing.geomantia.systems.city.infrastructure.dressing.CityDecorationStyleProfileResolver;
import com.rinsing.geomantia.systems.city.infrastructure.landuse.LandUseDefaultConfigBootstrap;
import com.rinsing.geomantia.systems.city.infrastructure.landuse.LandUseSettingsLoader;
import com.rinsing.geomantia.systems.city.infrastructure.world.CityDecorationWorldgenRegistry;
import com.rinsing.geomantia.systems.city.infrastructure.world.CityReservationMaskRegistry;
import com.rinsing.geomantia.systems.city.infrastructure.world.MinecraftCityWallArtifactWriter;
import com.rinsing.geomantia.systems.city.infrastructure.world.CityRoadMaskScanner;
import com.rinsing.geomantia.systems.city.infrastructure.world.CityRoadWeaverBridge;
import com.rinsing.geomantia.systems.city.infrastructure.world.CitySurfaceCache;
import com.rinsing.geomantia.systems.city.infrastructure.world.CityWallPlacementBackend;
import com.rinsing.geomantia.systems.city.infrastructure.world.MinecraftCityStructureMaterializationBackend;
import com.rinsing.geomantia.systems.city.infrastructure.world.MinecraftCityStructureEnvelopeSampler;
import com.rinsing.geomantia.systems.city.infrastructure.world.MinecraftCityTemplateReader;
import com.rinsing.geomantia.systems.city.infrastructure.world.MinecraftCityWorldgenStatusInspector;
import com.rinsing.geomantia.systems.city.infrastructure.world.WorldEditMutationBackend;
import com.rinsing.geomantia.systems.city.infrastructure.world.landuse.CityLandUseChunkStatusPreflight;
import com.rinsing.geomantia.systems.city.infrastructure.world.landuse.CityLandUseWorldgenRegistry;
import com.rinsing.geomantia.systems.gis.GisClassifierConfig;
import com.rinsing.geomantia.systems.gis.GisSampleConfig;
import com.rinsing.geomantia.systems.gis.adapter.minecraft.MinecraftPriorAtlasSampler;
import com.rinsing.geomantia.systems.gis.application.refresh.RefreshPriority;
import com.rinsing.geomantia.systems.gis.application.refresh.GisRefreshService;
import com.rinsing.geomantia.systems.gis.application.refresh.RefreshResult;
import com.rinsing.geomantia.systems.gis.application.refresh.SampleMode;
import com.rinsing.geomantia.systems.gis.domain.landform.LandformPatch;
import com.rinsing.geomantia.systems.gis.domain.region.AtlasRegion;
import com.rinsing.geomantia.systems.gis.domain.region.AtlasRegionStore;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class CityPlanningEndpointHandler {
    static final int DEFAULT_D3_PATCH_SCAN_PADDING_BLOCKS = 128;
    private static final CityD4StagedPlanCompiler D4_STAGED_PLAN_COMPILER =
            new CityD4StagedPlanCompiler();
    private static final CityWorkflowCandidateSelector WORKFLOW_CANDIDATE_SELECTOR =
            new CityWorkflowCandidateSelector();

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
        return handlePlanD3(debugRoot, runId, citySeedId, requestedCellStepBlocks, null, level);
    }

    static JsonObject handlePlanD3(Path debugRoot, String runId, String citySeedId,
                                    Integer requestedCellStepBlocks, Integer requestedPatchScanPaddingBlocks,
                                    ServerLevel level) throws IOException {
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

        int patchScanPaddingBlocks = normalizeD3PatchScanPaddingBlocks(requestedPatchScanPaddingBlocks);
        BlockBounds patchContextBounds = expandBounds(ctx.bounds(), patchScanPaddingBlocks);
        int localCellStepBlocks = ctx.grid().cellStepBlocks();
        GisSampleConfig sampleConfig = GisSampleConfig.defaults().withCellStepBlocks(localCellStepBlocks);
        AtlasRegionStore store = new AtlasRegionStore(sampleConfig);
        GisRefreshService gisService = new GisRefreshService(sampleConfig, GisClassifierConfig.defaults(),
                store, new MinecraftPriorAtlasSampler(level));
        Path outputDirectory = runDir.resolve("city_d3_" + safeFileName(citySeedId));
        List<RefreshResult> refreshResults = refreshCityD3Regions(gisService, sampleConfig,
                level.dimension().location().toString(), patchContextBounds, outputDirectory);

        List<AtlasRegion> regions = refreshResults.stream()
                .map(RefreshResult::region)
                .filter(java.util.Objects::nonNull)
                .toList();
        List<LandformPatch> patches = refreshResults.stream()
                .flatMap(result -> {
                    if (result.patches() != null && !result.patches().isEmpty()) {
                        return result.patches().stream();
                    }
                    return result.region() == null ? java.util.stream.Stream.<LandformPatch>empty()
                            : result.region().patches().stream();
                })
                .toList();

        CityLandformReviewPackage reviewPkg = regions.isEmpty()
                ? reviewBuilder.build(ctx, patches)
                : reviewBuilder.buildFromRegions(ctx, regions, patchContextBounds);
        Path reviewMapPath = mapRenderer.render(ctx, reviewPkg, patches, outputDirectory);
        String reviewMapRef = debugRef(debugRoot, reviewMapPath);
        reviewPkg = reviewPkg.withReviewMap(reviewMapRef, List.of(
                reviewMapRef,
                debugRef(debugRoot, outputDirectory)));
        Path packagePath = outputDirectory.resolve("city_landform_review_package.json");
        JsonObject packageJson = reviewPkg.asJson();
        addD3PatchScanMetadata(packageJson, patchScanPaddingBlocks, patchContextBounds, refreshResults);
        Files.writeString(packagePath, CityJson.GSON.toJson(packageJson));

        LandUseTerrainField landUseTerrainField = new LandUseTerrainFieldCompiler().compile(reviewPkg, regions);
        Path landUseDirectory = runDir.resolve("city_land_use_" + safeFileName(citySeedId));
        Files.createDirectories(landUseDirectory);
        Path landUseTerrainFieldPath = landUseDirectory.resolve("land_use_terrain_field.json");
        Files.writeString(landUseTerrainFieldPath, CityJson.GSON.toJson(
                new LandUseTerrainFieldCodec().toJson(landUseTerrainField)));
        Files.deleteIfExists(landUseDirectory.resolve("city_land_use_planning_complete.json"));
        Files.deleteIfExists(decorationDir(runDir, citySeedId)
                .resolve("city_decoration_planning_complete.json"));

        JsonObject response = new JsonObject();
        response.addProperty("ok", true);
        response.addProperty("patchCount", reviewPkg.landformPatches().size());
        response.addProperty("refreshedRegionCount", refreshResults.size());
        response.addProperty("patchScanPaddingBlocks", patchScanPaddingBlocks);
        response.addProperty("landUseTerrainCellCount", landUseTerrainField.cells().size());
        response.add("citySiteContext", ctx.asJson());
        response.add("landformReviewPackage", packageJson);
        JsonObject artifacts = new JsonObject();
        artifacts.addProperty("landformReviewMap", reviewMapRef);
        artifacts.addProperty("cityLandformReviewPackage", debugRef(debugRoot, packagePath));
        artifacts.addProperty("landUseTerrainField", debugRef(debugRoot, landUseTerrainFieldPath));
        response.add("artifacts", artifacts);
        return response;
    }

    private static int normalizeD3PatchScanPaddingBlocks(Integer requestedPatchScanPaddingBlocks) {
        if (requestedPatchScanPaddingBlocks == null) {
            return DEFAULT_D3_PATCH_SCAN_PADDING_BLOCKS;
        }
        return Math.max(0, requestedPatchScanPaddingBlocks);
    }

    private static List<RefreshResult> refreshCityD3Regions(GisRefreshService gisService,
                                                            GisSampleConfig sampleConfig,
                                                            String dimensionId,
                                                            BlockBounds patchContextBounds,
                                                            Path outputDirectory) throws IOException {
        int regionSizeBlocks = sampleConfig.regionSizeBlocks();
        int minRegionX = Math.floorDiv(patchContextBounds.minX(), regionSizeBlocks);
        int maxRegionX = Math.floorDiv(patchContextBounds.maxX(), regionSizeBlocks);
        int minRegionZ = Math.floorDiv(patchContextBounds.minZ(), regionSizeBlocks);
        int maxRegionZ = Math.floorDiv(patchContextBounds.maxZ(), regionSizeBlocks);
        List<RefreshResult> results = new ArrayList<>();
        for (int regionX = minRegionX; regionX <= maxRegionX; regionX++) {
            for (int regionZ = minRegionZ; regionZ <= maxRegionZ; regionZ++) {
                int centerX = regionX * regionSizeBlocks + regionSizeBlocks / 2;
                int centerZ = regionZ * regionSizeBlocks + regionSizeBlocks / 2;
                Path regionOutput = outputDirectory.resolve("gis_region_" + regionX + "_" + regionZ);
                results.add(gisService.refresh(dimensionId, centerX, centerZ,
                        sampleConfig.regionSizeChunks(), SampleMode.PRIOR, RefreshPriority.DEBUG, regionOutput));
            }
        }
        return results;
    }

    private static void addD3PatchScanMetadata(JsonObject packageJson,
                                               int patchScanPaddingBlocks,
                                               BlockBounds patchContextBounds,
                                               List<RefreshResult> refreshResults) {
        packageJson.addProperty("patchScanPaddingBlocks", patchScanPaddingBlocks);
        packageJson.add("patchContextBounds", boundsJson(patchContextBounds));
        JsonArray regions = new JsonArray();
        for (RefreshResult result : refreshResults) {
            if (result.region() == null) {
                continue;
            }
            JsonObject region = new JsonObject();
            region.addProperty("regionId", result.region().regionId());
            region.addProperty("regionX", result.region().regionX());
            region.addProperty("regionZ", result.region().regionZ());
            region.addProperty("patchCount", result.patches() == null
                    ? result.region().patches().size() : result.patches().size());
            regions.add(region);
        }
        packageJson.add("refreshedRegions", regions);
    }

    private static BlockBounds expandBounds(BlockBounds bounds, int margin) {
        int normalized = Math.max(0, margin);
        return new BlockBounds(bounds.minX() - normalized, bounds.minZ() - normalized,
                bounds.maxX() + normalized, bounds.maxZ() + normalized);
    }

    static JsonObject handlePlanD4(Path debugRoot, String runId, String citySeedId,
                                    JsonObject terraSenseProfileSource,
                                    JsonObject structureAnchorPlan) throws IOException {
        return handlePlanD4(debugRoot, runId, citySeedId, terraSenseProfileSource, structureAnchorPlan, null);
    }

    static JsonObject handlePlanD4(Path debugRoot, String runId, String citySeedId,
                                    JsonObject terraSenseProfileSource,
                                    JsonObject structureAnchorPlan,
                                    JsonObject structureEnvelopeFactsSource) throws IOException {
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
        Path envelopeFactsPath = envelopeFactsPath(runDir, citySeedId, structureEnvelopeFactsSource);
        CityStructureEnvelopeFacts envelopeFacts = CityStructureEnvelopeFacts.load(envelopeFactsPath);
        CityStructureAnchorPlanner.Result result = new CityStructureAnchorPlanner()
                .plan(runDir, reviewPackage, terraSenseProfileSource, structureAnchorPlan, envelopeFacts);

        Path outputDirectory = runDir.resolve("city_d4_" + safeFileName(citySeedId));
        Files.createDirectories(outputDirectory);
        Path anchorPlanPath = outputDirectory.resolve("structure_anchor_plan.json");
        Path anchorMapPath = outputDirectory.resolve("structure_anchor_map.json");
        Path catalogPath = outputDirectory.resolve("structure_profile_catalog.json");
        Path qualityPath = outputDirectory.resolve("quality_report.json");
        Files.writeString(anchorPlanPath, CityJson.GSON.toJson(result.structureAnchorPlan()));
        Files.writeString(anchorMapPath, CityJson.GSON.toJson(result.structureAnchorMap()));
        Files.writeString(catalogPath, CityJson.GSON.toJson(
                result.structureAnchorMap().getAsJsonObject("structureProfileCatalog")));
        Files.writeString(qualityPath, CityJson.GSON.toJson(result.qualityReport()));

        Path previewPath = new CityStructureLandingPreviewRenderer()
                .renderD4(result.structureAnchorMap(), reviewPackage, outputDirectory);

        JsonObject response = result.asJson();
        JsonObject artifacts = new JsonObject();
        artifacts.addProperty("structureAnchorPlan", debugRef(debugRoot, anchorPlanPath));
        artifacts.addProperty("structureAnchorMap", debugRef(debugRoot, anchorMapPath));
        artifacts.addProperty("structureProfileCatalog", debugRef(debugRoot, catalogPath));
        artifacts.addProperty("structureAnchorPreview", debugRef(debugRoot, previewPath));
        artifacts.addProperty("qualityReport", debugRef(debugRoot, qualityPath));
        artifacts.addProperty("sourceD3Package", debugRef(debugRoot, d3PackagePath));
        if (envelopeFactsPath != null && Files.exists(envelopeFactsPath)) {
            artifacts.addProperty("sourceStructureEnvelopeFacts", debugRef(debugRoot, envelopeFactsPath));
        }
        response.add("artifacts", artifacts);
        return response;
    }

    static JsonObject handlePlanD4Candidates(Path debugRoot, String runId, String citySeedId,
                                             JsonObject terraSenseProfileSource,
                                             JsonObject designSlotPlan,
                                             JsonObject structureEnvelopeFactsSource) throws IOException {
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
        Path envelopeFactsPath = envelopeFactsPath(runDir, citySeedId, structureEnvelopeFactsSource);
        CityStructureEnvelopeFacts envelopeFacts = CityStructureEnvelopeFacts.load(envelopeFactsPath);
        CityStructureAnchorCandidatePlanner.Result result = new CityStructureAnchorCandidatePlanner()
                .plan(runDir, reviewPackage, terraSenseProfileSource, designSlotPlan, envelopeFacts);

        Path outputDirectory = runDir.resolve("city_d4_candidates_" + safeFileName(citySeedId));
        Files.createDirectories(outputDirectory);
        Path slotPlanPath = outputDirectory.resolve("design_slot_plan.json");
        Path candidateSetPath = outputDirectory.resolve("anchor_candidate_set.json");
        Path qualityPath = outputDirectory.resolve("quality_report.json");
        Files.writeString(slotPlanPath, CityJson.GSON.toJson(result.designSlotPlan()));
        Files.writeString(candidateSetPath, CityJson.GSON.toJson(result.anchorCandidateSet()));
        Files.writeString(qualityPath, CityJson.GSON.toJson(result.qualityReport()));

        Path previewPath = new CityStructureLandingPreviewRenderer()
                .renderD4Candidates(result.anchorCandidateSet(), reviewPackage, outputDirectory);

        JsonObject response = result.asJson();
        JsonObject artifacts = new JsonObject();
        artifacts.addProperty("designSlotPlan", debugRef(debugRoot, slotPlanPath));
        artifacts.addProperty("anchorCandidateSet", debugRef(debugRoot, candidateSetPath));
        artifacts.addProperty("anchorCandidatePreview", debugRef(debugRoot, previewPath));
        artifacts.addProperty("qualityReport", debugRef(debugRoot, qualityPath));
        artifacts.addProperty("sourceD3Package", debugRef(debugRoot, d3PackagePath));
        if (envelopeFactsPath != null && Files.exists(envelopeFactsPath)) {
            artifacts.addProperty("sourceStructureEnvelopeFacts", debugRef(debugRoot, envelopeFactsPath));
        }
        response.add("artifacts", artifacts);
        return response;
    }

    static JsonObject handlePlanD4ArrayCandidates(Path debugRoot, String runId, String citySeedId,
                                                  JsonObject terraSenseProfileSource,
                                                  JsonObject arrayCandidatePlan,
                                                  JsonObject structureEnvelopeFactsSource,
                                                  JsonObject occupiedStructureAnchorMapSource,
                                                  JsonArray occupiedEnvelopes) throws IOException {
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
        Path envelopeFactsPath = envelopeFactsPath(runDir, citySeedId, structureEnvelopeFactsSource);
        CityStructureEnvelopeFacts envelopeFacts = CityStructureEnvelopeFacts.load(envelopeFactsPath);
        Path occupiedAnchorMapPath = occupiedStructureAnchorMapPath(runDir, occupiedStructureAnchorMapSource);
        JsonObject occupiedAnchorMap = new JsonObject();
        if (occupiedAnchorMapPath != null) {
            if (!Files.exists(occupiedAnchorMapPath)) {
                throw new IllegalArgumentException("occupiedStructureAnchorMapSource not found: "
                        + debugRef(debugRoot, occupiedAnchorMapPath));
            }
            occupiedAnchorMap = JsonParser.parseString(Files.readString(occupiedAnchorMapPath)).getAsJsonObject();
        }
        CityStructureArrayCandidatePlanner.Result result = new CityStructureArrayCandidatePlanner()
                .plan(runDir, reviewPackage, terraSenseProfileSource, arrayCandidatePlan,
                        envelopeFacts, occupiedAnchorMap,
                        occupiedEnvelopes == null ? new JsonArray() : occupiedEnvelopes);

        Path outputDirectory = runDir.resolve("city_d4_array_candidates_" + safeFileName(citySeedId));
        Files.createDirectories(outputDirectory);
        Path planPath = outputDirectory.resolve("d4_array_candidate_plan.json");
        Path candidateSetPath = outputDirectory.resolve("d4_array_candidate_set.json");
        Path qualityPath = outputDirectory.resolve("quality_report.json");
        Files.writeString(planPath, CityJson.GSON.toJson(result.arrayCandidatePlan()));
        Files.writeString(candidateSetPath, CityJson.GSON.toJson(result.arrayCandidateSet()));
        Files.writeString(qualityPath, CityJson.GSON.toJson(result.qualityReport()));

        Path previewPath = new CityStructureLandingPreviewRenderer()
                .renderD4ArrayCandidates(result.arrayCandidateSet(), reviewPackage, outputDirectory);

        JsonObject response = result.asJson();
        JsonObject artifacts = new JsonObject();
        artifacts.addProperty("arrayCandidatePlan", debugRef(debugRoot, planPath));
        artifacts.addProperty("arrayCandidateSet", debugRef(debugRoot, candidateSetPath));
        artifacts.addProperty("arrayCandidatePreview", debugRef(debugRoot, previewPath));
        artifacts.addProperty("d4ArrayCandidatePreview", debugRef(debugRoot, previewPath));
        artifacts.addProperty("qualityReport", debugRef(debugRoot, qualityPath));
        artifacts.addProperty("sourceD3Package", debugRef(debugRoot, d3PackagePath));
        if (envelopeFactsPath != null && Files.exists(envelopeFactsPath)) {
            artifacts.addProperty("sourceStructureEnvelopeFacts", debugRef(debugRoot, envelopeFactsPath));
        }
        if (occupiedAnchorMapPath != null && Files.exists(occupiedAnchorMapPath)) {
            artifacts.addProperty("sourceOccupiedStructureAnchorMap", debugRef(debugRoot, occupiedAnchorMapPath));
        }
        response.add("artifacts", artifacts);
        return response;
    }

    static JsonObject handleCreateD4ArrayLayoutLoop(Path debugRoot, String runId, String citySeedId,
                                                    JsonObject terraSenseProfileSource,
                                                    JsonObject arrayLayoutPlan,
                                                    JsonObject structureEnvelopeFactsSource,
                                                    JsonObject baseStructureAnchorPlanSource,
                                                    JsonObject occupiedStructureAnchorMapSource) throws IOException {
        Path runDir = debugRoot.resolve(runId);
        loadCitySeed(runDir, runId, citySeedId);
        CityLandformReviewPackage reviewPackage = loadD3Package(debugRoot, runDir, citySeedId);
        Path envelopeFactsPath = envelopeFactsPath(runDir, citySeedId, structureEnvelopeFactsSource);
        CityStructureEnvelopeFacts envelopeFacts = CityStructureEnvelopeFacts.load(envelopeFactsPath);
        JsonObject basePlan = loadOptionalStructureAnchorPlan(debugRoot, runDir, citySeedId,
                baseStructureAnchorPlanSource);
        JsonObject occupiedAnchorMap = loadOptionalOccupiedAnchorMap(debugRoot, runDir, citySeedId,
                occupiedStructureAnchorMapSource);
        CityStructureArrayLayoutLoopPlanner.CreateResult result = new CityStructureArrayLayoutLoopPlanner()
                .create(runDir, reviewPackage, terraSenseProfileSource, arrayLayoutPlan, envelopeFacts,
                        basePlan, occupiedAnchorMap);
        JsonObject response = result.asJson();
        response.add("artifacts", writeD4ArrayLayoutLoopArtifacts(debugRoot, runDir, citySeedId,
                result.loopState(), reviewPackage, envelopeFactsPath));
        return response;
    }

    static JsonObject handleCreateD4DesignLoopState(Path debugRoot, String runId, String citySeedId,
                                                    JsonObject designLoopOptions,
                                                    JsonObject baseStructureAnchorMapSource) throws IOException {
        Path runDir = debugRoot.resolve(runId);
        loadCitySeed(runDir, runId, citySeedId);
        CityLandformReviewPackage reviewPackage = loadD3Package(debugRoot, runDir, citySeedId);
        JsonObject baseAnchorMap = loadOptionalDesignLoopAnchorMap(runDir, baseStructureAnchorMapSource);
        CityD4DesignLoopStatePlanner.CreateResult result = new CityD4DesignLoopStatePlanner()
                .create(reviewPackage, designLoopOptions, baseAnchorMap);
        JsonObject response = result.asJson();
        response.add("artifacts", writeD4DesignLoopArtifacts(debugRoot, runDir, citySeedId, result.loopState()));
        return response;
    }

    static JsonObject handleReadD4DesignLoopState(Path debugRoot, String runId, String citySeedId,
                                                  JsonObject designLoopStateSource) throws IOException {
        Path runDir = debugRoot.resolve(runId);
        loadCitySeed(runDir, runId, citySeedId);
        JsonObject state = loadD4DesignLoopState(debugRoot, runDir, citySeedId, designLoopStateSource);
        CityD4DesignLoopStatePlanner.ReadResult result = new CityD4DesignLoopStatePlanner().read(state);
        JsonObject response = result.asJson();
        response.add("artifacts", d4DesignLoopArtifactRefs(debugRoot, runDir, citySeedId));
        return response;
    }

    static JsonObject handleAppendD4DesignLoopRound(Path debugRoot, String runId, String citySeedId,
                                                    String stateId,
                                                    JsonObject designLoopRound,
                                                    JsonObject designLoopStateSource) throws IOException {
        Path runDir = debugRoot.resolve(runId);
        loadCitySeed(runDir, runId, citySeedId);
        JsonObject currentState = loadD4DesignLoopState(debugRoot, runDir, citySeedId, designLoopStateSource);
        String currentStateId = stringValue(currentState, "stateId");
        if (stateId != null && !stateId.isBlank() && !stateId.equals(currentStateId)) {
            throw new IllegalArgumentException("D4_DESIGN_LOOP_STATE_STALE: requested " + stateId
                    + " but current state is " + currentStateId + ".");
        }
        CityD4DesignLoopStatePlanner.AppendResult result = new CityD4DesignLoopStatePlanner()
                .appendOneRound(currentState, designLoopRound);
        JsonObject response = result.asJson();
        response.add("artifacts", writeD4DesignLoopArtifacts(debugRoot, runDir, citySeedId, result.loopState()));
        return response;
    }

    static JsonObject handleWriteD4DesignLoopState(Path debugRoot, String runId, String citySeedId,
                                                   String stateId,
                                                   JsonObject designLoopState) throws IOException {
        Path runDir = debugRoot.resolve(runId);
        loadCitySeed(runDir, runId, citySeedId);
        Path currentPath = d4DesignLoopStatePath(runDir, citySeedId, null);
        if (stateId != null && !stateId.isBlank() && Files.exists(currentPath)) {
            JsonObject current = JsonParser.parseString(Files.readString(currentPath)).getAsJsonObject();
            String currentStateId = stringValue(current, "stateId");
            if (!stateId.equals(currentStateId)) {
                throw new IllegalArgumentException("D4_DESIGN_LOOP_STATE_STALE: requested " + stateId
                        + " but current state is " + currentStateId + ".");
            }
        }
        CityD4DesignLoopStatePlanner.WriteBackResult result = new CityD4DesignLoopStatePlanner()
                .writeBack(designLoopState);
        JsonObject response = result.asJson();
        response.add("artifacts", writeD4DesignLoopArtifacts(debugRoot, runDir, citySeedId, result.loopState()));
        return response;
    }

    static JsonObject handleExecuteD4ArrayLayoutItem(Path debugRoot, String runId, String citySeedId,
                                                     JsonObject terraSenseProfileSource,
                                                     String stateId,
                                                     JsonObject nextArrayLayoutPlanItem,
                                                     JsonObject arrayLayoutLoopStateSource,
                                                     JsonObject structureEnvelopeFactsSource) throws IOException {
        Path runDir = debugRoot.resolve(runId);
        loadCitySeed(runDir, runId, citySeedId);
        CityLandformReviewPackage reviewPackage = loadD3Package(debugRoot, runDir, citySeedId);
        JsonObject currentState = loadArrayLayoutLoopState(debugRoot, runDir, citySeedId, arrayLayoutLoopStateSource);
        String currentStateId = stringValue(currentState, "stateId");
        if (stateId != null && !stateId.isBlank() && !stateId.equals(currentStateId)) {
            throw new IllegalArgumentException("D4_ARRAY_LAYOUT_LOOP_STATE_STALE: requested " + stateId
                    + " but current state is " + currentStateId + ".");
        }
        Path envelopeFactsPath = envelopeFactsPath(runDir, citySeedId, structureEnvelopeFactsSource);
        CityStructureEnvelopeFacts envelopeFacts = CityStructureEnvelopeFacts.load(envelopeFactsPath);
        CityStructureArrayLayoutLoopPlanner.ExecuteResult result = new CityStructureArrayLayoutLoopPlanner()
                .execute(runDir, reviewPackage, terraSenseProfileSource, currentState,
                        nextArrayLayoutPlanItem, envelopeFacts);
        JsonObject response = result.asJson();
        response.add("artifacts", writeD4ArrayLayoutLoopArtifacts(debugRoot, runDir, citySeedId,
                result.loopState(), reviewPackage, envelopeFactsPath));
        return response;
    }

    static JsonObject handleQueryD4ArrayExpansionSpace(Path debugRoot, String runId, String citySeedId,
                                                       String stateId,
                                                       JsonObject expansionRequest,
                                                       JsonObject arrayLayoutLoopStateSource) throws IOException {
        Path runDir = debugRoot.resolve(runId);
        loadCitySeed(runDir, runId, citySeedId);
        CityLandformReviewPackage reviewPackage = loadD3Package(debugRoot, runDir, citySeedId);
        JsonObject currentState = loadArrayLayoutLoopState(debugRoot, runDir, citySeedId, arrayLayoutLoopStateSource);
        requireCurrentArrayLayoutState(stateId, currentState);
        CityStructureArrayLayoutLoopPlanner.ExpansionSpaceResult result = new CityStructureArrayLayoutLoopPlanner()
                .queryExpansionSpace(reviewPackage, currentState, expansionRequest);
        JsonObject response = result.asJson();
        response.add("artifacts", writeD4ArrayExpansionSpaceArtifact(debugRoot, runDir, citySeedId,
                result.expansionSpace()));
        return response;
    }

    static JsonObject handlePlanD4ArrayExpansionCandidates(Path debugRoot, String runId, String citySeedId,
                                                            JsonObject terraSenseProfileSource,
                                                            String stateId,
                                                            JsonObject expansionRequest,
                                                            JsonObject arrayLayoutLoopStateSource,
                                                            JsonObject structureEnvelopeFactsSource) throws IOException {
        Path runDir = debugRoot.resolve(runId);
        loadCitySeed(runDir, runId, citySeedId);
        CityLandformReviewPackage reviewPackage = loadD3Package(debugRoot, runDir, citySeedId);
        JsonObject currentState = loadArrayLayoutLoopState(debugRoot, runDir, citySeedId, arrayLayoutLoopStateSource);
        requireCurrentArrayLayoutState(stateId, currentState);
        Path envelopeFactsPath = envelopeFactsPath(runDir, citySeedId, structureEnvelopeFactsSource);
        CityStructureEnvelopeFacts envelopeFacts = CityStructureEnvelopeFacts.load(envelopeFactsPath);
        CityStructureArrayLayoutLoopPlanner.ExpansionCandidateSetResult result =
                new CityStructureArrayLayoutLoopPlanner().planExpansionCandidates(runDir, reviewPackage,
                        terraSenseProfileSource, currentState, expansionRequest, envelopeFacts);
        JsonObject response = result.asJson();
        response.add("artifacts", writeD4ArrayExpansionCandidateArtifacts(debugRoot, runDir, citySeedId,
                result.candidateSet(), reviewPackage, envelopeFactsPath));
        return response;
    }

    static JsonObject handleSelectD4ArrayExpansionCandidate(Path debugRoot, String runId, String citySeedId,
                                                             String stateId,
                                                             String candidateId,
                                                             boolean autoSelectHighestScore,
                                                             String selectionReason,
                                                             JsonObject arrayExpansionCandidateSetSource,
                                                             JsonObject arrayLayoutLoopStateSource,
                                                             JsonObject structureEnvelopeFactsSource) throws IOException {
        Path runDir = debugRoot.resolve(runId);
        loadCitySeed(runDir, runId, citySeedId);
        CityLandformReviewPackage reviewPackage = loadD3Package(debugRoot, runDir, citySeedId);
        JsonObject currentState = loadArrayLayoutLoopState(debugRoot, runDir, citySeedId, arrayLayoutLoopStateSource);
        requireCurrentArrayLayoutState(stateId, currentState);
        JsonObject candidateSet = loadD4ArrayExpansionCandidateSet(debugRoot, runDir, citySeedId,
                arrayExpansionCandidateSetSource);
        CityStructureArrayLayoutLoopPlanner.ExpansionSelectionResult result =
                new CityStructureArrayLayoutLoopPlanner().selectExpansionCandidate(reviewPackage, currentState,
                        candidateSet, candidateId, autoSelectHighestScore, selectionReason);
        JsonObject response = result.asJson();
        Path envelopeFactsPath = envelopeFactsPath(runDir, citySeedId, structureEnvelopeFactsSource);
        JsonObject artifacts = writeD4ArrayLayoutLoopArtifacts(debugRoot, runDir, citySeedId,
                result.loopState(), reviewPackage, envelopeFactsPath);
        artifacts.add("arrayExpansionCandidates", d4ArrayExpansionCandidateArtifactRefs(debugRoot, runDir, citySeedId));
        response.add("artifacts", artifacts);
        return response;
    }

    static JsonObject handleFinalizeD4ArrayLayoutLoop(Path debugRoot, String runId, String citySeedId,
                                                      JsonObject terraSenseProfileSource,
                                                      String stateId,
                                                      JsonObject arrayLayoutLoopStateSource,
                                                      JsonObject structureEnvelopeFactsSource) throws IOException {
        Path runDir = debugRoot.resolve(runId);
        loadCitySeed(runDir, runId, citySeedId);
        JsonObject currentState = loadArrayLayoutLoopState(debugRoot, runDir, citySeedId, arrayLayoutLoopStateSource);
        String currentStateId = stringValue(currentState, "stateId");
        if (stateId != null && !stateId.isBlank() && !stateId.equals(currentStateId)) {
            throw new IllegalArgumentException("D4_ARRAY_LAYOUT_LOOP_STATE_STALE: requested " + stateId
                    + " but current state is " + currentStateId + ".");
        }
        CityStructureArrayLayoutLoopPlanner.FinalizeResult finalized =
                new CityStructureArrayLayoutLoopPlanner().finalizeLoop(currentState);
        JsonObject response = handlePlanD4(debugRoot, runId, citySeedId, terraSenseProfileSource,
                finalized.structureAnchorPlan(), structureEnvelopeFactsSource);
        CityLandformReviewPackage reviewPackage = loadD3Package(debugRoot, runDir, citySeedId);
        Path envelopeFactsPath = envelopeFactsPath(runDir, citySeedId, structureEnvelopeFactsSource);
        JsonObject loopArtifacts = writeD4ArrayLayoutLoopArtifacts(debugRoot, runDir, citySeedId,
                currentState, reviewPackage, envelopeFactsPath);
        JsonObject artifacts = response.has("artifacts") && response.get("artifacts").isJsonObject()
                ? response.getAsJsonObject("artifacts") : new JsonObject();
        for (Map.Entry<String, JsonElement> entry : loopArtifacts.entrySet()) {
            artifacts.add(entry.getKey(), entry.getValue().deepCopy());
        }
        response.add("artifacts", artifacts);
        response.addProperty("planningMode", stringValue(currentState, "planningMode",
                CityStructureArrayLayoutLoopPlanner.PLANNING_MODE_V02));
        response.add("arrayLayoutLoopState", currentState.deepCopy());
        response.add("arrayLayoutFinalizedPlan", finalized.structureAnchorPlan().deepCopy());
        return response;
    }

    static JsonObject handlePlanD4StructureClusterGroups(Path debugRoot, String runId, String citySeedId,
                                                         JsonObject terraSenseProfileSource,
                                                         JsonObject designSlotPlan,
                                                         JsonObject structureEnvelopeFactsSource,
                                                         Integer requestedGroupCount,
                                                         Integer requestedCandidatesPerSlot,
                                                         Integer requestedBeamWidth) throws IOException {
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
        Path envelopeFactsPath = envelopeFactsPath(runDir, citySeedId, structureEnvelopeFactsSource);
        CityStructureEnvelopeFacts envelopeFacts = CityStructureEnvelopeFacts.load(envelopeFactsPath);
        CityStructureClusterGroupCandidatePlanner.Options options =
                new CityStructureClusterGroupCandidatePlanner.Options(
                        requestedGroupCount == null ? 0 : requestedGroupCount,
                        requestedCandidatesPerSlot == null ? 0 : requestedCandidatesPerSlot,
                        requestedBeamWidth == null ? 0 : requestedBeamWidth);
        CityStructureClusterGroupCandidatePlanner.Result result =
                new CityStructureClusterGroupCandidatePlanner()
                        .plan(runDir, reviewPackage, terraSenseProfileSource, designSlotPlan,
                                envelopeFacts, options);

        Path outputDirectory = runDir.resolve("city_d4_structure_cluster_groups_" + safeFileName(citySeedId));
        Files.createDirectories(outputDirectory);
        Path slotPlanPath = outputDirectory.resolve("design_slot_plan.json");
        Path candidateSetPath = outputDirectory.resolve("structure_cluster_group_candidate_set.json");
        Path qualityPath = outputDirectory.resolve("quality_report.json");
        Files.writeString(slotPlanPath, CityJson.GSON.toJson(result.designSlotPlan()));
        Files.writeString(candidateSetPath, CityJson.GSON.toJson(result.structureClusterGroupCandidateSet()));
        Files.writeString(qualityPath, CityJson.GSON.toJson(result.qualityReport()));
        Path previewPath = new CityStructureLandingPreviewRenderer()
                .renderD4StructureClusterGroupCandidates(result.structureClusterGroupCandidateSet(),
                        reviewPackage, outputDirectory);

        JsonObject response = result.asJson();
        JsonObject artifacts = new JsonObject();
        artifacts.addProperty("designSlotPlan", debugRef(debugRoot, slotPlanPath));
        artifacts.addProperty("structureClusterGroupCandidateSet", debugRef(debugRoot, candidateSetPath));
        artifacts.addProperty("structureClusterGroupCandidatesPreview", debugRef(debugRoot, previewPath));
        artifacts.addProperty("qualityReport", debugRef(debugRoot, qualityPath));
        artifacts.addProperty("sourceD3Package", debugRef(debugRoot, d3PackagePath));
        if (envelopeFactsPath != null && Files.exists(envelopeFactsPath)) {
            artifacts.addProperty("sourceStructureEnvelopeFacts", debugRef(debugRoot, envelopeFactsPath));
        }
        response.add("artifacts", artifacts);
        return response;
    }

    static JsonObject handleSelectD4StructureClusterGroup(Path debugRoot, String runId, String citySeedId,
                                                          JsonObject terraSenseProfileSource,
                                                          String groupCandidateId,
                                                          JsonObject structureEnvelopeFactsSource,
                                                          JsonObject structureClusterGroupCandidateSetSource)
            throws IOException {
        Path runDir = debugRoot.resolve(runId);
        loadCitySeed(runDir, runId, citySeedId);
        Path candidateSetPath = structureClusterGroupCandidateSetPath(runDir, citySeedId,
                structureClusterGroupCandidateSetSource);
        if (!Files.exists(candidateSetPath)) {
            throw new IllegalArgumentException("D4 structure_cluster_group_candidate_set.json not found. "
                    + "Run city_plan_d4_structure_cluster_groups first: "
                    + debugRef(debugRoot, candidateSetPath));
        }
        JsonObject candidateSet = JsonParser.parseString(Files.readString(candidateSetPath)).getAsJsonObject();
        JsonObject selectedGroup = findStructureClusterGroup(candidateSet, groupCandidateId);
        JsonObject structureAnchorPlan = selectedGroup.getAsJsonObject("expandedStructureAnchorPlan").deepCopy();

        JsonObject response = handlePlanD4(debugRoot, runId, citySeedId, terraSenseProfileSource,
                structureAnchorPlan, structureEnvelopeFactsSource);
        response.add("selectedStructureClusterGroup", selectedGroup.deepCopy());
        response.add("structureClusterGroupCandidateSet", candidateSet.deepCopy());
        JsonObject artifacts = response.getAsJsonObject("artifacts");
        artifacts.addProperty("sourceStructureClusterGroupCandidateSet", debugRef(debugRoot, candidateSetPath));
        return response;
    }

    static JsonObject handleSelectD4Candidates(Path debugRoot, String runId, String citySeedId,
                                               JsonObject terraSenseProfileSource,
                                               JsonObject anchorSelectionPlan,
                                               JsonObject structureEnvelopeFactsSource,
                                               JsonObject anchorCandidateSetSource) throws IOException {
        Path runDir = debugRoot.resolve(runId);
        loadCitySeed(runDir, runId, citySeedId);
        Path candidateSetPath = anchorCandidateSetPath(runDir, citySeedId, anchorCandidateSetSource);
        if (!Files.exists(candidateSetPath)) {
            throw new IllegalArgumentException("D4 anchor_candidate_set.json not found. Run city_plan_d4_candidates first: "
                    + debugRef(debugRoot, candidateSetPath));
        }
        JsonObject candidateSet = JsonParser.parseString(Files.readString(candidateSetPath)).getAsJsonObject();
        JsonObject structureAnchorPlan = new CityStructureAnchorCandidatePlanner()
                .select(candidateSet, anchorSelectionPlan);

        JsonObject response = handlePlanD4(debugRoot, runId, citySeedId, terraSenseProfileSource,
                structureAnchorPlan, structureEnvelopeFactsSource);
        response.add("anchorSelectionPlan", anchorSelectionPlan.deepCopy());
        response.add("anchorCandidateSet", candidateSet.deepCopy());
        JsonObject artifacts = response.getAsJsonObject("artifacts");
        artifacts.addProperty("sourceAnchorCandidateSet", debugRef(debugRoot, candidateSetPath));
        return response;
    }

    static JsonObject handleCreateD4CandidateSession(Path debugRoot, String runId, String citySeedId,
                                                     JsonObject terraSenseProfileSource,
                                                     JsonObject designSlotPlan,
                                                     JsonObject structureEnvelopeFactsSource,
                                                     String sessionId) throws IOException {
        Path runDir = debugRoot.resolve(runId);
        loadCitySeed(runDir, runId, citySeedId);
        CityLandformReviewPackage reviewPackage = loadD3Package(debugRoot, runDir, citySeedId);
        CityStructureAnchorCandidatePlanner.SessionResult result = new CityStructureAnchorCandidatePlanner()
                .createSession(runDir, reviewPackage, terraSenseProfileSource, designSlotPlan, sessionId);

        Path outputDirectory = d4SessionDir(runDir, citySeedId);
        Files.createDirectories(outputDirectory);
        Path sessionPath = outputDirectory.resolve("d4_candidate_session.json");
        Path slotPlanPath = outputDirectory.resolve("design_slot_plan.json");
        Path designTimePath = outputDirectory.resolve("d4_design_time_report.json");
        Path qualityPath = outputDirectory.resolve("quality_report.json");
        Path tracePath = outputDirectory.resolve("d4_candidate_session_trace.json");
        Files.writeString(sessionPath, CityJson.GSON.toJson(result.session()));
        Files.writeString(slotPlanPath, CityJson.GSON.toJson(designSlotPlan));
        Files.writeString(designTimePath, CityJson.GSON.toJson(result.designTimeReport()));
        Files.writeString(qualityPath, CityJson.GSON.toJson(result.qualityReport()));
        Files.writeString(tracePath, CityJson.GSON.toJson(result.session()));

        JsonObject response = result.asJson();
        response.add("artifacts", d4SessionArtifacts(debugRoot, outputDirectory, sessionPath,
                slotPlanPath, null, null, designTimePath, qualityPath, tracePath));
        Path envelopeFactsPath = envelopeFactsPath(runDir, citySeedId, structureEnvelopeFactsSource);
        if (envelopeFactsPath != null && Files.exists(envelopeFactsPath)) {
            response.getAsJsonObject("artifacts").addProperty("sourceStructureEnvelopeFacts",
                    debugRef(debugRoot, envelopeFactsPath));
        }
        return response;
    }

    static JsonObject handlePlanD4NextCandidates(Path debugRoot, String runId, String citySeedId,
                                                 JsonObject structureEnvelopeFactsSource) throws IOException {
        Path runDir = debugRoot.resolve(runId);
        loadCitySeed(runDir, runId, citySeedId);
        CityLandformReviewPackage reviewPackage = loadD3Package(debugRoot, runDir, citySeedId);
        Path outputDirectory = d4SessionDir(runDir, citySeedId);
        Path sessionPath = outputDirectory.resolve("d4_candidate_session.json");
        if (!Files.exists(sessionPath)) {
            throw new IllegalArgumentException("D4_CANDIDATE_SESSION_NOT_FOUND: run city_create_d4_candidate_session first: "
                    + debugRef(debugRoot, sessionPath));
        }
        JsonObject session = JsonParser.parseString(Files.readString(sessionPath)).getAsJsonObject();
        Path envelopeFactsPath = envelopeFactsPath(runDir, citySeedId, structureEnvelopeFactsSource);
        CityStructureEnvelopeFacts envelopeFacts = CityStructureEnvelopeFacts.load(envelopeFactsPath);
        CityStructureAnchorCandidatePlanner.NextCandidateResult result = new CityStructureAnchorCandidatePlanner()
                .planNext(runDir, reviewPackage, session, envelopeFacts);

        Files.createDirectories(outputDirectory);
        Path candidatePath = outputDirectory.resolve("slot_candidate_set.json");
        Path sessionTracePath = outputDirectory.resolve("d4_candidate_session_trace.json");
        Path qualityPath = outputDirectory.resolve("quality_report.json");
        Files.writeString(sessionPath, CityJson.GSON.toJson(result.session()));
        Files.writeString(candidatePath, CityJson.GSON.toJson(result.slotCandidateSet()));
        Files.writeString(sessionTracePath, CityJson.GSON.toJson(result.session()));
        Files.writeString(qualityPath, CityJson.GSON.toJson(result.qualityReport()));
        Path previewPath = new CityStructureLandingPreviewRenderer()
                .renderD4Candidates(result.slotCandidateSet(), reviewPackage, outputDirectory);

        JsonObject response = result.asJson();
        response.add("artifacts", d4SessionArtifacts(debugRoot, outputDirectory, sessionPath,
                outputDirectory.resolve("design_slot_plan.json"), candidatePath, previewPath,
                outputDirectory.resolve("d4_design_time_report.json"), qualityPath, sessionTracePath));
        if (envelopeFactsPath != null && Files.exists(envelopeFactsPath)) {
            response.getAsJsonObject("artifacts").addProperty("sourceStructureEnvelopeFacts",
                    debugRef(debugRoot, envelopeFactsPath));
        }
        return response;
    }

    static JsonObject handleSelectD4Candidate(Path debugRoot, String runId, String citySeedId,
                                              String sessionId,
                                              String slotId,
                                              String candidateId,
                                              String anchorId,
                                              String selectionReason,
                                              boolean quickPreflight) throws IOException {
        Path runDir = debugRoot.resolve(runId);
        loadCitySeed(runDir, runId, citySeedId);
        Path outputDirectory = d4SessionDir(runDir, citySeedId);
        Path sessionPath = outputDirectory.resolve("d4_candidate_session.json");
        Path candidatePath = outputDirectory.resolve("slot_candidate_set.json");
        if (!Files.exists(sessionPath)) {
            throw new IllegalArgumentException("D4_CANDIDATE_SESSION_NOT_FOUND: run city_create_d4_candidate_session first: "
                    + debugRef(debugRoot, sessionPath));
        }
        if (!Files.exists(candidatePath)) {
            throw new IllegalArgumentException("D4_SELECTED_CANDIDATE_NOT_FOUND: run city_plan_d4_next_candidates first: "
                    + debugRef(debugRoot, candidatePath));
        }
        JsonObject session = JsonParser.parseString(Files.readString(sessionPath)).getAsJsonObject();
        if (sessionId != null && !sessionId.isBlank() && !sessionId.equals(stringValue(session, "sessionId"))) {
            throw new IllegalArgumentException("D4_CANDIDATE_SESSION_NOT_FOUND: sessionId mismatch.");
        }
        JsonObject candidateSet = JsonParser.parseString(Files.readString(candidatePath)).getAsJsonObject();
        CityStructureAnchorCandidatePlanner.SelectionResult result = new CityStructureAnchorCandidatePlanner()
                .selectSession(session, candidateSet, slotId, candidateId, anchorId, selectionReason, quickPreflight);

        Path designTimePath = outputDirectory.resolve("d4_design_time_report.json");
        Path sessionTracePath = outputDirectory.resolve("d4_candidate_session_trace.json");
        Files.writeString(sessionPath, CityJson.GSON.toJson(result.session()));
        Files.writeString(designTimePath, CityJson.GSON.toJson(result.designTimeReport()));
        Files.writeString(sessionTracePath, CityJson.GSON.toJson(result.session()));

        JsonObject response = result.asJson();
        response.add("artifacts", d4SessionArtifacts(debugRoot, outputDirectory, sessionPath,
                outputDirectory.resolve("design_slot_plan.json"), candidatePath,
                outputDirectory.resolve("anchor_candidate_preview.png"), designTimePath,
                outputDirectory.resolve("quality_report.json"), sessionTracePath));
        return response;
    }

    static JsonObject handleFinalizeD4CandidateSession(Path debugRoot, String runId, String citySeedId,
                                                       JsonObject terrasenseProfileSource,
                                                       JsonObject structureEnvelopeFactsSource,
                                                       String sessionId) throws IOException {
        Path runDir = debugRoot.resolve(runId);
        loadCitySeed(runDir, runId, citySeedId);
        Path outputDirectory = d4SessionDir(runDir, citySeedId);
        Path sessionPath = outputDirectory.resolve("d4_candidate_session.json");
        if (!Files.exists(sessionPath)) {
            throw new IllegalArgumentException("D4_CANDIDATE_SESSION_NOT_FOUND: run city_create_d4_candidate_session first: "
                    + debugRef(debugRoot, sessionPath));
        }
        JsonObject session = JsonParser.parseString(Files.readString(sessionPath)).getAsJsonObject();
        if (sessionId != null && !sessionId.isBlank() && !sessionId.equals(stringValue(session, "sessionId"))) {
            throw new IllegalArgumentException("D4_CANDIDATE_SESSION_NOT_FOUND: sessionId mismatch.");
        }
        CityStructureAnchorCandidatePlanner.FinalizeResult result = new CityStructureAnchorCandidatePlanner()
                .finalizeSession(session);

        Path structureAnchorPlanPath = outputDirectory.resolve("structure_anchor_plan.json");
        Path designTimePath = outputDirectory.resolve("d4_design_time_report.json");
        Path sessionTracePath = outputDirectory.resolve("d4_candidate_session_trace.json");
        Files.writeString(structureAnchorPlanPath, CityJson.GSON.toJson(result.structureAnchorPlan()));
        Files.writeString(designTimePath, CityJson.GSON.toJson(result.designTimeReport()));
        Files.writeString(sessionTracePath, CityJson.GSON.toJson(result.session()));

        JsonObject response = handlePlanD4(debugRoot, runId, citySeedId, terrasenseProfileSource,
                result.structureAnchorPlan(), structureEnvelopeFactsSource);
        response.addProperty("sessionId", stringValue(session, "sessionId"));
        response.addProperty("status", "finalized");
        response.add("d4CandidateSession", session.deepCopy());
        response.add("d4DesignTimeReport", result.designTimeReport().deepCopy());
        response.add("structureAnchorPlanFromSession", result.structureAnchorPlan().deepCopy());
        JsonObject artifacts = response.getAsJsonObject("artifacts");
        artifacts.addProperty("d4CandidateSession", debugRef(debugRoot, sessionPath));
        artifacts.addProperty("d4DesignTimeReport", debugRef(debugRoot, designTimePath));
        artifacts.addProperty("d4CandidateSessionTrace", debugRef(debugRoot, sessionTracePath));
        artifacts.addProperty("sessionStructureAnchorPlan", debugRef(debugRoot, structureAnchorPlanPath));
        return response;
    }

    static JsonObject handleProfileStructureEnvelopes(Path debugRoot, String runId, String citySeedId,
                                                       JsonObject terraSenseProfileSource,
                                                       JsonArray structureIds,
                                                       int sampleCount,
                                                       MinecraftServerHolder serverHolder,
                                                       ServerLevel level) throws IOException {
        return handleProfileStructureEnvelopes(debugRoot, runId, citySeedId, terraSenseProfileSource,
                structureIds, sampleCount, false, serverHolder, level);
    }

    static JsonObject handleProfileStructureEnvelopes(Path debugRoot, String runId, String citySeedId,
                                                       JsonObject terraSenseProfileSource,
                                                       JsonArray structureIds,
                                                       int sampleCount,
                                                       boolean forceRefresh,
                                                       MinecraftServerHolder serverHolder,
                                                       ServerLevel level) throws IOException {
        Path runDir = debugRoot.resolve(runId);
        JsonObject seed = loadCitySeed(runDir, runId, citySeedId);
        int anchorBlockX = blockCoord(seed, "x", 4);
        int anchorBlockZ = blockCoord(seed, "z", 4);
        List<String> ids = new ArrayList<>();
        if (structureIds != null) {
            for (JsonElement elem : structureIds) {
                if (!elem.isJsonNull()) {
                    ids.add(elem.getAsString());
                }
            }
        }
        CityStructureEnvelopeProfiler.StructureEnvelopeSampler sampler = serverHolder == null
                ? (profile, sampleIndex) -> CityStructureEnvelopeProfiler.EnvelopeSample.invalid(sampleIndex,
                "CONFIGURED_STRUCTURE_REGISTRY_MISSING", "", "")
                : new MinecraftCityStructureEnvelopeSampler(serverHolder.server(), level, anchorBlockX, anchorBlockZ);
        Path outputDirectory = runDir.resolve("city_structure_envelopes_" + safeFileName(citySeedId));
        Path cacheDirectory = runDir.resolve("city_structure_profile_cache_" + safeFileName(citySeedId));
        String contextProfileHash = CityStructureEnvelopeProfiler.sha256(CityJson.GSON.toJson(seed));
        CityStructureEnvelopeProfiler.Result result = new CityStructureEnvelopeProfiler()
                .profile(runDir, terraSenseProfileSource, ids, sampleCount, sampler,
                        CityStructureEnvelopeProfiler.CacheOptions.enabled(
                                cacheDirectory, contextProfileHash, forceRefresh));

        Files.createDirectories(outputDirectory);
        Path factsPath = outputDirectory.resolve("structure_envelope_facts.json");
        Path qualityPath = outputDirectory.resolve("quality_report.json");
        Files.writeString(factsPath, CityJson.GSON.toJson(result.structureEnvelopeFacts()));
        Files.writeString(qualityPath, CityJson.GSON.toJson(result.qualityReport()));
        Path previewPath = new CityStructureLandingPreviewRenderer()
                .renderEnvelopeFacts(result.structureEnvelopeFacts(), outputDirectory);

        JsonObject response = result.asJson();
        JsonObject artifacts = new JsonObject();
        artifacts.addProperty("structureEnvelopeFacts", debugRef(debugRoot, factsPath));
        artifacts.addProperty("structureEnvelopeProfilePreview", debugRef(debugRoot, previewPath));
        artifacts.addProperty("structureProfileCacheDirectory", debugRef(debugRoot, cacheDirectory));
        artifacts.addProperty("qualityReport", debugRef(debugRoot, qualityPath));
        response.add("artifacts", artifacts);
        return response;
    }

    static JsonObject handlePlanD5(Path debugRoot, String runId, String citySeedId) throws IOException {
        return handlePlanD5(debugRoot, runId, citySeedId, "v2",
                CityWallReservationPlanner.DEFAULT_WALL_MARGIN_BLOCKS,
                CityWallReservationPlanner.DEFAULT_SEGMENT_LENGTH_BLOCKS,
                CityWallReservationPlanner.DEFAULT_WALL_CORRIDOR_HALF_WIDTH_BLOCKS);
    }

    static JsonObject handlePlanD5(Path debugRoot, String runId, String citySeedId,
                                   String wallVersion,
                                   int wallMarginBlocks,
                                   int segmentLengthBlocks,
                                   int wallCorridorHalfWidthBlocks) throws IOException {
        return handlePlanD5(debugRoot, runId, citySeedId, wallVersion, wallMarginBlocks, segmentLengthBlocks,
                wallCorridorHalfWidthBlocks, CityWallReservationPlanner.V3Options.defaults());
    }

    static JsonObject handlePlanD5(Path debugRoot, String runId, String citySeedId,
                                   String wallVersion,
                                   int wallMarginBlocks,
                                   int segmentLengthBlocks,
                                   int wallCorridorHalfWidthBlocks,
                                   CityWallReservationPlanner.V3Options wallV3Options) throws IOException {
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
        Path anchorMapPath = d4Dir.resolve("structure_anchor_map.json");
        if (!Files.exists(d3PackagePath)) {
            throw new IllegalArgumentException("D3 package not found. Run city_plan_d3 first: "
                    + debugRef(debugRoot, d3PackagePath));
        }
        if (!Files.exists(anchorMapPath)) {
            rejectLegacyArtifacts(d4Dir, "D4");
            throw new IllegalArgumentException("D4 artifacts not found. Run city_plan_d4 first: "
                    + debugRef(debugRoot, d4Dir));
        }

        JsonObject anchorMap = JsonParser.parseString(Files.readString(anchorMapPath)).getAsJsonObject();
        CityLandformReviewPackage reviewPackage = CityLandformReviewPackage.fromJson(
                JsonParser.parseString(Files.readString(d3PackagePath)).getAsJsonObject());
        JsonObject wallReservationPlan = new CityWallReservationPlanner().plan(
                reviewPackage, anchorMap, wallVersion, wallMarginBlocks, segmentLengthBlocks,
                wallCorridorHalfWidthBlocks, wallV3Options);
        CityReservationMaskPlanner.Result result = new CityReservationMaskPlanner().plan(ctx, anchorMap,
                wallReservationPlan);

        Path outputDirectory = runDir.resolve("city_d5_" + safeFileName(citySeedId));
        Files.createDirectories(outputDirectory);
        Path maskPath = outputDirectory.resolve("reservation_mask_plan.json");
        Path wallReservationPath = outputDirectory.resolve("wall_reservation_plan.json");
        Path roadPath = outputDirectory.resolve("road_access_plan.json");
        Path operationPath = outputDirectory.resolve("build_operation_plan.json");
        Path qualityPath = outputDirectory.resolve("quality_report.json");
        Files.writeString(maskPath, CityJson.GSON.toJson(result.reservationMaskPlan()));
        Files.writeString(wallReservationPath, CityJson.GSON.toJson(wallReservationPlan));
        Files.writeString(roadPath, CityJson.GSON.toJson(result.roadAccessPlan()));
        Files.writeString(operationPath, CityJson.GSON.toJson(result.buildOperationPlan()));
        Files.writeString(qualityPath, CityJson.GSON.toJson(result.qualityReport()));

        Path previewPath = new CityStructureLandingPreviewRenderer()
                .renderD5(result.reservationMaskPlan(), outputDirectory);
        Path wallReservationPreviewPath = new CityWallPreviewRenderer()
                .renderReservation(wallReservationPlan, reviewPackage.asJson(), outputDirectory);

        JsonObject response = result.asJson();
        response.add("wallReservationPlan", wallReservationPlan);
        JsonObject artifacts = new JsonObject();
        artifacts.addProperty("reservationMaskPlan", debugRef(debugRoot, maskPath));
        artifacts.addProperty("wallReservationPlan", debugRef(debugRoot, wallReservationPath));
        artifacts.addProperty("roadAccessPlan", debugRef(debugRoot, roadPath));
        artifacts.addProperty("buildOperationPlan", debugRef(debugRoot, operationPath));
        artifacts.addProperty("reservationMaskPreview", debugRef(debugRoot, previewPath));
        artifacts.addProperty("wallReservationPreview", debugRef(debugRoot, wallReservationPreviewPath));
        artifacts.addProperty("qualityReport", debugRef(debugRoot, qualityPath));
        artifacts.addProperty("sourceD3Package", debugRef(debugRoot, d3PackagePath));
        artifacts.addProperty("sourceStructureAnchorMap", debugRef(debugRoot, anchorMapPath));
        response.add("artifacts", artifacts);
        return response;
    }

    static JsonObject handlePlanLandUse(Path debugRoot,
                                        String runId,
                                        String citySeedId,
                                        JsonObject optionalIntent) throws IOException {
        long started = System.nanoTime();
        Path runDir = debugRoot.resolve(runId);
        loadCitySeed(runDir, runId, citySeedId);
        Path outputDirectory = runDir.resolve("city_land_use_" + safeFileName(citySeedId));
        Path terrainPath = outputDirectory.resolve("land_use_terrain_field.json");
        Path d4AnchorMapPath = runDir.resolve("city_d4_" + safeFileName(citySeedId))
                .resolve("structure_anchor_map.json");
        Path d5MaskPath = runDir.resolve("city_d5_" + safeFileName(citySeedId))
                .resolve("reservation_mask_plan.json");
        Path d6Directory = runDir.resolve("city_d6_" + safeFileName(citySeedId));
        Path d6PlanPath = d6Directory.resolve("structure_materialization_plan.json");
        if (!Files.isRegularFile(terrainPath)) {
            throw new IllegalArgumentException("LAND_USE_TERRAIN_FIELD_MISSING: run city_plan_d3 first: "
                    + debugRef(debugRoot, terrainPath));
        }
        if (!Files.isRegularFile(d4AnchorMapPath)) {
            throw new IllegalArgumentException("LAND_USE_D4_PROVENANCE_MISSING: run city_plan_d4 first: "
                    + debugRef(debugRoot, d4AnchorMapPath));
        }
        if (!Files.isRegularFile(d5MaskPath)) {
            throw new IllegalArgumentException("LAND_USE_D5_PLAN_MISSING: run city_plan_d5 first: "
                    + debugRef(debugRoot, d5MaskPath));
        }
        if (!Files.isRegularFile(d6PlanPath)) {
            throw new IllegalArgumentException("LAND_USE_D6_PLAN_MISSING: run city_plan_d6 first: "
                    + debugRef(debugRoot, d6PlanPath));
        }
        JsonObject d4AnchorMap = JsonParser.parseString(Files.readString(d4AnchorMapPath)).getAsJsonObject();
        if (!"city_structure_anchor_map.v0.2".equals(stringValue(d4AnchorMap, "schemaVersion", ""))) {
            throw new IllegalArgumentException("LAND_USE_D4_V02_PROVENANCE_REQUIRED");
        }
        JsonObject d6Plan = JsonParser.parseString(Files.readString(d6PlanPath)).getAsJsonObject();
        validateLockedMaterializationPlan(d6Plan);
        JsonObject d5MaskPlan = JsonParser.parseString(Files.readString(d5MaskPath)).getAsJsonObject();
        String cityId = stringValue(d6Plan, "cityId", citySeedId);
        LandUseTerrainField terrainField = new LandUseTerrainFieldCodec().fromJson(
                JsonParser.parseString(Files.readString(terrainPath)).getAsJsonObject());
        if (!cityId.equals(terrainField.cityId())) {
            throw new IllegalArgumentException("LAND_USE_TERRAIN_CITY_ID_MISMATCH: expected "
                    + cityId + " but found " + terrainField.cityId());
        }

        LandUseSettings settings = loadLandUseSettings();
        LandUseRuleCatalog rules = LandUseRuleCatalog.defaults();
        JsonObject functionalArrayZones = loadOptionalLandUseFunctionalArrayZones(runDir, citySeedId);
        Path completePath = outputDirectory.resolve("city_land_use_planning_complete.json");
        Files.deleteIfExists(completePath);
        LandUsePlanningService.Result result = new LandUsePlanningService().plan(
                d6Plan, optionalIntent, functionalArrayZones, d5MaskPlan, terrainField, rules);
        LandUseAreaPlanCodec planCodec = new LandUseAreaPlanCodec();
        JsonObject planJson = planCodec.toJson(result.plan());
        Path planPath = outputDirectory.resolve("city_land_use_area_plan.json");
        Path tracePath = outputDirectory.resolve("land_use_plan_trace.json");
        Path qualityPath = outputDirectory.resolve("quality_report.json");
        Files.createDirectories(outputDirectory);
        Files.writeString(planPath, CityJson.GSON.toJson(planJson));
        Files.writeString(tracePath, CityJson.GSON.toJson(result.trace()));
        Files.writeString(qualityPath, CityJson.GSON.toJson(result.quality()));
        JsonObject preview = new CityLandUsePreviewRenderer().render(
                terrainField, result.plan(), outputDirectory);
        Path previewPath = outputDirectory.resolve(stringValue(preview, "fileName", "land_use_preview.png"));

        JsonObject completion = new JsonObject();
        completion.addProperty("schemaVersion", "city_land_use_planning_complete.v0.1");
        completion.addProperty("cityId", cityId);
        completion.addProperty("planHash", result.plan().planHash());
        completion.addProperty("ruleProfileHash", rules.profileHash());
        completion.addProperty("sourceD6Hash", CityStructureEnvelopeProfiler.sha256(
                CityJson.GSON.toJson(d6Plan)));
        completion.addProperty("completedAt", Instant.now().toString());
        Files.deleteIfExists(decorationDir(runDir, citySeedId)
                .resolve("city_decoration_planning_complete.json"));
        writePlanningCompletion(completePath, completion);

        JsonObject response = new JsonObject();
        response.addProperty("ok", true);
        response.addProperty("explicitPlanning", true);
        response.addProperty("workflowEnabledByDefault", settings.enabledInWorkflow());
        response.addProperty("profileId", settings.profileId());
        response.addProperty("ruleProfileHash", rules.profileHash());
        response.addProperty("areaCount", result.plan().areas().size());
        response.addProperty("planHash", result.plan().planHash());
        response.add("landUseAreaPlan", planJson);
        response.add("qualityReport", result.quality());
        response.add("landUsePreview", preview);
        response.add("timingMs", timing(started));
        JsonObject artifacts = new JsonObject();
        artifacts.addProperty("landUseTerrainField", debugRef(debugRoot, terrainPath));
        artifacts.addProperty("landUseAreaPlan", debugRef(debugRoot, planPath));
        artifacts.addProperty("landUsePlanTrace", debugRef(debugRoot, tracePath));
        artifacts.addProperty("qualityReport", debugRef(debugRoot, qualityPath));
        artifacts.addProperty("landUsePreview", debugRef(debugRoot, previewPath));
        artifacts.addProperty("planningComplete", debugRef(debugRoot, completePath));
        artifacts.addProperty("sourceD4AnchorMap", debugRef(debugRoot, d4AnchorMapPath));
        artifacts.addProperty("sourceD5ReservationMaskPlan", debugRef(debugRoot, d5MaskPath));
        artifacts.addProperty("sourceD6MaterializationPlan", debugRef(debugRoot, d6PlanPath));
        response.add("artifacts", artifacts);
        return response;
    }

    static JsonObject handleExecuteD5(Path debugRoot, Path serverRoot, String runId, String citySeedId,
                                      boolean confirmWorldMutation, ServerLevel level,
                                      String requestedRoadProvider) throws IOException {
        return handleExecuteD5(debugRoot, serverRoot, runId, citySeedId, confirmWorldMutation, level,
                requestedRoadProvider, null, null);
    }

    static JsonObject handleExecuteD5(Path debugRoot, Path serverRoot, String runId, String citySeedId,
                                      boolean confirmWorldMutation, ServerLevel level,
                                      String requestedRoadProvider, Path requestedDecorationCatalogRoot) throws IOException {
        return handleExecuteD5(debugRoot, serverRoot, runId, citySeedId, confirmWorldMutation, level,
                requestedRoadProvider, requestedDecorationCatalogRoot, null);
    }

    static JsonObject handleExecuteD5(Path debugRoot, Path serverRoot, String runId, String citySeedId,
                                      boolean confirmWorldMutation, ServerLevel level,
                                      String requestedRoadProvider, Path requestedDecorationCatalogRoot,
                                      Boolean requestedLandUseLayer) throws IOException {
        long started = System.nanoTime();
        if (!confirmWorldMutation) {
            throw new IllegalArgumentException("confirmWorldMutation=true is required for city_execute_d5.");
        }
        String roadProvider = CityRoadWeaverBridge.normalizeProvider(requestedRoadProvider);
        Path runDir = debugRoot.resolve(runId);
        loadCitySeed(runDir, runId, citySeedId);
        Path d4Dir = runDir.resolve("city_d4_" + safeFileName(citySeedId));
        Path d5Dir = runDir.resolve("city_d5_" + safeFileName(citySeedId));
        Path maskPath = d5Dir.resolve("reservation_mask_plan.json");
        Path operationPath = d5Dir.resolve("build_operation_plan.json");
        Path d6PlanPath = runDir.resolve("city_d6_" + safeFileName(citySeedId))
                .resolve("structure_materialization_plan.json");
        Path landUseDirectory = runDir.resolve("city_land_use_" + safeFileName(citySeedId));
        Path landUsePlanPath = landUseDirectory.resolve("city_land_use_area_plan.json");
        Path landUseCompletePath = landUseDirectory.resolve("city_land_use_planning_complete.json");
        Path anchorMapPath = d4Dir.resolve("structure_anchor_map.json");
        if (!Files.exists(anchorMapPath)) {
            rejectLegacyArtifacts(d4Dir, "D4");
            throw new IllegalArgumentException("D4 structure_anchor_map.json not found. Run city_plan_d4 first: "
                    + debugRef(debugRoot, anchorMapPath));
        }
        if (!Files.exists(maskPath)) {
            rejectLegacyArtifacts(d5Dir, "D5");
            throw new IllegalArgumentException("D5 reservation_mask_plan.json not found. Run city_plan_d5 first: "
                    + debugRef(debugRoot, maskPath));
        }
        if (!Files.exists(operationPath)) {
            throw new IllegalArgumentException("D5 build_operation_plan.json not found. Run city_plan_d5 first: "
                    + debugRef(debugRoot, operationPath));
        }
        if (!Files.exists(d6PlanPath)) {
            throw new IllegalArgumentException("D6 locked structure_materialization_plan.json not found. "
                    + "Run city_plan_d6 before city_execute_d5: " + debugRef(debugRoot, d6PlanPath));
        }
        if (!CityReservationMaskRegistry.hooksAvailable()) {
            throw new IllegalArgumentException("CITY_MASK_HOOK_UNAVAILABLE: required City reservation mixins are not available.");
        }
        JsonObject maskPlan = JsonParser.parseString(Files.readString(maskPath)).getAsJsonObject();
        JsonObject materializationPlan = JsonParser.parseString(Files.readString(d6PlanPath)).getAsJsonObject();
        validateLockedMaterializationPlan(materializationPlan);
        JsonObject activeMaskPlan = maskPlanWithLockedEnvelopes(maskPlan, materializationPlan);
        boolean landUsePlanExists = Files.isRegularFile(landUsePlanPath);
        boolean landUseCompleteExists = Files.isRegularFile(landUseCompletePath);
        boolean landUseWorldgenMode = requestedLandUseLayer == null
                ? landUsePlanExists && landUseCompleteExists : requestedLandUseLayer;
        if (!Boolean.FALSE.equals(requestedLandUseLayer) && landUsePlanExists != landUseCompleteExists) {
            throw new IllegalArgumentException("CITY_LAND_USE_PLAN_INCOMPLETE: area plan and completion marker "
                    + "must both exist; rerun city_plan_land_use.");
        }
        if (landUseWorldgenMode && !landUsePlanExists) {
            throw new IllegalArgumentException("CITY_LAND_USE_PLAN_MISSING: run city_plan_land_use first.");
        }
        LandUseAreaPlan landUsePlan = null;
        if (landUseWorldgenMode) {
            landUsePlan = new LandUseAreaPlanCodec().fromJson(
                    JsonParser.parseString(Files.readString(landUsePlanPath)).getAsJsonObject());
            String expectedCityId = stringValue(materializationPlan, "cityId", citySeedId);
            if (!expectedCityId.equals(landUsePlan.cityId())) {
                throw new IllegalArgumentException("CITY_LAND_USE_CITY_ID_MISMATCH: expected "
                        + expectedCityId + " but found " + landUsePlan.cityId());
            }
            validateLandUseCompletion(JsonParser.parseString(Files.readString(landUseCompletePath))
                    .getAsJsonObject(), landUsePlan, expectedCityId,
                    LandUseRuleCatalog.defaults().profileHash(),
                    CityStructureEnvelopeProfiler.sha256(CityJson.GSON.toJson(materializationPlan)));
        }
        Path decorationDirectory = decorationDir(runDir, citySeedId);
        rejectLegacyDressingArtifacts(decorationDirectory, false);
        rejectLegacyDressingArtifacts(runDir.resolve("city_dressing_" + safeFileName(citySeedId)), true);
        Path compiledDecorationPath = decorationDirectory.resolve("city_decoration_compiled_program_plan.json");
        Path decorationSlotProjectionPath = decorationDirectory.resolve("city_decoration_slot_projection.json");
        Path decorationCompletePath = decorationDirectory.resolve("city_decoration_planning_complete.json");
        boolean compiledDecorationExists = Files.isRegularFile(compiledDecorationPath);
        boolean decorationSlotProjectionExists = Files.isRegularFile(decorationSlotProjectionPath);
        boolean decorationCompleteExists = Files.isRegularFile(decorationCompletePath);
        if ((compiledDecorationExists && (!decorationCompleteExists || !decorationSlotProjectionExists))
                || (!compiledDecorationExists && decorationCompleteExists)) {
            throw new IllegalArgumentException("CITY_DECORATION_PLAN_INCOMPLETE: compiled plan, slot projection and "
                    + "completion marker must all exist; rerun city_plan_city_dressing.");
        }
        boolean decorationWorldgenMode = compiledDecorationExists;
        Path decorationCatalogRoot = requestedDecorationCatalogRoot;
        if (decorationWorldgenMode && decorationCatalogRoot == null) {
            decorationCatalogRoot = defaultDecorationCatalogRoot();
        } else if (decorationCatalogRoot == null) {
            decorationCatalogRoot = optionalDefaultDecorationCatalogRoot();
        }
        RunMetadata metadata = loadRunMetadata(runDir, null, "");
        CityLandUseChunkStatusPreflight.PreflightResult landUseChunkPreflight = null;
        if (landUsePlan != null) {
            CityLandUseWorldgenRegistry.preflightActivate(metadata.dimensionId(), landUsePlan, serverRoot);
            landUseChunkPreflight = CityLandUseWorldgenRegistry.preflightChunkStatus(landUsePlan,
                    new CityLandUseChunkStatusPreflight.MinecraftChunkStatusProbe(level));
            if (!landUseChunkPreflight.eligible()) {
                throw new IllegalArgumentException(landUseChunkPreflight.reasonCode()
                        + ": LandUse only applies during first worldgen FEATURES; ownerChunks="
                        + landUseChunkPreflight.ownerChunkCount() + ", featuresOrLater="
                        + landUseChunkPreflight.featuresOrLaterCount() + ", unknown="
                        + landUseChunkPreflight.unknownCount());
            }
        }
        CompiledDecorationProgramPlan compiledDecorationPlan = null;
        DecorationMaskCounts decorationMaskCounts = DecorationMaskCounts.empty();
        if (decorationWorldgenMode) {
            JsonObject completion = readDecorationCompletion(decorationCompletePath);
            JsonObject compiledDecorationJson = JsonParser.parseString(
                    Files.readString(compiledDecorationPath)).getAsJsonObject();
            compiledDecorationPlan = new CompiledDecorationProgramCodec().parsePlan(compiledDecorationJson);
            String expectedCityId = stringValue(materializationPlan, "cityId", citySeedId);
            if (!expectedCityId.equals(compiledDecorationPlan.cityId())) {
                throw new IllegalArgumentException("CITY_DECORATION_CITY_ID_MISMATCH: expected "
                        + expectedCityId + " but found " + compiledDecorationPlan.cityId());
            }
            validateDecorationCompletion(completion, compiledDecorationPlan, expectedCityId);
            CityDecorationContentCatalog catalog = new CityDecorationContentCatalogLoader()
                    .load(decorationCatalogRoot);
            if (!catalog.catalogHash().equals(compiledDecorationPlan.catalogHash())) {
                throw new IllegalArgumentException("CITY_DECORATION_CATALOG_HASH_MISMATCH: expected "
                        + catalog.catalogHash() + " but found " + compiledDecorationPlan.catalogHash());
            }
            validateCompiledDecorationContentRefs(compiledDecorationPlan, catalog);
            validateCompiledDecorationStyleProfile(compiledDecorationPlan, catalog, decorationCatalogRoot);
            JsonObject projection = JsonParser.parseString(Files.readString(decorationSlotProjectionPath))
                    .getAsJsonObject();
            List<DecorationSlot> decorationSlots = parseDecorationProjectionSlots(projection, compiledDecorationPlan,
                    "CITY_DECORATION_D5_SLOT_PROJECTION");
            validateDecorationSlotProjection(compiledDecorationPlan, decorationSlots,
                    "CITY_DECORATION_D5_SLOT_PROJECTION_MISMATCH");
            decorationMaskCounts = appendDecorationProjectionMasks(activeMaskPlan, compiledDecorationPlan,
                    decorationSlots);
        }
        String decorationCityId = stringValue(materializationPlan, "cityId", citySeedId);
        if (decorationWorldgenMode) {
            CityDecorationWorldgenRegistry.preflightActivate(metadata.dimensionId(), compiledDecorationPlan,
                    serverRoot, decorationCatalogRoot);
        } else {
            CityDecorationWorldgenRegistry.preflightDeactivate(metadata.dimensionId(), decorationCityId,
                    serverRoot, decorationCatalogRoot);
        }
        JsonObject roadWeaverConnectionPlan = CityRoadWeaverBridge.createConnectionPlan(materializationPlan);
        BuildOperationPlan plan = BuildOperationPlan.fromJson(
                JsonParser.parseString(Files.readString(operationPath)).getAsJsonObject());
        WorldMutationReport report = skippedWorldMutationReport(plan,
                "D5 active path only activates worldgen-time mask/planned-structure registry; "
                        + "WorldEdit road operations are deferred to avoid generating chunks before structures.");
        JsonObject roadWeaverRegistrationReport = CityRoadWeaverBridge.register(level, roadWeaverConnectionPlan,
                roadProvider);
        if (CityRoadWeaverBridge.PROVIDER_ROADWEAVER.equals(roadProvider)
                && !"registered".equals(stringValue(roadWeaverRegistrationReport, "status"))) {
            throw new IllegalArgumentException(stringValue(roadWeaverRegistrationReport, "reasonCode",
                    "ROADWEAVER_REGISTRATION_FAILED") + ": "
                    + stringValue(roadWeaverRegistrationReport, "message", ""));
        }
        JsonObject activeRegistry = CityReservationMaskRegistry.activate(activeMaskPlan, null, materializationPlan,
                runId, citySeedId, serverRoot);
        JsonObject activeDecorationSummary = decorationWorldgenMode
                ? CityDecorationWorldgenRegistry.activate(metadata.dimensionId(), compiledDecorationPlan,
                        serverRoot, decorationCatalogRoot)
                : CityDecorationWorldgenRegistry.deactivate(metadata.dimensionId(),
                        decorationCityId, serverRoot, decorationCatalogRoot);
        JsonObject activeLandUseSummary = landUsePlan != null
                ? CityLandUseWorldgenRegistry.activate(metadata.dimensionId(), landUsePlan, serverRoot)
                : CityLandUseWorldgenRegistry.deactivate(metadata.dimensionId(), decorationCityId, serverRoot);
        Files.createDirectories(d5Dir);
        Path reportPath = d5Dir.resolve("world_mutation_report.json");
        Path activeMaskPath = d5Dir.resolve("active_mask_summary.json");
        Path activePlannedPath = d5Dir.resolve("active_planned_structure_registry.json");
        Path roadWeaverPlanPath = d5Dir.resolve("roadweaver_connection_plan.json");
        Path roadWeaverReportPath = d5Dir.resolve("roadweaver_registration_report.json");
        Path roadProviderStatePath = d5Dir.resolve("road_provider_state.json");
        Path activeDecorationPath = d5Dir.resolve("active_city_decoration_summary.json");
        Path activeLandUsePath = d5Dir.resolve("active_city_land_use_summary.json");
        Files.writeString(reportPath, CityJson.GSON.toJson(report.asJson()));
        Files.writeString(activeMaskPath, CityJson.GSON.toJson(CityReservationMaskRegistry.activeSummary()));
        Files.writeString(activePlannedPath, CityJson.GSON.toJson(activeRegistry));
        Files.writeString(roadWeaverPlanPath, CityJson.GSON.toJson(roadWeaverConnectionPlan));
        Files.writeString(roadWeaverReportPath, CityJson.GSON.toJson(roadWeaverRegistrationReport));
        JsonObject roadProviderState = new JsonObject();
        roadProviderState.addProperty("schemaVersion", "city_road_provider_state.v0.1");
        roadProviderState.addProperty("roadProvider", roadProvider);
        roadProviderState.addProperty("roadWeaverRegistered",
                CityRoadWeaverBridge.roadWeaverRegistered(roadWeaverRegistrationReport));
        roadProviderState.addProperty("roadWeaverAvailable",
                booleanValue(roadWeaverRegistrationReport, "roadweaverAvailable", false));
        roadProviderState.addProperty("useWorldEditDebugFallback",
                CityRoadWeaverBridge.shouldRunWorldEditDebugFallback(roadProvider, roadWeaverRegistrationReport));
        roadProviderState.add("roadWeaverRegistrationReport", roadWeaverRegistrationReport.deepCopy());
        Files.writeString(roadProviderStatePath, CityJson.GSON.toJson(roadProviderState));
        Files.writeString(activeDecorationPath, CityJson.GSON.toJson(activeDecorationSummary));
        Files.writeString(activeLandUsePath, CityJson.GSON.toJson(activeLandUseSummary));

        JsonObject response = new JsonObject();
        response.addProperty("ok", report.failedOperations() == 0);
        response.add("activeMaskSummary", CityReservationMaskRegistry.activeSummary());
        response.addProperty("activePlannedStructureCount", CityReservationMaskRegistry.activePlannedStructureCount());
        response.addProperty("plannedStructureRegistryPath",
                CityReservationMaskRegistry.plannedRegistryPath(serverRoot).toString());
        response.addProperty("worldgenPlacementMode", true);
        response.addProperty("decorationWorldgenMode", decorationWorldgenMode);
        response.addProperty("decorationVegetationMaskCount", decorationMaskCounts.vegetationMaskCount());
        response.addProperty("decorationStructureMaskCount", decorationMaskCounts.structureMaskCount());
        response.addProperty("landUseWorldgenMode", landUsePlan != null);
        response.addProperty("landUseGeometryMaskDuplicated", false);
        response.addProperty("requiresLockedMaterializationPlan", true);
        response.addProperty("roadPlanningStage", "d7_after_worldgen_ledger");
        response.addProperty("roadProvider", roadProvider);
        response.addProperty("roadWeaverAvailable", CityRoadWeaverBridge.available());
        response.add("roadWeaverConnectionPlan", roadWeaverConnectionPlan);
        response.add("roadWeaverRegistrationReport", roadWeaverRegistrationReport);
        response.add("roadProviderState", roadProviderState);
        response.add("activeDecorationSummary", activeDecorationSummary);
        response.add("activeLandUseSummary", activeLandUseSummary);
        if (landUseChunkPreflight != null) {
            response.add("landUseChunkPreflight", landUseChunkPreflightJson(landUseChunkPreflight));
        }
        response.add("worldMutationReport", report.asJson());
        response.add("timingMs", timing(started));
        JsonObject artifacts = new JsonObject();
        artifacts.addProperty("reservationMaskPlan", debugRef(debugRoot, maskPath));
        artifacts.addProperty("buildOperationPlan", debugRef(debugRoot, operationPath));
        artifacts.addProperty("sourceStructureMaterializationPlan", debugRef(debugRoot, d6PlanPath));
        artifacts.addProperty("worldMutationReport", debugRef(debugRoot, reportPath));
        artifacts.addProperty("activeMaskSummary", debugRef(debugRoot, activeMaskPath));
        artifacts.addProperty("activePlannedStructureRegistry", debugRef(debugRoot, activePlannedPath));
        artifacts.addProperty("serverPlannedStructureRegistry",
                CityReservationMaskRegistry.plannedRegistryPath(serverRoot).toString());
        artifacts.addProperty("roadWeaverConnectionPlan", debugRef(debugRoot, roadWeaverPlanPath));
        artifacts.addProperty("roadWeaverRegistrationReport", debugRef(debugRoot, roadWeaverReportPath));
        artifacts.addProperty("roadProviderState", debugRef(debugRoot, roadProviderStatePath));
        artifacts.addProperty("activeDecorationSummary", debugRef(debugRoot, activeDecorationPath));
        artifacts.addProperty("activeLandUseSummary", debugRef(debugRoot, activeLandUsePath));
        if (decorationWorldgenMode) {
            artifacts.addProperty("sourceCompiledDecorationProgramPlan",
                    debugRef(debugRoot, compiledDecorationPath));
            artifacts.addProperty("sourceDecorationSlotProjection",
                    debugRef(debugRoot, decorationSlotProjectionPath));
            artifacts.addProperty("serverActiveDecorationPlans",
                    CityDecorationWorldgenRegistry.activePlansPath(serverRoot).toString());
            artifacts.addProperty("serverDecorationWorldgenLedger",
                    CityDecorationWorldgenRegistry.worldgenLedgerPath(serverRoot).toString());
        }
        if (landUsePlan != null) {
            artifacts.addProperty("sourceLandUseAreaPlan", debugRef(debugRoot, landUsePlanPath));
            artifacts.addProperty("sourceLandUsePlanningComplete", debugRef(debugRoot, landUseCompletePath));
            artifacts.addProperty("serverActiveLandUsePlans",
                    CityLandUseWorldgenRegistry.activePlansPath(serverRoot).toString());
            artifacts.addProperty("serverLandUseWorldgenLedger",
                    CityLandUseWorldgenRegistry.worldgenLedgerPath(serverRoot).toString());
        }
        response.add("artifacts", artifacts);
        return response;
    }

    static JsonObject handleQueryDecorationCatalog() {
        return handleQueryDecorationCatalog(defaultDecorationCatalogRoot());
    }

    static JsonObject handleQueryDecorationCatalog(Path catalogRoot) {
        CityDecorationContentCatalog catalog = new CityDecorationContentCatalogLoader().load(catalogRoot);
        CityDecorationStyleProfileCatalog styles = new CityDecorationStyleProfileCatalogLoader()
                .load(catalogRoot, catalog);
        return decorationCatalogSummary(catalog, styles);
    }

    static JsonObject handleUpgradeDefaultDecorationCatalog(Path serverRoot,
                                                            Path catalogRoot,
                                                            boolean confirmConfigMutation) throws IOException {
        if (!confirmConfigMutation) {
            throw new IllegalArgumentException("CITY_DECORATION_DEFAULT_CATALOG_UPGRADE_CONFIRMATION_REQUIRED");
        }
        Path installedCatalogRoot = CityDecorationDefaultCatalogBootstrap.ensureInstalled(catalogRoot);
        CityDecorationContentCatalogLoader loader = new CityDecorationContentCatalogLoader();
        CityDecorationContentCatalog before = loader.load(installedCatalogRoot);
        CityDecorationDefaultCatalogBootstrap.UpgradeResult upgrade =
                CityDecorationDefaultCatalogBootstrap.upgradeManagedDefault(installedCatalogRoot);
        CityDecorationContentCatalog after = loader.load(installedCatalogRoot);

        JsonObject response = decorationCatalogSummary(after,
                new CityDecorationStyleProfileCatalogLoader().load(installedCatalogRoot, after));
        boolean catalogChanged = !before.catalogHash().equals(after.catalogHash());
        response.addProperty("configMutationConfirmed", true);
        response.addProperty("previousCatalogHash", before.catalogHash());
        response.addProperty("catalogChanged", catalogChanged);
        response.addProperty("requiresReplan", catalogChanged);
        response.addProperty("contentIndexChanged", upgrade.contentIndexChanged());
        response.addProperty("manifestChanged", upgrade.manifestChanged());
        if (upgrade.backupPath() != null) {
            response.addProperty("backupPath", upgrade.backupPath().toString());
        }
        if (upgrade.contentIndexChanged()) {
            response.add("deactivatedActivePlans", CityDecorationWorldgenRegistry
                    .deactivateAllForCatalogUpgrade(serverRoot));
        }
        return response;
    }

    static JsonObject handleProbeDecorationTerrain(Path debugRoot, String runId, String citySeedId,
                                                   ServerLevel level) throws IOException {
        if (level == null) {
            throw new IllegalArgumentException("CITY_DECORATION_TERRAIN_PROBE_LEVEL_REQUIRED");
        }
        return handleProbeDecorationTerrain(debugRoot, runId, citySeedId,
                new LoadedChunkDecorationTerrainView(level));
    }

    static JsonObject handleProbeDecorationTerrain(Path debugRoot, String runId, String citySeedId,
                                                   CityDecorationTerrainProbe.TerrainView terrain) throws IOException {
        if (terrain == null) {
            throw new IllegalArgumentException("CITY_DECORATION_TERRAIN_PROBE_TERRAIN_VIEW_REQUIRED");
        }
        Path runDir = debugRoot.resolve(runId);
        loadCitySeed(runDir, runId, citySeedId);
        Path outputDirectory = decorationDir(runDir, citySeedId);
        Path compiledPath = outputDirectory.resolve("city_decoration_compiled_program_plan.json");
        Path slotProjectionPath = outputDirectory.resolve("city_decoration_slot_projection.json");
        if (!Files.isRegularFile(compiledPath) || !Files.isRegularFile(slotProjectionPath)) {
            throw new IllegalArgumentException("CITY_DECORATION_TERRAIN_PROBE_PLAN_INCOMPLETE: "
                    + "compiled plan and slot projection are both required.");
        }
        CompiledDecorationProgramPlan compiled = new CompiledDecorationProgramCodec().parsePlan(
                JsonParser.parseString(Files.readString(compiledPath)).getAsJsonObject());
        if (!citySeedId.equals(compiled.cityId())) {
            throw new IllegalArgumentException("CITY_DECORATION_TERRAIN_PROBE_CITY_ID_MISMATCH: expected "
                    + citySeedId + " but found " + compiled.cityId());
        }
        JsonObject projection = JsonParser.parseString(Files.readString(slotProjectionPath)).getAsJsonObject();
        List<DecorationSlot> slots = parseDecorationTerrainSlots(projection, compiled);
        JsonObject response = new CityDecorationTerrainProbe().probe(compiled, slots, terrain);
        response.addProperty("ok", true);
        response.addProperty("samplingMode", "heightmap_motion_blocking_no_leaves");
        response.addProperty("unavailableBehavior", "slot_is_reported_unavailable_without_chunk_generation");
        JsonObject artifacts = new JsonObject();
        artifacts.addProperty("sourceCompiledDecorationProgramPlan", debugRef(debugRoot, compiledPath));
        artifacts.addProperty("sourceDecorationSlotProjection", debugRef(debugRoot, slotProjectionPath));
        response.add("artifacts", artifacts);
        return response;
    }

    private static List<DecorationSlot> parseDecorationTerrainSlots(JsonObject projection,
                                                                      CompiledDecorationProgramPlan compiled) {
        return parseDecorationProjectionSlots(projection, compiled,
                "CITY_DECORATION_TERRAIN_PROBE_SLOT_PROJECTION");
    }

    private static List<DecorationSlot> parseDecorationProjectionSlots(JsonObject projection,
                                                                         CompiledDecorationProgramPlan compiled,
                                                                         String reasonPrefix) {
        if (!"city_decoration_slot_projection.v0.2".equals(stringValue(projection, "schemaVersion", ""))) {
            throw new IllegalArgumentException(reasonPrefix + "_SCHEMA_UNSUPPORTED");
        }
        if (!compiled.cityId().equals(stringValue(projection, "cityId", ""))
                || !compiled.catalogHash().equals(stringValue(projection, "catalogHash", ""))) {
            throw new IllegalArgumentException(reasonPrefix + "_MISMATCH");
        }
        if (!projection.has("slots") || !projection.get("slots").isJsonArray()) {
            throw new IllegalArgumentException(reasonPrefix + "_INVALID");
        }
        Set<String> programIds = new LinkedHashSet<>();
        compiled.programs().forEach(program -> programIds.add(program.programId()));
        Set<String> slotIds = new LinkedHashSet<>();
        List<DecorationSlot> result = new ArrayList<>();
        for (JsonElement element : projection.getAsJsonArray("slots")) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException(reasonPrefix + "_INVALID");
            }
            JsonObject slot = element.getAsJsonObject();
            String slotId = requiredString(slot, "slotId");
            String programId = requiredString(slot, "programId");
            if (!programIds.contains(programId) || !slotIds.add(slotId)) {
                throw new IllegalArgumentException(reasonPrefix + "_MISMATCH");
            }
            JsonObject worldAnchor = requiredDecorationProjectionObject(slot, "worldAnchor", reasonPrefix);
            JsonObject localAnchor = requiredDecorationProjectionObject(slot, "localAnchor", reasonPrefix);
            result.add(new DecorationSlot(slotId, programId,
                    requiredString(slot, "paletteSlotId"),
                    new BlockPoint(requiredDecorationProjectionInt(worldAnchor, "x", reasonPrefix),
                            requiredDecorationProjectionInt(worldAnchor, "z", reasonPrefix)),
                    new CompiledDecorationProgram.LocalPoint(requiredDecorationProjectionInt(localAnchor, "u", reasonPrefix),
                            requiredDecorationProjectionInt(localAnchor, "v", reasonPrefix)),
                    requiredDecorationProjectionInt(slot, "rotationQuarterTurns", reasonPrefix)));
        }
        return List.copyOf(result);
    }

    private static JsonObject requiredDecorationProjectionObject(JsonObject source, String key, String reasonPrefix) {
        if (!source.has(key) || !source.get(key).isJsonObject()) {
            throw new IllegalArgumentException(reasonPrefix + "_INVALID: " + key);
        }
        return source.getAsJsonObject(key);
    }

    private static int requiredDecorationProjectionInt(JsonObject source, String key, String reasonPrefix) {
        if (!source.has(key) || !source.get(key).isJsonPrimitive()
                || !source.getAsJsonPrimitive(key).isNumber()) {
            throw new IllegalArgumentException(reasonPrefix + "_INVALID: " + key);
        }
        return source.get(key).getAsInt();
    }

    static JsonObject handleQueryStructureCatalog(Path baseDirectory, JsonObject terraSenseProfileSource,
                                                   JsonObject query) throws IOException {
        return new CityStructureCatalogQueryService().query(baseDirectory, terraSenseProfileSource, query);
    }

    static JsonObject handleQueryTemplateMetadata(ServerLevel level, JsonArray templateRefs) {
        if (level == null) {
            throw new IllegalArgumentException("CITY_TEMPLATE_METADATA_LEVEL_REQUIRED");
        }
        if (templateRefs == null || templateRefs.isEmpty()) {
            throw new IllegalArgumentException("CITY_TEMPLATE_METADATA_REFS_REQUIRED");
        }
        MinecraftCityTemplateReader reader = new MinecraftCityTemplateReader(level.getStructureManager());
        JsonArray templates = new JsonArray();
        for (JsonElement element : templateRefs) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("CITY_TEMPLATE_METADATA_REF_INVALID");
            }
            String templateRef = element.getAsString().trim();
            MinecraftCityTemplateReader.ReadResult result = reader.read(templateRef);
            JsonObject item = new JsonObject();
            item.addProperty("templateRef", result.templateRef() == null ? templateRef : result.templateRef());
            item.addProperty("readable", result.readable());
            item.addProperty("failureCode", result.failureCode().name());
            item.addProperty("failureDetail", result.failureDetail());
            if (result.readable()) {
                JsonObject rawSize = new JsonObject();
                rawSize.addProperty("width", result.size().getX());
                rawSize.addProperty("height", result.size().getY());
                rawSize.addProperty("depth", result.size().getZ());
                item.add("rawSize", rawSize);
                item.addProperty("templateHash", result.contentHash());
                item.addProperty("sourceId", result.sourceId());
            }
            templates.add(item);
        }
        JsonObject response = new JsonObject();
        response.addProperty("schemaVersion", "city_template_metadata_query.v0.1");
        response.addProperty("dimensionId", level.dimension().location().toString());
        response.add("templates", templates);
        return response;
    }

    static JsonObject handlePlanCityDressing(Path debugRoot, String runId, String citySeedId,
                                             JsonObject decorationProgramPlan) throws IOException {
        rejectLegacyDressingPlan(decorationProgramPlan);
        return handlePlanCityDressing(debugRoot, runId, citySeedId, decorationProgramPlan,
                defaultDecorationCatalogRoot());
    }

    static JsonObject handlePlanCityDressing(Path debugRoot, String runId, String citySeedId,
                                             JsonObject decorationProgramPlan,
                                             Path catalogRoot) throws IOException {
        rejectLegacyDressingPlan(decorationProgramPlan);
        Path runDir = debugRoot.resolve(runId);
        loadCitySeed(runDir, runId, citySeedId);
        CityLandformReviewPackage reviewPackage = loadD3Package(debugRoot, runDir, citySeedId);
        Path d4Dir = runDir.resolve("city_d4_" + safeFileName(citySeedId));
        Path d5Dir = runDir.resolve("city_d5_" + safeFileName(citySeedId));
        Path d6Dir = runDir.resolve("city_d6_" + safeFileName(citySeedId));
        Path landUsePath = runDir.resolve("city_land_use_" + safeFileName(citySeedId))
                .resolve("city_land_use_area_plan.json");
        Path landUseCompletePath = runDir.resolve("city_land_use_" + safeFileName(citySeedId))
                .resolve("city_land_use_planning_complete.json");
        Path anchorMapPath = d4Dir.resolve("structure_anchor_map.json");
        Path reservationMaskPath = d5Dir.resolve("reservation_mask_plan.json");
        Path wallReservationPath = d5Dir.resolve("wall_reservation_plan.json");
        Path materializationPath = d6Dir.resolve("structure_materialization_plan.json");
        if (!Files.exists(anchorMapPath)) {
            rejectLegacyArtifacts(d4Dir, "D4");
            throw new IllegalArgumentException("D4 structure_anchor_map.json not found. Run city_plan_d4 first: "
                    + debugRef(debugRoot, anchorMapPath));
        }
        if (!Files.exists(reservationMaskPath)) {
            rejectLegacyArtifacts(d5Dir, "D5");
            throw new IllegalArgumentException("D5 reservation_mask_plan.json not found. Run city_plan_d5 first: "
                    + debugRef(debugRoot, reservationMaskPath));
        }
        if (!Files.exists(materializationPath)) {
            throw new IllegalArgumentException("D6 structure_materialization_plan.json not found. Run city_plan_d6 first: "
                    + debugRef(debugRoot, materializationPath));
        }
        JsonObject materializationPlan = JsonParser.parseString(Files.readString(materializationPath)).getAsJsonObject();
        JsonObject wallReservationPlan = Files.exists(wallReservationPath)
                ? JsonParser.parseString(Files.readString(wallReservationPath)).getAsJsonObject() : new JsonObject();
        JsonObject roadConnectionPlan = CityRoadWeaverBridge.createConnectionPlan(materializationPlan);
        List<CompiledDecorationProgramPlan.HardObstacle> hardObstacles = new ArrayList<>(
                decorationHardObstacles(materializationPlan, wallReservationPlan, roadConnectionPlan));
        CityDecorationContentCatalog catalog = new CityDecorationContentCatalogLoader().load(catalogRoot);
        CityDecorationStyleProfileCatalog styleProfiles = new CityDecorationStyleProfileCatalogLoader()
                .load(catalogRoot, catalog);
        CityDecorationProgramPlanner planner = new CityDecorationProgramPlanner();
        DecorationProgramIntentPlan intentPlan = planner.parse(decorationProgramPlan);
        if (!reviewPackage.cityId().equals(intentPlan.cityId())) {
            throw new IllegalArgumentException("CITY_DECORATION_CITY_ID_MISMATCH: expected "
                    + reviewPackage.cityId() + " but found " + intentPlan.cityId());
        }
        if (!catalog.catalogHash().equals(intentPlan.catalogHash())) {
            throw new IllegalArgumentException("CITY_DECORATION_CATALOG_HASH_MISMATCH: expected "
                    + catalog.catalogHash() + " but found " + intentPlan.catalogHash());
        }
        CityDecorationStyleProfileCatalog.StyleProfile styleProfile = styleProfiles
                .requireProfile(intentPlan.styleProfileId());
        CityDecorationStyleProfileResolver.Resolution styleResolution = new CityDecorationStyleProfileResolver()
                .resolve(intentPlan, styleProfile);
        DecorationProgramIntentPlan resolvedIntent = styleResolution.resolvedIntent();
        validateDecorationContentRefs(resolvedIntent, catalog);
        LandUseAreaDecorationProgramContextResolver landUseResolver = null;
        boolean requiresLandUse = resolvedIntent.programs().stream()
                .anyMatch(program -> "land_use_area".equals(program.targetArea().sourceType()));
        if (requiresLandUse) {
            if (!Files.isRegularFile(landUsePath) || !Files.isRegularFile(landUseCompletePath)) {
                throw new IllegalArgumentException("CITY_DECORATION_LAND_USE_PLAN_REQUIRED: run city_plan_land_use first.");
            }
            LandUseAreaPlan typedLandUsePlan = new LandUseAreaPlanCodec().fromJson(
                    JsonParser.parseString(Files.readString(landUsePath)).getAsJsonObject());
            if (!reviewPackage.cityId().equals(typedLandUsePlan.cityId())) {
                throw new IllegalArgumentException("CITY_DECORATION_LAND_USE_CITY_ID_MISMATCH: expected "
                        + reviewPackage.cityId() + " but found " + typedLandUsePlan.cityId());
            }
            validateLandUseCompletion(JsonParser.parseString(Files.readString(landUseCompletePath)).getAsJsonObject(),
                    typedLandUsePlan, reviewPackage.cityId(), LandUseRuleCatalog.defaults().profileHash(),
                    CityStructureEnvelopeProfiler.sha256(CityJson.GSON.toJson(materializationPlan)));
            appendLandUseDecorationObstacles(hardObstacles, typedLandUsePlan);
            landUseResolver = new LandUseAreaDecorationProgramContextResolver(
                    new LandUseAreaPlanCodec().toJson(typedLandUsePlan), hardObstacles);
        }
        CompiledDecorationProgramPlan compiled = planner.compile(resolvedIntent,
                new CityDecorationProgramContextResolver(
                        new D3PatchDecorationProgramContextResolver(reviewPackage, hardObstacles),
                        landUseResolver),
                hardObstacles);
        BlockBounds projectionBounds = decorationProjectionBounds(compiled.programs());
        List<DecorationSlot> slots = planner.project(compiled, projectionBounds);

        DecorationProgramIntentCodec intentCodec = new DecorationProgramIntentCodec();
        CompiledDecorationProgramCodec compiledCodec = new CompiledDecorationProgramCodec();
        JsonObject normalizedIntent = intentCodec.toJson(intentPlan);
        JsonObject compiledJson = compiledCodec.toJson(compiled);
        JsonObject slotProjection = decorationSlotProjection(compiled, slots);
        JsonObject quality = decorationPlanningQuality(intentPlan, compiled, slots);
        JsonObject trace = decorationPlanningTrace(intentPlan, compiled, slots);

        Path outputDirectory = decorationDir(runDir, citySeedId);
        Files.createDirectories(outputDirectory);
        Path intentPath = outputDirectory.resolve("city_decoration_program_plan.json");
        Path compiledPath = outputDirectory.resolve("city_decoration_compiled_program_plan.json");
        Path slotsPath = outputDirectory.resolve("city_decoration_slot_projection.json");
        Path qualityPath = outputDirectory.resolve("quality_report.json");
        Path tracePath = outputDirectory.resolve("city_decoration_planning_trace.json");
        Path styleResolutionPath = outputDirectory.resolve("city_decoration_style_resolution.json");
        Path previewIndexPath = outputDirectory.resolve("city_decoration_preview_index.json");
        Path completePath = outputDirectory.resolve("city_decoration_planning_complete.json");
        Files.deleteIfExists(completePath);
        JsonObject previewIndex = new CityDecorationPreviewRenderer().render(compiled, slots, outputDirectory);
        Files.writeString(intentPath, CityJson.GSON.toJson(normalizedIntent));
        Files.writeString(compiledPath, CityJson.GSON.toJson(compiledJson));
        Files.writeString(slotsPath, CityJson.GSON.toJson(slotProjection));
        Files.writeString(qualityPath, CityJson.GSON.toJson(quality));
        Files.writeString(tracePath, CityJson.GSON.toJson(trace));
        Files.writeString(styleResolutionPath, CityJson.GSON.toJson(styleResolution.trace()));
        JsonObject completion = new JsonObject();
        completion.addProperty("schemaVersion", "city_decoration_planning_complete.v0.2");
        completion.addProperty("cityId", compiled.cityId());
        completion.addProperty("catalogHash", compiled.catalogHash());
        completion.addProperty("styleProfileId", compiled.styleProfileId());
        completion.addProperty("styleProfileHash", compiled.styleProfileHash());
        completion.addProperty("completedAt", Instant.now().toString());
        Files.writeString(completePath, CityJson.GSON.toJson(completion));

        JsonObject response = new JsonObject();
        response.addProperty("ok", true);
        response.addProperty("planningMode", "city_decoration_program_v0_2");
        response.add("decorationProgramPlan", normalizedIntent.deepCopy());
        response.add("compiledDecorationProgramPlan", compiledJson.deepCopy());
        response.add("slotProjection", slotProjection.deepCopy());
        response.add("qualityReport", quality.deepCopy());
        response.add("planningTrace", trace.deepCopy());
        response.add("styleResolution", styleResolution.trace().deepCopy());
        response.add("decorationPreviewIndex", previewIndex.deepCopy());
        response.add("planningComplete", completion.deepCopy());
        JsonObject artifacts = new JsonObject();
        artifacts.addProperty("decorationProgramPlan", debugRef(debugRoot, intentPath));
        artifacts.addProperty("compiledDecorationProgramPlan", debugRef(debugRoot, compiledPath));
        artifacts.addProperty("decorationSlotProjection", debugRef(debugRoot, slotsPath));
        artifacts.addProperty("qualityReport", debugRef(debugRoot, qualityPath));
        artifacts.addProperty("planningTrace", debugRef(debugRoot, tracePath));
        artifacts.addProperty("styleResolution", debugRef(debugRoot, styleResolutionPath));
        artifacts.addProperty("decorationPreviewIndex", debugRef(debugRoot, previewIndexPath));
        artifacts.addProperty("planningComplete", debugRef(debugRoot, completePath));
        artifacts.addProperty("sourceD3Package", debugRef(debugRoot, d3PackagePath(runDir, citySeedId)));
        artifacts.addProperty("sourceStructureAnchorMap", debugRef(debugRoot, anchorMapPath));
        artifacts.addProperty("sourceReservationMaskPlan", debugRef(debugRoot, reservationMaskPath));
        if (Files.exists(wallReservationPath)) {
            artifacts.addProperty("sourceWallReservationPlan", debugRef(debugRoot, wallReservationPath));
        }
        artifacts.addProperty("sourceStructureMaterializationPlan", debugRef(debugRoot, materializationPath));
        if (requiresLandUse) {
            artifacts.addProperty("sourceLandUseAreaPlan", debugRef(debugRoot, landUsePath));
            artifacts.addProperty("sourceLandUsePlanningComplete", debugRef(debugRoot, landUseCompletePath));
        }
        response.add("artifacts", artifacts);
        return response;
    }

    static JsonObject handlePlanD6(Path debugRoot, String runId, String citySeedId,
                                   MinecraftServerHolder serverHolder,
                                   ServerLevel level) throws IOException {
        Path runDir = debugRoot.resolve(runId);
        loadCitySeed(runDir, runId, citySeedId);
        Path d4Dir = runDir.resolve("city_d4_" + safeFileName(citySeedId));
        Path d5Dir = runDir.resolve("city_d5_" + safeFileName(citySeedId));
        Path anchorMapPath = d4Dir.resolve("structure_anchor_map.json");
        Path maskPath = d5Dir.resolve("reservation_mask_plan.json");
        Path wallReservationPath = d5Dir.resolve("wall_reservation_plan.json");
        if (!Files.exists(anchorMapPath)) {
            rejectLegacyArtifacts(d4Dir, "D4");
            throw new IllegalArgumentException("D4 structure_anchor_map.json not found. Run city_plan_d4 first: "
                    + debugRef(debugRoot, anchorMapPath));
        }
        if (!Files.exists(maskPath)) {
            rejectLegacyArtifacts(d5Dir, "D5");
            throw new IllegalArgumentException("D5 reservation_mask_plan.json not found. Run city_plan_d5 first: "
                    + debugRef(debugRoot, maskPath));
        }
        JsonObject anchorMap = JsonParser.parseString(Files.readString(anchorMapPath)).getAsJsonObject();
        CityStructureMaterializationPlanner.ChunkStatusInspector inspector = CityReservationMaskRegistry
                .hasActivePlannedStructuresFor(runId, citySeedId, stringValue(anchorMap, "cityId"))
                ? new MinecraftCityWorldgenStatusInspector(level)
                : task -> CityStructureMaterializationPlanner.ChunkStatusResult.plannedWorldgen(
                        "D6 preflight accepted before city_execute_d5; run execute_d5 before loading target chunks.");
        CityStructureMaterializationPlanner.PlacementBackend preflightBackend = serverHolder == null
                ? CityStructureMaterializationPlanner.PlacementBackend.traceOnly()
                : new MinecraftCityStructureMaterializationBackend(serverHolder.server(), level, false);
        CityStructureMaterializationPlanner.Result result = new CityStructureMaterializationPlanner()
                .planWorldgen(anchorMap, inspector, preflightBackend, null);
        validateD5V5FootprintsWithinReservation(wallReservationPath, result.structureMaterializationPlan(),
                "plannedWorldgenStructures", "D6");

        Path outputDirectory = runDir.resolve("city_d6_" + safeFileName(citySeedId));
        Files.createDirectories(outputDirectory);
        Path planPath = outputDirectory.resolve("structure_materialization_plan.json");
        Path ledgerPath = outputDirectory.resolve("placed_structure_ledger.json");
        Path tracePath = outputDirectory.resolve("structure_materialization_trace.json");
        Path inferredPath = outputDirectory.resolve("inferred_function_area_map.json");
        Path qualityPath = outputDirectory.resolve("quality_report.json");
        Files.writeString(planPath, CityJson.GSON.toJson(result.structureMaterializationPlan()));
        Files.writeString(ledgerPath, CityJson.GSON.toJson(result.placedStructureLedger()));
        Files.writeString(tracePath, CityJson.GSON.toJson(result.structureMaterializationTrace()));
        Files.writeString(inferredPath, CityJson.GSON.toJson(result.inferredFunctionAreaMap()));
        Files.writeString(qualityPath, CityJson.GSON.toJson(result.qualityReport()));

        Path previewPath = new CityStructureLandingPreviewRenderer()
                .renderD6(result.structureMaterializationPlan(), result.structureMaterializationTrace(), outputDirectory);
        Files.deleteIfExists(runDir.resolve("city_land_use_" + safeFileName(citySeedId))
                .resolve("city_land_use_planning_complete.json"));
        Files.deleteIfExists(decorationDir(runDir, citySeedId)
                .resolve("city_decoration_planning_complete.json"));

        JsonObject response = result.asJson();
        JsonObject artifacts = new JsonObject();
        artifacts.addProperty("structureMaterializationPlan", debugRef(debugRoot, planPath));
        artifacts.addProperty("placedStructureLedger", debugRef(debugRoot, ledgerPath));
        artifacts.addProperty("structureMaterializationTrace", debugRef(debugRoot, tracePath));
        artifacts.addProperty("inferredFunctionAreaMap", debugRef(debugRoot, inferredPath));
        artifacts.addProperty("structureMaterializationPreview", debugRef(debugRoot, previewPath));
        artifacts.addProperty("qualityReport", debugRef(debugRoot, qualityPath));
        artifacts.addProperty("sourceStructureAnchorMap", debugRef(debugRoot, anchorMapPath));
        artifacts.addProperty("sourceReservationMaskPlan", debugRef(debugRoot, maskPath));
        response.add("artifacts", artifacts);
        return response;
    }

    static JsonObject handleExecuteD7(Path debugRoot, String runId, String citySeedId, long worldSeed,
                                      boolean executeStructurePlacement, boolean debugLateMaterialize,
                                      MinecraftServerHolder serverHolder, ServerLevel level) throws IOException {
        Path runDir = debugRoot.resolve(runId);
        loadCitySeed(runDir, runId, citySeedId);
        Path d6Dir = runDir.resolve("city_d6_" + safeFileName(citySeedId));
        Path d5Dir = runDir.resolve("city_d5_" + safeFileName(citySeedId));
        Path planPath = d6Dir.resolve("structure_materialization_plan.json");
        Path d3PackagePath = d3PackagePath(runDir, citySeedId);
        Path wallReservationPath = d5Dir.resolve("wall_reservation_plan.json");
        if (!Files.exists(planPath)) {
            rejectLegacyArtifacts(d6Dir, "D6");
            throw new IllegalArgumentException("D6 artifacts not found. Run city_plan_d6 first: "
                    + debugRef(debugRoot, d6Dir));
        }
        JsonObject materializationPlan = JsonParser.parseString(Files.readString(planPath)).getAsJsonObject();
        CityLandformReviewPackage reviewPackage = null;
        if (Files.exists(d3PackagePath)) {
            reviewPackage = CityLandformReviewPackage.fromJson(
                    JsonParser.parseString(Files.readString(d3PackagePath)).getAsJsonObject());
        }
        Path outputDirectory = runDir.resolve("city_d7_" + safeFileName(citySeedId));
        Path ledgerPath = outputDirectory.resolve("placed_structure_ledger.json");
        CityStructureMaterializationPlanner.Result result;
        if (debugLateMaterialize) {
            CityStructureMaterializationPlanner.PlacementBackend backend = serverHolder == null
                    ? CityStructureMaterializationPlanner.PlacementBackend.traceOnly()
                    : new MinecraftCityStructureMaterializationBackend(serverHolder.server(), level, executeStructurePlacement);
            JsonObject previousLedger = executeStructurePlacement && Files.exists(ledgerPath)
                    ? JsonParser.parseString(Files.readString(ledgerPath)).getAsJsonObject()
                    : null;
            JsonObject debugPlan = materializationPlan.has("structures")
                    && !materializationPlan.getAsJsonArray("structures").isEmpty()
                    ? materializationPlan
                    : new CityStructureMaterializationPlanner().plan(
                            materializationPlan.getAsJsonObject("sourceStructureAnchorMap"), backend, previousLedger)
                    .structureMaterializationPlan();
            result = new CityStructureMaterializationPlanner().execute(
                    debugPlan, backend, previousLedger, executeStructurePlacement);
            result.structureMaterializationTrace().addProperty("lateMaterialization", true);
            result.structureMaterializationTrace().addProperty("debugLateMaterialize", true);
            result.qualityReport().addProperty("passed", false);
            result.qualityReport().addProperty("score", 0);
        } else {
            JsonObject runtimeLedger = CityReservationMaskRegistry.ledgerForCity(
                    runId,
                    citySeedId,
                    stringValue(materializationPlan, "cityId"));
            CityStructureMaterializationPlanner.ChunkStatusInspector inspector = CityReservationMaskRegistry
                    .hasActivePlannedStructuresFor(runId, citySeedId, stringValue(materializationPlan, "cityId"))
                    ? new MinecraftCityWorldgenStatusInspector(level)
                    : task -> CityStructureMaterializationPlanner.ChunkStatusResult.registryMissing(
                            "Run city_execute_d5 confirmWorldMutation=true before loading target chunks.");
            result = new CityStructureMaterializationPlanner().executeWorldgen(
                    materializationPlan,
                    runtimeLedger,
                    inspector,
                    executeStructurePlacement);
        }

        Files.createDirectories(outputDirectory);
        result.structureMaterializationTrace().add("terrainAdaptationReport",
                terrainAdaptationReport(result.placedStructureLedger()));
        validateD5V5FootprintsWithinReservation(wallReservationPath, result.placedStructureLedger(),
                "placedStructures", "D7");
        JsonObject roadProviderState = loadRoadProviderState(d5Dir);
        JsonObject roadPostprocessReport = maybeRunDeferredRoadPostprocess(
                debugRoot, runDir, citySeedId, materializationPlan, result.placedStructureLedger(),
                executeStructurePlacement, debugLateMaterialize, serverHolder, level, outputDirectory,
                roadProviderState);
        Path tracePath = outputDirectory.resolve("structure_materialization_trace.json");
        Path inferredPath = outputDirectory.resolve("inferred_function_area_map.json");
        Path qualityPath = outputDirectory.resolve("quality_report.json");
        Files.writeString(ledgerPath, CityJson.GSON.toJson(result.placedStructureLedger()));
        Files.writeString(tracePath, CityJson.GSON.toJson(result.structureMaterializationTrace()));
        Files.writeString(inferredPath, CityJson.GSON.toJson(result.inferredFunctionAreaMap()));
        Files.writeString(qualityPath, CityJson.GSON.toJson(result.qualityReport()));

        Path previewPath = new CityStructureLandingPreviewRenderer()
                .renderD7(result.placedStructureLedger(), result.structureMaterializationTrace(),
                        materializationPlan, reviewPackage, outputDirectory);

        JsonObject response = result.asJson();
        JsonObject artifacts = new JsonObject();
        artifacts.addProperty("placedStructureLedger", debugRef(debugRoot, ledgerPath));
        artifacts.addProperty("structureMaterializationTrace", debugRef(debugRoot, tracePath));
        artifacts.addProperty("inferredFunctionAreaMap", debugRef(debugRoot, inferredPath));
        artifacts.addProperty("placedStructurePreview", debugRef(debugRoot, previewPath));
        artifacts.addProperty("qualityReport", debugRef(debugRoot, qualityPath));
        artifacts.addProperty("sourceStructureMaterializationPlan", debugRef(debugRoot, planPath));
        if (Files.exists(d3PackagePath)) {
            artifacts.addProperty("sourceD3Package", debugRef(debugRoot, d3PackagePath));
        }
        if (roadPostprocessReport != null) {
            artifacts.addProperty("deferredRoadPostprocessReport",
                    debugRef(debugRoot, outputDirectory.resolve("deferred_road_postprocess_report.json")));
            response.add("deferredRoadPostprocessReport", roadPostprocessReport);
        }
        response.add("roadProviderState", roadProviderState);
        response.add("artifacts", artifacts);
        response.addProperty("structurePlacementExecuted", executeStructurePlacement);
        response.addProperty("debugLateMaterialize", debugLateMaterialize);
        response.addProperty("worldgenPlacementMode", !debugLateMaterialize);
        response.addProperty("worldSeed", worldSeed);
        return response;
    }

    static JsonObject handlePlanCityWalls(Path debugRoot, String runId, String citySeedId,
                                          int wallMarginBlocks, int segmentLengthBlocks,
                                          int gateWidthBlocks) throws IOException {
        return handlePlanCityWalls(debugRoot, runId, citySeedId, wallMarginBlocks, segmentLengthBlocks,
                gateWidthBlocks, "v2", null, 8, 2, 8, 7,
                CityWallPlanner.V3Options.defaults(), CityWallPlanner.V4Options.defaults(),
                CityWallPlanner.V5Options.defaults());
    }

    static JsonObject handlePlanCityWalls(Path debugRoot, String runId, String citySeedId,
                                          int wallMarginBlocks, int segmentLengthBlocks,
                                          int gateWidthBlocks,
                                          String wallVersion,
                                          ServerLevel level,
                                          int roadScanMarginBlocks,
                                          int roadProtectionMarginBlocks,
                                          int maxFoundationDepthBlocks,
                                          int maxSegmentHeightDeltaBlocks) throws IOException {
        return handlePlanCityWalls(debugRoot, runId, citySeedId, wallMarginBlocks, segmentLengthBlocks,
                gateWidthBlocks, wallVersion, level, roadScanMarginBlocks, roadProtectionMarginBlocks,
                maxFoundationDepthBlocks, maxSegmentHeightDeltaBlocks, CityWallPlanner.V3Options.defaults(),
                CityWallPlanner.V4Options.defaults(), CityWallPlanner.V5Options.defaults());
    }

    static JsonObject handlePlanCityWalls(Path debugRoot, String runId, String citySeedId,
                                          int wallMarginBlocks, int segmentLengthBlocks,
                                          int gateWidthBlocks,
                                          String wallVersion,
                                          ServerLevel level,
                                          int roadScanMarginBlocks,
                                          int roadProtectionMarginBlocks,
                                          int maxFoundationDepthBlocks,
                                          int maxSegmentHeightDeltaBlocks,
                                          CityWallPlanner.V3Options wallV3Options) throws IOException {
        return handlePlanCityWalls(debugRoot, runId, citySeedId, wallMarginBlocks, segmentLengthBlocks,
                gateWidthBlocks, wallVersion, level, roadScanMarginBlocks, roadProtectionMarginBlocks,
                maxFoundationDepthBlocks, maxSegmentHeightDeltaBlocks, wallV3Options,
                CityWallPlanner.V4Options.defaults(), CityWallPlanner.V5Options.defaults());
    }

    static JsonObject handlePlanCityWalls(Path debugRoot, String runId, String citySeedId,
                                          int wallMarginBlocks, int segmentLengthBlocks,
                                          int gateWidthBlocks,
                                          String wallVersion,
                                          ServerLevel level,
                                          int roadScanMarginBlocks,
                                          int roadProtectionMarginBlocks,
                                          int maxFoundationDepthBlocks,
                                          int maxSegmentHeightDeltaBlocks,
                                          CityWallPlanner.V3Options wallV3Options,
                                          CityWallPlanner.V4Options wallV4Options) throws IOException {
        return handlePlanCityWalls(debugRoot, runId, citySeedId, wallMarginBlocks, segmentLengthBlocks,
                gateWidthBlocks, wallVersion, level, roadScanMarginBlocks, roadProtectionMarginBlocks,
                maxFoundationDepthBlocks, maxSegmentHeightDeltaBlocks, wallV3Options, wallV4Options,
                CityWallPlanner.V5Options.defaults());
    }

    static JsonObject handlePlanCityWalls(Path debugRoot, String runId, String citySeedId,
                                          int wallMarginBlocks, int segmentLengthBlocks,
                                          int gateWidthBlocks,
                                          String wallVersion,
                                          ServerLevel level,
                                          int roadScanMarginBlocks,
                                          int roadProtectionMarginBlocks,
                                          int maxFoundationDepthBlocks,
                                          int maxSegmentHeightDeltaBlocks,
                                          CityWallPlanner.V3Options wallV3Options,
                                          CityWallPlanner.V4Options wallV4Options,
                                          CityWallPlanner.V5Options wallV5Options) throws IOException {
        Path runDir = debugRoot.resolve(runId);
        loadCitySeed(runDir, runId, citySeedId);
        Path d7Dir = runDir.resolve("city_d7_" + safeFileName(citySeedId));
        Path d5Dir = runDir.resolve("city_d5_" + safeFileName(citySeedId));
        Path d3Dir = runDir.resolve("city_d3_" + safeFileName(citySeedId));
        Path ledgerPath = d7Dir.resolve("placed_structure_ledger.json");
        if (!Files.exists(ledgerPath)) {
            throw new IllegalArgumentException("D7 placed_structure_ledger.json not found. Run city_execute_d7 first: "
                    + debugRef(debugRoot, ledgerPath));
        }
        JsonObject ledger = JsonParser.parseString(Files.readString(ledgerPath)).getAsJsonObject();
        Path d3PackagePath = d3Dir.resolve("city_landform_review_package.json");
        JsonObject d3Package = Files.exists(d3PackagePath)
                ? JsonParser.parseString(Files.readString(d3PackagePath)).getAsJsonObject()
                : null;
        String normalizedWallVersion = CityWallReservationPlanner.normalizeWallVersion(wallVersion);
        JsonObject wallPlan;
        Path outputDirectory = runDir.resolve("city_walls_" + safeFileName(citySeedId));
        JsonObject actualRoadMask = null;
        JsonObject surfaceCacheBackfill = null;
        Path roadMaskPath = outputDirectory.resolve("actual_road_mask.json");
        Path wallReservationPath = d5Dir.resolve("wall_reservation_plan.json");
        if (CityWallReservationPlanner.V1_DEBUG.equals(normalizedWallVersion)) {
            wallPlan = new CityWallPlanner().plan(ledger, wallMarginBlocks, segmentLengthBlocks, gateWidthBlocks);
        } else {
            if (!Files.exists(wallReservationPath)) {
                throw new IllegalArgumentException("wall_reservation_plan.json not found. Run city_plan_d5 with matching wallVersion first: "
                        + debugRef(debugRoot, wallReservationPath));
            }
            JsonObject wallReservationPlan = JsonParser.parseString(Files.readString(wallReservationPath))
                    .getAsJsonObject();
            JsonObject wallReservationForPlan = enrichWallReservationWithD3Cells(wallReservationPlan, d3Package);
            actualRoadMask = new CityRoadMaskScanner().scan(level, wallReservationForPlan, roadScanMarginBlocks);
            if (CityWallReservationPlanner.V5.equals(normalizedWallVersion)) {
                Files.createDirectories(outputDirectory);
                surfaceCacheBackfill = CitySurfaceCache.writeBackfill(level,
                        v5SurfaceBackfillBounds(wallReservationForPlan),
                        outputDirectory,
                        stringValue(wallReservationForPlan, "cityId", citySeedId));
                wallPlan = new CityWallPlanner().planV5(ledger, wallReservationForPlan, actualRoadMask,
                        wallV5Options);
                wallPlan.add("surfaceCacheBackfill", surfaceCacheBackfill.deepCopy());
            } else if (CityWallReservationPlanner.V4.equals(normalizedWallVersion)) {
                wallPlan = new CityWallPlanner().planV4(ledger, wallReservationForPlan, actualRoadMask, gateWidthBlocks,
                        roadProtectionMarginBlocks, maxFoundationDepthBlocks, maxSegmentHeightDeltaBlocks,
                        wallV3Options, wallV4Options);
            } else if (CityWallReservationPlanner.V3.equals(normalizedWallVersion)) {
                wallPlan = new CityWallPlanner().planV3(ledger, wallReservationForPlan, actualRoadMask, gateWidthBlocks,
                        roadProtectionMarginBlocks, maxFoundationDepthBlocks, maxSegmentHeightDeltaBlocks,
                        wallV3Options);
            } else {
                wallPlan = new CityWallPlanner().planV2(ledger, wallReservationForPlan, actualRoadMask, gateWidthBlocks,
                        roadProtectionMarginBlocks, maxFoundationDepthBlocks, maxSegmentHeightDeltaBlocks);
            }
        }
        Path planPath = new MinecraftCityWallArtifactWriter().writeArtifacts(wallPlan, outputDirectory);
        if (actualRoadMask != null) {
            Files.writeString(roadMaskPath, CityJson.GSON.toJson(actualRoadMask));
        }
        Path surfaceCachePath = outputDirectory.resolve("surface_cache_backfill_report.json");
        if (surfaceCacheBackfill != null) {
            Files.writeString(surfaceCachePath, CityJson.GSON.toJson(surfaceCacheBackfill));
        }
        Path previewPath = new CityWallPreviewRenderer().render(wallPlan, ledger, d3Package, outputDirectory);
        JsonObject response = new JsonObject();
        response.addProperty("ok", true);
        response.add("cityWallPlan", wallPlan);
        if (actualRoadMask != null) {
            response.add("actualRoadMask", actualRoadMask);
        }
        JsonObject artifacts = new JsonObject();
        artifacts.addProperty("cityWallPlan", debugRef(debugRoot, planPath));
        artifacts.addProperty("cityWallPreview", debugRef(debugRoot, previewPath));
        artifacts.addProperty("cityWallTemplateDirectory",
                debugRef(debugRoot, outputDirectory.resolve("city_wall_templates")));
        artifacts.addProperty("sourcePlacedStructureLedger", debugRef(debugRoot, ledgerPath));
        if (Files.exists(d3PackagePath)) {
            artifacts.addProperty("sourceD3Package", debugRef(debugRoot, d3PackagePath));
        }
        if (Files.exists(wallReservationPath)) {
            artifacts.addProperty("sourceWallReservationPlan", debugRef(debugRoot, wallReservationPath));
        }
        if (actualRoadMask != null) {
            artifacts.addProperty("actualRoadMask", debugRef(debugRoot, roadMaskPath));
        }
        if (surfaceCacheBackfill != null) {
            artifacts.addProperty("surfaceCacheBackfill", debugRef(debugRoot, surfaceCachePath));
        }
        response.add("artifacts", artifacts);
        return response;
    }

    static JsonObject handleExecuteCityWalls(Path debugRoot, String runId, String citySeedId,
                                              boolean confirmWorldMutation, ServerLevel level) throws IOException {
        return handleExecuteCityWalls(debugRoot, runId, citySeedId, confirmWorldMutation, level, false, 1);
    }

    static JsonObject handleExecuteCityWalls(Path debugRoot, String runId, String citySeedId,
                                             boolean confirmWorldMutation, ServerLevel level,
                                             boolean debugScan, int debugScanStepBlocks) throws IOException {
        if (!confirmWorldMutation) {
            throw new IllegalArgumentException("confirmWorldMutation=true is required for city_execute_city_walls.");
        }
        Path runDir = debugRoot.resolve(runId);
        loadCitySeed(runDir, runId, citySeedId);
        Path outputDirectory = runDir.resolve("city_walls_" + safeFileName(citySeedId));
        Path planPath = outputDirectory.resolve("city_wall_plan.json");
        if (!Files.exists(planPath)) {
            throw new IllegalArgumentException("city_wall_plan.json not found. Run city_plan_city_walls first: "
                    + debugRef(debugRoot, planPath));
        }
        JsonObject wallPlan = JsonParser.parseString(Files.readString(planPath)).getAsJsonObject();
        JsonObject report = new CityWallPlacementBackend().execute(level, wallPlan, debugScan, debugScanStepBlocks);
        Path reportPath = outputDirectory.resolve("city_wall_placement_report.json");
        Files.writeString(reportPath, CityJson.GSON.toJson(report));
        JsonObject response = new JsonObject();
        response.addProperty("ok", booleanValue(report, "ok", false));
        response.add("cityWallPlacementReport", report);
        JsonObject artifacts = new JsonObject();
        artifacts.addProperty("cityWallPlan", debugRef(debugRoot, planPath));
        artifacts.addProperty("cityWallPlacementReport", debugRef(debugRoot, reportPath));
        writeOptionalDebugReport(debugRoot, outputDirectory, report, artifacts,
                "wallTerrainDebugScan", "wall_terrain_debug_scan.json", "wallTerrainDebugScan");
        writeOptionalDebugReport(debugRoot, outputDirectory, report, artifacts,
                "wallMaskConflictReport", "wall_mask_conflict_report.json", "wallMaskConflictReport");
        writeOptionalDebugReport(debugRoot, outputDirectory, report, artifacts,
                "wallGapDebugReport", "wall_gap_debug_report.json", "wallGapDebugReport");
        response.add("artifacts", artifacts);
        return response;
    }

    static JsonObject handleRunWorkflow(Path debugRoot,
                                        Path serverRoot,
                                        String runId,
                                        String citySeedId,
                                        JsonObject request,
                                        MinecraftServerHolder serverHolder,
                                        ServerLevel level) throws IOException {
        Path runDir = debugRoot.resolve(runId);
        loadCitySeed(runDir, runId, citySeedId);
        Path outputDirectory = runDir.resolve("city_workflow_" + safeFileName(citySeedId));
        Files.createDirectories(outputDirectory);
        Path reportPath = outputDirectory.resolve("city_workflow_report.json");

        JsonObject report = new JsonObject();
        report.addProperty("schemaVersion", "city_workflow_report.v0.1");
        report.addProperty("workflowMode", "city_structure_landing_fast_loop");
        report.addProperty("runId", runId);
        report.addProperty("citySeedId", citySeedId);
        report.addProperty("startedAt", Instant.now().toString());
        report.addProperty("status", "running");
        report.addProperty("skipExisting", booleanValue(request, "skipExisting", true));
        report.addProperty("planWalls", booleanValue(request, "planWalls", false));
        report.addProperty("executeWalls", booleanValue(request, "executeWalls", false));
        report.addProperty("d4CandidateMode", stringValue(request, "d4CandidateMode", "key_then_array"));
        JsonArray steps = new JsonArray();
        report.add("steps", steps);
        JsonObject artifacts = new JsonObject();
        artifacts.addProperty("workflowReport", debugRef(debugRoot, reportPath));
        report.add("artifacts", artifacts);
        long workflowStarted = System.nanoTime();
        CityWorkflowStepRunner workflow = new CityWorkflowStepRunner(report, steps,
                booleanValue(request, "skipExisting", true),
                artifact -> debugRef(debugRoot, artifact),
                currentReport -> Files.writeString(reportPath, CityJson.GSON.toJson(currentReport)));

        WorkflowContext ctx = new WorkflowContext(debugRoot, runDir, runId, citySeedId, request, report, workflow);

        LandUseSettings landUseSettings = loadWorkflowLandUseSettings();
        boolean enableLandUseLayer = hasValue(request, "enableLandUseLayer")
                ? booleanValue(request, "enableLandUseLayer", landUseSettings.enabledInWorkflow())
                : landUseSettings.enabledInWorkflow();
        report.addProperty("enableLandUseLayer", enableLandUseLayer);
        report.addProperty("landUseProfileId", landUseSettings.profileId());
        Path d3WorkflowArtifact = enableLandUseLayer
                ? runDir.resolve("city_land_use_" + safeFileName(citySeedId))
                        .resolve("land_use_terrain_field.json")
                : d3PackagePath(runDir, citySeedId);

        if (!ctx.workflow().runStep("city_plan_d3", d3WorkflowArtifact, () -> handlePlanD3(
                debugRoot, runId, citySeedId,
                hasValue(request, "cellStepBlocks") ? intValue(request, "cellStepBlocks", 4) : null,
                hasValue(request, "patchScanPaddingBlocks")
                        ? intValue(request, "patchScanPaddingBlocks", DEFAULT_D3_PATCH_SCAN_PADDING_BLOCKS) : null,
                level))) {
            return ctx.workflow().finish(workflowStarted, "failed");
        }

        boolean refreshStructureProfile = booleanValue(request, "forceRefresh", false)
                || "rescan".equals(stringValue(request, "cacheMode", ""));
        Path structureEnvelopeFactsPath = runDir.resolve("city_structure_envelopes_" + safeFileName(citySeedId))
                .resolve("structure_envelope_facts.json");
        if (!ctx.workflow().runStep("city_profile_structure_envelopes",
                refreshStructureProfile ? null : structureEnvelopeFactsPath,
                () -> {
                    requireObject(request, "terrasenseProfileSource", "city_profile_structure_envelopes");
                    return handleProfileStructureEnvelopes(debugRoot, runId, citySeedId,
                            request.getAsJsonObject("terrasenseProfileSource"),
                            request.has("structureIds") && request.get("structureIds").isJsonArray()
                                    ? request.getAsJsonArray("structureIds") : new JsonArray(),
                            intValue(request, "sampleCount", 256),
                            refreshStructureProfile,
                            serverHolder,
                            level);
                })) {
            return ctx.workflow().finish(workflowStarted, "failed");
        }

        if (!workflowRunD4(ctx)) {
            String requestedStatus = stringValue(ctx.report(), "requestedWorkflowStatus");
            return ctx.workflow().finish(workflowStarted,
                    requestedStatus.isBlank() ? "failed" : requestedStatus);
        }

        if (!ctx.workflow().runStep("city_plan_d5", runDir.resolve("city_d5_" + safeFileName(citySeedId))
                .resolve("reservation_mask_plan.json"), () -> handlePlanD5(debugRoot, runId, citySeedId,
                stringValue(request, "wallVersion", "v3"),
                intValue(request, "wallMarginBlocks", 24),
                intValue(request, "segmentLengthBlocks", 15),
                intValue(request, "wallCorridorHalfWidthBlocks", 4),
                new CityWallReservationPlanner.V3Options(
                        intValue(request, "wallBreathingRoomBlocks",
                                CityWallReservationPlanner.DEFAULT_WALL_BREATHING_ROOM_BLOCKS),
                        intValue(request, "patchExpansionMaxRounds",
                                CityWallReservationPlanner.DEFAULT_PATCH_EXPANSION_MAX_ROUNDS),
                        intValue(request, "concavityOpeningMaxBlocks",
                                CityWallReservationPlanner.DEFAULT_CONCAVITY_OPENING_MAX_BLOCKS),
                        doubleValue(request, "concavityDepthRatioMin",
                                CityWallReservationPlanner.DEFAULT_CONCAVITY_DEPTH_RATIO_MIN))))) {
            return ctx.workflow().finish(workflowStarted, "failed");
        }

        if (!ctx.workflow().runStep("city_plan_d6", runDir.resolve("city_d6_" + safeFileName(citySeedId))
                .resolve("structure_materialization_plan.json"), () -> handlePlanD6(debugRoot, runId, citySeedId,
                serverHolder, level))) {
            return ctx.workflow().finish(workflowStarted, "failed");
        }

        if (enableLandUseLayer) {
            if (request.has("landUseIntentPlan") && !request.get("landUseIntentPlan").isJsonNull()
                    && !request.get("landUseIntentPlan").isJsonObject()) {
                throw new IllegalArgumentException("LAND_USE_INTENT_OBJECT_REQUIRED");
            }
            if (!ctx.workflow().runStep("city_plan_land_use",
                    runDir.resolve("city_land_use_" + safeFileName(citySeedId))
                            .resolve("city_land_use_planning_complete.json"),
                    () -> handlePlanLandUse(debugRoot, runId, citySeedId,
                            request.has("landUseIntentPlan") && request.get("landUseIntentPlan").isJsonObject()
                                    ? request.getAsJsonObject("landUseIntentPlan") : null))) {
                return ctx.workflow().finish(workflowStarted, "failed");
            }
        }

        if (booleanValue(request, "enableDressingLayer", false)
                || request.has("decorationProgramPlan") || request.has("dressingBrushPlan")) {
            if (request.has("dressingBrushPlan")) {
                throw new IllegalArgumentException("CITY_DRESSING_LEGACY_SCHEMA_REMOVED: "
                        + "workflow no longer accepts dressingBrushPlan.");
            }
            if (!ctx.workflow().runStep("city_plan_city_dressing", decorationDir(runDir, citySeedId)
                    .resolve("city_decoration_planning_complete.json"), () -> {
                if (!request.has("decorationProgramPlan") || !request.get("decorationProgramPlan").isJsonObject()) {
                    throw new IllegalArgumentException("CITY_DECORATION_PROGRAM_PLAN_REQUIRED: "
                            + "decorationProgramPlan object is required when enableDressingLayer=true.");
                }
                return handlePlanCityDressing(debugRoot, runId, citySeedId,
                        request.getAsJsonObject("decorationProgramPlan"));
            })) {
                return ctx.workflow().finish(workflowStarted, "failed");
            }
        }

        if (!booleanValue(request, "confirmWorldMutation", false)) {
            ctx.workflow().addStop("city_execute_d5", "needs_confirmation",
                    "WORKFLOW_CONFIRM_WORLD_MUTATION_REQUIRED",
                    "Pass confirmWorldMutation=true to activate masks/planned structures.");
            return ctx.workflow().finish(workflowStarted, "waiting_for_confirmation");
        }

        if (!ctx.workflow().runStep("city_execute_d5", runDir.resolve("city_d5_" + safeFileName(citySeedId))
                .resolve("active_planned_structure_registry.json"), () -> handleExecuteD5(debugRoot, serverRoot,
                runId, citySeedId, true, level, stringValue(request, "roadProvider", "auto"),
                null, enableLandUseLayer))) {
            return ctx.workflow().finish(workflowStarted, "failed");
        }

        if (!ctx.workflow().runStep("city_execute_d7", null, () -> handleExecuteD7(debugRoot, runId, citySeedId,
                level.getSeed(),
                true,
                booleanValue(request, "debugLateMaterialize", false),
                serverHolder,
                level))) {
            return ctx.workflow().finish(workflowStarted, "failed");
        }
        JsonObject d7Step = steps.get(steps.size() - 1).getAsJsonObject();
        String d7Status = stringValue(d7Step, "responseStatus", "");
        String d7Reason = stringValue(d7Step, "reasonCode", "");
        if ("WAITING_FOR_WORLDGEN".equals(d7Reason) || "waiting_for_worldgen".equals(d7Status)) {
            ctx.workflow().addStop("worldgen_wait", "waiting_for_worldgen", "WAITING_FOR_WORLDGEN",
                    "TP/load target chunks, then rerun this workflow with the same runId/citySeedId.");
            return ctx.workflow().finish(workflowStarted, "waiting_for_worldgen");
        }

        if (booleanValue(request, "planWalls", false)) {
            Path wallPlanPath = runDir.resolve("city_walls_" + safeFileName(citySeedId))
                    .resolve("city_wall_plan.json");
            Path wallSkipArtifact = workflowWallPlanMatchesRequest(wallPlanPath, request) ? wallPlanPath : null;
            if (!ctx.workflow().runStep("city_plan_city_walls", wallSkipArtifact, () -> handlePlanCityWalls(debugRoot, runId, citySeedId,
                    intValue(request, "wallMarginBlocks", 24),
                    intValue(request, "segmentLengthBlocks", 15),
                    intValue(request, "gateWidthBlocks", 9),
                    stringValue(request, "wallVersion", "v3"),
                    level,
                    intValue(request, "roadScanMarginBlocks", 8),
                    intValue(request, "roadProtectionMarginBlocks", 2),
                    intValue(request, "maxFoundationDepthBlocks", 8),
                    intValue(request, "maxSegmentHeightDeltaBlocks", 7),
                    workflowWallV3Options(request),
                    workflowWallV4Options(request),
                    workflowWallV5Options(request)))) {
                return ctx.workflow().finish(workflowStarted, "failed");
            }
        }

        if (booleanValue(request, "executeWalls", false)) {
            if (!ctx.workflow().runStep("city_execute_city_walls", null,
                    () -> handleExecuteCityWalls(debugRoot, runId, citySeedId, true, level,
                            booleanValue(request, "debugScan", true),
                            intValue(request, "debugScanStepBlocks", 1)))) {
                return ctx.workflow().finish(workflowStarted, "failed");
            }
        }

        return ctx.workflow().finish(workflowStarted, "completed");
    }

    private static boolean workflowRunD4(WorkflowContext ctx) throws IOException {
        String mode = stringValue(ctx.request(), "d4CandidateMode", "key_then_array");
        if ("key_then_array".equals(mode) || "staged_key_then_array".equals(mode)) {
            return workflowRunD4KeyThenArray(ctx);
        }
        if ("array_layout_loop_v0_2".equals(mode) || "array_layout_loop_v0_3".equals(mode)) {
            return workflowRunD4ArrayLayoutLoop(ctx);
        }
        if ("structure_cluster_groups".equals(mode)) {
            return workflowRunD4StructureClusterGroups(ctx);
        }
        if ("sequential_session".equals(mode)) {
            return workflowRunD4Session(ctx);
        }
        throw new IllegalArgumentException("D4_WORKFLOW_MODE_UNSUPPORTED: " + mode);
    }

    private static boolean workflowRunD4KeyThenArray(WorkflowContext ctx) throws IOException {
        Path anchorMapPath = ctx.runDir().resolve("city_d4_" + safeFileName(ctx.citySeedId()))
                .resolve("structure_anchor_map.json");
        if (booleanValue(ctx.request(), "skipExisting", true) && Files.exists(anchorMapPath)) {
            ctx.workflow().addSkippedStep("city_d4_key_then_array", anchorMapPath,
                    "Existing structure_anchor_map.json found.");
            return true;
        }
        CityD4StagedPlanCompiler.StagePlan[] stagePlanRef = new CityD4StagedPlanCompiler.StagePlan[1];
        if (!ctx.workflow().runStep("city_validate_d4_staged_plan", null, () -> {
            requireObject(ctx.request(), "designSlotPlan", "city_run_workflow key_then_array");
            stagePlanRef[0] = D4_STAGED_PLAN_COMPILER.compile(
                    ctx.request().getAsJsonObject("designSlotPlan"));
            writeWorkflowD4StagePlan(ctx, stagePlanRef[0]);
            JsonObject response = new JsonObject();
            response.addProperty("ok", true);
            response.addProperty("planningMode", "key_then_array");
            response.addProperty("arrayStageCount", stagePlanRef[0].arraySlots().size());
            return response;
        })) {
            return false;
        }
        CityD4StagedPlanCompiler.StagePlan stagePlan = stagePlanRef[0];

        if (!workflowRunD4Session(ctx, stagePlan.keyDesignSlotPlan(), "city_d4_key_structure")) {
            return false;
        }
        if (stagePlan.arraySlots().isEmpty()) {
            return true;
        }

        Path anchorPlanPath = ctx.runDir().resolve("city_d4_" + safeFileName(ctx.citySeedId()))
                .resolve("structure_anchor_plan.json");
        JsonObject currentPlan = JsonParser.parseString(Files.readString(anchorPlanPath)).getAsJsonObject();
        JsonArray stageTrace = new JsonArray();
        JsonObject keyTrace = new JsonObject();
        keyTrace.addProperty("stageType", "key_structure");
        keyTrace.addProperty("anchorCount", array(currentPlan, "anchors").size());
        stageTrace.add(keyTrace);

        for (JsonObject arraySlot : stagePlan.arraySlots()) {
            String arrayId = stringValue(arraySlot, "slotId");
            JsonObject arrayCandidatePlan = D4_STAGED_PLAN_COMPILER.arrayCandidatePlanFromSlot(
                    stagePlan.sourceDesignSlotPlan(), arraySlot);
            JsonObject occupiedAnchorMap = JsonParser.parseString(Files.readString(anchorMapPath)).getAsJsonObject();
            Path candidateSetPath = d4ArrayStageDir(ctx.runDir(), ctx.citySeedId(), arrayId)
                    .resolve("d4_array_candidate_set.json");
            if (!ctx.workflow().runStep("city_plan_d4_array_stage_" + safeFileName(arrayId), null,
                    () -> workflowPlanD4ArrayStage(ctx, arrayCandidatePlan, occupiedAnchorMap))) {
                return false;
            }
            JsonObject candidateSet = JsonParser.parseString(Files.readString(candidateSetPath)).getAsJsonObject();
            JsonObject chosen = WORKFLOW_CANDIDATE_SELECTOR.chooseArrayCandidate(candidateSet);
            JsonObject expandedPlan = chosen.getAsJsonObject("expandedStructureAnchorPlan");
            JsonObject arrayTrace = new JsonObject();
            arrayTrace.addProperty("stageType", "array_fill");
            arrayTrace.addProperty("arrayId", arrayId);
            arrayTrace.addProperty("arrayCandidateId", stringValue(chosen, "arrayCandidateId"));
            arrayTrace.addProperty("itemCount", array(chosen, "items").size());
            arrayTrace.addProperty("variantSelectionMode", stringValue(arrayCandidatePlan,
                    "variantSelectionMode", "seeded_random"));
            stageTrace.add(arrayTrace);
            currentPlan = D4_STAGED_PLAN_COMPILER.mergeStructureAnchorPlans(currentPlan, expandedPlan,
                    stringValue(stagePlan.sourceDesignSlotPlan(), "cityId"), stageTrace);
            JsonObject mergedPlan = currentPlan.deepCopy();
            if (!ctx.workflow().runStep("city_plan_d4_merge_array_stage_" + safeFileName(arrayId), null, () -> {
                requireObject(ctx.request(), "terrasenseProfileSource", "city_plan_d4 merge array stage");
                JsonObject response = handlePlanD4(ctx.debugRoot(), ctx.runId(), ctx.citySeedId(),
                        ctx.request().getAsJsonObject("terrasenseProfileSource"), mergedPlan,
                        ctx.request().has("structureEnvelopeFactsSource")
                                && ctx.request().get("structureEnvelopeFactsSource").isJsonObject()
                                ? ctx.request().getAsJsonObject("structureEnvelopeFactsSource") : null);
                response.addProperty("d4StageMode", "key_then_array");
                response.addProperty("mergedArrayStageId", arrayId);
                response.add("d4StageTrace", stageTrace.deepCopy());
                return response;
            })) {
                return false;
            }
        }
        writeWorkflowD4StageTrace(ctx, stageTrace);
        return true;
    }

    private static boolean workflowRunD4ArrayLayoutLoop(WorkflowContext ctx) throws IOException {
        Path anchorMapPath = ctx.runDir().resolve("city_d4_" + safeFileName(ctx.citySeedId()))
                .resolve("structure_anchor_map.json");
        if (booleanValue(ctx.request(), "skipExisting", true) && Files.exists(anchorMapPath)) {
            ctx.workflow().addSkippedStep("city_d4_array_layout_loop", anchorMapPath,
                    "Existing structure_anchor_map.json found.");
            return true;
        }
        CityD4StagedPlanCompiler.StagePlan[] stagePlanRef = new CityD4StagedPlanCompiler.StagePlan[1];
        if (!ctx.workflow().runStep("city_validate_d4_array_layout_loop_plan", null, () -> {
            requireObject(ctx.request(), "designSlotPlan", "city_run_workflow array_layout_loop_v0_2");
            stagePlanRef[0] = D4_STAGED_PLAN_COMPILER.compile(
                    ctx.request().getAsJsonObject("designSlotPlan"));
            writeWorkflowD4StagePlan(ctx, stagePlanRef[0]);
            JsonObject response = new JsonObject();
            response.addProperty("ok", true);
            response.addProperty("planningMode", "array_layout_loop_v0_2");
            response.addProperty("arrayStageCount", stagePlanRef[0].arraySlots().size());
            return response;
        })) {
            return false;
        }
        CityD4StagedPlanCompiler.StagePlan stagePlan = stagePlanRef[0];
        if (!workflowRunD4Session(ctx, stagePlan.keyDesignSlotPlan(), "city_d4_key_structure")) {
            return false;
        }
        String mode = stringValue(ctx.request(), "d4CandidateMode", "array_layout_loop_v0_2");
        JsonObject arrayLayoutPlan = ctx.request().has("arrayLayoutPlan")
                && ctx.request().get("arrayLayoutPlan").isJsonObject()
                ? ctx.request().getAsJsonObject("arrayLayoutPlan").deepCopy()
                : D4_STAGED_PLAN_COMPILER.minimalArrayLayoutPlan(stagePlan.sourceDesignSlotPlan(), mode);
        if (!ctx.workflow().runStep("city_create_d4_array_layout_loop", null, () -> {
            requireObject(ctx.request(), "terrasenseProfileSource", "city_create_d4_array_layout_loop");
            return handleCreateD4ArrayLayoutLoop(ctx.debugRoot(), ctx.runId(), ctx.citySeedId(),
                    ctx.request().getAsJsonObject("terrasenseProfileSource"),
                    arrayLayoutPlan,
                    ctx.request().has("structureEnvelopeFactsSource")
                            && ctx.request().get("structureEnvelopeFactsSource").isJsonObject()
                            ? ctx.request().getAsJsonObject("structureEnvelopeFactsSource") : null,
                    null,
                    null);
        })) {
            return false;
        }
        JsonArray layoutPlans = array(arrayLayoutPlan, "layoutPlans");
        if (layoutPlans.isEmpty()) {
            ctx.report().addProperty("requestedWorkflowStatus", "waiting_for_array_layout_input");
            ctx.workflow().addStop("city_d4_array_layout_loop_waiting_for_item",
                    "waiting_for_array_layout_input", "D4_ARRAY_LAYOUT_LOOP_WAITING_FOR_ITEM",
                    "Array layout loop created; submit nextArrayLayoutPlanItem before continuing.");
            return false;
        }
        for (JsonElement elem : layoutPlans) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject item = elem.getAsJsonObject();
            String arrayId = stringValue(item, "arrayId", "array");
            if (!ctx.workflow().runStep("city_execute_d4_array_layout_item_" + safeFileName(arrayId), null, () -> {
                JsonObject state = loadArrayLayoutLoopState(ctx.debugRoot(), ctx.runDir(), ctx.citySeedId(), null);
                requireObject(ctx.request(), "terrasenseProfileSource", "city_execute_d4_array_layout_item");
                return handleExecuteD4ArrayLayoutItem(ctx.debugRoot(), ctx.runId(), ctx.citySeedId(),
                        ctx.request().getAsJsonObject("terrasenseProfileSource"),
                        stringValue(state, "stateId"),
                        item,
                        null,
                        ctx.request().has("structureEnvelopeFactsSource")
                                && ctx.request().get("structureEnvelopeFactsSource").isJsonObject()
                                ? ctx.request().getAsJsonObject("structureEnvelopeFactsSource") : null);
            })) {
                return false;
            }
        }
        return ctx.workflow().runStep("city_finalize_d4_array_layout_loop", null, () -> {
            JsonObject state = loadArrayLayoutLoopState(ctx.debugRoot(), ctx.runDir(), ctx.citySeedId(), null);
            requireObject(ctx.request(), "terrasenseProfileSource", "city_finalize_d4_array_layout_loop");
            return handleFinalizeD4ArrayLayoutLoop(ctx.debugRoot(), ctx.runId(), ctx.citySeedId(),
                    ctx.request().getAsJsonObject("terrasenseProfileSource"),
                    stringValue(state, "stateId"),
                    null,
                    ctx.request().has("structureEnvelopeFactsSource")
                            && ctx.request().get("structureEnvelopeFactsSource").isJsonObject()
                            ? ctx.request().getAsJsonObject("structureEnvelopeFactsSource") : null);
        });
    }

    private static boolean workflowRunD4StructureClusterGroups(WorkflowContext ctx) throws IOException {
        Path anchorMapPath = ctx.runDir().resolve("city_d4_" + safeFileName(ctx.citySeedId()))
                .resolve("structure_anchor_map.json");
        if (booleanValue(ctx.request(), "skipExisting", true) && Files.exists(anchorMapPath)) {
            ctx.workflow().addSkippedStep("city_d4_structure_cluster_groups", anchorMapPath,
                    "Existing structure_anchor_map.json found.");
            return true;
        }
        Path candidateSetPath = ctx.runDir()
                .resolve("city_d4_structure_cluster_groups_" + safeFileName(ctx.citySeedId()))
                .resolve("structure_cluster_group_candidate_set.json");
        if (!ctx.workflow().runStep("city_plan_d4_structure_cluster_groups", candidateSetPath, () -> {
            requireObject(ctx.request(), "terrasenseProfileSource", "city_plan_d4_structure_cluster_groups");
            requireObject(ctx.request(), "designSlotPlan", "city_plan_d4_structure_cluster_groups");
            return handlePlanD4StructureClusterGroups(ctx.debugRoot(), ctx.runId(), ctx.citySeedId(),
                    ctx.request().getAsJsonObject("terrasenseProfileSource"),
                    ctx.request().getAsJsonObject("designSlotPlan"),
                    ctx.request().has("structureEnvelopeFactsSource")
                            && ctx.request().get("structureEnvelopeFactsSource").isJsonObject()
                            ? ctx.request().getAsJsonObject("structureEnvelopeFactsSource") : null,
                    hasValue(ctx.request(), "groupCount") ? intValue(ctx.request(), "groupCount", 5) : null,
                    hasValue(ctx.request(), "candidatesPerSlot")
                            ? intValue(ctx.request(), "candidatesPerSlot", 5) : null,
                    hasValue(ctx.request(), "beamWidth") ? intValue(ctx.request(), "beamWidth", 25) : null);
        })) {
            return false;
        }
        JsonObject candidateSet = JsonParser.parseString(Files.readString(candidateSetPath)).getAsJsonObject();
        JsonObject choice = WORKFLOW_CANDIDATE_SELECTOR.chooseStructureClusterGroup(candidateSet);
        return ctx.workflow().runStep("city_select_d4_structure_cluster_group", anchorMapPath, () -> {
            requireObject(ctx.request(), "terrasenseProfileSource", "city_select_d4_structure_cluster_group");
            return handleSelectD4StructureClusterGroup(ctx.debugRoot(), ctx.runId(), ctx.citySeedId(),
                    ctx.request().getAsJsonObject("terrasenseProfileSource"),
                    stringValue(choice, "groupCandidateId"),
                    ctx.request().has("structureEnvelopeFactsSource")
                            && ctx.request().get("structureEnvelopeFactsSource").isJsonObject()
                            ? ctx.request().getAsJsonObject("structureEnvelopeFactsSource") : null,
                    null);
        });
    }

    private static boolean workflowRunD4Session(WorkflowContext ctx) throws IOException {
        requireObject(ctx.request(), "designSlotPlan", "city_create_d4_candidate_session");
        return workflowRunD4Session(ctx, ctx.request().getAsJsonObject("designSlotPlan"), "city_d4_session");
    }

    private static boolean workflowRunD4Session(WorkflowContext ctx,
                                                JsonObject designSlotPlan,
                                                String stepPrefix) throws IOException {
        Path anchorMapPath = ctx.runDir().resolve("city_d4_" + safeFileName(ctx.citySeedId()))
                .resolve("structure_anchor_map.json");
        if (booleanValue(ctx.request(), "skipExisting", true) && Files.exists(anchorMapPath)) {
            ctx.workflow().addSkippedStep(stepPrefix, anchorMapPath,
                    "Existing structure_anchor_map.json found.");
            return true;
        }
        if (!ctx.workflow().runStep(d4SessionStepName(stepPrefix, "create_session", "city_create_d4_candidate_session"),
                d4SessionDir(ctx.runDir(), ctx.citySeedId())
                .resolve("d4_candidate_session.json"), () -> {
            requireObject(ctx.request(), "terrasenseProfileSource", stepPrefix + "_create_session");
            return handleCreateD4CandidateSession(ctx.debugRoot(), ctx.runId(), ctx.citySeedId(),
                    ctx.request().getAsJsonObject("terrasenseProfileSource"),
                    designSlotPlan,
                    ctx.request().has("structureEnvelopeFactsSource")
                            && ctx.request().get("structureEnvelopeFactsSource").isJsonObject()
                            ? ctx.request().getAsJsonObject("structureEnvelopeFactsSource") : null,
                    stringValue(ctx.request(), "sessionId", ""));
        })) {
            return false;
        }

        Path sessionPath = d4SessionDir(ctx.runDir(), ctx.citySeedId()).resolve("d4_candidate_session.json");
        for (int guard = 0; guard < 64; guard++) {
            JsonObject session = JsonParser.parseString(Files.readString(sessionPath)).getAsJsonObject();
            String currentSlotId = stringValue(session, "currentSlotId");
            if (currentSlotId.isBlank()) {
                break;
            }
            if (!ctx.workflow().runStep(d4SessionStepName(stepPrefix, "plan_next_candidates",
                    "city_plan_d4_next_candidates"), null,
                    () -> handlePlanD4NextCandidates(ctx.debugRoot(), ctx.runId(), ctx.citySeedId(),
                            ctx.request().has("structureEnvelopeFactsSource")
                                    && ctx.request().get("structureEnvelopeFactsSource").isJsonObject()
                                    ? ctx.request().getAsJsonObject("structureEnvelopeFactsSource") : null))) {
                return false;
            }
            Path candidatePath = d4SessionDir(ctx.runDir(), ctx.citySeedId()).resolve("slot_candidate_set.json");
            JsonObject candidateSet = JsonParser.parseString(Files.readString(candidatePath)).getAsJsonObject();
            JsonObject choice = WORKFLOW_CANDIDATE_SELECTOR.chooseAnchorCandidate(candidateSet);
            if (!ctx.workflow().runStep(d4SessionStepName(stepPrefix, "select_candidate",
                    "city_select_d4_candidate"), null,
                    () -> handleSelectD4Candidate(ctx.debugRoot(), ctx.runId(), ctx.citySeedId(),
                            stringValue(ctx.request(), "sessionId", ""),
                            stringValue(choice, "slotId"),
                            stringValue(choice, "candidateId"),
                            stringValue(choice, "slotId"),
                            "workflow auto-select highest scored candidate",
                            false))) {
                return false;
            }
        }
        return ctx.workflow().runStep(d4SessionStepName(stepPrefix, "finalize_session",
                "city_finalize_d4_candidate_session"), anchorMapPath, () -> {
            requireObject(ctx.request(), "terrasenseProfileSource", stepPrefix + "_finalize_session");
            return handleFinalizeD4CandidateSession(ctx.debugRoot(), ctx.runId(), ctx.citySeedId(),
                    ctx.request().getAsJsonObject("terrasenseProfileSource"),
                    ctx.request().has("structureEnvelopeFactsSource")
                            && ctx.request().get("structureEnvelopeFactsSource").isJsonObject()
                            ? ctx.request().getAsJsonObject("structureEnvelopeFactsSource") : null,
                    stringValue(ctx.request(), "sessionId", ""));
        });
    }

    private static String d4SessionStepName(String prefix, String suffix, String legacyName) {
        return "city_d4_session".equals(prefix) ? legacyName : prefix + "_" + suffix;
    }

    private static JsonObject workflowPlanD4ArrayStage(WorkflowContext ctx,
                                                       JsonObject arrayCandidatePlan,
                                                       JsonObject occupiedAnchorMap) throws IOException {
        requireObject(ctx.request(), "terrasenseProfileSource", "city_plan_d4_array_stage");
        CityLandformReviewPackage reviewPackage = loadD3Package(ctx.debugRoot(), ctx.runDir(), ctx.citySeedId());
        Path envelopeFactsPath = envelopeFactsPath(ctx.runDir(), ctx.citySeedId(),
                ctx.request().has("structureEnvelopeFactsSource")
                        && ctx.request().get("structureEnvelopeFactsSource").isJsonObject()
                        ? ctx.request().getAsJsonObject("structureEnvelopeFactsSource") : null);
        CityStructureEnvelopeFacts envelopeFacts = CityStructureEnvelopeFacts.load(envelopeFactsPath);
        CityStructureArrayCandidatePlanner.Result result = new CityStructureArrayCandidatePlanner()
                .plan(ctx.runDir(), reviewPackage, ctx.request().getAsJsonObject("terrasenseProfileSource"),
                        arrayCandidatePlan, envelopeFacts, occupiedAnchorMap, new JsonArray());

        String arrayId = stringValue(arrayCandidatePlan, "arrayId", "array");
        Path outputDirectory = d4ArrayStageDir(ctx.runDir(), ctx.citySeedId(), arrayId);
        Files.createDirectories(outputDirectory);
        Path planPath = outputDirectory.resolve("d4_array_candidate_plan.json");
        Path candidateSetPath = outputDirectory.resolve("d4_array_candidate_set.json");
        Path qualityPath = outputDirectory.resolve("quality_report.json");
        Files.writeString(planPath, CityJson.GSON.toJson(result.arrayCandidatePlan()));
        Files.writeString(candidateSetPath, CityJson.GSON.toJson(result.arrayCandidateSet()));
        Files.writeString(qualityPath, CityJson.GSON.toJson(result.qualityReport()));
        Path previewPath = new CityStructureLandingPreviewRenderer()
                .renderD4ArrayCandidates(result.arrayCandidateSet(), reviewPackage, outputDirectory);

        JsonObject response = result.asJson();
        JsonObject artifacts = new JsonObject();
        artifacts.addProperty("arrayCandidatePlan", debugRef(ctx.debugRoot(), planPath));
        artifacts.addProperty("arrayCandidateSet", debugRef(ctx.debugRoot(), candidateSetPath));
        artifacts.addProperty("arrayCandidatePreview", debugRef(ctx.debugRoot(), previewPath));
        artifacts.addProperty("qualityReport", debugRef(ctx.debugRoot(), qualityPath));
        if (envelopeFactsPath != null && Files.exists(envelopeFactsPath)) {
            artifacts.addProperty("sourceStructureEnvelopeFacts", debugRef(ctx.debugRoot(), envelopeFactsPath));
        }
        response.add("artifacts", artifacts);
        return response;
    }

    private static Path d4ArrayStageDir(Path runDir, String citySeedId, String arrayId) {
        return runDir.resolve("city_d4_array_candidates_" + safeFileName(citySeedId)
                + "_" + safeFileName(arrayId));
    }

    private static Path d4ArrayLayoutDir(Path runDir, String citySeedId) {
        return runDir.resolve("city_d4_array_layout_" + safeFileName(citySeedId));
    }

    private static Path d4DesignLoopDir(Path runDir, String citySeedId) {
        return runDir.resolve("city_d4_design_loop_" + safeFileName(citySeedId));
    }

    private static JsonObject loadOptionalLandUseFunctionalArrayZones(Path runDir,
                                                                      String citySeedId) throws IOException {
        for (Path candidate : List.of(
                d4DesignLoopDir(runDir, citySeedId).resolve("d4_design_loop_array_zones.json"),
                d4ArrayLayoutDir(runDir, citySeedId).resolve("d4_functional_array_zones.json"))) {
            if (Files.isRegularFile(candidate)) {
                JsonElement parsed = JsonParser.parseString(Files.readString(candidate));
                if (!parsed.isJsonObject()) {
                    throw new IllegalArgumentException("LAND_USE_FUNCTIONAL_ARRAY_ZONES_INVALID: " + candidate);
                }
                return parsed.getAsJsonObject();
            }
        }
        return null;
    }

    private static LandUseSettings loadLandUseSettings() {
        Path root = defaultLandUseConfigRoot();
        try {
            return new LandUseSettingsLoader().load(LandUseDefaultConfigBootstrap.ensureInstalled(root));
        } catch (IOException ex) {
            throw new IllegalArgumentException("LAND_USE_DEFAULT_CONFIG_BOOTSTRAP_FAILED: " + root, ex);
        }
    }

    private static LandUseSettings loadWorkflowLandUseSettings() {
        if (FMLPaths.CONFIGDIR.get() == null) {
            return new LandUseSettings(LandUseSettings.SCHEMA, false, LandUseSettings.DEFAULT_PROFILE_ID);
        }
        return loadLandUseSettings();
    }

    private static Path defaultLandUseConfigRoot() {
        Path configDir = FMLPaths.CONFIGDIR.get();
        if (configDir == null) {
            throw new IllegalArgumentException("LAND_USE_CONFIG_ROOT_UNAVAILABLE: Forge config directory is not initialized.");
        }
        return configDir.resolve("geomantia").resolve("city_land_use");
    }

    private static Path defaultDecorationCatalogRoot() {
        Path catalogRoot = optionalDefaultDecorationCatalogRoot();
        if (catalogRoot == null) {
            throw new IllegalArgumentException("CITY_DECORATION_CATALOG_ROOT_UNAVAILABLE: Forge config directory is not initialized.");
        }
        try {
            return CityDecorationDefaultCatalogBootstrap.ensureInstalled(catalogRoot);
        } catch (IOException ex) {
            throw new IllegalArgumentException("CITY_DECORATION_DEFAULT_CATALOG_BOOTSTRAP_FAILED: "
                    + catalogRoot, ex);
        }
    }

    private static Path optionalDefaultDecorationCatalogRoot() {
        Path configDir = FMLPaths.CONFIGDIR.get();
        if (configDir == null) {
            return null;
        }
        return configDir.resolve("geomantia").resolve("city_decoration");
    }

    private static void rejectLegacyDressingPlan(JsonObject plan) {
        if (plan == null) {
            throw new IllegalArgumentException("CITY_DECORATION_PROGRAM_PLAN_REQUIRED: decorationProgramPlan is required.");
        }
        String schema = stringValue(plan, "schemaVersion", "");
        if (schema.startsWith("city_dressing_brush_plan.v0.1")
                || plan.has("dressingLayoutItems") || plan.has("brushes") || plan.has("dressingBrushPlan")) {
            throw new IllegalArgumentException("CITY_DRESSING_LEGACY_SCHEMA_REMOVED: v0.1 dressingBrushPlan is no longer accepted.");
        }
    }

    private static JsonObject decorationCatalogSummary(CityDecorationContentCatalog catalog,
                                                       CityDecorationStyleProfileCatalog styleProfiles) {
        JsonObject response = new JsonObject();
        response.addProperty("schemaVersion", "city_decoration_catalog_query.v0.2");
        response.addProperty("catalogHash", catalog.catalogHash());
        JsonArray contents = new JsonArray();
        for (CityDecorationContentCatalog.Content content : catalog.contents().values()) {
            JsonObject summary = new JsonObject();
            summary.addProperty("contentRef", content.contentId());
            summary.addProperty("contentKind", content.contentKind());
            JsonArray rotations = new JsonArray();
            content.allowedRotations().forEach(rotations::add);
            summary.add("allowedRotations", rotations);
            summary.addProperty("supportMode", content.supportMode());
            summary.addProperty("placementMode", content.placementMode());
            summary.addProperty("replacePolicy", content.replacePolicy());
            summary.addProperty("maxFootprintHeightSpreadBlocks", content.maxFootprintHeightSpreadBlocks());
            summary.addProperty("comfortMarginBlocks", content.comfortMarginBlocks());
            JsonArray tags = new JsonArray();
            content.tags().forEach(tags::add);
            summary.add("tags", tags);
            JsonObject size = new JsonObject();
            size.addProperty("widthBlocks", content.size().widthBlocks());
            size.addProperty("heightBlocks", content.size().heightBlocks());
            size.addProperty("depthBlocks", content.size().depthBlocks());
            summary.add("size", size);
            summary.addProperty("contentHash", content.contentHash());
            contents.add(summary);
        }
        response.add("contents", contents);
        JsonArray profiles = new JsonArray();
        styleProfiles.profiles().values().forEach(profile -> {
            JsonObject summary = new JsonObject();
            summary.addProperty("styleProfileId", profile.styleProfileId());
            summary.addProperty("styleProfileHash", profile.styleProfileHash());
            JsonArray semanticRefs = new JsonArray();
            profile.mappings().keySet().stream().sorted().forEach(semanticRefs::add);
            summary.add("semanticRefs", semanticRefs);
            profiles.add(summary);
        });
        response.add("styleProfiles", profiles);
        return response;
    }

    private static void validateDecorationContentRefs(DecorationProgramIntentPlan plan,
                                                      CityDecorationContentCatalog catalog) {
        for (DecorationProgramIntent program : plan.programs()) {
            for (CompiledDecorationProgram.PaletteSlot slot : program.contentPalette().slots()) {
                for (CompiledDecorationProgram.ContentEntry entry : slot.entries()) {
                    catalog.requireContent(entry.contentRef());
                }
            }
        }
    }

    private static void validateCompiledDecorationContentRefs(CompiledDecorationProgramPlan plan,
                                                              CityDecorationContentCatalog catalog) {
        for (CompiledDecorationProgram program : plan.programs()) {
            for (CompiledDecorationProgram.PaletteSlot slot : program.contentPalette().slots()) {
                for (CompiledDecorationProgram.ContentEntry entry : slot.entries()) {
                    catalog.requireContent(entry.contentRef());
                }
            }
        }
    }

    private static void validateCompiledDecorationStyleProfile(CompiledDecorationProgramPlan plan,
                                                               CityDecorationContentCatalog catalog,
                                                               Path catalogRoot) {
        CityDecorationStyleProfileCatalog styles = new CityDecorationStyleProfileCatalogLoader()
                .load(catalogRoot, catalog);
        CityDecorationStyleProfileCatalog.StyleProfile profile = styles.requireProfile(plan.styleProfileId());
        if (!profile.styleProfileHash().equals(plan.styleProfileHash())) {
            throw new IllegalArgumentException("CITY_DECORATION_STYLE_PROFILE_HASH_MISMATCH: expected "
                    + profile.styleProfileHash() + " but found " + plan.styleProfileHash());
        }
    }

    private static void validateLandUseCompletion(JsonObject completion,
                                                  LandUseAreaPlan plan,
                                                  String expectedCityId,
                                                  String expectedRuleProfileHash,
                                                  String expectedSourceD6Hash) {
        if (!"city_land_use_planning_complete.v0.1".equals(
                stringValue(completion, "schemaVersion", ""))) {
            throw new IllegalArgumentException("CITY_LAND_USE_PLAN_INCOMPLETE: completion schema is invalid.");
        }
        if (!expectedCityId.equals(stringValue(completion, "cityId", ""))
                || !plan.cityId().equals(stringValue(completion, "cityId", ""))) {
            throw new IllegalArgumentException("CITY_LAND_USE_PLAN_INCOMPLETE: completion cityId does not match plan.");
        }
        if (plan.planHash().isBlank()
                || !plan.planHash().equals(stringValue(completion, "planHash", ""))) {
            throw new IllegalArgumentException("CITY_LAND_USE_PLAN_INCOMPLETE: completion planHash does not match plan.");
        }
        if (!LandUseRuleCatalog.RULE_VERSION.equals(plan.ruleVersion())) {
            throw new IllegalArgumentException("CITY_LAND_USE_RULE_VERSION_MISMATCH: " + plan.ruleVersion());
        }
        if (!expectedRuleProfileHash.equals(stringValue(completion, "ruleProfileHash", ""))) {
            throw new IllegalArgumentException("CITY_LAND_USE_RULE_PROFILE_HASH_MISMATCH");
        }
        if (!expectedSourceD6Hash.equals(stringValue(completion, "sourceD6Hash", ""))) {
            throw new IllegalArgumentException("CITY_LAND_USE_SOURCE_D6_HASH_MISMATCH");
        }
        if (stringValue(completion, "completedAt", "").isBlank()) {
            throw new IllegalArgumentException("CITY_LAND_USE_PLAN_INCOMPLETE: completion completedAt is required.");
        }
    }

    private static JsonObject landUseChunkPreflightJson(
            CityLandUseChunkStatusPreflight.PreflightResult result) {
        JsonObject json = new JsonObject();
        json.addProperty("eligible", result.eligible());
        json.addProperty("reasonCode", result.reasonCode());
        json.addProperty("ownerChunkCount", result.ownerChunkCount());
        json.addProperty("featuresOrLaterCount", result.featuresOrLaterCount());
        json.addProperty("unknownCount", result.unknownCount());
        return json;
    }

    private static void validateDecorationCompletion(JsonObject completion,
                                                     CompiledDecorationProgramPlan compiledPlan,
                                                     String expectedCityId) {
        if (!"city_decoration_planning_complete.v0.2".equals(stringValue(completion, "schemaVersion", ""))) {
            throw new IllegalArgumentException("CITY_DECORATION_PLAN_INCOMPLETE: completion schema is invalid.");
        }
        if (!expectedCityId.equals(stringValue(completion, "cityId", ""))
                || !compiledPlan.cityId().equals(stringValue(completion, "cityId", ""))) {
            throw new IllegalArgumentException("CITY_DECORATION_PLAN_INCOMPLETE: completion cityId does not match compiled plan.");
        }
        if (!compiledPlan.catalogHash().equals(stringValue(completion, "catalogHash", ""))) {
            throw new IllegalArgumentException("CITY_DECORATION_PLAN_INCOMPLETE: completion catalogHash does not match compiled plan.");
        }
        if (!compiledPlan.styleProfileId().equals(stringValue(completion, "styleProfileId", ""))
                || !compiledPlan.styleProfileHash().equals(stringValue(completion, "styleProfileHash", ""))) {
            throw new IllegalArgumentException("CITY_DECORATION_PLAN_INCOMPLETE: completion style profile does not match compiled plan.");
        }
        if (stringValue(completion, "completedAt", "").isBlank()) {
            throw new IllegalArgumentException("CITY_DECORATION_PLAN_INCOMPLETE: completion completedAt is required.");
        }
    }

    private static JsonObject readDecorationCompletion(Path completionPath) throws IOException {
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(completionPath));
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("completion root must be an object");
            }
            return parsed.getAsJsonObject();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("CITY_DECORATION_PLAN_INCOMPLETE: completion marker is invalid: "
                    + completionPath, ex);
        }
    }

    private static List<CompiledDecorationProgramPlan.HardObstacle> decorationHardObstacles(
            JsonObject materializationPlan,
            JsonObject wallReservationPlan,
            JsonObject roadConnectionPlan) {
        List<CompiledDecorationProgramPlan.HardObstacle> result = new ArrayList<>();
        for (JsonElement element : array(materializationPlan, "plannedWorldgenStructures")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject structure = element.getAsJsonObject();
            String sourceRef = stringValue(structure, "anchorId", "planned_structure");
            for (String field : List.of("lockedCollisionEnvelope", "lockedActualFootprint")) {
                if (structure.has(field) && structure.get(field).isJsonObject()) {
                    result.add(new CompiledDecorationProgramPlan.HardObstacle(
                            "structure_" + field, sourceRef, bounds(structure.getAsJsonObject(field))));
                }
            }
        }
        appendDecorationMaskObstacles(result, wallReservationPlan, "wallCorridorMask", "wall_corridor");
        appendDecorationMaskObstacles(result, wallReservationPlan, "gateCorridorMask", "gate_corridor");
        for (JsonElement element : array(roadConnectionPlan, "connections")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject connection = element.getAsJsonObject();
            JsonObject from = object(connection, "from");
            JsonObject to = object(connection, "to");
            BlockBounds segment = new BlockBounds(
                    Math.min(intValue(from, "x", 0), intValue(to, "x", 0)),
                    Math.min(intValue(from, "z", 0), intValue(to, "z", 0)),
                    Math.max(intValue(from, "x", 0), intValue(to, "x", 0)),
                    Math.max(intValue(from, "z", 0), intValue(to, "z", 0)));
            result.add(new CompiledDecorationProgramPlan.HardObstacle("planned_road_corridor",
                    stringValue(connection, "connectionId", "planned_road"), expandBounds(segment, 4)));
        }
        return List.copyOf(result);
    }

    private static void appendLandUseDecorationObstacles(
            List<CompiledDecorationProgramPlan.HardObstacle> target,
            LandUseAreaPlan plan) {
        for (LandUseAreaPlan.CorridorExclusion corridor : plan.corridorExclusions()) {
            target.add(new CompiledDecorationProgramPlan.HardObstacle(
                    "land_use_entrance_corridor", corridor.exclusionId(), corridor.blockBounds()));
        }
        for (LandUseAreaPlan.Area area : plan.areas()) {
            for (LandUseAreaPlan.GateSlot gate : area.gateSlots()) {
                target.add(new CompiledDecorationProgramPlan.HardObstacle(
                        "land_use_gate", gate.gateId(),
                        new BlockBounds(gate.block().x(), gate.block().z(), gate.block().x(), gate.block().z())));
            }
        }
    }

    private static void appendDecorationMaskObstacles(
            List<CompiledDecorationProgramPlan.HardObstacle> target,
            JsonObject plan,
            String arrayKey,
            String obstacleType) {
        int index = 0;
        for (JsonElement element : array(plan, arrayKey)) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject mask = element.getAsJsonObject();
            if (!mask.has("blockBounds") || !mask.get("blockBounds").isJsonObject()) {
                continue;
            }
            target.add(new CompiledDecorationProgramPlan.HardObstacle(obstacleType,
                    stringValue(mask, "maskId", arrayKey + "_" + index),
                    bounds(mask.getAsJsonObject("blockBounds"))));
            index++;
        }
    }

    private static BlockBounds decorationProjectionBounds(List<CompiledDecorationProgram> programs) {
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (CompiledDecorationProgram program : programs) {
            BlockBounds bounds = program.targetMask().bounds();
            minX = Math.min(minX, bounds.minX());
            minZ = Math.min(minZ, bounds.minZ());
            maxX = Math.max(maxX, bounds.maxX());
            maxZ = Math.max(maxZ, bounds.maxZ());
        }
        return new BlockBounds(minX, minZ, maxX, maxZ);
    }

    private static void validateDecorationSlotProjection(CompiledDecorationProgramPlan compiledDecorationPlan,
                                                         List<DecorationSlot> projectedSlots,
                                                         String mismatchCode) {
        List<DecorationSlot> expectedSlots = new CityDecorationProgramPlanner().project(compiledDecorationPlan,
                decorationProjectionBounds(compiledDecorationPlan.programs()));
        if (expectedSlots.size() != projectedSlots.size()) {
            throw new IllegalArgumentException(mismatchCode);
        }
        Map<String, DecorationSlot> expectedById = new LinkedHashMap<>();
        for (DecorationSlot expected : expectedSlots) {
            if (expectedById.put(expected.slotId(), expected) != null) {
                throw new IllegalArgumentException(mismatchCode);
            }
        }
        for (DecorationSlot projected : projectedSlots) {
            if (!projected.equals(expectedById.remove(projected.slotId()))) {
                throw new IllegalArgumentException(mismatchCode);
            }
        }
        if (!expectedById.isEmpty()) {
            throw new IllegalArgumentException(mismatchCode);
        }
    }

    private static DecorationMaskCounts appendDecorationProjectionMasks(JsonObject activeMaskPlan,
                                                                          CompiledDecorationProgramPlan compiledDecorationPlan,
                                                                          List<DecorationSlot> projectedSlots) {
        JsonArray noVegetation = activeMaskPlan.has("noVegetationMask")
                && activeMaskPlan.get("noVegetationMask").isJsonArray()
                ? activeMaskPlan.getAsJsonArray("noVegetationMask") : new JsonArray();
        JsonArray noVanillaStructure = activeMaskPlan.has("noVanillaStructureMask")
                && activeMaskPlan.get("noVanillaStructureMask").isJsonArray()
                ? activeMaskPlan.getAsJsonArray("noVanillaStructureMask") : new JsonArray();
        JsonArray reasons = activeMaskPlan.has("reservationReason")
                && activeMaskPlan.get("reservationReason").isJsonArray()
                ? activeMaskPlan.getAsJsonArray("reservationReason") : new JsonArray();
        Map<String, BlockBounds> projectionBoundsByProgram = new LinkedHashMap<>();
        for (DecorationSlot slot : projectedSlots) {
            BlockBounds slotBounds = new BlockBounds(slot.worldAnchor().x(), slot.worldAnchor().z(),
                    slot.worldAnchor().x(), slot.worldAnchor().z());
            projectionBoundsByProgram.merge(slot.programId(), slotBounds,
                    CityPlanningEndpointHandler::unionBounds);
        }
        int vegetationMaskCount = 0;
        int structureMaskCount = 0;
        for (CompiledDecorationProgram program : compiledDecorationPlan.programs()) {
            BlockBounds projection = projectionBoundsByProgram.get(program.programId());
            if (projection == null) {
                continue;
            }
            String sourceRef = "decoration_program:" + program.programId();
            addMask(noVegetation, "decoration_" + program.programId() + "_no_vegetation", projection,
                    "decoration_projection", sourceRef);
            addMask(noVanillaStructure, "decoration_" + program.programId() + "_no_vanilla_structure",
                    projection, "decoration_projection", sourceRef);
            addReason(reasons, program.programId(), "decoration_projection", projection,
                    "suppress vegetation and normal worldgen structure starts in the planned decoration projection "
                            + "before worldgen placement");
            vegetationMaskCount++;
            structureMaskCount++;
        }
        activeMaskPlan.add("noVegetationMask", noVegetation);
        activeMaskPlan.add("noVanillaStructureMask", noVanillaStructure);
        activeMaskPlan.add("reservationReason", reasons);
        return new DecorationMaskCounts(vegetationMaskCount, structureMaskCount);
    }

    private static BlockBounds unionBounds(BlockBounds first, BlockBounds second) {
        return new BlockBounds(Math.min(first.minX(), second.minX()), Math.min(first.minZ(), second.minZ()),
                Math.max(first.maxX(), second.maxX()), Math.max(first.maxZ(), second.maxZ()));
    }

    private static JsonObject decorationSlotProjection(CompiledDecorationProgramPlan plan,
                                                       List<DecorationSlot> slots) {
        JsonObject projection = new JsonObject();
        projection.addProperty("schemaVersion", "city_decoration_slot_projection.v0.2");
        projection.addProperty("cityId", plan.cityId());
        projection.addProperty("catalogHash", plan.catalogHash());
        JsonArray array = new JsonArray();
        for (DecorationSlot slot : slots) {
            JsonObject item = new JsonObject();
            item.addProperty("slotId", slot.slotId());
            item.addProperty("programId", slot.programId());
            item.addProperty("paletteSlotId", slot.paletteSlotId());
            item.add("worldAnchor", slot.worldAnchor().asJson());
            JsonObject local = new JsonObject();
            local.addProperty("u", slot.localAnchor().u());
            local.addProperty("v", slot.localAnchor().v());
            item.add("localAnchor", local);
            item.addProperty("rotationQuarterTurns", slot.rotationQuarterTurns());
            array.add(item);
        }
        projection.add("slots", array);
        return projection;
    }

    private static JsonObject decorationPlanningQuality(DecorationProgramIntentPlan intent,
                                                        CompiledDecorationProgramPlan compiled,
                                                        List<DecorationSlot> slots) {
        JsonObject quality = new JsonObject();
        quality.addProperty("schemaVersion", "city_decoration_quality_report.v0.2");
        quality.addProperty("passed", true);
        quality.addProperty("score", 100);
        quality.add("warnings", new JsonArray());
        quality.add("hardBlocks", new JsonArray());
        JsonObject metrics = new JsonObject();
        metrics.addProperty("intentProgramCount", intent.programs().size());
        metrics.addProperty("compiledProgramCount", compiled.programs().size());
        metrics.addProperty("projectedSlotCount", slots.size());
        quality.add("metrics", metrics);
        return quality;
    }

    private static JsonObject decorationPlanningTrace(DecorationProgramIntentPlan intent,
                                                      CompiledDecorationProgramPlan compiled,
                                                      List<DecorationSlot> slots) {
        JsonObject trace = new JsonObject();
        trace.addProperty("schemaVersion", "city_decoration_planning_trace.v0.2");
        trace.addProperty("cityId", intent.cityId());
        trace.addProperty("catalogHash", intent.catalogHash());
        trace.addProperty("projectedSlotCount", slots.size());
        trace.addProperty("hardObstacleCount", compiled.hardObstacles().size());
        JsonArray obstacles = new JsonArray();
        for (CompiledDecorationProgramPlan.HardObstacle obstacle : compiled.hardObstacles()) {
            JsonObject item = new JsonObject();
            item.addProperty("obstacleType", obstacle.obstacleType());
            item.addProperty("sourceRef", obstacle.sourceRef());
            item.add("blockBounds", boundsJson(obstacle.blockBounds()));
            obstacles.add(item);
        }
        trace.add("hardObstacles", obstacles);
        JsonArray programs = new JsonArray();
        for (int i = 0; i < intent.programs().size(); i++) {
            DecorationProgramIntent source = intent.programs().get(i);
            CompiledDecorationProgram resolved = compiled.programs().get(i);
            JsonObject item = new JsonObject();
            item.addProperty("programId", source.programId());
            item.addProperty("targetSourceType", source.targetArea().sourceType());
            item.addProperty("targetRef", source.targetArea().ref());
            item.addProperty("resolvedMaskMemberCount", resolved.targetMask().memberBounds().size());
            item.add("resolvedOrigin", resolved.coordinateFrame().origin().asJson());
            item.addProperty("shapeType", source.shape().type());
            item.addProperty("patternType", source.pattern().type());
            programs.add(item);
        }
        trace.add("programs", programs);
        return trace;
    }

    private static Path decorationDir(Path runDir, String citySeedId) {
        return runDir.resolve("city_decoration_" + safeFileName(citySeedId));
    }

    private static JsonObject writeD4ArrayLayoutLoopArtifacts(Path debugRoot,
                                                              Path runDir,
                                                              String citySeedId,
                                                              JsonObject loopState,
                                                              CityLandformReviewPackage reviewPackage,
                                                              Path envelopeFactsPath) throws IOException {
        Path outputDirectory = d4ArrayLayoutDir(runDir, citySeedId);
        Files.createDirectories(outputDirectory);
        Path planPath = outputDirectory.resolve("d4_array_layout_plan.json");
        Path statePath = outputDirectory.resolve("d4_array_layout_loop_state.json");
        Path tracePath = outputDirectory.resolve("d4_array_layout_execution_trace.json");
        Path occupiedPath = outputDirectory.resolve("d4_array_occupied_field.json");
        Path patchAvailabilityPath = outputDirectory.resolve("d4_array_patch_availability.json");
        Path zonesPath = outputDirectory.resolve("d4_functional_array_zones.json");
        Path qualityPath = outputDirectory.resolve("quality_report.json");
        Files.writeString(planPath, CityJson.GSON.toJson(object(loopState, "accumulatedArrayLayoutPlan")));
        Files.writeString(statePath, CityJson.GSON.toJson(loopState));
        Files.writeString(tracePath, CityJson.GSON.toJson(object(loopState, "executionTrace")));
        JsonObject occupied = new JsonObject();
        occupied.addProperty("schemaVersion", CityStructureArrayLayoutLoopPlanner.OCCUPIED_SCHEMA);
        occupied.addProperty("planningMode", stringValue(loopState, "planningMode",
                CityStructureArrayLayoutLoopPlanner.PLANNING_MODE_V02));
        occupied.add("occupiedEnvelopes", array(loopState, "occupiedEnvelopes").deepCopy());
        Files.writeString(occupiedPath, CityJson.GSON.toJson(occupied));
        Files.writeString(patchAvailabilityPath, CityJson.GSON.toJson(object(loopState, "patchAvailability")));
        Files.writeString(zonesPath, CityJson.GSON.toJson(object(loopState, "functionalArrayZones")));
        Files.writeString(qualityPath, CityJson.GSON.toJson(object(loopState, "quality")));
        Path previewPath = new CityStructureLandingPreviewRenderer()
                .renderD4ArrayLayoutLoop(loopState, reviewPackage, outputDirectory);

        JsonObject artifacts = new JsonObject();
        artifacts.addProperty("arrayLayoutPlan", debugRef(debugRoot, planPath));
        artifacts.addProperty("arrayLayoutLoopState", debugRef(debugRoot, statePath));
        artifacts.addProperty("arrayLayoutExecutionTrace", debugRef(debugRoot, tracePath));
        artifacts.addProperty("arrayLayoutOccupiedField", debugRef(debugRoot, occupiedPath));
        artifacts.addProperty("arrayLayoutPatchAvailability", debugRef(debugRoot, patchAvailabilityPath));
        artifacts.addProperty("functionalArrayZones", debugRef(debugRoot, zonesPath));
        artifacts.addProperty("arrayLayoutPreview", debugRef(debugRoot, previewPath));
        artifacts.addProperty("qualityReport", debugRef(debugRoot, qualityPath));
        Path d3Path = d3PackagePath(runDir, citySeedId);
        if (Files.exists(d3Path)) {
            artifacts.addProperty("sourceD3Package", debugRef(debugRoot, d3Path));
        }
        if (envelopeFactsPath != null && Files.exists(envelopeFactsPath)) {
            artifacts.addProperty("sourceStructureEnvelopeFacts", debugRef(debugRoot, envelopeFactsPath));
        }
        return artifacts;
    }

    private static JsonObject writeD4ArrayExpansionSpaceArtifact(Path debugRoot,
                                                                   Path runDir,
                                                                   String citySeedId,
                                                                   JsonObject expansionSpace) throws IOException {
        Path outputDirectory = d4ArrayLayoutDir(runDir, citySeedId);
        Files.createDirectories(outputDirectory);
        Path spacePath = outputDirectory.resolve("d4_array_expansion_space.json");
        Files.writeString(spacePath, CityJson.GSON.toJson(expansionSpace));
        JsonObject artifacts = new JsonObject();
        artifacts.addProperty("arrayExpansionSpace", debugRef(debugRoot, spacePath));
        return artifacts;
    }

    private static JsonObject writeD4ArrayExpansionCandidateArtifacts(Path debugRoot,
                                                                        Path runDir,
                                                                        String citySeedId,
                                                                        JsonObject candidateSet,
                                                                        CityLandformReviewPackage reviewPackage,
                                                                        Path envelopeFactsPath) throws IOException {
        Path outputDirectory = d4ArrayLayoutDir(runDir, citySeedId);
        Files.createDirectories(outputDirectory);
        Path setPath = d4ArrayExpansionCandidateSetPath(runDir, citySeedId, null);
        Path spacePath = outputDirectory.resolve("d4_array_expansion_space.json");
        Path previewPath = new CityStructureLandingPreviewRenderer()
                .renderD4ArrayExpansionCandidates(candidateSet, reviewPackage, outputDirectory);
        Path qualityPath = outputDirectory.resolve("d4_array_expansion_candidate_quality_report.json");
        Files.writeString(setPath, CityJson.GSON.toJson(candidateSet));
        Files.writeString(spacePath, CityJson.GSON.toJson(object(candidateSet, "expansionSpace")));
        Files.writeString(qualityPath, CityJson.GSON.toJson(object(candidateSet, "qualityReport")));
        JsonObject artifacts = d4ArrayExpansionCandidateArtifactRefs(debugRoot, runDir, citySeedId);
        artifacts.addProperty("arrayExpansionCandidatePreview", debugRef(debugRoot, previewPath));
        artifacts.addProperty("arrayExpansionCandidateQualityReport", debugRef(debugRoot, qualityPath));
        if (envelopeFactsPath != null && Files.exists(envelopeFactsPath)) {
            artifacts.addProperty("sourceStructureEnvelopeFacts", debugRef(debugRoot, envelopeFactsPath));
        }
        return artifacts;
    }

    private static JsonObject d4ArrayExpansionCandidateArtifactRefs(Path debugRoot, Path runDir, String citySeedId) {
        Path outputDirectory = d4ArrayLayoutDir(runDir, citySeedId);
        JsonObject artifacts = new JsonObject();
        addArtifactIfExists(debugRoot, artifacts, "arrayExpansionCandidateSet",
                outputDirectory.resolve("d4_array_expansion_candidate_set.json"));
        addArtifactIfExists(debugRoot, artifacts, "arrayExpansionSpace",
                outputDirectory.resolve("d4_array_expansion_space.json"));
        addArtifactIfExists(debugRoot, artifacts, "arrayExpansionCandidatePreview",
                outputDirectory.resolve("d4_array_expansion_candidates.png"));
        addArtifactIfExists(debugRoot, artifacts, "arrayExpansionCandidateQualityReport",
                outputDirectory.resolve("d4_array_expansion_candidate_quality_report.json"));
        return artifacts;
    }

    private static JsonObject writeD4DesignLoopArtifacts(Path debugRoot,
                                                         Path runDir,
                                                         String citySeedId,
                                                         JsonObject loopState) throws IOException {
        Path outputDirectory = d4DesignLoopDir(runDir, citySeedId);
        Files.createDirectories(outputDirectory);
        Path statePath = outputDirectory.resolve("d4_design_loop_state.json");
        Path occupiedPath = outputDirectory.resolve("d4_design_loop_occupied_field.json");
        Path functionZonesPath = outputDirectory.resolve("d4_design_loop_function_zones.json");
        Path arrayZonesPath = outputDirectory.resolve("d4_design_loop_array_zones.json");
        Path patchAvailabilityPath = outputDirectory.resolve("d4_design_loop_patch_availability.json");
        Path summaryPath = outputDirectory.resolve("d4_design_loop_next_ai_context_summary.json");
        Path tracePath = outputDirectory.resolve("d4_design_loop_execution_trace.json");
        Path qualityPath = outputDirectory.resolve("quality_report.json");
        Files.writeString(statePath, CityJson.GSON.toJson(loopState));
        Files.writeString(occupiedPath, CityJson.GSON.toJson(object(loopState, "occupiedField")));
        Files.writeString(functionZonesPath, CityJson.GSON.toJson(object(loopState, "functionZones")));
        Files.writeString(arrayZonesPath, CityJson.GSON.toJson(object(loopState, "arrayZones")));
        Files.writeString(patchAvailabilityPath, CityJson.GSON.toJson(object(loopState, "patchAvailability")));
        Files.writeString(summaryPath, CityJson.GSON.toJson(object(loopState, "nextAiContextSummary")));
        Files.writeString(tracePath, CityJson.GSON.toJson(object(loopState, "executionTrace")));
        Files.writeString(qualityPath, CityJson.GSON.toJson(object(loopState, "quality")));
        return d4DesignLoopArtifactRefs(debugRoot, runDir, citySeedId);
    }

    private static JsonObject d4DesignLoopArtifactRefs(Path debugRoot, Path runDir, String citySeedId) {
        Path outputDirectory = d4DesignLoopDir(runDir, citySeedId);
        JsonObject artifacts = new JsonObject();
        addArtifactIfExists(debugRoot, artifacts, "designLoopState",
                outputDirectory.resolve("d4_design_loop_state.json"));
        addArtifactIfExists(debugRoot, artifacts, "designLoopOccupiedField",
                outputDirectory.resolve("d4_design_loop_occupied_field.json"));
        addArtifactIfExists(debugRoot, artifacts, "designLoopFunctionZones",
                outputDirectory.resolve("d4_design_loop_function_zones.json"));
        addArtifactIfExists(debugRoot, artifacts, "designLoopArrayZones",
                outputDirectory.resolve("d4_design_loop_array_zones.json"));
        addArtifactIfExists(debugRoot, artifacts, "designLoopPatchAvailability",
                outputDirectory.resolve("d4_design_loop_patch_availability.json"));
        addArtifactIfExists(debugRoot, artifacts, "designLoopNextAiContextSummary",
                outputDirectory.resolve("d4_design_loop_next_ai_context_summary.json"));
        addArtifactIfExists(debugRoot, artifacts, "designLoopExecutionTrace",
                outputDirectory.resolve("d4_design_loop_execution_trace.json"));
        addArtifactIfExists(debugRoot, artifacts, "qualityReport",
                outputDirectory.resolve("quality_report.json"));
        Path d3Path = d3PackagePath(runDir, citySeedId);
        addArtifactIfExists(debugRoot, artifacts, "sourceD3Package", d3Path);
        return artifacts;
    }

    private static void addArtifactIfExists(Path debugRoot, JsonObject artifacts, String key, Path path) {
        if (Files.exists(path)) {
            artifacts.addProperty(key, debugRef(debugRoot, path));
        }
    }

    private static void writeWorkflowD4StagePlan(WorkflowContext ctx,
                                                 CityD4StagedPlanCompiler.StagePlan stagePlan) throws IOException {
        Path outputDirectory = ctx.runDir().resolve("city_d4_staged_" + safeFileName(ctx.citySeedId()));
        Files.createDirectories(outputDirectory);
        Path path = outputDirectory.resolve("d4_staged_plan.json");
        JsonObject obj = new JsonObject();
        obj.addProperty("schemaVersion", "city_d4_staged_plan.v0.1");
        obj.addProperty("planningMode", "key_then_array");
        obj.add("sourceDesignSlotPlan", stagePlan.sourceDesignSlotPlan().deepCopy());
        obj.add("keyDesignSlotPlan", stagePlan.keyDesignSlotPlan().deepCopy());
        JsonArray arrays = new JsonArray();
        for (JsonObject slot : stagePlan.arraySlots()) {
            JsonObject array = new JsonObject();
            array.addProperty("slotId", stringValue(slot, "slotId"));
            array.addProperty("arrayCount", intValue(slot, "arrayCount", 0));
            array.addProperty("variantSelectionMode", stringValue(slot, "variantSelectionMode", "seeded_random"));
            arrays.add(array);
        }
        obj.add("arrayStages", arrays);
        Files.writeString(path, CityJson.GSON.toJson(obj));
        ctx.report().getAsJsonObject("artifacts").addProperty("d4StagedPlan", debugRef(ctx.debugRoot(), path));
        ctx.workflow().writeReport();
    }

    private static void writeWorkflowD4StageTrace(WorkflowContext ctx, JsonArray stageTrace) throws IOException {
        Path outputDirectory = ctx.runDir().resolve("city_d4_staged_" + safeFileName(ctx.citySeedId()));
        Files.createDirectories(outputDirectory);
        Path path = outputDirectory.resolve("d4_staged_trace.json");
        JsonObject obj = new JsonObject();
        obj.addProperty("schemaVersion", "city_d4_staged_key_then_array_trace.v0.1");
        obj.addProperty("planningMode", "key_then_array");
        obj.addProperty("stageCount", stageTrace.size());
        obj.add("stages", stageTrace.deepCopy());
        Files.writeString(path, CityJson.GSON.toJson(obj));
        ctx.report().getAsJsonObject("artifacts").addProperty("d4StagedTrace", debugRef(ctx.debugRoot(), path));
        ctx.workflow().writeReport();
    }

    private static boolean workflowWallPlanMatchesRequest(Path wallPlanPath, JsonObject request) throws IOException {
        if (!booleanValue(request, "skipExisting", true) || !Files.exists(wallPlanPath)) {
            return false;
        }
        JsonObject existing = JsonParser.parseString(Files.readString(wallPlanPath)).getAsJsonObject();
        String expectedVersion = CityWallReservationPlanner.normalizeWallVersion(stringValue(request, "wallVersion", "v3"));
        String actualVersion = stringValue(existing, "wallVersion", "");
        if (!expectedVersion.equals(actualVersion)) {
            return false;
        }
        if (request.has("wallDesignPolicy") && !request.get("wallDesignPolicy").isJsonNull()
                && !stringValue(request, "wallDesignPolicy", "").isBlank()
                && !stringValue(request, "wallDesignPolicy", "").equals(stringValue(existing, "wallDesignPolicy", ""))) {
            return false;
        }
        if (request.has("wallTerrainPolicy") && !request.get("wallTerrainPolicy").isJsonNull()
                && !stringValue(request, "wallTerrainPolicy", "").isBlank()
                && !stringValue(request, "wallTerrainPolicy", "").equals(stringValue(existing, "wallTerrainPolicy", ""))) {
            return false;
        }
        if (CityWallReservationPlanner.V4.equals(expectedVersion)
                && !"city_wall_plan.v0.4".equals(stringValue(existing, "schemaVersion", ""))) {
            return false;
        }
        if (CityWallReservationPlanner.V5.equals(expectedVersion)
                && !"city_wall_plan.v0.5".equals(stringValue(existing, "schemaVersion", ""))) {
            return false;
        }
        return true;
    }

    private static CityWallPlanner.V3Options workflowWallV3Options(JsonObject request) {
        return new CityWallPlanner.V3Options(
                intValue(request, "gateClusterRadiusBlocks", CityWallPlanner.DEFAULT_GATE_CLUSTER_RADIUS_BLOCKS),
                intValue(request, "terrainFitUnitLengthBlocks", CityWallPlanner.DEFAULT_TERRAIN_FIT_UNIT_LENGTH_BLOCKS),
                stringValue(request, "wallTerrainPolicy", CityWallPlanner.DEFAULT_WALL_TERRAIN_POLICY),
                intValue(request, "flatMaxDeltaBlocks", CityWallPlanner.DEFAULT_FLAT_MAX_DELTA_BLOCKS),
                intValue(request, "steppedMaxDeltaBlocks", CityWallPlanner.DEFAULT_STEPPED_MAX_DELTA_BLOCKS),
                intValue(request, "mountainProbeDistanceBlocks",
                        CityWallPlanner.DEFAULT_MOUNTAIN_PROBE_DISTANCE_BLOCKS),
                intValue(request, "naturalBoundaryMinDeltaBlocks",
                        CityWallPlanner.DEFAULT_NATURAL_BOUNDARY_MIN_DELTA_BLOCKS),
                booleanValue(request, "embeddedSlopeTower", true),
                stringValue(request, "wallDesignPolicy", CityWallPlanner.DEFAULT_WALL_DESIGN_POLICY),
                intValue(request, "minGateSpacingBlocks", CityWallPlanner.DEFAULT_MIN_GATE_SPACING_BLOCKS),
                intValue(request, "minGateRoadLengthBlocks", CityWallPlanner.DEFAULT_MIN_GATE_ROAD_LENGTH_BLOCKS),
                intValue(request, "naturalWaterBoundaryMinAreaBlocks",
                        CityWallPlanner.DEFAULT_NATURAL_WATER_BOUNDARY_MIN_AREA_BLOCKS),
                intValue(request, "roadProjectionMaxDistanceBlocks",
                        CityWallPlanner.DEFAULT_ROAD_PROJECTION_MAX_DISTANCE_BLOCKS));
    }

    private static CityWallPlanner.V4Options workflowWallV4Options(JsonObject request) {
        return new CityWallPlanner.V4Options(
                intValue(request, "wallUnitLengthBlocks", CityWallPlanner.DEFAULT_WALL_UNIT_LENGTH_BLOCKS),
                intValue(request, "waterRunMinUnits", CityWallPlanner.DEFAULT_WATER_RUN_MIN_UNITS),
                intValue(request, "waterRetreatMaxCells", CityWallPlanner.DEFAULT_WATER_RETREAT_MAX_CELLS),
                intValue(request, "structureWallBreathingRoomBlocks",
                        intValue(request, "wallMarginBlocks", CityWallPlanner.DEFAULT_STRUCTURE_WALL_BREATHING_ROOM_BLOCKS)),
                intValue(request, "heightDatumClampBlocks", CityWallPlanner.DEFAULT_HEIGHT_DATUM_CLAMP_BLOCKS),
                intValue(request, "localMedianWindowUnits", CityWallPlanner.DEFAULT_LOCAL_MEDIAN_WINDOW_UNITS));
    }

    private static CityWallPlanner.V5Options workflowWallV5Options(JsonObject request) {
        return new CityWallPlanner.V5Options(
                intValue(request, "wallUnitLengthBlocks", CityWallPlanner.DEFAULT_V5_WALL_UNIT_LENGTH_BLOCKS),
                intValue(request, "nominalWallHeightBlocks", CityWallPlanner.DEFAULT_V5_NOMINAL_WALL_HEIGHT_BLOCKS),
                intValue(request, "waterRunMinBlocks", CityWallPlanner.DEFAULT_V5_WATER_RUN_MIN_BLOCKS),
                doubleValue(request, "waterFluidRatioMin", CityWallPlanner.DEFAULT_V5_WATER_FLUID_RATIO_MIN),
                intValue(request, "heightSegmentMaxDeltaBlocks",
                        CityWallPlanner.DEFAULT_V5_SEGMENT_MAX_DELTA_BLOCKS),
                intValue(request, "heightSteppedTransitionMaxDeltaBlocks",
                        CityWallPlanner.DEFAULT_V5_STEPPED_TRANSITION_MAX_DELTA_BLOCKS),
                intValue(request, "naturalBoundaryMinDeltaBlocks",
                        CityWallPlanner.DEFAULT_V5_NATURAL_BOUNDARY_MIN_DELTA_BLOCKS));
    }

    private static void requireObject(JsonObject request, String key, String stepName) {
        if (request == null || !request.has(key) || !request.get(key).isJsonObject()) {
            throw new IllegalArgumentException(stepName + " requires " + key + " object.");
        }
    }

    private static String requiredString(JsonObject obj, String key) {
        String value = stringValue(obj, key, "");
        if (value.isBlank()) {
            throw new IllegalArgumentException(key + " is required.");
        }
        return value;
    }

    private static void writeOptionalDebugReport(Path debugRoot, Path outputDirectory, JsonObject report,
                                                 JsonObject artifacts, String reportKey, String fileName,
                                                 String artifactKey) throws IOException {
        if (report.has(reportKey) && report.get(reportKey).isJsonObject()) {
            Path path = outputDirectory.resolve(fileName);
            Files.writeString(path, CityJson.GSON.toJson(report.getAsJsonObject(reportKey)));
            artifacts.addProperty(artifactKey, debugRef(debugRoot, path));
        }
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

    private static JsonObject timing(long started) {
        JsonObject timing = new JsonObject();
        timing.addProperty("total", (System.nanoTime() - started) / 1_000_000L);
        return timing;
    }

    private static WorldMutationReport skippedWorldMutationReport(BuildOperationPlan plan, String reason) {
        List<WorldMutationReport.OperationResult> results = new ArrayList<>();
        for (BuildOperationPlan.Operation operation : plan.operations()) {
            results.add(new WorldMutationReport.OperationResult(operation.operationId(), "skipped", 0, reason));
        }
        return new WorldMutationReport(
                WorldMutationReport.CURRENT_SCHEMA_VERSION,
                plan.cityId(),
                "worldgen_time_registry_only",
                false,
                false,
                0,
                0,
                plan.operations().size(),
                0,
                results,
                List.of(reason),
                List.of());
    }

    static void validateLockedMaterializationPlan(JsonObject materializationPlan) {
        if (materializationPlan == null
                || !materializationPlan.has("plannedWorldgenStructures")
                || !materializationPlan.get("plannedWorldgenStructures").isJsonArray()) {
            throw new IllegalArgumentException("D6 locked structure_materialization_plan.json is required.");
        }
        if (!materializationPlan.has("locked") || !materializationPlan.get("locked").getAsBoolean()) {
            throw new IllegalArgumentException("D6 materialization plan is not locked; rerun city_plan_d6 before city_execute_d5.");
        }
        JsonArray planned = materializationPlan.getAsJsonArray("plannedWorldgenStructures");
        if (planned.isEmpty()) {
            throw new IllegalArgumentException("D6 locked materialization plan contains no planned structures.");
        }
        for (JsonElement elem : planned) {
            if (!elem.isJsonObject()) {
                throw new IllegalArgumentException("D6 locked materialization plan has an invalid structure item.");
            }
            JsonObject item = elem.getAsJsonObject();
            if (!"planned_worldgen".equals(stringValue(item, "status"))) {
                throw new IllegalArgumentException("D6 locked materialization plan contains non-planned item "
                        + stringValue(item, "anchorId") + ": " + stringValue(item, "reasonCode"));
            }
            boolean templatePlacement = CityStructureMaterializationPlanner.TEMPLATE_MATERIALIZATION_SOURCE.equals(
                    stringValue(item, "materializationSource")) || hasValue(item, "templateRef");
            if (!item.has("locked") || !item.get("locked").getAsBoolean()
                    || !item.has("actualFootprint") || !item.has("lockedActualFootprint")
                    || !item.has("pieceBoxes") || !item.get("pieceBoxes").isJsonArray()
                    || !item.has("lockedCollisionEnvelope") || !item.has("lockedBBoxGroupKey")
                    || (!templatePlacement && stringValue(item, "expectedStartSignature").isBlank())) {
                throw new IllegalArgumentException("D6 planned structure is not fully locked: "
                        + stringValue(item, "anchorId"));
            }
            if (templatePlacement && !CityStructureMaterializationPlanner.TEMPLATE_DATUM_POLICY_WORLDGEN_SURFACE.equals(
                    stringValue(item, "templateDatumPolicy"))) {
                throw new IllegalArgumentException("D6 template placement is missing a supported datum policy: "
                        + stringValue(item, "anchorId"));
            }
        }
    }

    private static void validateD5V5FootprintsWithinReservation(Path wallReservationPath,
                                                                JsonObject source,
                                                                String arrayKey,
                                                                String stage) throws IOException {
        if (wallReservationPath == null || !Files.exists(wallReservationPath)) {
            return;
        }
        JsonObject reservation = JsonParser.parseString(Files.readString(wallReservationPath)).getAsJsonObject();
        if (!CityWallReservationPlanner.V5.equals(stringValue(reservation, "wallVersion", ""))) {
            return;
        }
        BlockBounds coverage = reservation.has("wallCoverageBounds")
                && reservation.get("wallCoverageBounds").isJsonObject()
                ? bounds(reservation.getAsJsonObject("wallCoverageBounds"))
                : bounds(requiredObject(reservation, "wallBounds"));
        for (JsonElement elem : array(source, arrayKey)) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject item = elem.getAsJsonObject();
            JsonObject footprint = item.has("lockedActualFootprint")
                    && item.get("lockedActualFootprint").isJsonObject()
                    ? item.getAsJsonObject("lockedActualFootprint")
                    : item.has("actualFootprint") && item.get("actualFootprint").isJsonObject()
                    ? item.getAsJsonObject("actualFootprint")
                    : item.has("plannedFootprint") && item.get("plannedFootprint").isJsonObject()
                    ? item.getAsJsonObject("plannedFootprint")
                    : null;
            if (footprint == null) {
                continue;
            }
            BlockBounds footprintBounds = bounds(footprint);
            if (containsBounds(coverage, footprintBounds)) {
                continue;
            }
            throw new IllegalArgumentException("D5_V5_LOCKED_FOOTPRINT_OUTSIDE_RESERVATION: " + stage
                    + " footprint for " + stringValue(item, "anchorId", "unknown_anchor")
                    + " exceeds D5 v5 reservation coverage. Return to D5 and expand/rescan reservation.");
        }
    }

    private static JsonObject maskPlanWithLockedEnvelopes(JsonObject maskPlan, JsonObject materializationPlan) {
        JsonObject copy = maskPlan.deepCopy();
        JsonArray noVegetation = new JsonArray();
        JsonArray vegetationLimited = new JsonArray();
        JsonArray noVanilla = new JsonArray();
        JsonArray reasons = new JsonArray();
        appendMasksByType(maskPlan, noVegetation, "noVegetationMask", "wall_reservation");
        appendMasksByType(maskPlan, vegetationLimited, "vegetationLimitedMask", "wall_reservation");
        appendMasksByType(maskPlan, noVanilla, "noVanillaStructureMask", "wall_reservation");
        appendReasonsByType(maskPlan, reasons, "wall_reservation");
        for (JsonElement elem : materializationPlan.getAsJsonArray("plannedWorldgenStructures")) {
            JsonObject item = elem.getAsJsonObject();
            String anchorId = stringValue(item, "anchorId");
            BlockBounds collision = bounds(requiredObject(item, "lockedCollisionEnvelope"));
            BlockBounds mask = CityStructureMaterializationPlanner.expand(collision, 4);
            addMask(noVegetation, anchorId + "_locked_no_vegetation", mask,
                    "locked_structure_mask_envelope", anchorId);
            addMask(vegetationLimited, anchorId + "_locked_vegetation_limited",
                    CityStructureMaterializationPlanner.expand(mask, 4),
                    "locked_structure_transition", anchorId);
            addMask(noVanilla, anchorId + "_locked_no_vanilla_structure", mask,
                    "locked_planned_structure", anchorId);
            addReason(reasons, anchorId, "locked_structure", mask,
                    "protect D6 locked actual footprint and collision envelope");
        }
        copy.add("noVegetationMask", noVegetation);
        copy.add("vegetationLimitedMask", vegetationLimited);
        copy.add("noVanillaStructureMask", noVanilla);
        copy.add("reservationReason", reasons);
        copy.addProperty("requiresLockedMaterializationPlan", true);
        copy.addProperty("roadPlanningStage", "d7_after_worldgen_ledger");
        copy.add("sourceLockedMaterializationPlan", materializationPlan.deepCopy());
        return copy;
    }

    private static void rejectLegacyDressingArtifacts(Path directory, boolean rejectAnyFile) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            Path legacyPath = paths.filter(Files::isRegularFile)
                    .filter(path -> rejectAnyFile
                            || path.getFileName().toString().startsWith("city_dressing_")
                            || path.getFileName().toString().startsWith("active_city_dressing_"))
                    .findFirst().orElse(null);
            if (legacyPath != null) {
                throw new IllegalArgumentException("CITY_DRESSING_LEGACY_SCHEMA_REMOVED: legacy artifact found: "
                        + legacyPath);
            }
        }
    }

    private static void appendMasksByType(JsonObject source, JsonArray target, String arrayKey, String sourceTypePrefix) {
        for (JsonElement elem : source.has(arrayKey) && source.get(arrayKey).isJsonArray()
                ? source.getAsJsonArray(arrayKey) : new JsonArray()) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject mask = elem.getAsJsonObject();
            String maskType = stringValue(mask, "maskType", "");
            if (maskType.startsWith(sourceTypePrefix) || maskType.contains(sourceTypePrefix)) {
                target.add(mask.deepCopy());
            }
        }
    }

    private static void appendReasonsByType(JsonObject source, JsonArray target, String sourceType) {
        for (JsonElement elem : source.has("reservationReason") && source.get("reservationReason").isJsonArray()
                ? source.getAsJsonArray("reservationReason") : new JsonArray()) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject reason = elem.getAsJsonObject();
            if (stringValue(reason, "sourceType", "").equals(sourceType)) {
                target.add(reason.deepCopy());
            }
        }
    }

    private static void addMask(JsonArray array, String id, BlockBounds bounds, String type, String sourceRef) {
        JsonObject obj = new JsonObject();
        obj.addProperty("maskId", id);
        obj.addProperty("maskType", type);
        obj.addProperty("sourceRef", sourceRef);
        obj.add("blockBounds", boundsJson(bounds));
        array.add(obj);
    }

    private static void addReason(JsonArray array, String sourceRef, String sourceType,
                                  BlockBounds bounds, String reason) {
        JsonObject obj = new JsonObject();
        obj.addProperty("sourceRef", sourceRef);
        obj.addProperty("sourceType", sourceType);
        obj.addProperty("reason", reason);
        obj.add("blockBounds", boundsJson(bounds));
        array.add(obj);
    }

    private static JsonObject maybeRunDeferredRoadPostprocess(Path debugRoot,
                                                              Path runDir,
                                                              String citySeedId,
                                                              JsonObject materializationPlan,
                                                              JsonObject placedLedger,
                                                              boolean executeStructurePlacement,
                                                              boolean debugLateMaterialize,
                                                              MinecraftServerHolder serverHolder,
                                                              ServerLevel level,
                                                              Path outputDirectory,
                                                              JsonObject roadProviderState) throws IOException {
        if (debugLateMaterialize || !executeStructurePlacement
                || !allPlannedWorldgenStructuresRecorded(materializationPlan, placedLedger)) {
            return null;
        }
        String roadProvider = stringValue(roadProviderState, "roadProvider", CityRoadWeaverBridge.PROVIDER_AUTO);
        boolean explicitWorldEditDebug = CityRoadWeaverBridge.PROVIDER_WORLDEDIT_DEBUG.equals(roadProvider);
        if (!explicitWorldEditDebug || !booleanValue(roadProviderState, "useWorldEditDebugFallback", false)) {
            JsonObject skipped = skippedDeferredRoadPostprocessReport(roadProviderState);
            Path reportPath = outputDirectory.resolve("deferred_road_postprocess_report.json");
            Files.writeString(reportPath, CityJson.GSON.toJson(skipped));
            return skipped;
        }
        Path reportPath = outputDirectory.resolve("deferred_road_postprocess_report.json");
        if (Files.exists(reportPath)) {
            return JsonParser.parseString(Files.readString(reportPath)).getAsJsonObject();
        }
        BuildOperationPlan roadPlan = ledgerRoadPlan(materializationPlan, placedLedger);
        WorldMutationReport report;
        if (roadPlan.operations().isEmpty()) {
            report = skippedWorldMutationReport(roadPlan,
                    "D7 ledger road postprocess found no valid actual footprint road operations.");
        } else if (serverHolder == null || level == null) {
            report = skippedWorldMutationReport(roadPlan,
                    "D7 deferred road postprocess requires an active Minecraft server.");
        } else {
            report = new WorldEditMutationBackend().execute(
                    level, roadPlan, serverHolder.server().getServerDirectory().toPath());
        }
        JsonObject reportJson = report.asJson();
        reportJson.addProperty("postprocessStage", "d7_after_worldgen_ledger_complete");
        reportJson.addProperty("roadPostprocessSource", "worldgen_ledger_actual_footprint");
        reportJson.addProperty("roadAvoidanceMarginBlocks", 3);
        reportJson.addProperty("roadBlockedByStructureCount", placedLedger.getAsJsonArray("placedStructures").size());
        reportJson.addProperty("boundarySource", "actual_footprint_union");
        reportJson.addProperty("debugFallback", true);
        reportJson.add("roadProviderState", roadProviderState.deepCopy());
        reportJson.add("generatedBuildOperationPlan", roadPlan.asJson());
        Files.writeString(reportPath, CityJson.GSON.toJson(reportJson));
        return reportJson;
    }

    private static JsonObject skippedDeferredRoadPostprocessReport(JsonObject roadProviderState) {
        String provider = stringValue(roadProviderState, "roadProvider", CityRoadWeaverBridge.PROVIDER_AUTO);
        JsonObject registration = roadProviderState != null
                && roadProviderState.has("roadWeaverRegistrationReport")
                && roadProviderState.get("roadWeaverRegistrationReport").isJsonObject()
                ? roadProviderState.getAsJsonObject("roadWeaverRegistrationReport")
                : new JsonObject();
        String registrationReason = stringValue(registration, "reasonCode", "");
        String stateReason = stringValue(roadProviderState, "reasonCode", "");
        boolean roadWeaverRegistered = booleanValue(roadProviderState, "roadWeaverRegistered", false);

        String reasonCode;
        String message;
        String source;
        if (roadWeaverRegistered) {
            reasonCode = "ROADWEAVER_REGISTERED";
            message = "RoadWeaver owns road generation; WorldEdit debug road fallback skipped.";
            source = "roadweaver";
        } else if (CityRoadWeaverBridge.PROVIDER_NONE.equals(provider)) {
            reasonCode = "ROAD_PROVIDER_NONE";
            message = "Road generation disabled by roadProvider=none.";
            source = "none";
        } else if ("ROAD_PROVIDER_STATE_MISSING".equals(stateReason)) {
            reasonCode = "ROAD_PROVIDER_STATE_MISSING";
            message = "Missing D5 road provider state; automatic WorldEdit debug road fallback is disabled.";
            source = "none";
        } else if (CityRoadWeaverBridge.PROVIDER_AUTO.equals(provider)
                && "ROADWEAVER_UNAVAILABLE".equals(registrationReason)) {
            reasonCode = "ROADWEAVER_UNAVAILABLE";
            message = "RoadWeaver is unavailable and roadProvider=auto no longer runs legacy WorldEdit debug roads. "
                    + "Use roadProvider=worldedit_debug for diagnostic roads.";
            source = "none";
        } else {
            reasonCode = "ROAD_DEBUG_FALLBACK_DISABLED";
            message = "WorldEdit debug road fallback is disabled for this road provider.";
            source = "none";
        }

        JsonObject skipped = new JsonObject();
        skipped.addProperty("schemaVersion", "city_deferred_road_postprocess_report.v0.1");
        skipped.addProperty("status", "skipped");
        skipped.addProperty("reasonCode", reasonCode);
        skipped.addProperty("message", message);
        skipped.addProperty("roadPostprocessSource", source);
        skipped.addProperty("boundarySource", "actual_footprint_union");
        skipped.add("roadProviderState", roadProviderState.deepCopy());
        return skipped;
    }

    private static JsonObject loadRoadProviderState(Path d5Dir) throws IOException {
        Path path = d5Dir.resolve("road_provider_state.json");
        if (Files.exists(path)) {
            return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        }
        JsonObject obj = new JsonObject();
        obj.addProperty("schemaVersion", "city_road_provider_state.v0.1");
        obj.addProperty("roadProvider", CityRoadWeaverBridge.PROVIDER_AUTO);
        obj.addProperty("roadWeaverRegistered", false);
        obj.addProperty("roadWeaverAvailable", false);
        obj.addProperty("useWorldEditDebugFallback", false);
        obj.addProperty("reasonCode", "ROAD_PROVIDER_STATE_MISSING");
        obj.addProperty("message", "Missing D5 road provider state; automatic WorldEdit debug road fallback is disabled.");
        return obj;
    }

    private static JsonObject terrainAdaptationReport(JsonObject placedLedger) {
        JsonObject report = new JsonObject();
        report.addProperty("schemaVersion", "city_terrain_adaptation_report.v0.1");
        JsonArray structures = new JsonArray();
        int placedCount = 0;
        int hookUnavailable = 0;
        int terrainNone = 0;
        int beardifierSeen = 0;
        JsonArray placed = placedLedger != null && placedLedger.has("placedStructures")
                && placedLedger.get("placedStructures").isJsonArray()
                ? placedLedger.getAsJsonArray("placedStructures")
                : new JsonArray();
        for (JsonElement elem : placed) {
            if (!elem.isJsonObject()) {
                continue;
            }
            placedCount++;
            JsonObject source = elem.getAsJsonObject();
            String terrain = stringValue(source, "terrainAdaptation", "unknown");
            boolean hook = booleanValue(source, "terrainAdaptationHookAvailable", false);
            boolean seen = booleanValue(source, "beardifierSeen", false);
            if (!hook) {
                hookUnavailable++;
            }
            if ("none".equalsIgnoreCase(terrain)) {
                terrainNone++;
            }
            if (seen) {
                beardifierSeen++;
            }
            JsonObject item = new JsonObject();
            item.addProperty("anchorId", stringValue(source, "anchorId"));
            item.addProperty("structureId", stringValue(source, "structureId"));
            item.addProperty("terrainAdaptation", terrain);
            item.addProperty("terrainAdaptationHookAvailable", hook);
            item.addProperty("beardifierSeen", seen);
            item.addProperty("reasonCode", !hook
                    ? "CITY_TERRAIN_ADAPTATION_HOOK_UNAVAILABLE"
                    : seen ? "TERRAIN_ADAPTATION_OBSERVED" : "TERRAIN_ADAPTATION_NOT_APPLIED");
            structures.add(item);
        }
        report.addProperty("placedStructureCount", placedCount);
        report.addProperty("hookUnavailableCount", hookUnavailable);
        report.addProperty("terrainAdaptationNoneCount", terrainNone);
        report.addProperty("beardifierSeenCount", beardifierSeen);
        report.addProperty("status", hookUnavailable > 0 ? "diagnostic_only" : "observed");
        report.add("structures", structures);
        return report;
    }

    private static BuildOperationPlan ledgerRoadPlan(JsonObject materializationPlan, JsonObject placedLedger) {
        String cityId = stringValue(materializationPlan, "cityId");
        JsonArray placed = placedLedger != null && placedLedger.has("placedStructures")
                && placedLedger.get("placedStructures").isJsonArray()
                ? placedLedger.getAsJsonArray("placedStructures")
                : new JsonArray();
        List<JsonObject> structures = new ArrayList<>();
        for (JsonElement elem : placed) {
            if (elem.isJsonObject() && elem.getAsJsonObject().has("actualFootprint")) {
                structures.add(elem.getAsJsonObject());
            }
        }
        structures.sort(Comparator.comparingInt(a -> intValue(a, "priority", 0)));
        List<BuildOperationPlan.Operation> operations = new ArrayList<>();
        if (structures.isEmpty()) {
            return new BuildOperationPlan(BuildOperationPlan.CURRENT_SCHEMA_VERSION,
                    cityId.isBlank() ? "unknown_city" : cityId, "geomantia_templates/d5", operations);
        }
        List<BlockBounds> obstacles = structures.stream()
                .map(obj -> CityStructureMaterializationPlanner.expand(bounds(obj.getAsJsonObject("actualFootprint")), 3))
                .toList();
        BlockPoint entry = outerConnectionPoint(bounds(structures.get(0).getAsJsonObject("actualFootprint")), obstacles);
        int index = 0;
        for (JsonObject structure : structures) {
            index++;
            String anchorId = stringValue(structure, "anchorId");
            BlockBounds actual = bounds(structure.getAsJsonObject("actualFootprint"));
            BlockPoint target = nearestConnectionPoint(entry, actual, obstacles);
            String edgeId = "ledger_road_access_" + safeFileName(anchorId.isBlank() ? "structure_" + index : anchorId);
            List<BlockPoint> polyline = avoidObstacles(entry, target, obstacles);
            operations.add(new BuildOperationPlan.Operation(edgeId + "_clear", "clearVegetation", edgeId,
                    polyline, 7, "", "", "", BlockPoint.ORIGIN,
                    "clear vegetation for D7 actual-footprint road"));
            operations.add(new BuildOperationPlan.Operation(edgeId + "_surface", "surfaceFill", edgeId,
                    polyline, 5, "minecraft:gravel", "minecraft:coarse_dirt", "", BlockPoint.ORIGIN,
                    "surface D7 actual-footprint road"));
        }
        return new BuildOperationPlan(BuildOperationPlan.CURRENT_SCHEMA_VERSION,
                cityId.isBlank() ? "unknown_city" : cityId, "geomantia_templates/d5", operations);
    }

    private static BlockPoint outerConnectionPoint(BlockBounds first, List<BlockBounds> obstacles) {
        return nearestClearPoint(new BlockPoint(first.center().x() - 48, first.center().z()), obstacles);
    }

    private static BlockPoint nearestConnectionPoint(BlockPoint from, BlockBounds actual, List<BlockBounds> obstacles) {
        List<BlockPoint> candidates = List.of(
                new BlockPoint(actual.minX() - 2, actual.center().z()),
                new BlockPoint(actual.maxX() + 2, actual.center().z()),
                new BlockPoint(actual.center().x(), actual.minZ() - 2),
                new BlockPoint(actual.center().x(), actual.maxZ() + 2));
        return candidates.stream()
                .map(candidate -> nearestClearPoint(candidate, obstacles))
                .min(Comparator.comparingInt(candidate -> manhattan(from, candidate)))
                .orElse(candidates.get(0));
    }

    private static BlockPoint nearestClearPoint(BlockPoint point, List<BlockBounds> obstacles) {
        if (!insideAny(point, obstacles)) {
            return point;
        }
        for (int radius = 1; radius <= 64; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) {
                        continue;
                    }
                    BlockPoint candidate = new BlockPoint(point.x() + dx, point.z() + dz);
                    if (!insideAny(candidate, obstacles)) {
                        return candidate;
                    }
                }
            }
        }
        return point;
    }

    private static List<BlockPoint> avoidObstacles(BlockPoint from, BlockPoint to, List<BlockBounds> obstacles) {
        if (!segmentIntersects(from, to, obstacles)) {
            return List.of(from, to);
        }
        BlockPoint bendA = new BlockPoint(from.x(), to.z());
        if (!segmentIntersects(from, bendA, obstacles) && !segmentIntersects(bendA, to, obstacles)) {
            return List.of(from, bendA, to);
        }
        BlockPoint bendB = new BlockPoint(to.x(), from.z());
        if (!segmentIntersects(from, bendB, obstacles) && !segmentIntersects(bendB, to, obstacles)) {
            return List.of(from, bendB, to);
        }
        BlockBounds blocking = obstacles.stream()
                .filter(bounds -> segmentIntersects(from, to, List.of(bounds)))
                .findFirst()
                .orElse(null);
        if (blocking != null) {
            int offsetZ = Math.abs(from.z() - blocking.minZ()) < Math.abs(from.z() - blocking.maxZ())
                    ? blocking.minZ() - 2 : blocking.maxZ() + 2;
            BlockPoint detourA = new BlockPoint(from.x(), offsetZ);
            BlockPoint detourB = new BlockPoint(to.x(), offsetZ);
            return List.of(from, detourA, detourB, to);
        }
        return List.of(from, bendA, to);
    }

    private static boolean segmentIntersects(BlockPoint from, BlockPoint to, List<BlockBounds> obstacles) {
        int steps = Math.max(Math.abs(to.x() - from.x()), Math.abs(to.z() - from.z()));
        steps = Math.max(1, steps);
        for (int i = 0; i <= steps; i++) {
            int x = from.x() + Math.round((to.x() - from.x()) * (i / (float) steps));
            int z = from.z() + Math.round((to.z() - from.z()) * (i / (float) steps));
            if (insideAny(new BlockPoint(x, z), obstacles)) {
                return true;
            }
        }
        return false;
    }

    private static boolean insideAny(BlockPoint point, List<BlockBounds> obstacles) {
        return obstacles.stream().anyMatch(bounds -> bounds.contains(point.x(), point.z()));
    }

    private static int manhattan(BlockPoint a, BlockPoint b) {
        return Math.abs(a.x() - b.x()) + Math.abs(a.z() - b.z());
    }

    private static boolean allPlannedWorldgenStructuresRecorded(JsonObject materializationPlan,
                                                               JsonObject placedLedger) {
        JsonArray planned = materializationPlan != null && materializationPlan.has("plannedWorldgenStructures")
                && materializationPlan.get("plannedWorldgenStructures").isJsonArray()
                ? materializationPlan.getAsJsonArray("plannedWorldgenStructures")
                : new JsonArray();
        JsonArray placed = placedLedger != null && placedLedger.has("placedStructures")
                && placedLedger.get("placedStructures").isJsonArray()
                ? placedLedger.getAsJsonArray("placedStructures")
                : new JsonArray();
        List<String> placedIds = new ArrayList<>();
        for (JsonElement elem : placed) {
            if (elem.isJsonObject()) {
                placedIds.add(stringValue(elem.getAsJsonObject(), "anchorId"));
            }
        }
        int required = 0;
        for (JsonElement elem : planned) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject item = elem.getAsJsonObject();
            if (!"planned_worldgen".equals(stringValue(item, "status"))) {
                return false;
            }
            required++;
            if (!placedIds.contains(stringValue(item, "anchorId"))) {
                return false;
            }
        }
        return required > 0;
    }

    private static void rejectLegacyArtifacts(Path directory, String stage) throws IOException {
        if (directory == null || !Files.isDirectory(directory)) {
            return;
        }
        for (String fileName : List.of("function_zone_map.json", "buildable_area_map.json",
                "planned_fixed_placement_map.json", "structure_pool_map.json",
                "start_candidate_set.json")) {
            if (Files.exists(directory.resolve(fileName))) {
                throw CityStructureProfileCatalog.legacyFlow(stage + " legacy artifact " + fileName
                        + " is no longer accepted by the City D3-D6 structure landing flow.");
            }
        }
    }

    private static Path envelopeFactsPath(Path runDir, String citySeedId, JsonObject source) {
        if (source != null) {
            String raw = stringValue(source, "factsPath");
            if (!raw.isBlank()) {
                Path path = Path.of(raw);
                return path.isAbsolute() ? path.normalize() : runDir.resolve(path).normalize();
            }
        }
        return runDir.resolve("city_structure_envelopes_" + safeFileName(citySeedId))
                .resolve("structure_envelope_facts.json");
    }

    private static Path d3PackagePath(Path runDir, String citySeedId) {
        return runDir.resolve("city_d3_" + safeFileName(citySeedId))
                .resolve("city_landform_review_package.json");
    }

    private static CityLandformReviewPackage loadD3Package(Path debugRoot, Path runDir, String citySeedId)
            throws IOException {
        Path d3PackagePath = d3PackagePath(runDir, citySeedId);
        if (!Files.exists(d3PackagePath)) {
            throw new IllegalArgumentException("D3 package not found. Run city_plan_d3 first: "
                    + debugRef(debugRoot, d3PackagePath));
        }
        return CityLandformReviewPackage.fromJson(
                JsonParser.parseString(Files.readString(d3PackagePath)).getAsJsonObject());
    }

    private static Path d4SessionDir(Path runDir, String citySeedId) {
        return runDir.resolve("city_d4_candidate_session_" + safeFileName(citySeedId));
    }

    private static JsonObject d4SessionArtifacts(Path debugRoot,
                                                 Path outputDirectory,
                                                 Path sessionPath,
                                                 Path slotPlanPath,
                                                 Path candidatePath,
                                                 Path previewPath,
                                                 Path designTimePath,
                                                 Path qualityPath,
                                                 Path tracePath) {
        JsonObject artifacts = new JsonObject();
        artifacts.addProperty("d4CandidateSession", debugRef(debugRoot, sessionPath));
        if (slotPlanPath != null && Files.exists(slotPlanPath)) {
            artifacts.addProperty("designSlotPlan", debugRef(debugRoot, slotPlanPath));
        }
        if (candidatePath != null && Files.exists(candidatePath)) {
            artifacts.addProperty("slotCandidateSet", debugRef(debugRoot, candidatePath));
        }
        if (previewPath != null && Files.exists(previewPath)) {
            artifacts.addProperty("candidatePreview", debugRef(debugRoot, previewPath));
            artifacts.addProperty("anchorCandidatePreview", debugRef(debugRoot, previewPath));
        } else if (Files.exists(outputDirectory.resolve("anchor_candidate_preview.png"))) {
            artifacts.addProperty("candidatePreview",
                    debugRef(debugRoot, outputDirectory.resolve("anchor_candidate_preview.png")));
            artifacts.addProperty("anchorCandidatePreview",
                    debugRef(debugRoot, outputDirectory.resolve("anchor_candidate_preview.png")));
        }
        if (designTimePath != null && Files.exists(designTimePath)) {
            artifacts.addProperty("d4DesignTimeReport", debugRef(debugRoot, designTimePath));
        }
        if (qualityPath != null && Files.exists(qualityPath)) {
            artifacts.addProperty("qualityReport", debugRef(debugRoot, qualityPath));
        }
        if (tracePath != null && Files.exists(tracePath)) {
            artifacts.addProperty("d4CandidateSessionTrace", debugRef(debugRoot, tracePath));
        }
        return artifacts;
    }

    private static JsonObject findStructureClusterGroup(JsonObject candidateSet, String groupCandidateId) {
        if (groupCandidateId == null || groupCandidateId.isBlank()) {
            throw new IllegalArgumentException("groupCandidateId is required.");
        }
        for (JsonElement elem : array(candidateSet, "groupCandidates")) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject group = elem.getAsJsonObject();
            if (groupCandidateId.equals(stringValue(group, "groupCandidateId"))) {
                if (!group.has("expandedStructureAnchorPlan")
                        || !group.get("expandedStructureAnchorPlan").isJsonObject()) {
                    throw new IllegalArgumentException("D4_STRUCTURE_CLUSTER_GROUP_HAS_NO_EXPANDED_PLAN: "
                            + groupCandidateId);
                }
                return group;
            }
        }
        throw new IllegalArgumentException("D4_STRUCTURE_CLUSTER_GROUP_NOT_FOUND: " + groupCandidateId);
    }

    private static Path anchorCandidateSetPath(Path runDir, String citySeedId, JsonObject source) {
        if (source != null) {
            String raw = stringValue(source, "candidateSetPath");
            if (!raw.isBlank()) {
                Path path = Path.of(raw);
                return path.isAbsolute() ? path.normalize() : runDir.resolve(path).normalize();
            }
        }
        return runDir.resolve("city_d4_candidates_" + safeFileName(citySeedId))
                .resolve("anchor_candidate_set.json");
    }

    private static Path structureClusterGroupCandidateSetPath(Path runDir, String citySeedId, JsonObject source) {
        if (source != null) {
            String raw = stringValue(source, "candidateSetPath");
            if (raw.isBlank()) {
                raw = stringValue(source, "structureClusterGroupCandidateSetPath");
            }
            if (!raw.isBlank()) {
                Path path = Path.of(raw);
                return path.isAbsolute() ? path.normalize() : runDir.resolve(path).normalize();
            }
        }
        return runDir.resolve("city_d4_structure_cluster_groups_" + safeFileName(citySeedId))
                .resolve("structure_cluster_group_candidate_set.json");
    }

    private static Path occupiedStructureAnchorMapPath(Path runDir, JsonObject source) {
        if (source == null) {
            return null;
        }
        String raw = stringValue(source, "anchorMapPath");
        if (raw.isBlank()) {
            raw = stringValue(source, "structureAnchorMapPath");
        }
        if (raw.isBlank()) {
            return null;
        }
        Path path = Path.of(raw);
        return path.isAbsolute() ? path.normalize() : runDir.resolve(path).normalize();
    }

    private static JsonObject loadOptionalStructureAnchorPlan(Path debugRoot, Path runDir, String citySeedId,
                                                              JsonObject source) throws IOException {
        Path path = structureAnchorPlanPath(runDir, citySeedId, source);
        if (path == null || !Files.exists(path)) {
            return new JsonObject();
        }
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }

    private static JsonObject loadOptionalOccupiedAnchorMap(Path debugRoot, Path runDir, String citySeedId,
                                                           JsonObject source) throws IOException {
        Path path = source == null
                ? runDir.resolve("city_d4_" + safeFileName(citySeedId)).resolve("structure_anchor_map.json")
                : occupiedStructureAnchorMapPath(runDir, source);
        if (path == null || !Files.exists(path)) {
            return new JsonObject();
        }
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }

    private static JsonObject loadOptionalDesignLoopAnchorMap(Path runDir, JsonObject source) throws IOException {
        Path path = occupiedStructureAnchorMapPath(runDir, source);
        if (path == null || !Files.exists(path)) {
            return new JsonObject();
        }
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }

    private static Path structureAnchorPlanPath(Path runDir, String citySeedId, JsonObject source) {
        if (source != null) {
            String raw = stringValue(source, "structureAnchorPlanPath");
            if (raw.isBlank()) {
                raw = stringValue(source, "anchorPlanPath");
            }
            if (raw.isBlank()) {
                raw = stringValue(source, "planPath");
            }
            if (!raw.isBlank()) {
                Path path = Path.of(raw);
                return path.isAbsolute() ? path.normalize() : runDir.resolve(path).normalize();
            }
        }
        return runDir.resolve("city_d4_" + safeFileName(citySeedId)).resolve("structure_anchor_plan.json");
    }

    private static JsonObject loadArrayLayoutLoopState(Path debugRoot, Path runDir, String citySeedId,
                                                       JsonObject source) throws IOException {
        Path path = arrayLayoutLoopStatePath(runDir, citySeedId, source);
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("D4_ARRAY_LAYOUT_LOOP_STATE_NOT_FOUND: "
                    + debugRef(debugRoot, path));
        }
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }

    private static JsonObject loadD4ArrayExpansionCandidateSet(Path debugRoot, Path runDir, String citySeedId,
                                                                 JsonObject source) throws IOException {
        Path path = d4ArrayExpansionCandidateSetPath(runDir, citySeedId, source);
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("D4_ARRAY_LAYOUT_CANDIDATE_SET_NOT_FOUND: "
                    + debugRef(debugRoot, path));
        }
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }

    private static JsonObject loadD4DesignLoopState(Path debugRoot, Path runDir, String citySeedId,
                                                    JsonObject source) throws IOException {
        Path path = d4DesignLoopStatePath(runDir, citySeedId, source);
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("D4_DESIGN_LOOP_STATE_NOT_FOUND: "
                    + debugRef(debugRoot, path));
        }
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }

    private static Path arrayLayoutLoopStatePath(Path runDir, String citySeedId, JsonObject source) {
        if (source != null) {
            String raw = stringValue(source, "arrayLayoutLoopStatePath");
            if (raw.isBlank()) {
                raw = stringValue(source, "loopStatePath");
            }
            if (raw.isBlank()) {
                raw = stringValue(source, "statePath");
            }
            if (!raw.isBlank()) {
                Path path = Path.of(raw);
                return path.isAbsolute() ? path.normalize() : runDir.resolve(path).normalize();
            }
        }
        return d4ArrayLayoutDir(runDir, citySeedId).resolve("d4_array_layout_loop_state.json");
    }

    private static Path d4ArrayExpansionCandidateSetPath(Path runDir, String citySeedId, JsonObject source) {
        if (source != null) {
            String raw = stringValue(source, "arrayExpansionCandidateSetPath");
            if (raw.isBlank()) {
                raw = stringValue(source, "candidateSetPath");
            }
            if (!raw.isBlank()) {
                Path path = Path.of(raw);
                return path.isAbsolute() ? path.normalize() : runDir.resolve(path).normalize();
            }
        }
        return d4ArrayLayoutDir(runDir, citySeedId).resolve("d4_array_expansion_candidate_set.json");
    }

    private static void requireCurrentArrayLayoutState(String requestedStateId, JsonObject currentState) {
        String currentStateId = stringValue(currentState, "stateId");
        if (requestedStateId != null && !requestedStateId.isBlank() && !requestedStateId.equals(currentStateId)) {
            throw new IllegalArgumentException("D4_ARRAY_LAYOUT_LOOP_STATE_STALE: requested " + requestedStateId
                    + " but current state is " + currentStateId + ".");
        }
    }

    private static Path d4DesignLoopStatePath(Path runDir, String citySeedId, JsonObject source) {
        if (source != null) {
            String raw = stringValue(source, "designLoopStatePath");
            if (raw.isBlank()) {
                raw = stringValue(source, "loopStatePath");
            }
            if (raw.isBlank()) {
                raw = stringValue(source, "statePath");
            }
            if (!raw.isBlank()) {
                Path path = Path.of(raw);
                return path.isAbsolute() ? path.normalize() : runDir.resolve(path).normalize();
            }
        }
        return d4DesignLoopDir(runDir, citySeedId).resolve("d4_design_loop_state.json");
    }

    private static String safeFileName(String raw) {
        return raw.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static BlockBounds v5SurfaceBackfillBounds(JsonObject wallReservationPlan) {
        if (wallReservationPlan != null && wallReservationPlan.has("wallCoverageBounds")
                && wallReservationPlan.get("wallCoverageBounds").isJsonObject()) {
            return bounds(wallReservationPlan.getAsJsonObject("wallCoverageBounds"));
        }
        if (wallReservationPlan != null && wallReservationPlan.has("wallBounds")
                && wallReservationPlan.get("wallBounds").isJsonObject()) {
            return expandBounds(bounds(wallReservationPlan.getAsJsonObject("wallBounds")), 16);
        }
        return new BlockBounds(-64, -64, 64, 64);
    }

    private static BlockBounds bounds(JsonObject obj) {
        return new BlockBounds(intValue(obj, "minX", 0), intValue(obj, "minZ", 0),
                intValue(obj, "maxX", 0), intValue(obj, "maxZ", 0));
    }

    private static JsonObject boundsJson(BlockBounds bounds) {
        JsonObject obj = new JsonObject();
        obj.addProperty("minX", bounds.minX());
        obj.addProperty("minZ", bounds.minZ());
        obj.addProperty("maxX", bounds.maxX());
        obj.addProperty("maxZ", bounds.maxZ());
        return obj;
    }

    private static boolean containsBounds(BlockBounds outer, BlockBounds inner) {
        return outer.contains(inner.minX(), inner.minZ())
                && outer.contains(inner.maxX(), inner.maxZ());
    }

    private static JsonObject enrichWallReservationWithD3Cells(JsonObject reservation, JsonObject d3Package) {
        if (reservation == null || d3Package == null) {
            return reservation;
        }
        Map<String, JsonObject> patchesByRef = new LinkedHashMap<>();
        for (JsonElement elem : array(d3Package, "landformPatches")) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject patch = elem.getAsJsonObject();
            rememberPatchRef(patchesByRef, patch, "landformPatchId");
            rememberPatchRef(patchesByRef, patch, "mapLabel");
        }
        if (patchesByRef.isEmpty()) {
            return reservation;
        }
        JsonObject copy = reservation.deepCopy();
        for (JsonElement elem : array(copy, "seedPatches")) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject seedPatch = elem.getAsJsonObject();
            if (!array(seedPatch, "memberCells").isEmpty()) {
                continue;
            }
            JsonObject sourcePatch = patchesByRef.get(stringValue(seedPatch, "landformPatchId"));
            if (sourcePatch == null) {
                sourcePatch = patchesByRef.get(stringValue(seedPatch, "mapLabel"));
            }
            JsonArray memberCells = array(sourcePatch, "memberCells");
            if (memberCells.isEmpty()) {
                continue;
            }
            seedPatch.addProperty("geometryMode", stringValue(sourcePatch, "geometryMode", "patch_member_cells"));
            JsonObject grid = d3Package.has("grid") && d3Package.get("grid").isJsonObject()
                    ? d3Package.getAsJsonObject("grid") : null;
            seedPatch.addProperty("cellStepBlocks", intValue(grid, "cellStepBlocks", 16));
            seedPatch.add("memberCells", memberCells.deepCopy());
        }
        return copy;
    }

    private static void rememberPatchRef(Map<String, JsonObject> patchesByRef, JsonObject patch, String key) {
        String ref = stringValue(patch, key);
        if (!ref.isBlank()) {
            patchesByRef.putIfAbsent(ref, patch);
        }
    }

    private static JsonObject requiredObject(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || !obj.get(key).isJsonObject()) {
            throw new IllegalArgumentException(key + " object is required.");
        }
        return obj.getAsJsonObject(key);
    }

    private static String stringValue(JsonObject obj, String key) {
        return stringValue(obj, key, "");
    }

    private static String stringValue(JsonObject obj, String key, String defaultValue) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return defaultValue;
        return obj.get(key).getAsString();
    }

    private static int intValue(JsonObject obj, String key, int defaultValue) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return defaultValue;
        return obj.get(key).getAsInt();
    }

    private static boolean booleanValue(JsonObject obj, String key, boolean defaultValue) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return defaultValue;
        return obj.get(key).getAsBoolean();
    }

    private static double doubleValue(JsonObject obj, String key, double defaultValue) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return defaultValue;
        return obj.get(key).getAsDouble();
    }

    private static JsonArray array(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull() || !obj.get(key).isJsonArray()) {
            return new JsonArray();
        }
        return obj.getAsJsonArray(key);
    }

    private static JsonObject object(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull() || !obj.get(key).isJsonObject()) {
            return new JsonObject();
        }
        return obj.getAsJsonObject(key);
    }

    private static boolean hasValue(JsonObject obj, String key) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull();
    }

    private static void writePlanningCompletion(Path path, JsonObject completion) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), "." + path.getFileName(), ".tmp");
        try {
            Files.writeString(temporary, CityJson.GSON.toJson(completion));
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private record WorkflowContext(Path debugRoot,
                                   Path runDir,
                                   String runId,
                                   String citySeedId,
                                   JsonObject request,
                                   JsonObject report,
                                   CityWorkflowStepRunner workflow) {
    }

    private record DecorationMaskCounts(int vegetationMaskCount, int structureMaskCount) {
        static DecorationMaskCounts empty() {
            return new DecorationMaskCounts(0, 0);
        }
    }

    private record RunMetadata(int cellStepBlocks, String dimensionId) {
    }

    private static final class LoadedChunkDecorationTerrainView implements CityDecorationTerrainProbe.TerrainView {
        private final ServerLevel level;

        private LoadedChunkDecorationTerrainView(ServerLevel level) {
            this.level = level;
        }

        @Override
        public CityDecorationTerrainProbe.Sample sample(int worldX, int worldZ) {
            int chunkX = Math.floorDiv(worldX, 16);
            int chunkZ = Math.floorDiv(worldZ, 16);
            ChunkAccess chunk = level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
            if (chunk == null) {
                return CityDecorationTerrainProbe.Sample.unavailable();
            }
            int surfaceY = Math.max(level.getMinBuildHeight(),
                    level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ) - 1);
            return new CityDecorationTerrainProbe.Sample(true, surfaceY);
        }
    }

    record MinecraftServerHolder(net.minecraft.server.MinecraftServer server) {
    }
}
