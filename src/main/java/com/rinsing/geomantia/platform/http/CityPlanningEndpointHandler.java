package com.rinsing.geomantia.platform.http;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.application.CityFunctionZoneBuilder;
import com.rinsing.geomantia.systems.city.application.CityLandformReviewBuilder;
import com.rinsing.geomantia.systems.city.application.CityReservationMaskPlanner;
import com.rinsing.geomantia.systems.city.application.CityRoadBoundaryPlanner;
import com.rinsing.geomantia.systems.city.application.CitySiteContextBuilder;
import com.rinsing.geomantia.systems.city.application.CitySiteContextBuilder.TerritoryCellRef;
import com.rinsing.geomantia.systems.city.application.CityStructureD6Planner;
import com.rinsing.geomantia.systems.city.application.CityStructureD7Executor;
import com.rinsing.geomantia.systems.city.application.CityStructureAnchorPlanner;
import com.rinsing.geomantia.systems.city.application.CityStructureAnchorCandidatePlanner;
import com.rinsing.geomantia.systems.city.application.CityStructureArrayCandidatePlanner;
import com.rinsing.geomantia.systems.city.application.CityStructureClusterGroupCandidatePlanner;
import com.rinsing.geomantia.systems.city.application.CityStructureEnvelopeFacts;
import com.rinsing.geomantia.systems.city.application.CityStructureEnvelopeProfiler;
import com.rinsing.geomantia.systems.city.application.CityStructureMaterializationPlanner;
import com.rinsing.geomantia.systems.city.application.CityStructureProfileCatalog;
import com.rinsing.geomantia.systems.city.application.CityWallPlanner;
import com.rinsing.geomantia.systems.city.application.CityWallReservationPlanner;
import com.rinsing.geomantia.systems.city.domain.config.CityPlanningConfig;
import com.rinsing.geomantia.systems.city.domain.model.BoundaryIntent;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
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
import com.rinsing.geomantia.systems.city.infrastructure.preview.CityStructureLandingPreviewRenderer;
import com.rinsing.geomantia.systems.city.infrastructure.preview.CityStructurePreviewRenderer;
import com.rinsing.geomantia.systems.city.infrastructure.preview.CityWallPreviewRenderer;
import com.rinsing.geomantia.systems.city.infrastructure.preview.FunctionZonePreviewRenderer;
import com.rinsing.geomantia.systems.city.infrastructure.world.CityReservationMaskRegistry;
import com.rinsing.geomantia.systems.city.infrastructure.world.CityRoadMaskScanner;
import com.rinsing.geomantia.systems.city.infrastructure.world.CityRoadWeaverBridge;
import com.rinsing.geomantia.systems.city.infrastructure.world.CitySurfaceCache;
import com.rinsing.geomantia.systems.city.infrastructure.world.CityWallPlacementBackend;
import com.rinsing.geomantia.systems.city.infrastructure.world.MinecraftCityStructureMaterializationBackend;
import com.rinsing.geomantia.systems.city.infrastructure.world.MinecraftCityStructureEnvelopeSampler;
import com.rinsing.geomantia.systems.city.infrastructure.world.MinecraftCityWorldgenStatusInspector;
import com.rinsing.geomantia.systems.city.infrastructure.world.MinecraftStructurePlacementBackend;
import com.rinsing.geomantia.systems.city.infrastructure.world.WorldEditMutationBackend;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

        JsonObject response = new JsonObject();
        response.addProperty("ok", true);
        response.addProperty("patchCount", reviewPkg.landformPatches().size());
        response.addProperty("refreshedRegionCount", refreshResults.size());
        response.addProperty("patchScanPaddingBlocks", patchScanPaddingBlocks);
        response.add("citySiteContext", ctx.asJson());
        response.add("landformReviewPackage", packageJson);
        JsonObject artifacts = new JsonObject();
        artifacts.addProperty("landformReviewMap", reviewMapRef);
        artifacts.addProperty("cityLandformReviewPackage", debugRef(debugRoot, packagePath));
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
        CityStructureEnvelopeProfiler.Result result = new CityStructureEnvelopeProfiler()
                .profile(runDir, terraSenseProfileSource, ids, sampleCount, sampler);

        Path outputDirectory = runDir.resolve("city_structure_envelopes_" + safeFileName(citySeedId));
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

    static JsonObject handleExecuteD5(Path debugRoot, Path serverRoot, String runId, String citySeedId,
                                      boolean confirmWorldMutation, ServerLevel level,
                                      String requestedRoadProvider) throws IOException {
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
        JsonObject activeRegistry = CityReservationMaskRegistry.activate(activeMaskPlan, null, materializationPlan,
                runId, citySeedId, serverRoot);
        JsonObject roadWeaverConnectionPlan = CityRoadWeaverBridge.createConnectionPlan(materializationPlan);
        JsonObject roadWeaverRegistrationReport = CityRoadWeaverBridge.register(level, roadWeaverConnectionPlan,
                roadProvider);
        if (CityRoadWeaverBridge.PROVIDER_ROADWEAVER.equals(roadProvider)
                && !"registered".equals(stringValue(roadWeaverRegistrationReport, "status"))) {
            throw new IllegalArgumentException(stringValue(roadWeaverRegistrationReport, "reasonCode",
                    "ROADWEAVER_REGISTRATION_FAILED") + ": "
                    + stringValue(roadWeaverRegistrationReport, "message", ""));
        }
        BuildOperationPlan plan = BuildOperationPlan.fromJson(
                JsonParser.parseString(Files.readString(operationPath)).getAsJsonObject());
        WorldMutationReport report = skippedWorldMutationReport(plan,
                "D5 active path only activates worldgen-time mask/planned-structure registry; "
                        + "WorldEdit road operations are deferred to avoid generating chunks before structures.");
        Files.createDirectories(d5Dir);
        Path reportPath = d5Dir.resolve("world_mutation_report.json");
        Path activeMaskPath = d5Dir.resolve("active_mask_summary.json");
        Path activePlannedPath = d5Dir.resolve("active_planned_structure_registry.json");
        Path roadWeaverPlanPath = d5Dir.resolve("roadweaver_connection_plan.json");
        Path roadWeaverReportPath = d5Dir.resolve("roadweaver_registration_report.json");
        Path roadProviderStatePath = d5Dir.resolve("road_provider_state.json");
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

        JsonObject response = new JsonObject();
        response.addProperty("ok", report.failedOperations() == 0);
        response.add("activeMaskSummary", CityReservationMaskRegistry.activeSummary());
        response.addProperty("activePlannedStructureCount", CityReservationMaskRegistry.activePlannedStructureCount());
        response.addProperty("plannedStructureRegistryPath",
                CityReservationMaskRegistry.plannedRegistryPath(serverRoot).toString());
        response.addProperty("worldgenPlacementMode", true);
        response.addProperty("requiresLockedMaterializationPlan", true);
        response.addProperty("roadPlanningStage", "d7_after_worldgen_ledger");
        response.addProperty("roadProvider", roadProvider);
        response.addProperty("roadWeaverAvailable", CityRoadWeaverBridge.available());
        response.add("roadWeaverConnectionPlan", roadWeaverConnectionPlan);
        response.add("roadWeaverRegistrationReport", roadWeaverRegistrationReport);
        response.add("roadProviderState", roadProviderState);
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
        Path planPath = new CityWallPlanner().writeArtifacts(wallPlan, outputDirectory);
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

        WorkflowContext ctx = new WorkflowContext(debugRoot, serverRoot, runDir, outputDirectory, reportPath,
                runId, citySeedId, request, serverHolder, level, report, steps);

        if (!workflowStep(ctx, "city_plan_d3", d3PackagePath(runDir, citySeedId), () -> handlePlanD3(
                debugRoot, runId, citySeedId,
                hasValue(request, "cellStepBlocks") ? intValue(request, "cellStepBlocks", 4) : null,
                hasValue(request, "patchScanPaddingBlocks")
                        ? intValue(request, "patchScanPaddingBlocks", DEFAULT_D3_PATCH_SCAN_PADDING_BLOCKS) : null,
                level))) {
            return finalizeWorkflow(ctx, workflowStarted, "failed");
        }

        if (!workflowStep(ctx, "city_profile_structure_envelopes", envelopeFactsPath(runDir, citySeedId, null),
                () -> {
                    requireObject(request, "terrasenseProfileSource", "city_profile_structure_envelopes");
                    return handleProfileStructureEnvelopes(debugRoot, runId, citySeedId,
                            request.getAsJsonObject("terrasenseProfileSource"),
                            request.has("structureIds") && request.get("structureIds").isJsonArray()
                                    ? request.getAsJsonArray("structureIds") : new JsonArray(),
                            intValue(request, "sampleCount", 256),
                            serverHolder,
                            level);
                })) {
            return finalizeWorkflow(ctx, workflowStarted, "failed");
        }

        if (!workflowRunD4(ctx)) {
            return finalizeWorkflow(ctx, workflowStarted, "failed");
        }

        if (!workflowStep(ctx, "city_plan_d5", runDir.resolve("city_d5_" + safeFileName(citySeedId))
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
            return finalizeWorkflow(ctx, workflowStarted, "failed");
        }

        if (!workflowStep(ctx, "city_plan_d6", runDir.resolve("city_d6_" + safeFileName(citySeedId))
                .resolve("structure_materialization_plan.json"), () -> handlePlanD6(debugRoot, runId, citySeedId,
                serverHolder, level))) {
            return finalizeWorkflow(ctx, workflowStarted, "failed");
        }

        if (!booleanValue(request, "confirmWorldMutation", false)) {
            addWorkflowStop(ctx, "city_execute_d5", "needs_confirmation",
                    "WORKFLOW_CONFIRM_WORLD_MUTATION_REQUIRED",
                    "Pass confirmWorldMutation=true to activate masks/planned structures.");
            return finalizeWorkflow(ctx, workflowStarted, "waiting_for_confirmation");
        }

        if (!workflowStep(ctx, "city_execute_d5", runDir.resolve("city_d5_" + safeFileName(citySeedId))
                .resolve("active_planned_structure_registry.json"), () -> handleExecuteD5(debugRoot, serverRoot,
                runId, citySeedId, true, level, stringValue(request, "roadProvider", "auto")))) {
            return finalizeWorkflow(ctx, workflowStarted, "failed");
        }

        if (!workflowStep(ctx, "city_execute_d7", null, () -> handleExecuteD7(debugRoot, runId, citySeedId,
                level.getSeed(),
                true,
                booleanValue(request, "debugLateMaterialize", false),
                serverHolder,
                level))) {
            return finalizeWorkflow(ctx, workflowStarted, "failed");
        }
        JsonObject d7Step = steps.get(steps.size() - 1).getAsJsonObject();
        String d7Status = stringValue(d7Step, "responseStatus", "");
        String d7Reason = stringValue(d7Step, "reasonCode", "");
        if ("WAITING_FOR_WORLDGEN".equals(d7Reason) || "waiting_for_worldgen".equals(d7Status)) {
            addWorkflowStop(ctx, "worldgen_wait", "waiting_for_worldgen", "WAITING_FOR_WORLDGEN",
                    "TP/load target chunks, then rerun this workflow with the same runId/citySeedId.");
            return finalizeWorkflow(ctx, workflowStarted, "waiting_for_worldgen");
        }

        if (booleanValue(request, "planWalls", false)) {
            Path wallPlanPath = runDir.resolve("city_walls_" + safeFileName(citySeedId))
                    .resolve("city_wall_plan.json");
            Path wallSkipArtifact = workflowWallPlanMatchesRequest(wallPlanPath, request) ? wallPlanPath : null;
            if (!workflowStep(ctx, "city_plan_city_walls", wallSkipArtifact, () -> handlePlanCityWalls(debugRoot, runId, citySeedId,
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
                return finalizeWorkflow(ctx, workflowStarted, "failed");
            }
        }

        if (booleanValue(request, "executeWalls", false)) {
            if (!workflowStep(ctx, "city_execute_city_walls", null,
                    () -> handleExecuteCityWalls(debugRoot, runId, citySeedId, true, level,
                            booleanValue(request, "debugScan", true),
                            intValue(request, "debugScanStepBlocks", 1)))) {
                return finalizeWorkflow(ctx, workflowStarted, "failed");
            }
        }

        return finalizeWorkflow(ctx, workflowStarted, "completed");
    }

    private static boolean workflowRunD4(WorkflowContext ctx) throws IOException {
        String mode = stringValue(ctx.request(), "d4CandidateMode", "key_then_array");
        if ("key_then_array".equals(mode) || "staged_key_then_array".equals(mode)) {
            return workflowRunD4KeyThenArray(ctx);
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
            addSkippedWorkflowStep(ctx, "city_d4_key_then_array", anchorMapPath,
                    "Existing structure_anchor_map.json found.");
            return true;
        }
        D4StagePlan[] stagePlanRef = new D4StagePlan[1];
        if (!workflowStep(ctx, "city_validate_d4_staged_plan", null, () -> {
            requireObject(ctx.request(), "designSlotPlan", "city_run_workflow key_then_array");
            stagePlanRef[0] = d4StagePlan(ctx.request().getAsJsonObject("designSlotPlan"));
            writeWorkflowD4StagePlan(ctx, stagePlanRef[0]);
            JsonObject response = new JsonObject();
            response.addProperty("ok", true);
            response.addProperty("planningMode", "key_then_array");
            response.addProperty("arrayStageCount", stagePlanRef[0].arraySlots().size());
            return response;
        })) {
            return false;
        }
        D4StagePlan stagePlan = stagePlanRef[0];

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
            JsonObject arrayCandidatePlan = arrayCandidatePlanFromSlot(stagePlan.sourceDesignSlotPlan(), arraySlot);
            JsonObject occupiedAnchorMap = JsonParser.parseString(Files.readString(anchorMapPath)).getAsJsonObject();
            Path candidateSetPath = d4ArrayStageDir(ctx.runDir(), ctx.citySeedId(), arrayId)
                    .resolve("d4_array_candidate_set.json");
            if (!workflowStep(ctx, "city_plan_d4_array_stage_" + safeFileName(arrayId), null,
                    () -> workflowPlanD4ArrayStage(ctx, arrayCandidatePlan, occupiedAnchorMap))) {
                return false;
            }
            JsonObject candidateSet = JsonParser.parseString(Files.readString(candidateSetPath)).getAsJsonObject();
            JsonObject chosen = chooseWorkflowArrayCandidate(candidateSet);
            JsonObject expandedPlan = chosen.getAsJsonObject("expandedStructureAnchorPlan");
            JsonObject arrayTrace = new JsonObject();
            arrayTrace.addProperty("stageType", "array_fill");
            arrayTrace.addProperty("arrayId", arrayId);
            arrayTrace.addProperty("arrayCandidateId", stringValue(chosen, "arrayCandidateId"));
            arrayTrace.addProperty("itemCount", array(chosen, "items").size());
            arrayTrace.addProperty("variantSelectionMode", stringValue(arrayCandidatePlan,
                    "variantSelectionMode", "seeded_random"));
            stageTrace.add(arrayTrace);
            currentPlan = mergeStructureAnchorPlans(currentPlan, expandedPlan,
                    stringValue(stagePlan.sourceDesignSlotPlan(), "cityId"), stageTrace);
            JsonObject mergedPlan = currentPlan.deepCopy();
            if (!workflowStep(ctx, "city_plan_d4_merge_array_stage_" + safeFileName(arrayId), null, () -> {
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

    private static boolean workflowRunD4StructureClusterGroups(WorkflowContext ctx) throws IOException {
        Path anchorMapPath = ctx.runDir().resolve("city_d4_" + safeFileName(ctx.citySeedId()))
                .resolve("structure_anchor_map.json");
        if (booleanValue(ctx.request(), "skipExisting", true) && Files.exists(anchorMapPath)) {
            addSkippedWorkflowStep(ctx, "city_d4_structure_cluster_groups", anchorMapPath,
                    "Existing structure_anchor_map.json found.");
            return true;
        }
        Path candidateSetPath = ctx.runDir()
                .resolve("city_d4_structure_cluster_groups_" + safeFileName(ctx.citySeedId()))
                .resolve("structure_cluster_group_candidate_set.json");
        if (!workflowStep(ctx, "city_plan_d4_structure_cluster_groups", candidateSetPath, () -> {
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
        JsonObject choice = chooseWorkflowStructureClusterGroup(candidateSet);
        return workflowStep(ctx, "city_select_d4_structure_cluster_group", anchorMapPath, () -> {
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
            addSkippedWorkflowStep(ctx, stepPrefix, anchorMapPath,
                    "Existing structure_anchor_map.json found.");
            return true;
        }
        if (!workflowStep(ctx, d4SessionStepName(stepPrefix, "create_session", "city_create_d4_candidate_session"),
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
            if (!workflowStep(ctx, d4SessionStepName(stepPrefix, "plan_next_candidates",
                    "city_plan_d4_next_candidates"), null,
                    () -> handlePlanD4NextCandidates(ctx.debugRoot(), ctx.runId(), ctx.citySeedId(),
                            ctx.request().has("structureEnvelopeFactsSource")
                                    && ctx.request().get("structureEnvelopeFactsSource").isJsonObject()
                                    ? ctx.request().getAsJsonObject("structureEnvelopeFactsSource") : null))) {
                return false;
            }
            Path candidatePath = d4SessionDir(ctx.runDir(), ctx.citySeedId()).resolve("slot_candidate_set.json");
            JsonObject candidateSet = JsonParser.parseString(Files.readString(candidatePath)).getAsJsonObject();
            JsonObject choice = chooseWorkflowCandidate(candidateSet);
            if (!workflowStep(ctx, d4SessionStepName(stepPrefix, "select_candidate",
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
        return workflowStep(ctx, d4SessionStepName(stepPrefix, "finalize_session",
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

    private static boolean workflowStep(WorkflowContext ctx, String name, Path skipArtifact,
                                        WorkflowAction action) throws IOException {
        if (skipArtifact != null && booleanValue(ctx.request(), "skipExisting", true) && Files.exists(skipArtifact)) {
            addSkippedWorkflowStep(ctx, name, skipArtifact, "Existing artifact found.");
            return true;
        }
        JsonObject step = new JsonObject();
        step.addProperty("name", name);
        step.addProperty("startedAt", Instant.now().toString());
        ctx.steps().add(step);
        long started = System.nanoTime();
        try {
            JsonObject response = action.run();
            boolean ok = response == null || !response.has("ok") || response.get("ok").getAsBoolean();
            step.addProperty("status", ok ? "success" : "failed");
            step.addProperty("ok", ok);
            step.addProperty("responseStatus", stringValue(response, "status"));
            step.addProperty("reasonCode", stringValue(response, "reasonCode"));
            if (response != null && response.has("artifacts") && response.get("artifacts").isJsonObject()) {
                step.add("artifacts", response.getAsJsonObject("artifacts").deepCopy());
            }
            if (response != null && response.has("cityWallPlan") && response.get("cityWallPlan").isJsonObject()) {
                step.addProperty("wallSegmentCount",
                        array(response.getAsJsonObject("cityWallPlan"), "wallSegments").size());
                step.addProperty("wallNodeCount",
                        array(response.getAsJsonObject("cityWallPlan"), "wallNodes").size());
                step.addProperty("wallUnitCount",
                        array(response.getAsJsonObject("cityWallPlan"), "wallUnits").size());
            }
            if (response != null && response.has("d4CandidateSession") && response.get("d4CandidateSession").isJsonObject()) {
                JsonObject session = response.getAsJsonObject("d4CandidateSession");
                step.addProperty("selectedAnchorCount", intValue(session, "selectedAnchorCount", 0));
                step.addProperty("remainingSlotCount", intValue(session, "remainingSlotCount", 0));
            }
            step.addProperty("endedAt", Instant.now().toString());
            step.addProperty("durationMs", (System.nanoTime() - started) / 1_000_000L);
            writeWorkflowReport(ctx);
            return ok;
        } catch (Exception ex) {
            step.addProperty("status", "failed");
            step.addProperty("ok", false);
            step.addProperty("error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            step.addProperty("reasonCode", workflowReasonFromError(ex));
            step.addProperty("endedAt", Instant.now().toString());
            step.addProperty("durationMs", (System.nanoTime() - started) / 1_000_000L);
            writeWorkflowReport(ctx);
            return false;
        }
    }

    private static void addSkippedWorkflowStep(WorkflowContext ctx, String name, Path artifact, String message)
            throws IOException {
        JsonObject step = new JsonObject();
        step.addProperty("name", name);
        step.addProperty("status", "skipped");
        step.addProperty("ok", true);
        step.addProperty("reasonCode", "WORKFLOW_EXISTING_ARTIFACT");
        step.addProperty("message", message);
        step.addProperty("artifact", debugRef(ctx.debugRoot(), artifact));
        step.addProperty("startedAt", Instant.now().toString());
        step.addProperty("endedAt", Instant.now().toString());
        step.addProperty("durationMs", 0);
        ctx.steps().add(step);
        writeWorkflowReport(ctx);
    }

    private static void addWorkflowStop(WorkflowContext ctx, String name, String status, String reasonCode,
                                        String message) throws IOException {
        JsonObject step = new JsonObject();
        step.addProperty("name", name);
        step.addProperty("status", status);
        step.addProperty("ok", true);
        step.addProperty("reasonCode", reasonCode);
        step.addProperty("message", message);
        step.addProperty("startedAt", Instant.now().toString());
        step.addProperty("endedAt", Instant.now().toString());
        step.addProperty("durationMs", 0);
        ctx.steps().add(step);
        writeWorkflowReport(ctx);
    }

    private static JsonObject finalizeWorkflow(WorkflowContext ctx, long workflowStarted, String status)
            throws IOException {
        ctx.report().addProperty("status", status);
        ctx.report().addProperty("ok", "completed".equals(status)
                || "waiting_for_worldgen".equals(status)
                || "waiting_for_confirmation".equals(status));
        ctx.report().addProperty("endedAt", Instant.now().toString());
        ctx.report().addProperty("durationMs", (System.nanoTime() - workflowStarted) / 1_000_000L);
        writeWorkflowReport(ctx);
        JsonObject response = new JsonObject();
        response.addProperty("ok", ctx.report().get("ok").getAsBoolean());
        response.addProperty("status", status);
        response.add("workflowReport", ctx.report().deepCopy());
        response.add("artifacts", ctx.report().getAsJsonObject("artifacts").deepCopy());
        return response;
    }

    private static void writeWorkflowReport(WorkflowContext ctx) throws IOException {
        Files.writeString(ctx.reportPath(), CityJson.GSON.toJson(ctx.report()));
    }

    private static JsonObject chooseWorkflowCandidate(JsonObject candidateSet) {
        JsonObject best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (JsonElement slotElem : array(candidateSet, "slotCandidates")) {
            JsonObject slot = slotElem.getAsJsonObject();
            String slotId = stringValue(slot, "slotId", stringValue(candidateSet, "currentSlotId"));
            for (JsonElement candidateElem : array(slot, "candidates")) {
                JsonObject candidate = candidateElem.getAsJsonObject();
                double score = 0;
                JsonObject scoreBreakdown = candidate.has("scoreBreakdown")
                        && candidate.get("scoreBreakdown").isJsonObject()
                        ? candidate.getAsJsonObject("scoreBreakdown") : new JsonObject();
                if (scoreBreakdown.has("total") && !scoreBreakdown.get("total").isJsonNull()) {
                    score = scoreBreakdown.get("total").getAsDouble();
                }
                if (best == null || score > bestScore) {
                    best = candidate;
                    bestScore = score;
                    if (!best.has("slotId")) {
                        best.addProperty("slotId", slotId);
                    }
                }
            }
        }
        if (best == null) {
            throw new IllegalArgumentException("WORKFLOW_NO_D4_CANDIDATE: current slot produced no candidate.");
        }
        return best;
    }

    private static JsonObject chooseWorkflowStructureClusterGroup(JsonObject candidateSet) {
        JsonObject best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (JsonElement groupElem : array(candidateSet, "groupCandidates")) {
            if (!groupElem.isJsonObject()) {
                continue;
            }
            JsonObject group = groupElem.getAsJsonObject();
            JsonObject scoreBreakdown = group.has("scoreBreakdown")
                    && group.get("scoreBreakdown").isJsonObject()
                    ? group.getAsJsonObject("scoreBreakdown") : new JsonObject();
            double score = scoreBreakdown.has("total") && !scoreBreakdown.get("total").isJsonNull()
                    ? scoreBreakdown.get("total").getAsDouble() : 0.0;
            if (best == null || score > bestScore) {
                best = group;
                bestScore = score;
            }
        }
        if (best == null) {
            throw new IllegalArgumentException("WORKFLOW_NO_D4_STRUCTURE_CLUSTER_GROUP: no complete group candidate.");
        }
        return best;
    }

    private static JsonObject chooseWorkflowArrayCandidate(JsonObject candidateSet) {
        JsonObject best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (JsonElement groupElem : array(candidateSet, "arrayCandidates")) {
            if (!groupElem.isJsonObject()) {
                continue;
            }
            JsonObject group = groupElem.getAsJsonObject();
            JsonObject scoreBreakdown = group.has("scoreBreakdown")
                    && group.get("scoreBreakdown").isJsonObject()
                    ? group.getAsJsonObject("scoreBreakdown") : new JsonObject();
            double score = scoreBreakdown.has("total") && !scoreBreakdown.get("total").isJsonNull()
                    ? scoreBreakdown.get("total").getAsDouble() : 0.0;
            if (best == null || score > bestScore) {
                best = group;
                bestScore = score;
            }
        }
        if (best == null) {
            throw new IllegalArgumentException("WORKFLOW_NO_D4_ARRAY_CANDIDATE: no complete array candidate.");
        }
        return best;
    }

    private static D4StagePlan d4StagePlan(JsonObject designSlotPlan) {
        if (designSlotPlan == null || !designSlotPlan.has("slots")
                || !designSlotPlan.get("slots").isJsonArray()) {
            throw new IllegalArgumentException("D4_STAGED_PLAN_REQUIRES_SLOTS: designSlotPlan.slots is required.");
        }
        Map<String, JsonObject> slots = new LinkedHashMap<>();
        for (JsonElement elem : array(designSlotPlan, "slots")) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject slot = elem.getAsJsonObject();
            String slotId = requiredString(slot, "slotId");
            slots.put(slotId, slot);
        }
        List<String> order = d4PlacementOrder(designSlotPlan, slots.keySet());
        JsonArray keySlots = new JsonArray();
        List<String> keyOrder = new ArrayList<>();
        List<JsonObject> arraySlots = new ArrayList<>();
        boolean seenArray = false;
        for (String slotId : order) {
            JsonObject slot = slots.get(slotId);
            if (slot == null) {
                throw new IllegalArgumentException("D4_SLOT_ORDER_VIOLATION: placementOrder references missing slot "
                        + slotId + ".");
            }
            String strategy = normalizeD4PlacementStrategy(slot);
            if ("array_fill".equals(strategy)) {
                seenArray = true;
                arraySlots.add(slot.deepCopy());
                continue;
            }
            if (seenArray) {
                throw new IllegalArgumentException("D4_KEY_STRUCTURES_MUST_PRECEDE_ARRAYS: key slot "
                        + slotId + " appears after an array_fill slot.");
            }
            keySlots.add(slot.deepCopy());
            keyOrder.add(slotId);
        }
        if (!arraySlots.isEmpty() && keySlots.isEmpty()) {
            throw new IllegalArgumentException("D4_KEY_STRUCTURE_STAGE_REQUIRED: array_fill requires at least one "
                    + "key_structure slot before arrays.");
        }
        JsonObject keyPlan = designSlotPlan.deepCopy();
        keyPlan.addProperty("planningMode", "key_structure_stage");
        keyPlan.add("slots", keySlots);
        keyPlan.add("placementOrder", stringArray(keyOrder));
        return new D4StagePlan(designSlotPlan.deepCopy(), keyPlan, arraySlots);
    }

    private static List<String> d4PlacementOrder(JsonObject designSlotPlan, Set<String> slotIds) {
        JsonArray explicit = array(designSlotPlan, "placementOrder");
        List<String> order = new ArrayList<>();
        if (!explicit.isEmpty()) {
            for (JsonElement elem : explicit) {
                if (!elem.isJsonNull()) {
                    order.add(elem.getAsString());
                }
            }
            return order;
        }
        order.addAll(slotIds);
        return order;
    }

    private static String normalizeD4PlacementStrategy(JsonObject slot) {
        String raw = stringValue(slot, "placementStrategy", "key_structure");
        return switch (raw) {
            case "array_fill", "array", "array_group" -> "array_fill";
            case "key_structure", "single_ai_selected", "single_anchor", "manual_anchor" -> "key_structure";
            default -> throw new IllegalArgumentException("D4_PLACEMENT_STRATEGY_UNSUPPORTED: "
                    + requiredString(slot, "slotId") + " uses " + raw + ".");
        };
    }

    private static JsonObject arrayCandidatePlanFromSlot(JsonObject sourceDesignSlotPlan, JsonObject slot) {
        JsonObject plan = slot.has("arrayCandidatePlan") && slot.get("arrayCandidatePlan").isJsonObject()
                ? slot.getAsJsonObject("arrayCandidatePlan").deepCopy() : new JsonObject();
        String cityId = stringValue(sourceDesignSlotPlan, "cityId");
        String slotId = requiredString(slot, "slotId");
        plan.addProperty("schemaVersion", CityStructureArrayCandidatePlanner.PLAN_SCHEMA);
        if (stringValue(plan, "cityId").isBlank()) {
            plan.addProperty("cityId", cityId);
        }
        if (stringValue(plan, "arrayId").isBlank()) {
            plan.addProperty("arrayId", stringValue(slot, "arrayId", slotId));
        }
        if (stringValue(plan, "displayRole").isBlank() && slot.has("displayRole")) {
            plan.addProperty("displayRole", stringValue(slot, "displayRole", slotId));
        }
        if (!plan.has("candidatePatchRefs")) {
            plan.add("candidatePatchRefs", requiredArrayCopy(slot, "candidatePatchRefs"));
        }
        if (!plan.has("structureIds")) {
            plan.add("structureIds", slotStructureIds(slot));
        }
        if (!plan.has("arrayCount")) {
            int arrayCount = intValue(slot, "arrayCount", 0);
            if (arrayCount <= 0) {
                throw new IllegalArgumentException("D4_ARRAY_COUNT_REQUIRED: array_fill slot "
                        + slotId + " must set arrayCount.");
            }
            plan.addProperty("arrayCount", arrayCount);
        }
        if (!plan.has("variantSelectionMode")) {
            plan.addProperty("variantSelectionMode", stringValue(slot, "variantSelectionMode", "seeded_random"));
        }
        for (String key : List.of("patterns", "structureWeights", "variantSeed", "priority",
                "clearanceBlocks", "smallClearanceBlocks", "vegetationMarginBlocks", "roadAccessMarginBlocks")) {
            copyIfPresent(slot, plan, key);
        }
        return plan;
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

    private static JsonObject mergeStructureAnchorPlans(JsonObject basePlan,
                                                        JsonObject appendedPlan,
                                                        String cityId,
                                                        JsonArray stageTrace) {
        String normalizedCityId = cityId == null || cityId.isBlank()
                ? stringValue(basePlan, "cityId", stringValue(appendedPlan, "cityId")) : cityId;
        JsonArray anchors = new JsonArray();
        Set<String> anchorIds = new LinkedHashSet<>();
        appendAnchors(anchors, anchorIds, basePlan, "base");
        appendAnchors(anchors, anchorIds, appendedPlan, "array");
        JsonObject plan = new JsonObject();
        plan.addProperty("schemaVersion", CityStructureAnchorPlanner.PLAN_SCHEMA);
        plan.addProperty("cityId", normalizedCityId);
        plan.add("anchors", anchors);
        JsonObject trace = new JsonObject();
        trace.addProperty("schemaVersion", "city_d4_staged_key_then_array_trace.v0.1");
        trace.addProperty("planningMode", "key_then_array");
        trace.addProperty("stageCount", stageTrace.size());
        trace.add("stages", stageTrace.deepCopy());
        plan.add("stagedD4Trace", trace);
        return plan;
    }

    private static void appendAnchors(JsonArray anchors,
                                      Set<String> anchorIds,
                                      JsonObject plan,
                                      String source) {
        for (JsonElement elem : array(plan, "anchors")) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject anchor = elem.getAsJsonObject();
            String anchorId = requiredString(anchor, "anchorId");
            if (!anchorIds.add(anchorId)) {
                throw new IllegalArgumentException("D4_STAGED_DUPLICATE_ANCHOR_ID: " + anchorId
                        + " from " + source + ".");
            }
            anchors.add(anchor.deepCopy());
        }
    }

    private static JsonArray slotStructureIds(JsonObject slot) {
        JsonArray ids = new JsonArray();
        if (slot.has("structureIds") && slot.get("structureIds").isJsonArray()) {
            for (JsonElement elem : slot.getAsJsonArray("structureIds")) {
                if (!elem.isJsonNull()) {
                    ids.add(elem.getAsString());
                }
            }
        } else if (slot.has("structureId") && !slot.get("structureId").isJsonNull()) {
            ids.add(slot.get("structureId").getAsString());
        }
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("D4_ARRAY_STRUCTURE_IDS_REQUIRED: array_fill slot "
                    + requiredString(slot, "slotId") + " must set structureId or structureIds[].");
        }
        return ids;
    }

    private static JsonArray requiredArrayCopy(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || !obj.get(key).isJsonArray()) {
            throw new IllegalArgumentException(key + " array is required.");
        }
        return obj.getAsJsonArray(key).deepCopy();
    }

    private static void copyIfPresent(JsonObject source, JsonObject target, String key) {
        if (source != null && source.has(key) && !target.has(key)) {
            target.add(key, source.get(key).deepCopy());
        }
    }

    private static JsonArray stringArray(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    private static Path d4ArrayStageDir(Path runDir, String citySeedId, String arrayId) {
        return runDir.resolve("city_d4_array_candidates_" + safeFileName(citySeedId)
                + "_" + safeFileName(arrayId));
    }

    private static void writeWorkflowD4StagePlan(WorkflowContext ctx, D4StagePlan stagePlan) throws IOException {
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
        writeWorkflowReport(ctx);
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
        writeWorkflowReport(ctx);
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

    private static String workflowReasonFromError(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        int colon = message.indexOf(':');
        String prefix = colon > 0 ? message.substring(0, colon) : message;
        return prefix.length() <= 80 ? prefix : prefix.substring(0, 80);
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

    private static void validateLockedMaterializationPlan(JsonObject materializationPlan) {
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
            if (!item.has("locked") || !item.get("locked").getAsBoolean()
                    || !item.has("lockedActualFootprint") || !item.has("lockedCollisionEnvelope")
                    || stringValue(item, "expectedStartSignature").isBlank()) {
                throw new IllegalArgumentException("D6 planned structure is not fully locked: "
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

    private static boolean hasValue(JsonObject obj, String key) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull();
    }

    @FunctionalInterface
    private interface WorkflowAction {
        JsonObject run() throws Exception;
    }

    private record WorkflowContext(Path debugRoot,
                                   Path serverRoot,
                                   Path runDir,
                                   Path outputDirectory,
                                   Path reportPath,
                                   String runId,
                                   String citySeedId,
                                   JsonObject request,
                                   MinecraftServerHolder serverHolder,
                                   ServerLevel level,
                                   JsonObject report,
                                   JsonArray steps) {
    }

    private record D4StagePlan(JsonObject sourceDesignSlotPlan,
                               JsonObject keyDesignSlotPlan,
                               List<JsonObject> arraySlots) {
    }

    private record RunMetadata(int cellStepBlocks, String dimensionId) {
    }

    private record CityInputs(FunctionZoneMap zoneMap, BuildableAreaMap buildableAreaMap,
                              Path zoneMapPath, Path terrainStatsPath, Path buildablePath) {
    }

    record MinecraftServerHolder(net.minecraft.server.MinecraftServer server) {
    }
}
