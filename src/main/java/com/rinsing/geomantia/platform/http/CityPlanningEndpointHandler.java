package com.rinsing.geomantia.platform.http;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.application.CityD4DesignLoopStatePlanner;
import com.rinsing.geomantia.systems.city.application.CityD4StagedPlanCompiler;
import com.rinsing.geomantia.systems.city.application.CityBlueprintService;
import com.rinsing.geomantia.systems.city.application.CityBlueprintCompilerService;
import com.rinsing.geomantia.systems.city.application.CityBlueprintFailureBudget;
import com.rinsing.geomantia.systems.city.application.CityBlueprintCodec;
import com.rinsing.geomantia.systems.city.application.CityBlueprintReferenceCatalog;
import com.rinsing.geomantia.systems.city.application.CityLandformReviewBuilder;
import com.rinsing.geomantia.systems.city.application.CityReservationMaskPlanner;
import com.rinsing.geomantia.systems.city.application.CitySiteContextBuilder;
import com.rinsing.geomantia.systems.city.application.CitySiteContextBuilder.TerritoryCellRef;
import com.rinsing.geomantia.systems.city.application.CityStructureAnchorPlanner;
import com.rinsing.geomantia.systems.city.application.CityStructureAnchorCandidatePlanner;
import com.rinsing.geomantia.systems.city.application.CityStructureArrayCandidatePlanner;
import com.rinsing.geomantia.systems.city.application.CityStructureArrayLayoutLoopPlanner;
import com.rinsing.geomantia.systems.city.application.CityStructureClusterGroupCandidatePlanner;
import com.rinsing.geomantia.systems.city.application.CityStructureMaterializationPlanner;
import com.rinsing.geomantia.systems.city.application.CityStructureProfileCatalog;
import com.rinsing.geomantia.systems.city.application.CityStructureCatalogQueryService;
import com.rinsing.geomantia.systems.city.application.CityTemplatePlacementGeometry;
import com.rinsing.geomantia.systems.city.application.CityTemplateCatalog;
import com.rinsing.geomantia.systems.city.application.CityTemplateCatalogLoader;
import com.rinsing.geomantia.systems.city.application.CityTemplateTerrainPosePolicy;
import com.rinsing.geomantia.systems.city.application.CityTestRunLayout;
import com.rinsing.geomantia.systems.city.application.CityWallPlanner;
import com.rinsing.geomantia.systems.city.application.CityWallReservationPlanner;
import com.rinsing.geomantia.systems.city.application.CityWorkflowCandidateSelector;
import com.rinsing.geomantia.systems.city.application.CityWorkflowStepRunner;
import com.rinsing.geomantia.systems.city.application.landuse.LandUseAreaPlanCodec;
import com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlan;
import com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlanCodec;
import com.rinsing.geomantia.systems.city.application.landuse.LandUsePlanningService;
import com.rinsing.geomantia.systems.city.application.landuse.LandUseTerrainFieldCodec;
import com.rinsing.geomantia.systems.city.application.landuse.LandUseTerrainFieldCompiler;
import com.rinsing.geomantia.systems.city.application.outdoor.CityOutdoorBlueprintCompiler;
import com.rinsing.geomantia.systems.city.application.outdoor.CityOutdoorIntentPlan;
import com.rinsing.geomantia.systems.city.application.outdoor.CityUrbanSpacePlan;
import com.rinsing.geomantia.systems.city.domain.blueprint.CityBlueprint;
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
import com.rinsing.geomantia.systems.city.infrastructure.preview.CityLandUsePreviewRenderer;
import com.rinsing.geomantia.systems.city.infrastructure.preview.CityLandformReviewMapRenderer;
import com.rinsing.geomantia.systems.city.infrastructure.preview.CityStructureLandingPreviewRenderer;
import com.rinsing.geomantia.systems.city.infrastructure.preview.CityWallPreviewRenderer;
import com.rinsing.geomantia.systems.city.infrastructure.landuse.LandUseDefaultConfigBootstrap;
import com.rinsing.geomantia.systems.city.infrastructure.landuse.LandUseRuleCatalogLoader;
import com.rinsing.geomantia.systems.city.infrastructure.landuse.LandUseSettingsLoader;
import com.rinsing.geomantia.systems.city.infrastructure.world.CityReservationMaskRegistry;
import com.rinsing.geomantia.systems.city.infrastructure.world.MinecraftCityWallArtifactWriter;
import com.rinsing.geomantia.systems.city.infrastructure.world.CityRoadMaskScanner;
import com.rinsing.geomantia.systems.city.infrastructure.world.CitySurfaceCache;
import com.rinsing.geomantia.systems.city.infrastructure.world.CityWallPlacementBackend;
import com.rinsing.geomantia.systems.city.infrastructure.world.MinecraftCityTemplateReader;
import com.rinsing.geomantia.systems.city.infrastructure.world.MinecraftCityWorldgenStatusInspector;
import com.rinsing.geomantia.systems.city.infrastructure.world.landuse.CityLandUseChunkStatusPreflight;
import com.rinsing.geomantia.systems.city.infrastructure.world.landuse.CityLandUseWorldgenRegistry;
import com.rinsing.geomantia.systems.gis.GisClassifierConfig;
import com.rinsing.geomantia.systems.gis.GisSampleConfig;
import com.rinsing.geomantia.systems.gis.adapter.minecraft.MinecraftPriorAtlasSampler;
import com.rinsing.geomantia.systems.gis.application.refresh.RefreshPriority;
import com.rinsing.geomantia.systems.gis.application.refresh.GisRefreshService;
import com.rinsing.geomantia.systems.gis.application.analysis.MultiRegionLandformAnalyzer;
import com.rinsing.geomantia.systems.gis.application.refresh.RefreshResult;
import com.rinsing.geomantia.systems.gis.application.refresh.SampleMode;
import com.rinsing.geomantia.systems.gis.domain.landform.LandformPatch;
import com.rinsing.geomantia.systems.gis.domain.region.AtlasRegion;
import com.rinsing.geomantia.systems.gis.domain.region.AtlasRegionStore;
import com.rinsing.geomantia.systems.gis.preview.BiomeOverviewRenderer;
import com.rinsing.geomantia.systems.realm_planning.adapter.minecraft.MinecraftTerrainPreviewProviderFactory;
import com.rinsing.geomantia.systems.realm_planning.application.terrain.TerrainPreviewAtlasSampler;
import com.rinsing.geomantia.systems.realm_planning.application.terrain.TerrainPreviewProviderSelection;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

final class CityPlanningEndpointHandler {
    static final int DEFAULT_D3_PATCH_SCAN_PADDING_BLOCKS = 128;
    static final int D3_CELL_STEP_BLOCKS = 16;
    private static final String BLUEPRINT_OUTDOOR_COMPLETION_SCHEMA =
            "city_land_use_planning_complete";
    private static final CityD4StagedPlanCompiler D4_STAGED_PLAN_COMPILER =
            new CityD4StagedPlanCompiler();
    private static final CityWorkflowCandidateSelector WORKFLOW_CANDIDATE_SELECTOR =
            new CityWorkflowCandidateSelector();

    private CityPlanningEndpointHandler() {
    }

    static JsonObject handlePrepareD4BlueprintContext(Path debugRoot, String runId, String citySeedId,
                                                       JsonObject terraSenseProfileSource,
                                                       JsonObject templateCatalogSource,
                                                       JsonObject blueprintReferenceCatalog) throws IOException {
        return handlePrepareD4BlueprintContext(debugRoot, runId, citySeedId, terraSenseProfileSource,
                templateCatalogSource, blueprintReferenceCatalog, null);
    }

    static JsonObject handlePrepareD4BlueprintContext(Path debugRoot, String runId, String citySeedId,
                                                       JsonObject terraSenseProfileSource,
                                                       JsonObject templateCatalogSource,
                                                       JsonObject blueprintReferenceCatalog,
                                                       JsonObject patchReviewEvidence) throws IOException {
        JsonObject response = new CityBlueprintService().prepare(debugRoot, runId, citySeedId,
                terraSenseProfileSource, templateCatalogSource, blueprintReferenceCatalog, patchReviewEvidence);
        JsonObject request = standaloneRequest("city_prepare_d4_blueprint_context", runId, citySeedId);
        request.add("terraSenseProfileSource", terraSenseProfileSource.deepCopy());
        request.add("templateCatalogSource", templateCatalogSource.deepCopy());
        request.add("blueprintReferenceCatalog", blueprintReferenceCatalog.deepCopy());
        return recordStandaloneTestRunState(debugRoot, runId, citySeedId, request,
                "awaiting_city_blueprint", "city_submit_d4_blueprint", response);
    }

    static JsonObject handleSubmitD4Blueprint(Path debugRoot, String runId, String citySeedId,
                                               String contextId, JsonObject cityBlueprint) throws IOException {
        JsonObject response = new CityBlueprintService().submit(debugRoot, runId, citySeedId,
                contextId, cityBlueprint);
        JsonObject request = standaloneRequest("city_submit_d4_blueprint", runId, citySeedId);
        request.addProperty("contextId", contextId);
        request.add("cityBlueprint", cityBlueprint.deepCopy());
        boolean accepted = booleanValue(response, "ok", false);
        String nextAction = stringValue(response, "nextAction",
                accepted ? "city_compile_d4_blueprint" : "city_submit_d4_blueprint");
        return recordStandaloneTestRunState(debugRoot, runId, citySeedId, request,
                accepted ? "awaiting_d4_compile"
                        : "stop_for_human_review".equals(nextAction) ? "failed" : "awaiting_city_blueprint",
                nextAction, response);
    }

    static JsonObject handleCompileD4Blueprint(Path debugRoot, String runId, String citySeedId) throws IOException {
        CityBlueprintFailureBudget failureBudget = new CityBlueprintFailureBudget();
        JsonObject currentBudget = failureBudget.current(debugRoot, runId, citySeedId);
        if (CityBlueprintFailureBudget.exhausted(currentBudget)) {
            JsonObject exhausted = new JsonObject();
            exhausted.addProperty("ok", false);
            exhausted.addProperty("status", "failure_budget_exhausted");
            exhausted.addProperty("reasonCode", "CITY_BLUEPRINT_FAILURE_BUDGET_EXHAUSTED");
            exhausted.addProperty("message", "The current D4 context has reached five failed compilations.");
            exhausted.add("artifacts", new JsonObject());
            CityBlueprintFailureBudget.attach(exhausted, currentBudget, debugRoot, runId, citySeedId);
            addBlueprintRetryGuidance(exhausted);
            return recordStandaloneTestRunState(debugRoot, runId, citySeedId,
                    standaloneRequest("city_compile_d4_blueprint", runId, citySeedId),
                    "failed", "stop_for_human_review", exhausted);
        }
        CityBlueprintCompilerService compiler = new CityBlueprintCompilerService();
        CityBlueprintCompilerService.CompilationResult compiled = compiler.compile(debugRoot, runId, citySeedId);
        JsonObject compileResponse = compiler.persist(debugRoot, runId, citySeedId, compiled);
        if (!compiled.ok()) {
            JsonObject budget = failureBudget.recordFailure(debugRoot, runId, citySeedId,
                    compiled.reasonCode(), compiled.message());
            CityBlueprintFailureBudget.attach(compileResponse, budget, debugRoot, runId, citySeedId);
            addBlueprintRetryGuidance(compileResponse);
            return recordStandaloneTestRunState(debugRoot, runId, citySeedId,
                    standaloneRequest("city_compile_d4_blueprint", runId, citySeedId),
                    CityBlueprintFailureBudget.retryAllowed(budget)
                            ? "awaiting_city_blueprint_revision" : "failed",
                    stringValue(compileResponse, "nextAction", "stop_for_human_review"), compileResponse);
        }
        JsonObject finalized = handleFinalizeCompiledD4(debugRoot, runId, citySeedId,
                compiled.terraSenseProfileSource(), compiled.structureAnchorPlan(),
                compiled.landscapeCapacityReservationPlan(), compiled.groupExtentMap(),
                compiled.compileTrace());
        JsonObject artifacts = finalized.has("artifacts") && finalized.get("artifacts").isJsonObject()
                ? finalized.getAsJsonObject("artifacts") : new JsonObject();
        compileResponse.getAsJsonObject("artifacts").entrySet().forEach(entry ->
                artifacts.add(entry.getKey(), entry.getValue().deepCopy()));
        finalized.add("artifacts", artifacts);
        finalized.addProperty("compileStatus", booleanValue(finalized, "ok", false)
                ? "compiled" : "anchor_finalization_failed");
        finalized.add("cityGenerationCompileTrace", compiled.compileTrace().deepCopy());
        finalized.add("groupExtentMap", compiled.groupExtentMap().deepCopy());
        JsonObject budget = booleanValue(finalized, "ok", false)
                ? failureBudget.recordSuccess(debugRoot, runId, citySeedId)
                : failureBudget.recordFailure(debugRoot, runId, citySeedId,
                stringValue(finalized, "reasonCode", "CITY_BLUEPRINT_COMPILED_ANCHOR_FINALIZATION_FAILED"),
                stringValue(finalized, "message", "Compiled D4 anchor finalization failed."));
        CityBlueprintFailureBudget.attach(finalized, budget, debugRoot, runId, citySeedId);
        if (!booleanValue(finalized, "ok", false)) addBlueprintRetryGuidance(finalized);
        else finalized.addProperty("nextAction", "city_run_workflow");
        JsonObject request = standaloneRequest("city_compile_d4_blueprint", runId, citySeedId);
        return recordStandaloneTestRunState(debugRoot, runId, citySeedId, request,
                booleanValue(finalized, "ok", false) ? "awaiting_workflow_resume"
                        : CityBlueprintFailureBudget.retryAllowed(budget)
                        ? "awaiting_city_blueprint_revision" : "failed",
                stringValue(finalized, "nextAction", booleanValue(finalized, "ok", false)
                        ? "city_run_workflow" : "stop_for_human_review"),
                finalized);
    }

    private static void addBlueprintRetryGuidance(JsonObject response) {
        boolean retryAllowed = booleanValue(response, "retryAllowed", false);
        response.addProperty("nextAction", retryAllowed
                ? "city_submit_d4_blueprint" : "stop_for_human_review");
        JsonObject policy = new JsonObject();
        policy.addProperty("instruction", retryAllowed
                ? "Revise and resubmit the complete Blueprint using the returned failureSummary and tool evidence."
                : "Stop this Agent Loop and request human review.");
        policy.addProperty("sourceCodeInspectionAllowed", false);
        policy.addProperty("projectDocumentationInspectionAllowed", false);
        policy.addProperty("rawRunArtifactInspectionAllowed", false);
        response.add("agentRecoveryPolicy", policy);
    }

    private static JsonObject handleFinalizeCompiledD4(Path debugRoot, String runId, String citySeedId,
                                                          JsonObject terraSenseProfileSource,
                                                          JsonObject resolvedAnchorPlan,
                                                          JsonObject landscapeCapacityPlan,
                                                          JsonObject groupExtentMap,
                                                          JsonObject compileTrace) throws IOException {
        Path runDir = debugRoot.resolve(runId);
        loadCitySeedForD4(runDir, runId, citySeedId);
        Path d3PackagePath = d3PackagePath(runDir, citySeedId);
        CityLandformReviewPackage reviewPackage = loadD3Package(debugRoot, runDir, citySeedId);
        CityStructureAnchorPlanner.Result result = new CityStructureAnchorPlanner()
                .plan(runDir, reviewPackage, terraSenseProfileSource, resolvedAnchorPlan);
        Path outputDirectory = cityStageDir(runDir, citySeedId, CityTestRunLayout.D4);
        Files.createDirectories(outputDirectory);
        Path anchorPlanPath = outputDirectory.resolve("structure_anchor_plan.json");
        Path anchorMapPath = outputDirectory.resolve("structure_anchor_map.json");
        Path semanticSourcePath = outputDirectory.resolve("semantic_profile_source.json");
        Path qualityPath = outputDirectory.resolve("quality_report.json");
        Files.writeString(anchorPlanPath, CityJson.GSON.toJson(result.structureAnchorPlan()));
        Files.writeString(anchorMapPath, CityJson.GSON.toJson(result.structureAnchorMap()));
        Files.writeString(semanticSourcePath, CityJson.GSON.toJson(
                result.structureAnchorMap().getAsJsonObject("semanticProfileSource")));
        Files.writeString(qualityPath, CityJson.GSON.toJson(result.qualityReport()));
        CityStructureLandingPreviewRenderer.D4PreviewArtifacts previews =
                new CityStructureLandingPreviewRenderer().renderD4WithGroupDetails(
                        result.structureAnchorMap(), reviewPackage, landscapeCapacityPlan, groupExtentMap,
                        compileTrace, outputDirectory);
        JsonObject response = result.asJson();
        JsonObject artifacts = new JsonObject();
        artifacts.addProperty("structureAnchorPlan", debugRef(debugRoot, anchorPlanPath));
        artifacts.addProperty("structureAnchorMap", debugRef(debugRoot, anchorMapPath));
        artifacts.addProperty("semanticProfileSource", debugRef(debugRoot, semanticSourcePath));
        artifacts.addProperty("structureAnchorPreview", debugRef(debugRoot, previews.overview()));
        JsonObject groupPreviews = new JsonObject();
        previews.groupPreviews().forEach((groupId, path) ->
                groupPreviews.addProperty(groupId, debugRef(debugRoot, path)));
        artifacts.add("groupStructurePreviews", groupPreviews);
        artifacts.addProperty("qualityReport", debugRef(debugRoot, qualityPath));
        artifacts.addProperty("sourceD3Package", debugRef(debugRoot, d3PackagePath));
        response.add("artifacts", artifacts);
        if (!booleanValue(result.qualityReport(), "passed", false)) {
            response.addProperty("reasonCode", "CITY_BLUEPRINT_COMPILED_ANCHOR_FINALIZATION_FAILED");
        }
        return response;
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
        return handlePlanD3(debugRoot, runId, citySeedId, requestedCellStepBlocks, null, true, level);
    }

    static JsonObject handlePlanD3(Path debugRoot, String runId, String citySeedId,
                                    Integer requestedCellStepBlocks, Integer requestedPatchScanPaddingBlocks,
                                    ServerLevel level) throws IOException {
        return handlePlanD3(debugRoot, runId, citySeedId, requestedCellStepBlocks,
                requestedPatchScanPaddingBlocks, true, level);
    }

    static JsonObject handlePlanD3(Path debugRoot, String runId, String citySeedId,
                                    Integer requestedCellStepBlocks, Integer requestedPatchScanPaddingBlocks,
                                    boolean preferGeneratorNativeTerrain, ServerLevel level) throws IOException {
        long started = System.nanoTime();
        if (requestedCellStepBlocks != null && requestedCellStepBlocks != D3_CELL_STEP_BLOCKS) {
            throw new IllegalArgumentException("CITY_D3_CELL_STEP_FIXED: cellStepBlocks must be 16.");
        }
        Path runDir = debugRoot.resolve(runId);
        requireMatchingRunWorldIdentity(runDir, level);
        JsonObject seed = loadCitySeed(runDir, runId, citySeedId);
        RunMetadata metadata = loadRunMetadata(runDir, null,
                level.dimension().location().toString());

        CityPlanningConfig config = CityPlanningConfig.defaults();
        CitySiteContextBuilder siteBuilder = new CitySiteContextBuilder(config);
        CityLandformReviewBuilder reviewBuilder = new CityLandformReviewBuilder(config);
        CityLandformReviewMapRenderer mapRenderer = new CityLandformReviewMapRenderer();

        List<TerritoryCellRef> territoryCells = loadTerritoryCells(runDir, stringValue(seed, "realmId"));
        CitySiteContext ctx = buildD3SiteContext(siteBuilder, seed, metadata, territoryCells);

        int patchScanPaddingBlocks = normalizeD3PatchScanPaddingBlocks(requestedPatchScanPaddingBlocks);
        BlockBounds patchContextBounds = expandBounds(ctx.bounds(), patchScanPaddingBlocks);
        int localCellStepBlocks = ctx.grid().cellStepBlocks();
        GisSampleConfig sampleConfig = GisSampleConfig.defaults().withCellStepBlocks(localCellStepBlocks);
        AtlasRegionStore store = new AtlasRegionStore(sampleConfig);
        TerrainPreviewProviderSelection terrainProvider = selectD3TerrainProvider(level,
                preferGeneratorNativeTerrain);
        GisRefreshService gisService = new GisRefreshService(sampleConfig, GisClassifierConfig.defaults(),
                store, new TerrainPreviewAtlasSampler(terrainProvider));
        Path outputDirectory = cityStageDir(runDir, citySeedId, CityTestRunLayout.D3);
        List<RefreshResult> refreshResults = refreshCityD3Regions(gisService, sampleConfig,
                level.dimension().location().toString(), patchContextBounds, outputDirectory);

        List<AtlasRegion> regions = refreshResults.stream()
                .map(RefreshResult::region)
                .filter(java.util.Objects::nonNull)
                .toList();
        List<LandformPatch> patches = regions.isEmpty() ? List.of()
                : new MultiRegionLandformAnalyzer(sampleConfig, GisClassifierConfig.defaults())
                        .analyze(regions, d3PatchNamespace(level, localCellStepBlocks, citySeedId));

        CityLandformReviewPackage reviewPkg = regions.isEmpty()
                ? reviewBuilder.build(ctx, patches)
                : reviewBuilder.buildFromRegions(ctx, regions, patches, patchContextBounds);
        Path reviewMapPath = mapRenderer.render(ctx, reviewPkg, patches, outputDirectory);
        String reviewMapRef = debugRef(debugRoot, reviewMapPath);
        Path biomeOverviewPath = outputDirectory.resolve("biome_overview.png");
        renderD3BiomeOverview(regions, patchContextBounds, localCellStepBlocks, biomeOverviewPath);
        String biomeOverviewRef = debugRef(debugRoot, biomeOverviewPath);
        reviewPkg = reviewPkg.withReviewMap(reviewMapRef, List.of(
                reviewMapRef,
                biomeOverviewRef,
                debugRef(debugRoot, outputDirectory)));
        Path packagePath = outputDirectory.resolve("city_landform_review_package.json");
        JsonObject packageJson = reviewPkg.asJson();
        packageJson.addProperty("biomeOverviewImage", biomeOverviewRef);
        packageJson.add("terrainProvider", terrainProviderJson(terrainProvider));
        addD3PatchScanMetadata(packageJson, patchScanPaddingBlocks, patchContextBounds, refreshResults);
        Files.writeString(packagePath, CityJson.GSON.toJson(packageJson));
        Files.deleteIfExists(d3SiteDecisionPath(runDir, citySeedId));

        LandUseTerrainField landUseTerrainField = new LandUseTerrainFieldCompiler().compile(reviewPkg, regions);
        Path landUseDirectory = cityStageDir(runDir, citySeedId, CityTestRunLayout.LAND_USE);
        Files.createDirectories(landUseDirectory);
        Path landUseTerrainFieldPath = landUseDirectory.resolve("land_use_terrain_field.json");
        Files.writeString(landUseTerrainFieldPath, CityJson.GSON.toJson(
                new LandUseTerrainFieldCodec().toJson(landUseTerrainField)));
        Files.deleteIfExists(landUseDirectory.resolve("city_land_use_planning_complete.json"));

        JsonObject response = new JsonObject();
        response.addProperty("ok", true);
        boolean serverThreadBlocked = level.getServer().isSameThread();
        response.addProperty("executionMode", serverThreadBlocked ? "server_thread_legacy" : "api_worker");
        response.addProperty("serverThreadBlocked", serverThreadBlocked);
        response.addProperty("durationMs", (System.nanoTime() - started) / 1_000_000.0);
        response.addProperty("patchCount", reviewPkg.landformPatches().size());
        response.addProperty("refreshedRegionCount", refreshResults.size());
        response.addProperty("patchScanPaddingBlocks", patchScanPaddingBlocks);
        response.addProperty("landUseTerrainCellCount", landUseTerrainField.cells().size());
        response.add("terrainProvider", terrainProviderJson(terrainProvider));
        boolean siteReviewRequired = requiresD3SiteReview(seed);
        response.addProperty("siteReviewStatus", siteReviewRequired ? "awaiting_review" : "not_required");
        response.add("citySiteContext", ctx.asJson());
        response.add("landformReviewPackage", packageJson);
        JsonObject artifacts = new JsonObject();
        artifacts.addProperty("landformReviewMap", reviewMapRef);
        artifacts.addProperty("biomeOverview", biomeOverviewRef);
        artifacts.addProperty("cityLandformReviewPackage", debugRef(debugRoot, packagePath));
        artifacts.addProperty("landUseTerrainField", debugRef(debugRoot, landUseTerrainFieldPath));
        CityTestRunLayout testRunLayout = CityTestRunLayout.open(runDir, citySeedId);
        if (!testRunLayout.legacy()) {
            JsonObject stageRequest = new JsonObject();
            stageRequest.addProperty("toolName", "city_plan_d3");
            stageRequest.addProperty("runId", runId);
            stageRequest.addProperty("citySeedId", citySeedId);
            stageRequest.addProperty("cellStepBlocks", D3_CELL_STEP_BLOCKS);
            stageRequest.addProperty("preferGeneratorNativeTerrain", preferGeneratorNativeTerrain);
            if (requestedPatchScanPaddingBlocks != null) {
                stageRequest.addProperty("patchScanPaddingBlocks", requestedPatchScanPaddingBlocks);
            }
            JsonObject manifest = loadOrCreateTestRunManifest(testRunLayout, runDir, runId, citySeedId,
                    seed, stageRequest, debugRoot);
            artifacts.addProperty("testRunManifest", debugRef(debugRoot, testRunLayout.manifestPath()));
            artifacts.addProperty("testRunPackage", debugRef(debugRoot, testRunLayout.packageDirectory()));
            JsonObject stageState = new JsonObject();
            stageState.addProperty("status", siteReviewRequired ? "awaiting_site_review" : "waiting_for_patch_review");
            stageState.addProperty("nextAction", siteReviewRequired
                    ? "city_review_d3_site" : "patch_explorer_open");
            stageState.add("artifacts", artifacts.deepCopy());
            writeTestRunState(testRunLayout.manifestPath(), manifest, stageState);
        }
        response.add("artifacts", artifacts);
        JsonArray nextActions = new JsonArray();
        nextActions.add(siteReviewRequired ? "city_review_d3_site" : "patch_explorer_open");
        response.add("nextActions", nextActions);
        return response;
    }

    private static TerrainPreviewProviderSelection selectD3TerrainProvider(ServerLevel level,
            boolean preferGeneratorNativeTerrain) {
        String dimensionId = level.dimension().location().toString();
        String fallbackFingerprint = String.join("|", "minecraft_prior", dimensionId,
                Long.toString(level.getSeed()), level.getChunkSource().getGenerator().getClass().getName());
        return MinecraftTerrainPreviewProviderFactory
                .createSelector(level, new MinecraftPriorAtlasSampler(level), fallbackFingerprint)
                .select(preferGeneratorNativeTerrain);
    }

    private static JsonObject terrainProviderJson(TerrainPreviewProviderSelection selection) {
        JsonObject value = new JsonObject();
        value.addProperty("providerId", selection.providerId());
        value.addProperty("sourceKind", selection.sourceKind());
        value.addProperty("fastPath", selection.fastPath());
        value.addProperty("fallbackReason", selection.fallbackReason());
        value.addProperty("sourceFingerprint", selection.sourceFingerprint());
        value.addProperty("samplingSemantics", selection.samplingSemantics());
        return value;
    }

    private static void renderD3BiomeOverview(List<AtlasRegion> regions, BlockBounds bounds,
            int cellStepBlocks, Path output) throws IOException {
        List<BiomeOverviewRenderer.Cell> cells = regions.stream()
                .flatMap(region -> region.cells().stream())
                .filter(cell -> cell.blockMinX() <= bounds.maxX()
                        && cell.blockMinX() + cellStepBlocks - 1 >= bounds.minX()
                        && cell.blockMinZ() <= bounds.maxZ()
                        && cell.blockMinZ() + cellStepBlocks - 1 >= bounds.minZ())
                .map(cell -> new BiomeOverviewRenderer.Cell(
                        cell.globalCellX(), cell.globalCellZ(), cell.biomeId()))
                .toList();
        new BiomeOverviewRenderer().render(cells, output, "D3 biome overview", cellStepBlocks);
    }

    private static String d3PatchNamespace(ServerLevel level, int cellStepBlocks, String citySeedId) {
        return level.dimension().location() + ":step." + cellStepBlocks + ":city."
                + citySeedId.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    static JsonObject handleReviewD3Site(Path debugRoot, String runId, String citySeedId,
                                         String decision, String decisionReason,
                                         String reviewedBy) throws IOException {
        Path runDir = debugRoot.resolve(runId);
        JsonObject seed = loadCitySeed(runDir, runId, citySeedId);
        if (!requiresD3SiteReview(seed)) {
            throw new IllegalArgumentException("CITY_D3_SITE_REVIEW_NOT_REQUIRED");
        }
        if (!"accept_selected_site".equals(decision) && !"reselect_required".equals(decision)) {
            throw new IllegalArgumentException("CITY_D3_SITE_REVIEW_DECISION_INVALID");
        }
        if (decisionReason == null || decisionReason.isBlank()) {
            throw new IllegalArgumentException("CITY_D3_SITE_REVIEW_REASON_REQUIRED");
        }
        Path packagePath = d3PackagePath(runDir, citySeedId);
        if (!Files.isRegularFile(packagePath)) {
            throw new IllegalArgumentException("CITY_D3_SITE_REVIEW_PACKAGE_MISSING: run city_plan_d3 first: "
                    + debugRef(debugRoot, packagePath));
        }
        String packageJson = Files.readString(packagePath);
        JsonObject source = seed.getAsJsonObject("source");
        JsonObject review = new JsonObject();
        review.addProperty("schema", "city_d3_site_review_decision");
        review.addProperty("runId", runId);
        review.addProperty("citySeedId", citySeedId);
        review.addProperty("decision", decision);
        review.addProperty("decisionReason", decisionReason);
        review.addProperty("reviewedBy", reviewedBy == null || reviewedBy.isBlank() ? "ai" : reviewedBy);
        review.addProperty("reviewedAt", Instant.now().toString());
        review.addProperty("d3PackageIdentity", sha256(packageJson));
        review.addProperty("citySeedIdentity", sha256(CityJson.GSON.toJson(seed)));
        review.addProperty("patchSelectionRef", stringValue(source, "patchSelectionRef"));
        Path decisionPath = d3SiteDecisionPath(runDir, citySeedId);
        Files.writeString(decisionPath, CityJson.GSON.toJson(review));

        JsonObject response = new JsonObject();
        response.addProperty("ok", true);
        response.addProperty("siteReviewStatus", "accept_selected_site".equals(decision)
                ? "accepted" : "reselection_required");
        response.add("siteReviewDecision", review);
        JsonObject artifacts = new JsonObject();
        artifacts.addProperty("citySiteReviewDecision", debugRef(debugRoot, decisionPath));
        response.add("artifacts", artifacts);
        JsonArray nextActions = new JsonArray();
        nextActions.add("accept_selected_site".equals(decision)
                ? "patch_explorer_open" : "realm_t4_patch_planning_create");
        response.add("nextActions", nextActions);
        JsonObject request = standaloneRequest("city_review_d3_site", runId, citySeedId);
        request.addProperty("decision", decision);
        request.addProperty("decisionReason", decisionReason);
        request.addProperty("reviewedBy", reviewedBy == null || reviewedBy.isBlank() ? "ai" : reviewedBy);
        return recordStandaloneTestRunState(debugRoot, runId, citySeedId, request,
                "accept_selected_site".equals(decision) ? "waiting_for_patch_review" : "reselection_required",
                "accept_selected_site".equals(decision)
                        ? "patch_explorer_open" : "realm_t4_patch_planning_create", response);
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
                                    JsonObject templateCatalogSource,
                                    JsonObject structureAnchorPlan) throws IOException {
        Path runDir = debugRoot.resolve(runId);
        loadCitySeedForD4(runDir, runId, citySeedId);
        Path d3Dir = cityStageDir(runDir, citySeedId, CityTestRunLayout.D3);
        Path d3PackagePath = d3Dir.resolve("city_landform_review_package.json");
        CityLandformReviewPackage reviewPackage = loadD3Package(debugRoot, runDir, citySeedId);
        if (templateCatalogSource == null) {
            throw new IllegalArgumentException("templateCatalogSource object is required.");
        }
        JsonObject templateCatalogJson = loadTemplateCatalogJson(debugRoot, runDir, templateCatalogSource);
        JsonObject resolvedAnchorPlan = resolveTemplateAnchorPlan(structureAnchorPlan, templateCatalogJson);
        CityStructureAnchorPlanner.Result result = new CityStructureAnchorPlanner()
                .plan(runDir, reviewPackage, terraSenseProfileSource, resolvedAnchorPlan);

        Path outputDirectory = cityStageDir(runDir, citySeedId, CityTestRunLayout.D4);
        Files.createDirectories(outputDirectory);
        Path anchorPlanPath = outputDirectory.resolve("structure_anchor_plan.json");
        Path anchorMapPath = outputDirectory.resolve("structure_anchor_map.json");
        Path semanticSourcePath = outputDirectory.resolve("semantic_profile_source.json");
        Path qualityPath = outputDirectory.resolve("quality_report.json");
        Files.writeString(anchorPlanPath, CityJson.GSON.toJson(result.structureAnchorPlan()));
        Files.writeString(anchorMapPath, CityJson.GSON.toJson(result.structureAnchorMap()));
        Files.writeString(semanticSourcePath, CityJson.GSON.toJson(
                result.structureAnchorMap().getAsJsonObject("semanticProfileSource")));
        Files.writeString(qualityPath, CityJson.GSON.toJson(result.qualityReport()));

        Path previewPath = new CityStructureLandingPreviewRenderer()
                .renderD4(result.structureAnchorMap(), reviewPackage, outputDirectory);

        JsonObject response = result.asJson();
        JsonObject artifacts = new JsonObject();
        artifacts.addProperty("structureAnchorPlan", debugRef(debugRoot, anchorPlanPath));
        artifacts.addProperty("structureAnchorMap", debugRef(debugRoot, anchorMapPath));
        artifacts.addProperty("semanticProfileSource", debugRef(debugRoot, semanticSourcePath));
        artifacts.addProperty("structureAnchorPreview", debugRef(debugRoot, previewPath));
        artifacts.addProperty("qualityReport", debugRef(debugRoot, qualityPath));
        artifacts.addProperty("sourceD3Package", debugRef(debugRoot, d3PackagePath));
        artifacts.add("sourceTemplateCatalog", templateCatalogSource.deepCopy());
        response.add("artifacts", artifacts);
        return response;
    }

    static JsonObject handlePlanD4Candidates(Path debugRoot, String runId, String citySeedId,
                                             JsonObject terraSenseProfileSource,
                                             JsonObject designSlotPlan,
                                             JsonObject templateCatalogSource) throws IOException {
        Path runDir = debugRoot.resolve(runId);
        loadCitySeedForD4(runDir, runId, citySeedId);
        Path d3Dir = cityStageDir(runDir, citySeedId, CityTestRunLayout.D3);
        Path d3PackagePath = d3Dir.resolve("city_landform_review_package.json");
        CityLandformReviewPackage reviewPackage = loadD3Package(debugRoot, runDir, citySeedId);
        JsonObject fixedTemplatePlan = attachTemplateCatalog(debugRoot, runDir, designSlotPlan,
                templateCatalogSource);
        CityStructureAnchorCandidatePlanner.Result result = new CityStructureAnchorCandidatePlanner()
                .plan(runDir, reviewPackage, terraSenseProfileSource, fixedTemplatePlan);

        Path outputDirectory = cityStageDir(runDir, citySeedId, CityTestRunLayout.D4_CANDIDATES);
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
        artifacts.add("sourceTemplateCatalog", templateCatalogSource.deepCopy());
        response.add("artifacts", artifacts);
        return response;
    }

    static JsonObject handlePlanD4ArrayCandidates(Path debugRoot, String runId, String citySeedId,
                                                  JsonObject terraSenseProfileSource,
                                                  JsonObject arrayCandidatePlan,
                                                  JsonObject templateCatalogSource,
                                                  JsonObject occupiedStructureAnchorMapSource,
                                                  JsonArray occupiedEnvelopes) throws IOException {
        Path runDir = debugRoot.resolve(runId);
        loadCitySeedForD4(runDir, runId, citySeedId);
        Path d3Dir = cityStageDir(runDir, citySeedId, CityTestRunLayout.D3);
        Path d3PackagePath = d3Dir.resolve("city_landform_review_package.json");
        CityLandformReviewPackage reviewPackage = loadD3Package(debugRoot, runDir, citySeedId);
        JsonObject fixedTemplatePlan = attachTemplateCatalog(debugRoot, runDir, arrayCandidatePlan,
                templateCatalogSource);
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
                .plan(runDir, reviewPackage, terraSenseProfileSource, fixedTemplatePlan,
                        occupiedAnchorMap,
                        occupiedEnvelopes == null ? new JsonArray() : occupiedEnvelopes);

        Path outputDirectory = cityStageDir(runDir, citySeedId, CityTestRunLayout.D4_ARRAY_CANDIDATES);
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
        artifacts.add("sourceTemplateCatalog", templateCatalogSource.deepCopy());
        if (occupiedAnchorMapPath != null && Files.exists(occupiedAnchorMapPath)) {
            artifacts.addProperty("sourceOccupiedStructureAnchorMap", debugRef(debugRoot, occupiedAnchorMapPath));
        }
        response.add("artifacts", artifacts);
        return response;
    }

    static JsonObject handleCreateD4ArrayLayoutLoop(Path debugRoot, String runId, String citySeedId,
                                                    JsonObject terraSenseProfileSource,
                                                    JsonObject arrayLayoutPlan,
                                                    JsonObject templateCatalogSource,
                                                    JsonObject baseStructureAnchorPlanSource,
                                                    JsonObject occupiedStructureAnchorMapSource) throws IOException {
        Path runDir = debugRoot.resolve(runId);
        loadCitySeedForD4(runDir, runId, citySeedId);
        CityLandformReviewPackage reviewPackage = loadD3Package(debugRoot, runDir, citySeedId);
        JsonObject fixedTemplatePlan = attachTemplateCatalog(debugRoot, runDir, arrayLayoutPlan,
                templateCatalogSource);
        JsonObject basePlan = loadOptionalStructureAnchorPlan(debugRoot, runDir, citySeedId,
                baseStructureAnchorPlanSource);
        JsonObject occupiedAnchorMap = loadOptionalOccupiedAnchorMap(debugRoot, runDir, citySeedId,
                occupiedStructureAnchorMapSource);
        CityStructureArrayLayoutLoopPlanner.CreateResult result = new CityStructureArrayLayoutLoopPlanner()
                .create(runDir, reviewPackage, terraSenseProfileSource, fixedTemplatePlan,
                        basePlan, occupiedAnchorMap);
        JsonObject response = result.asJson();
        response.add("artifacts", writeD4ArrayLayoutLoopArtifacts(debugRoot, runDir, citySeedId,
                result.loopState(), reviewPackage, templateCatalogSource));
        return response;
    }

    static JsonObject handleCreateD4DesignLoopState(Path debugRoot, String runId, String citySeedId,
                                                    JsonObject designLoopOptions,
                                                    JsonObject baseStructureAnchorMapSource) throws IOException {
        Path runDir = debugRoot.resolve(runId);
        loadCitySeedForD4(runDir, runId, citySeedId);
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
        loadCitySeedForD4(runDir, runId, citySeedId);
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
        loadCitySeedForD4(runDir, runId, citySeedId);
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
        loadCitySeedForD4(runDir, runId, citySeedId);
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
                                                     JsonObject templateCatalogSource) throws IOException {
        Path runDir = debugRoot.resolve(runId);
        loadCitySeedForD4(runDir, runId, citySeedId);
        CityLandformReviewPackage reviewPackage = loadD3Package(debugRoot, runDir, citySeedId);
        JsonObject currentState = loadArrayLayoutLoopState(debugRoot, runDir, citySeedId, arrayLayoutLoopStateSource);
        String currentStateId = stringValue(currentState, "stateId");
        if (stateId != null && !stateId.isBlank() && !stateId.equals(currentStateId)) {
            throw new IllegalArgumentException("D4_ARRAY_LAYOUT_LOOP_STATE_STALE: requested " + stateId
                    + " but current state is " + currentStateId + ".");
        }
        requireTemplateCatalogMatchesState(debugRoot, runDir, currentState, templateCatalogSource);
        CityStructureArrayLayoutLoopPlanner.ExecuteResult result = new CityStructureArrayLayoutLoopPlanner()
                .execute(runDir, reviewPackage, terraSenseProfileSource, currentState,
                        nextArrayLayoutPlanItem);
        JsonObject response = result.asJson();
        response.add("artifacts", writeD4ArrayLayoutLoopArtifacts(debugRoot, runDir, citySeedId,
                result.loopState(), reviewPackage, templateCatalogSource));
        return response;
    }

    static JsonObject handleQueryD4ArrayExpansionSpace(Path debugRoot, String runId, String citySeedId,
                                                       String stateId,
                                                       JsonObject expansionRequest,
                                                       JsonObject arrayLayoutLoopStateSource) throws IOException {
        Path runDir = debugRoot.resolve(runId);
        loadCitySeedForD4(runDir, runId, citySeedId);
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
                                                            JsonObject templateCatalogSource) throws IOException {
        Path runDir = debugRoot.resolve(runId);
        loadCitySeedForD4(runDir, runId, citySeedId);
        CityLandformReviewPackage reviewPackage = loadD3Package(debugRoot, runDir, citySeedId);
        JsonObject currentState = loadArrayLayoutLoopState(debugRoot, runDir, citySeedId, arrayLayoutLoopStateSource);
        requireCurrentArrayLayoutState(stateId, currentState);
        requireTemplateCatalogMatchesState(debugRoot, runDir, currentState, templateCatalogSource);
        CityStructureArrayLayoutLoopPlanner.ExpansionCandidateSetResult result =
                new CityStructureArrayLayoutLoopPlanner().planExpansionCandidates(runDir, reviewPackage,
                        terraSenseProfileSource, currentState, expansionRequest);
        JsonObject response = result.asJson();
        response.add("artifacts", writeD4ArrayExpansionCandidateArtifacts(debugRoot, runDir, citySeedId,
                result.candidateSet(), reviewPackage, templateCatalogSource));
        return response;
    }

    static JsonObject handleSelectD4ArrayExpansionCandidate(Path debugRoot, String runId, String citySeedId,
                                                             String stateId,
                                                             String candidateId,
                                                             boolean autoSelectHighestScore,
                                                             String selectionReason,
                                                             JsonObject arrayExpansionCandidateSetSource,
                                                             JsonObject arrayLayoutLoopStateSource,
                                                             JsonObject templateCatalogSource) throws IOException {
        Path runDir = debugRoot.resolve(runId);
        loadCitySeedForD4(runDir, runId, citySeedId);
        CityLandformReviewPackage reviewPackage = loadD3Package(debugRoot, runDir, citySeedId);
        JsonObject currentState = loadArrayLayoutLoopState(debugRoot, runDir, citySeedId, arrayLayoutLoopStateSource);
        requireCurrentArrayLayoutState(stateId, currentState);
        requireTemplateCatalogMatchesState(debugRoot, runDir, currentState, templateCatalogSource);
        JsonObject candidateSet = loadD4ArrayExpansionCandidateSet(debugRoot, runDir, citySeedId,
                arrayExpansionCandidateSetSource);
        CityStructureArrayLayoutLoopPlanner.ExpansionSelectionResult result =
                new CityStructureArrayLayoutLoopPlanner().selectExpansionCandidate(reviewPackage, currentState,
                        candidateSet, candidateId, autoSelectHighestScore, selectionReason);
        JsonObject response = result.asJson();
        JsonObject artifacts = writeD4ArrayLayoutLoopArtifacts(debugRoot, runDir, citySeedId,
                result.loopState(), reviewPackage, templateCatalogSource);
        artifacts.add("arrayExpansionCandidates", d4ArrayExpansionCandidateArtifactRefs(debugRoot, runDir, citySeedId));
        response.add("artifacts", artifacts);
        return response;
    }

    static JsonObject handleFinalizeD4ArrayLayoutLoop(Path debugRoot, String runId, String citySeedId,
                                                      JsonObject terraSenseProfileSource,
                                                      String stateId,
                                                      JsonObject arrayLayoutLoopStateSource,
                                                      JsonObject templateCatalogSource) throws IOException {
        Path runDir = debugRoot.resolve(runId);
        loadCitySeedForD4(runDir, runId, citySeedId);
        JsonObject currentState = loadArrayLayoutLoopState(debugRoot, runDir, citySeedId, arrayLayoutLoopStateSource);
        String currentStateId = stringValue(currentState, "stateId");
        if (stateId != null && !stateId.isBlank() && !stateId.equals(currentStateId)) {
            throw new IllegalArgumentException("D4_ARRAY_LAYOUT_LOOP_STATE_STALE: requested " + stateId
                    + " but current state is " + currentStateId + ".");
        }
        CityStructureArrayLayoutLoopPlanner.FinalizeResult finalized =
                new CityStructureArrayLayoutLoopPlanner().finalizeLoop(currentState);
        JsonObject response = handlePlanD4(debugRoot, runId, citySeedId, terraSenseProfileSource,
                templateCatalogSource, finalized.structureAnchorPlan());
        CityLandformReviewPackage reviewPackage = loadD3Package(debugRoot, runDir, citySeedId);
        JsonObject loopArtifacts = writeD4ArrayLayoutLoopArtifacts(debugRoot, runDir, citySeedId,
                currentState, reviewPackage, null);
        JsonObject artifacts = response.has("artifacts") && response.get("artifacts").isJsonObject()
                ? response.getAsJsonObject("artifacts") : new JsonObject();
        for (Map.Entry<String, JsonElement> entry : loopArtifacts.entrySet()) {
            artifacts.add(entry.getKey(), entry.getValue().deepCopy());
        }
        response.add("artifacts", artifacts);
        response.addProperty("planningMode", stringValue(currentState, "planningMode",
                CityStructureArrayLayoutLoopPlanner.PLANNING_MODE));
        response.add("arrayLayoutLoopState", currentState.deepCopy());
        response.add("arrayLayoutFinalizedPlan", finalized.structureAnchorPlan().deepCopy());
        return response;
    }

    static JsonObject handlePlanD4StructureClusterGroups(Path debugRoot, String runId, String citySeedId,
                                                         JsonObject terraSenseProfileSource,
                                                         JsonObject designSlotPlan,
                                                         JsonObject templateCatalogSource,
                                                         Integer requestedGroupCount,
                                                         Integer requestedCandidatesPerSlot,
                                                         Integer requestedBeamWidth) throws IOException {
        Path runDir = debugRoot.resolve(runId);
        loadCitySeedForD4(runDir, runId, citySeedId);
        Path d3Dir = cityStageDir(runDir, citySeedId, CityTestRunLayout.D3);
        Path d3PackagePath = d3Dir.resolve("city_landform_review_package.json");
        CityLandformReviewPackage reviewPackage = loadD3Package(debugRoot, runDir, citySeedId);
        JsonObject fixedTemplatePlan = attachTemplateCatalog(debugRoot, runDir, designSlotPlan,
                templateCatalogSource);
        CityStructureClusterGroupCandidatePlanner.Options options =
                new CityStructureClusterGroupCandidatePlanner.Options(
                        requestedGroupCount == null ? 0 : requestedGroupCount,
                        requestedCandidatesPerSlot == null ? 0 : requestedCandidatesPerSlot,
                        requestedBeamWidth == null ? 0 : requestedBeamWidth);
        CityStructureClusterGroupCandidatePlanner.Result result =
                new CityStructureClusterGroupCandidatePlanner()
                        .plan(runDir, reviewPackage, terraSenseProfileSource, fixedTemplatePlan, options);

        Path outputDirectory = cityStageDir(runDir, citySeedId, CityTestRunLayout.D4_STRUCTURE_CLUSTER_GROUPS);
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
        artifacts.add("sourceTemplateCatalog", templateCatalogSource.deepCopy());
        response.add("artifacts", artifacts);
        return response;
    }

    static JsonObject handleSelectD4StructureClusterGroup(Path debugRoot, String runId, String citySeedId,
                                                          JsonObject terraSenseProfileSource,
                                                          String groupCandidateId,
                                                          JsonObject templateCatalogSource,
                                                          JsonObject structureClusterGroupCandidateSetSource)
            throws IOException {
        Path runDir = debugRoot.resolve(runId);
        loadCitySeedForD4(runDir, runId, citySeedId);
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
                templateCatalogSource, structureAnchorPlan);
        response.add("selectedStructureClusterGroup", selectedGroup.deepCopy());
        response.add("structureClusterGroupCandidateSet", candidateSet.deepCopy());
        JsonObject artifacts = response.getAsJsonObject("artifacts");
        artifacts.addProperty("sourceStructureClusterGroupCandidateSet", debugRef(debugRoot, candidateSetPath));
        return response;
    }

    static JsonObject handleSelectD4Candidates(Path debugRoot, String runId, String citySeedId,
                                               JsonObject terraSenseProfileSource,
                                               JsonObject anchorSelectionPlan,
                                               JsonObject templateCatalogSource,
                                               JsonObject anchorCandidateSetSource) throws IOException {
        Path runDir = debugRoot.resolve(runId);
        loadCitySeedForD4(runDir, runId, citySeedId);
        Path candidateSetPath = anchorCandidateSetPath(runDir, citySeedId, anchorCandidateSetSource);
        if (!Files.exists(candidateSetPath)) {
            throw new IllegalArgumentException("D4 anchor_candidate_set.json not found. Run city_plan_d4_candidates first: "
                    + debugRef(debugRoot, candidateSetPath));
        }
        JsonObject candidateSet = JsonParser.parseString(Files.readString(candidateSetPath)).getAsJsonObject();
        JsonObject structureAnchorPlan = new CityStructureAnchorCandidatePlanner()
                .select(candidateSet, anchorSelectionPlan);

        JsonObject response = handlePlanD4(debugRoot, runId, citySeedId, terraSenseProfileSource,
                templateCatalogSource, structureAnchorPlan);
        response.add("anchorSelectionPlan", anchorSelectionPlan.deepCopy());
        response.add("anchorCandidateSet", candidateSet.deepCopy());
        JsonObject artifacts = response.getAsJsonObject("artifacts");
        artifacts.addProperty("sourceAnchorCandidateSet", debugRef(debugRoot, candidateSetPath));
        return response;
    }

    static JsonObject handleCreateD4CandidateSession(Path debugRoot, String runId, String citySeedId,
                                                     JsonObject terraSenseProfileSource,
                                                     JsonObject designSlotPlan,
                                                     JsonObject templateCatalogSource,
                                                     String sessionId) throws IOException {
        Path runDir = debugRoot.resolve(runId);
        loadCitySeedForD4(runDir, runId, citySeedId);
        CityLandformReviewPackage reviewPackage = loadD3Package(debugRoot, runDir, citySeedId);
        JsonObject fixedTemplatePlan = attachTemplateCatalog(debugRoot, runDir, designSlotPlan,
                templateCatalogSource);
        CityStructureAnchorCandidatePlanner.SessionResult result = new CityStructureAnchorCandidatePlanner()
                .createSession(runDir, reviewPackage, terraSenseProfileSource, fixedTemplatePlan, sessionId);

        Path outputDirectory = d4SessionDir(runDir, citySeedId);
        Files.createDirectories(outputDirectory);
        Path sessionPath = outputDirectory.resolve("d4_candidate_session.json");
        Path slotPlanPath = outputDirectory.resolve("design_slot_plan.json");
        Path designTimePath = outputDirectory.resolve("d4_design_time_report.json");
        Path qualityPath = outputDirectory.resolve("quality_report.json");
        Path tracePath = outputDirectory.resolve("d4_candidate_session_trace.json");
        Files.writeString(sessionPath, CityJson.GSON.toJson(result.session()));
        Files.writeString(slotPlanPath, CityJson.GSON.toJson(fixedTemplatePlan));
        Files.writeString(designTimePath, CityJson.GSON.toJson(result.designTimeReport()));
        Files.writeString(qualityPath, CityJson.GSON.toJson(result.qualityReport()));
        Files.writeString(tracePath, CityJson.GSON.toJson(result.session()));

        JsonObject response = result.asJson();
        response.add("artifacts", d4SessionArtifacts(debugRoot, outputDirectory, sessionPath,
                slotPlanPath, null, null, designTimePath, qualityPath, tracePath));
        response.getAsJsonObject("artifacts").add("sourceTemplateCatalog", templateCatalogSource.deepCopy());
        return response;
    }

    static JsonObject handlePlanD4NextCandidates(Path debugRoot, String runId, String citySeedId,
                                                 JsonObject templateCatalogSource) throws IOException {
        Path runDir = debugRoot.resolve(runId);
        loadCitySeedForD4(runDir, runId, citySeedId);
        CityLandformReviewPackage reviewPackage = loadD3Package(debugRoot, runDir, citySeedId);
        Path outputDirectory = d4SessionDir(runDir, citySeedId);
        Path sessionPath = outputDirectory.resolve("d4_candidate_session.json");
        if (!Files.exists(sessionPath)) {
            throw new IllegalArgumentException("D4_CANDIDATE_SESSION_NOT_FOUND: run city_create_d4_candidate_session first: "
                    + debugRef(debugRoot, sessionPath));
        }
        JsonObject session = JsonParser.parseString(Files.readString(sessionPath)).getAsJsonObject();
        requireTemplateCatalogMatchesState(debugRoot, runDir, session, templateCatalogSource);
        CityStructureAnchorCandidatePlanner.NextCandidateResult result = new CityStructureAnchorCandidatePlanner()
                .planNext(runDir, reviewPackage, session);

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
        response.getAsJsonObject("artifacts").add("sourceTemplateCatalog", templateCatalogSource.deepCopy());
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
        loadCitySeedForD4(runDir, runId, citySeedId);
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
                                                       JsonObject templateCatalogSource,
                                                       String sessionId) throws IOException {
        Path runDir = debugRoot.resolve(runId);
        loadCitySeedForD4(runDir, runId, citySeedId);
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
                templateCatalogSource, result.structureAnchorPlan());
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

    static JsonObject handlePlanD5(Path debugRoot, String runId, String citySeedId) throws IOException {
        return handlePlanD5(debugRoot, runId, citySeedId,
                CityWallReservationPlanner.DEFAULT_WALL_MARGIN_BLOCKS,
                CityWallReservationPlanner.DEFAULT_WALL_CORRIDOR_HALF_WIDTH_BLOCKS);
    }

    static JsonObject handlePlanD5(Path debugRoot, String runId, String citySeedId,
                                   int wallMarginBlocks,
                                   int wallCorridorHalfWidthBlocks) throws IOException {
        Path runDir = debugRoot.resolve(runId);
        JsonObject seed = loadCitySeed(runDir, runId, citySeedId);
        RunMetadata metadata = loadRunMetadata(runDir, null, "");
        CitySiteContext ctx = buildSiteContext(
                new CitySiteContextBuilder(CityPlanningConfig.defaults()),
                seed,
                metadata,
                loadTerritoryCells(runDir, stringValue(seed, "realmId")));

        Path d3Dir = cityStageDir(runDir, citySeedId, CityTestRunLayout.D3);
        Path d4Dir = cityStageDir(runDir, citySeedId, CityTestRunLayout.D4);
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
        JsonObject reviewPackageJson = JsonParser.parseString(Files.readString(d3PackagePath)).getAsJsonObject();
        CityLandformReviewPackage reviewPackage = CityLandformReviewPackage.fromJson(reviewPackageJson);
        BlockBounds patchContextBounds = reviewPackageJson.has("patchContextBounds")
                && reviewPackageJson.get("patchContextBounds").isJsonObject()
                ? bounds(reviewPackageJson.getAsJsonObject("patchContextBounds"))
                : null;
        JsonObject wallReservationPlan = new CityWallReservationPlanner().plan(
                reviewPackage, anchorMap, wallMarginBlocks, wallCorridorHalfWidthBlocks, patchContextBounds);
        CityReservationMaskPlanner.Result result = new CityReservationMaskPlanner().plan(ctx, anchorMap,
                wallReservationPlan);

        Path outputDirectory = cityStageDir(runDir, citySeedId, CityTestRunLayout.D5);
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
        Path outputDirectory = cityStageDir(runDir, citySeedId, CityTestRunLayout.LAND_USE);
        Path terrainPath = outputDirectory.resolve("land_use_terrain_field.json");
        Path d4AnchorMapPath = cityStageDir(runDir, citySeedId, CityTestRunLayout.D4)
                .resolve("structure_anchor_map.json");
        Path d5MaskPath = cityStageDir(runDir, citySeedId, CityTestRunLayout.D5)
                .resolve("reservation_mask_plan.json");
        Path d6Directory = cityStageDir(runDir, citySeedId, CityTestRunLayout.D6);
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
        if (!"city_structure_anchor_map".equals(stringValue(d4AnchorMap, "schema", ""))) {
            throw new IllegalArgumentException("LAND_USE_D4_PROVENANCE_REQUIRED");
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

        LandUseConfiguration landUseConfiguration = loadLandUseConfiguration();
        LandUseSettings settings = landUseConfiguration.settings();
        LandUseRuleCatalog rules = landUseConfiguration.rules();
        JsonObject functionalArrayZones = loadOptionalLandUseFunctionalArrayZones(runDir, citySeedId);
        Path completePath = outputDirectory.resolve("city_land_use_planning_complete.json");
        Path surfacePrintPath = outputDirectory.resolve("city_land_use_surface_print_plan.json");
        Files.deleteIfExists(completePath);
        Files.deleteIfExists(surfacePrintPath);
        LandUsePlanningService.Result result = new LandUsePlanningService().plan(
                d6Plan, optionalIntent, functionalArrayZones, d5MaskPlan, terrainField, rules);
        LandUseAreaPlanCodec planCodec = new LandUseAreaPlanCodec();
        JsonObject planJson = planCodec.toJson(result.plan());
        JsonObject surfacePrintJson = new CityLandUseSurfacePrintPlanCodec()
                .toJson(result.surfacePrintPlan());
        Path planPath = outputDirectory.resolve("city_land_use_area_plan.json");
        Path tracePath = outputDirectory.resolve("land_use_plan_trace.json");
        Path qualityPath = outputDirectory.resolve("quality_report.json");
        Files.createDirectories(outputDirectory);
        Files.writeString(planPath, CityJson.GSON.toJson(planJson));
        // SurfacePrintPlan uses explicit JSON nulls as strict union fields.
        Files.writeString(surfacePrintPath, surfacePrintJson.toString());
        Files.writeString(tracePath, CityJson.GSON.toJson(result.trace()));
        Files.writeString(qualityPath, CityJson.GSON.toJson(result.quality()));
        JsonObject preview = new CityLandUsePreviewRenderer().render(
                terrainField, result.plan(), result.surfacePrintPlan(), outputDirectory);
        Path previewPath = outputDirectory.resolve(stringValue(preview, "fileName", "land_use_preview.png"));

        JsonObject completion = new JsonObject();
        completion.addProperty("schema", "city_land_use_planning_complete");
        completion.addProperty("cityId", cityId);
        completion.addProperty("planHash", result.plan().planHash());
        completion.addProperty("surfacePrintPlanHash", result.surfacePrintPlan().planHash());
        completion.addProperty("ruleProfileHash", rules.profileHash());
        completion.addProperty("sourceD6Hash", sha256(
                CityJson.GSON.toJson(d6Plan)));
        completion.addProperty("completedAt", Instant.now().toString());
        writePlanningCompletion(completePath, completion);

        JsonObject response = new JsonObject();
        response.addProperty("ok", true);
        response.addProperty("explicitPlanning", true);
        response.addProperty("workflowEnabledByDefault", settings.enabledInWorkflow());
        response.addProperty("profileId", settings.profileId());
        response.addProperty("ruleProfileHash", rules.profileHash());
        response.addProperty("areaCount", result.plan().areas().size());
        response.addProperty("planHash", result.plan().planHash());
        response.addProperty("surfacePrintPlanHash", result.surfacePrintPlan().planHash());
        response.add("landUseAreaPlan", planJson);
        response.add("landUseSurfacePrintPlan", surfacePrintJson);
        response.add("qualityReport", result.quality());
        response.add("landUsePreview", preview);
        response.add("timingMs", timing(started));
        JsonObject artifacts = new JsonObject();
        artifacts.addProperty("landUseTerrainField", debugRef(debugRoot, terrainPath));
        artifacts.addProperty("landUseAreaPlan", debugRef(debugRoot, planPath));
        artifacts.addProperty("landUseSurfacePrintPlan", debugRef(debugRoot, surfacePrintPath));
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

    static JsonObject handlePlanBlueprintOutdoor(Path debugRoot,
                                                  String runId,
                                                  String citySeedId) throws IOException {
        long started = System.nanoTime();
        Path runDir = debugRoot.resolve(runId);
        loadCitySeed(runDir, runId, citySeedId);
        BlueprintOutdoorInputs inputs = loadBlueprintOutdoorInputs(debugRoot, runDir, citySeedId);
        CityOutdoorBlueprintCompiler.Result compiled = new CityOutdoorBlueprintCompiler().compile(
                inputs.blueprint(), inputs.d6Plan(), inputs.terrainField(), inputs.referenceCatalog(),
                inputs.landscapeCapacityReservationPlan());
        Path outputDirectory = inputs.landUseDirectory();
        Files.createDirectories(outputDirectory);
        Path intentPath = outputDirectory.resolve("city_outdoor_intent_plan.json");
        JsonObject intentJson = compiled.intentPlan().toJson();
        // Intent and UrbanSpace contain explicit JSON nulls in strict union fields.
        Files.writeString(intentPath, intentJson.toString());
        if (inputs.blueprint().outdoorPlan().mode() == CityBlueprint.OutdoorMode.PRESERVE) {
            Files.deleteIfExists(outputDirectory.resolve("city_land_use_planning_complete.json"));
            JsonObject response = new JsonObject();
            response.addProperty("ok", true);
            response.addProperty("planningSource", "city_blueprint");
            response.addProperty("outdoorMode", CityBlueprint.OutdoorMode.PRESERVE.name());
            response.addProperty("planned", false);
            response.addProperty("activated", false);
            response.addProperty("outdoorIntentPlanHash", compiled.intentPlan().planHash());
            response.add("cityOutdoorIntentPlan", intentJson);
            response.add("timingMs", timing(started));
            JsonObject artifacts = new JsonObject();
            artifacts.addProperty("cityBlueprint", debugRef(debugRoot, inputs.blueprintPath()));
            artifacts.addProperty("cityBlueprintCatalogSnapshot", debugRef(debugRoot, inputs.snapshotPath()));
            artifacts.addProperty("cityOutdoorIntentPlan", debugRef(debugRoot, intentPath));
            response.add("artifacts", artifacts);
            return response;
        }

        LandUsePlanningService.Result result = new LandUsePlanningService().plan(
                citySeedId, compiled.resolution(), inputs.d5MaskPlan(), inputs.terrainField(),
                compiled.residualConfig());

        Path urbanSpacePath = outputDirectory.resolve("city_urban_space_plan.json");
        Path areaPath = outputDirectory.resolve("city_land_use_area_plan.json");
        Path surfacePath = outputDirectory.resolve("city_land_use_surface_print_plan.json");
        Path tracePath = outputDirectory.resolve("land_use_plan_trace.json");
        Path qualityPath = outputDirectory.resolve("quality_report.json");
        Path completePath = outputDirectory.resolve("city_land_use_planning_complete.json");
        Files.deleteIfExists(completePath);

        JsonObject urbanSpaceJson = result.urbanSpacePlan().toJson();
        JsonObject areaJson = new LandUseAreaPlanCodec().toJson(result.plan());
        JsonObject surfaceJson = new CityLandUseSurfacePrintPlanCodec().toJson(result.surfacePrintPlan());
        Files.writeString(urbanSpacePath, urbanSpaceJson.toString());
        Files.writeString(areaPath, CityJson.GSON.toJson(areaJson));
        // SurfacePrintPlan uses explicit JSON nulls as strict union fields.
        Files.writeString(surfacePath, surfaceJson.toString());
        Files.writeString(tracePath, CityJson.GSON.toJson(result.trace()));
        Files.writeString(qualityPath, CityJson.GSON.toJson(result.quality()));
        JsonObject preview = new CityLandUsePreviewRenderer().render(
                inputs.terrainField(), result.plan(), result.urbanSpacePlan(),
                result.surfacePrintPlan(), outputDirectory);
        Path previewPath = outputDirectory.resolve(stringValue(preview, "fileName", "land_use_preview.png"));

        JsonObject completion = blueprintOutdoorCompletion(inputs, compiled.intentPlan(),
                result.urbanSpacePlan(), result.plan(), result.surfacePrintPlan());
        writePlanningCompletion(completePath, completion);

        JsonObject response = new JsonObject();
        response.addProperty("ok", true);
        response.addProperty("planningSource", "city_blueprint");
        response.addProperty("outdoorMode", CityBlueprint.OutdoorMode.GENERATE.name());
        response.addProperty("areaCount", result.plan().areas().size());
        response.addProperty("planHash", result.plan().planHash());
        response.addProperty("surfacePrintPlanHash", result.surfacePrintPlan().planHash());
        response.addProperty("outdoorIntentPlanHash", compiled.intentPlan().planHash());
        response.addProperty("urbanSpacePlanHash", result.urbanSpacePlan().planHash());
        response.add("cityOutdoorIntentPlan", intentJson);
        response.add("cityUrbanSpacePlan", urbanSpaceJson);
        response.add("landUseAreaPlan", areaJson);
        response.add("landUseSurfacePrintPlan", surfaceJson);
        response.add("qualityReport", result.quality());
        response.add("landUsePreview", preview);
        response.add("timingMs", timing(started));
        JsonObject artifacts = new JsonObject();
        artifacts.addProperty("cityBlueprint", debugRef(debugRoot, inputs.blueprintPath()));
        artifacts.addProperty("cityBlueprintCatalogSnapshot", debugRef(debugRoot, inputs.snapshotPath()));
        artifacts.addProperty("landUseTerrainField", debugRef(debugRoot, inputs.terrainPath()));
        artifacts.addProperty("cityOutdoorIntentPlan", debugRef(debugRoot, intentPath));
        artifacts.addProperty("cityUrbanSpacePlan", debugRef(debugRoot, urbanSpacePath));
        artifacts.addProperty("landUseAreaPlan", debugRef(debugRoot, areaPath));
        artifacts.addProperty("landUseSurfacePrintPlan", debugRef(debugRoot, surfacePath));
        artifacts.addProperty("landUsePlanTrace", debugRef(debugRoot, tracePath));
        artifacts.addProperty("qualityReport", debugRef(debugRoot, qualityPath));
        artifacts.addProperty("landUsePreview", debugRef(debugRoot, previewPath));
        artifacts.addProperty("planningComplete", debugRef(debugRoot, completePath));
        artifacts.addProperty("sourceD5ReservationMaskPlan", debugRef(debugRoot, inputs.d5MaskPath()));
        artifacts.addProperty("sourceD6MaterializationPlan", debugRef(debugRoot, inputs.d6Path()));
        response.add("artifacts", artifacts);
        return response;
    }

    static JsonObject handleExecuteD5(Path debugRoot, Path serverRoot, String runId, String citySeedId,
                                      boolean confirmWorldMutation, ServerLevel level) throws IOException {
        return handleExecuteD5(debugRoot, serverRoot, runId, citySeedId, confirmWorldMutation, level,
                null);
    }

    static JsonObject handleExecuteD5(Path debugRoot, Path serverRoot, String runId, String citySeedId,
                                      boolean confirmWorldMutation, ServerLevel level,
                                      Path ignoredRemovedStageRoot,
                                      Boolean requestedLandUseLayer) throws IOException {
        return handleExecuteD5(debugRoot, serverRoot, runId, citySeedId, confirmWorldMutation, level,
                requestedLandUseLayer);
    }

    static JsonObject handleExecuteD5(Path debugRoot, Path serverRoot, String runId, String citySeedId,
                                      boolean confirmWorldMutation, ServerLevel level,
                                      Boolean requestedLandUseLayer) throws IOException {
        long started = System.nanoTime();
        if (!confirmWorldMutation) {
            throw new IllegalArgumentException("confirmWorldMutation=true is required for city_execute_d5.");
        }
        Path runDir = debugRoot.resolve(runId);
        requireMatchingRunWorldIdentity(runDir, level);
        loadCitySeed(runDir, runId, citySeedId);
        Path d4Dir = cityStageDir(runDir, citySeedId, CityTestRunLayout.D4);
        Path d5Dir = cityStageDir(runDir, citySeedId, CityTestRunLayout.D5);
        Path maskPath = d5Dir.resolve("reservation_mask_plan.json");
        Path operationPath = d5Dir.resolve("build_operation_plan.json");
        Path d6PlanPath = cityStageDir(runDir, citySeedId, CityTestRunLayout.D6)
                .resolve("structure_materialization_plan.json");
        Path landUseDirectory = cityStageDir(runDir, citySeedId, CityTestRunLayout.LAND_USE);
        Path landUsePlanPath = landUseDirectory.resolve("city_land_use_area_plan.json");
        Path landUseSurfacePrintPlanPath = landUseDirectory.resolve("city_land_use_surface_print_plan.json");
        Path outdoorIntentPlanPath = landUseDirectory.resolve("city_outdoor_intent_plan.json");
        Path urbanSpacePlanPath = landUseDirectory.resolve("city_urban_space_plan.json");
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
        JsonObject anchorMap = JsonParser.parseString(Files.readString(anchorMapPath)).getAsJsonObject();
        boolean blueprintOutdoorAuthority = isBlueprintAnchorMap(anchorMap);
        if (blueprintOutdoorAuthority && requestedLandUseLayer != null) {
            throw new IllegalArgumentException("CITY_BLUEPRINT_WORKFLOW_LAND_USE_OVERRIDE_FORBIDDEN: "
                    + "enableLandUseLayer is controlled by cityBlueprint.outdoorPlan.mode.");
        }
        BlueprintOutdoorInputs blueprintInputs = blueprintOutdoorAuthority
                ? loadBlueprintOutdoorInputs(debugRoot, runDir, citySeedId) : null;
        JsonObject maskPlan = JsonParser.parseString(Files.readString(maskPath)).getAsJsonObject();
        JsonObject materializationPlan = JsonParser.parseString(Files.readString(d6PlanPath)).getAsJsonObject();
        validateLockedMaterializationPlan(materializationPlan);
        JsonObject activeMaskPlan = maskPlanWithLockedEnvelopes(maskPlan, materializationPlan);
        boolean landUsePlanExists = Files.isRegularFile(landUsePlanPath);
        boolean landUseSurfacePrintPlanExists = Files.isRegularFile(landUseSurfacePrintPlanPath);
        boolean landUseCompleteExists = Files.isRegularFile(landUseCompletePath);
        boolean landUseWorldgenMode = blueprintInputs != null
                ? blueprintInputs.blueprint().outdoorPlan().mode() == CityBlueprint.OutdoorMode.GENERATE
                : requestedLandUseLayer == null
                ? landUsePlanExists && landUseCompleteExists : requestedLandUseLayer;
        boolean validateLandUseArtifacts = blueprintInputs != null
                ? landUseWorldgenMode : !Boolean.FALSE.equals(requestedLandUseLayer);
        if (validateLandUseArtifacts && landUsePlanExists != landUseCompleteExists) {
            throw new IllegalArgumentException("CITY_LAND_USE_PLAN_INCOMPLETE: area plan and completion marker "
                    + "must both exist; rerun city_plan_land_use.");
        }
        if (validateLandUseArtifacts && landUseSurfacePrintPlanExists
                && (!landUsePlanExists || !landUseCompleteExists)) {
            throw new IllegalArgumentException("CITY_LAND_USE_PLAN_INCOMPLETE: orphan surface print plan "
                    + "requires both area plan and completion marker; rerun city_plan_land_use.");
        }
        if (landUseWorldgenMode && !landUsePlanExists) {
            throw new IllegalArgumentException("CITY_LAND_USE_PLAN_MISSING: run city_plan_land_use first.");
        }
        if (landUseWorldgenMode && !landUseSurfacePrintPlanExists) {
            throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_PLAN_MISSING: "
                    + "run city_plan_land_use first.");
        }
        if (landUseWorldgenMode && blueprintInputs != null
                && (!Files.isRegularFile(outdoorIntentPlanPath) || !Files.isRegularFile(urbanSpacePlanPath))) {
            throw new IllegalArgumentException("CITY_BLUEPRINT_OUTDOOR_PLAN_INCOMPLETE: intent and urban-space "
                    + "artifacts are required; rerun the Blueprint workflow.");
        }
        LandUseAreaPlan landUsePlan = null;
        CityLandUseSurfacePrintPlan landUseSurfacePrintPlan = null;
        if (landUseWorldgenMode) {
            landUsePlan = new LandUseAreaPlanCodec().fromJson(
                    JsonParser.parseString(Files.readString(landUsePlanPath)).getAsJsonObject());
            String expectedCityId = stringValue(materializationPlan, "cityId", citySeedId);
            if (!expectedCityId.equals(landUsePlan.cityId())) {
                throw new IllegalArgumentException("CITY_LAND_USE_CITY_ID_MISMATCH: expected "
                        + expectedCityId + " but found " + landUsePlan.cityId());
            }
            landUseSurfacePrintPlan = new CityLandUseSurfacePrintPlanCodec().fromJson(
                    JsonParser.parseString(Files.readString(landUseSurfacePrintPlanPath)).getAsJsonObject());
            validateLandUseCompletionArtifacts(debugRoot, runDir, citySeedId, materializationPlan,
                    landUsePlan, landUseSurfacePrintPlan, blueprintInputs);
        }
        RunMetadata metadata = loadRunMetadata(runDir, null, "");
        CityLandUseChunkStatusPreflight.PreflightResult landUseChunkPreflight = null;
        if (landUsePlan != null) {
            CityLandUseWorldgenRegistry.preflightActivate(metadata.dimensionId(), landUsePlan,
                    landUseSurfacePrintPlan, serverRoot);
            landUseChunkPreflight = CityLandUseWorldgenRegistry.preflightChunkStatus(landUsePlan,
                    landUseSurfacePrintPlan,
                    new CityLandUseChunkStatusPreflight.MinecraftChunkStatusProbe(level));
            if (!landUseChunkPreflight.eligible()) {
                throw new IllegalArgumentException(landUseChunkPreflight.reasonCode()
                        + ": LandUse only applies during first worldgen FEATURES; ownerChunks="
                        + landUseChunkPreflight.ownerChunkCount() + ", featuresOrLater="
                        + landUseChunkPreflight.featuresOrLaterCount() + ", unknown="
                        + landUseChunkPreflight.unknownCount());
            }
        }
        BuildOperationPlan plan = BuildOperationPlan.fromJson(
                JsonParser.parseString(Files.readString(operationPath)).getAsJsonObject());
        WorldMutationReport report = skippedWorldMutationReport(plan,
                "D5 activates worldgen-time masks and City-owned structure, road, and land-use plans.");
        JsonObject activeRegistry = CityReservationMaskRegistry.activate(activeMaskPlan, null, materializationPlan,
                runId, citySeedId, serverRoot);
        JsonObject activationProvenance = new JsonObject();
        activationProvenance.addProperty("schema", "city_d5_activation_provenance");
        activationProvenance.addProperty("sourceD5Hash", sha256(Files.readString(maskPath)));
        activationProvenance.addProperty("sourceD6Hash", sha256(Files.readString(d6PlanPath)));
        activationProvenance.addProperty("sourceLandUseCompletionHash", optionalArtifactHash(landUseCompletePath));
        activeRegistry.add("activationProvenance", activationProvenance);
        String cityId = stringValue(materializationPlan, "cityId", citySeedId);
        JsonObject activeLandUseSummary;
        if (landUsePlan == null) {
            activeLandUseSummary = CityLandUseWorldgenRegistry.deactivate(
                    metadata.dimensionId(), cityId, serverRoot);
        } else {
            activeLandUseSummary = CityLandUseWorldgenRegistry.activate(metadata.dimensionId(), landUsePlan,
                    landUseSurfacePrintPlan, serverRoot);
        }
        Files.createDirectories(d5Dir);
        Path reportPath = d5Dir.resolve("world_mutation_report.json");
        Path activeMaskPath = d5Dir.resolve("active_mask_summary.json");
        Path activePlannedPath = d5Dir.resolve("active_planned_structure_registry.json");
        Path activeLandUsePath = d5Dir.resolve("active_city_land_use_summary.json");
        Files.writeString(reportPath, CityJson.GSON.toJson(report.asJson()));
        Files.writeString(activeMaskPath, CityJson.GSON.toJson(CityReservationMaskRegistry.activeSummary()));
        Files.writeString(activePlannedPath, CityJson.GSON.toJson(activeRegistry));
        Files.writeString(activeLandUsePath, CityJson.GSON.toJson(activeLandUseSummary));

        JsonObject response = new JsonObject();
        response.addProperty("ok", report.failedOperations() == 0);
        response.add("activeMaskSummary", CityReservationMaskRegistry.activeSummary());
        response.addProperty("activePlannedStructureCount",
                activeRegistry.getAsJsonArray("plannedStructures").size());
        response.addProperty("totalActivePlannedStructureCount",
                CityReservationMaskRegistry.activePlannedStructureCount());
        response.addProperty("plannedStructureRegistryPath",
                CityReservationMaskRegistry.plannedRegistryPath(serverRoot).toString());
        response.addProperty("worldgenPlacementMode", true);
        response.addProperty("landUseWorldgenMode", landUsePlan != null);
        response.addProperty("landUseSurfacePrintMode", landUseSurfacePrintPlan != null);
        response.addProperty("landUsePlanningSource", blueprintInputs == null ? "legacy_debug" : "city_blueprint");
        if (landUseSurfacePrintPlan != null) {
            response.addProperty("landUseSurfacePrintPlanHash", landUseSurfacePrintPlan.planHash());
        }
        response.addProperty("landUseGeometryMaskDuplicated", false);
        response.addProperty("requiresLockedMaterializationPlan", true);
        response.addProperty("roadPlanningSource", "city_owned");
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
        artifacts.addProperty("activeLandUseSummary", debugRef(debugRoot, activeLandUsePath));
        if (landUsePlan != null) {
            artifacts.addProperty("sourceLandUseAreaPlan", debugRef(debugRoot, landUsePlanPath));
            artifacts.addProperty("sourceLandUsePlanningComplete", debugRef(debugRoot, landUseCompletePath));
            if (landUseSurfacePrintPlan != null) {
                artifacts.addProperty("sourceLandUseSurfacePrintPlan",
                        debugRef(debugRoot, landUseSurfacePrintPlanPath));
            }
            if (blueprintInputs != null) {
                artifacts.addProperty("sourceCityOutdoorIntentPlan", debugRef(debugRoot, outdoorIntentPlanPath));
                artifacts.addProperty("sourceCityUrbanSpacePlan", debugRef(debugRoot, urbanSpacePlanPath));
            }
            artifacts.addProperty("serverActiveLandUsePlans",
                    CityLandUseWorldgenRegistry.activePlansPath(serverRoot).toString());
            artifacts.addProperty("serverLandUseWorldgenLedger",
                    CityLandUseWorldgenRegistry.worldgenLedgerPath(serverRoot).toString());
        }
        response.add("artifacts", artifacts);
        return response;
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
        response.addProperty("schema", "city_template_metadata_query");
        response.addProperty("dimensionId", level.dimension().location().toString());
        response.add("templates", templates);
        return response;
    }






    static JsonObject handlePlanD6(Path debugRoot, String runId, String citySeedId,
                                   MinecraftServerHolder serverHolder,
                                   ServerLevel level) throws IOException {
        Path runDir = debugRoot.resolve(runId);
        requireMatchingRunWorldIdentity(runDir, level);
        loadCitySeed(runDir, runId, citySeedId);
        Path d4Dir = cityStageDir(runDir, citySeedId, CityTestRunLayout.D4);
        Path d5Dir = cityStageDir(runDir, citySeedId, CityTestRunLayout.D5);
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
        CityStructureMaterializationPlanner.TemplateMetadataInspector templateMetadataInspector = templateRef -> {
            if (level == null) {
                return CityStructureMaterializationPlanner.TemplateMetadata.unreadable(
                        "TEMPLATE_RUNTIME_LEVEL_REQUIRED", "D6 must read templates from the current server world.");
            }
            MinecraftCityTemplateReader.ReadResult read = new MinecraftCityTemplateReader(level.getStructureManager())
                    .read(templateRef);
            if (!read.success()) {
                return CityStructureMaterializationPlanner.TemplateMetadata.unreadable(
                        read.failureCode().name(), read.failureDetail());
            }
            return CityStructureMaterializationPlanner.TemplateMetadata.readable(
                    read.contentHash(), new CityTemplatePlacementGeometry.Size(
                            read.size().getX(), read.size().getY(), read.size().getZ()));
        };
        CityStructureMaterializationPlanner.Result result = new CityStructureMaterializationPlanner()
                .planWorldgen(anchorMap, inspector, null, templateMetadataInspector);
        validateFootprintsWithinWallReservation(wallReservationPath, result.structureMaterializationPlan(),
                "plannedWorldgenStructures", "D6");

        Path outputDirectory = cityStageDir(runDir, citySeedId, CityTestRunLayout.D6);
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
        Files.deleteIfExists(cityStageDir(runDir, citySeedId, CityTestRunLayout.LAND_USE)
                .resolve("city_land_use_planning_complete.json"));

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
                                      boolean executeStructurePlacement,
                                      MinecraftServerHolder serverHolder, ServerLevel level) throws IOException {
        Path runDir = debugRoot.resolve(runId);
        requireMatchingRunWorldIdentity(runDir, level);
        loadCitySeed(runDir, runId, citySeedId);
        Path d6Dir = cityStageDir(runDir, citySeedId, CityTestRunLayout.D6);
        Path d5Dir = cityStageDir(runDir, citySeedId, CityTestRunLayout.D5);
        Path planPath = d6Dir.resolve("structure_materialization_plan.json");
        Path d3PackagePath = d3PackagePath(runDir, citySeedId);
        Path wallReservationPath = d5Dir.resolve("wall_reservation_plan.json");
        Path landUseDir = cityStageDir(runDir, citySeedId, CityTestRunLayout.LAND_USE);
        Path landUseAreaPlanPath = landUseDir.resolve("city_land_use_area_plan.json");
        Path landUseSurfacePlanPath = landUseDir.resolve("city_land_use_surface_print_plan.json");
        rejectLegacyArtifacts(d6Dir, "D6");
        if (!Files.exists(planPath)) {
            throw new IllegalArgumentException("D6 artifacts not found. Run city_plan_d6 first: "
                    + debugRef(debugRoot, d6Dir));
        }
        JsonObject materializationPlan = JsonParser.parseString(Files.readString(planPath)).getAsJsonObject();
        CityLandformReviewPackage reviewPackage = null;
        if (Files.exists(d3PackagePath)) {
            reviewPackage = CityLandformReviewPackage.fromJson(
                    JsonParser.parseString(Files.readString(d3PackagePath)).getAsJsonObject());
        }
        Path outputDirectory = cityStageDir(runDir, citySeedId, CityTestRunLayout.D7);
        Path ledgerPath = outputDirectory.resolve("placed_structure_ledger.json");
        JsonObject runtimeLedger = CityReservationMaskRegistry.ledgerForCity(
                runId,
                citySeedId,
                stringValue(materializationPlan, "cityId"));
        CityStructureMaterializationPlanner.ChunkStatusInspector inspector = CityReservationMaskRegistry
                .hasActivePlannedStructuresFor(runId, citySeedId, stringValue(materializationPlan, "cityId"))
                ? new MinecraftCityWorldgenStatusInspector(level)
                : task -> CityStructureMaterializationPlanner.ChunkStatusResult.registryMissing(
                        "Run city_execute_d5 confirmWorldMutation=true before loading target chunks.");
        CityStructureMaterializationPlanner.Result result = new CityStructureMaterializationPlanner()
                .executeWorldgen(materializationPlan, runtimeLedger, inspector, executeStructurePlacement);

        Files.createDirectories(outputDirectory);
        CityLandUseWorldgenRegistry.BackfillSummary landUseBackfill = null;
        Path landUseBackfillPath = outputDirectory.resolve("land_use_owner_completion.json");
        boolean hasLandUseAreaPlan = Files.isRegularFile(landUseAreaPlanPath);
        boolean hasLandUseSurfacePlan = Files.isRegularFile(landUseSurfacePlanPath);
        if (hasLandUseAreaPlan != hasLandUseSurfacePlan) {
            throw new IllegalArgumentException("CITY_LAND_USE_D7_ARTIFACTS_INCOMPLETE");
        }
        if (executeStructurePlacement && hasLandUseAreaPlan) {
            LandUseAreaPlan landUseAreaPlan = new LandUseAreaPlanCodec().fromJson(
                    JsonParser.parseString(Files.readString(landUseAreaPlanPath)).getAsJsonObject());
            CityLandUseSurfacePrintPlan landUseSurfacePlan = new CityLandUseSurfacePrintPlanCodec().fromJson(
                    JsonParser.parseString(Files.readString(landUseSurfacePlanPath)).getAsJsonObject());
            landUseBackfill = CityLandUseWorldgenRegistry.enqueueD7Backfill(
                    level.dimension().location().toString(), landUseAreaPlan, landUseSurfacePlan, level);
            Files.writeString(landUseBackfillPath, CityJson.GSON.toJson(
                    landUseBackfillJson(landUseBackfill)));
            if (!landUseBackfill.failures().isEmpty()) {
                throw new IllegalArgumentException("CITY_LAND_USE_D7_OWNER_INCOMPLETE: plannedOwners="
                        + landUseBackfill.plannedOwnerCount() + ", appliedOwners="
                        + landUseBackfill.appliedAfterCount() + ", missingOwners="
                        + landUseBackfill.missingOwners().size() + "; report="
                        + debugRef(debugRoot, landUseBackfillPath));
            }
        }
        result.structureMaterializationTrace().add("terrainAdaptationReport",
                terrainAdaptationReport(result.placedStructureLedger()));
        validateFootprintsWithinWallReservation(wallReservationPath, result.placedStructureLedger(),
                "placedStructures", "D7");
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
        if (landUseBackfill != null) {
            response.add("landUseOwnerCompletion", landUseBackfillJson(landUseBackfill));
            artifacts.addProperty("landUseOwnerCompletion", debugRef(debugRoot, landUseBackfillPath));
            if (!landUseBackfill.complete()) {
                response.addProperty("ok", true);
                response.addProperty("status", "waiting_for_worldgen");
                response.addProperty("reasonCode", "CITY_LAND_USE_D7_BACKFILL_IN_PROGRESS");
            }
        }
        response.add("artifacts", artifacts);
        response.addProperty("structurePlacementExecuted", executeStructurePlacement);
        response.addProperty("worldgenPlacementMode", true);
        response.addProperty("worldSeed", worldSeed);
        return response;
    }

    static JsonObject handlePlanCityWalls(Path debugRoot, String runId, String citySeedId,
                                          ServerLevel level,
                                          int roadScanMarginBlocks,
                                          CityWallPlanner.Options wallOptions) throws IOException {
        Path runDir = debugRoot.resolve(runId);
        requireMatchingRunWorldIdentity(runDir, level);
        loadCitySeed(runDir, runId, citySeedId);
        Path d7Dir = cityStageDir(runDir, citySeedId, CityTestRunLayout.D7);
        Path d5Dir = cityStageDir(runDir, citySeedId, CityTestRunLayout.D5);
        Path d3Dir = cityStageDir(runDir, citySeedId, CityTestRunLayout.D3);
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
        Path outputDirectory = cityStageDir(runDir, citySeedId, CityTestRunLayout.WALLS);
        Path roadMaskPath = outputDirectory.resolve("actual_road_mask.json");
        Path wallReservationPath = d5Dir.resolve("wall_reservation_plan.json");
        if (!Files.exists(wallReservationPath)) {
            throw new IllegalArgumentException("wall_reservation_plan.json not found. Run city_plan_d5 first: "
                    + debugRef(debugRoot, wallReservationPath));
        }
        JsonObject wallReservationPlan = JsonParser.parseString(Files.readString(wallReservationPath))
                .getAsJsonObject();
        JsonObject wallReservationForPlan = enrichWallReservationWithD3Cells(wallReservationPlan, d3Package);
        JsonObject actualRoadMask = new CityRoadMaskScanner().scan(level, wallReservationForPlan, roadScanMarginBlocks);
        Files.createDirectories(outputDirectory);
        JsonObject surfaceCacheBackfill = CitySurfaceCache.writeBackfill(level,
                wallSurfaceBackfillBounds(wallReservationForPlan),
                outputDirectory,
                stringValue(wallReservationForPlan, "cityId", citySeedId));
        JsonObject wallPlan = new CityWallPlanner().plan(ledger, wallReservationForPlan, actualRoadMask,
                wallOptions);
        wallPlan.add("surfaceCacheBackfill", surfaceCacheBackfill.deepCopy());
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
        requireMatchingRunWorldIdentity(runDir, level);
        loadCitySeed(runDir, runId, citySeedId);
        Path outputDirectory = cityStageDir(runDir, citySeedId, CityTestRunLayout.WALLS);
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
        if (request.has("d4CandidateMode")) {
            throw new IllegalArgumentException("D4_WORKFLOW_MODE_REMOVED: CityBlueprint is the only D4 workflow.");
        }
        Path runDir = debugRoot.resolve(runId);
        requireMatchingRunWorldIdentity(runDir, level);
        JsonObject citySeedSnapshot = loadCitySeed(runDir, runId, citySeedId);
        CityTestRunLayout testRunLayout = CityTestRunLayout.open(runDir, citySeedId);
        Path outputDirectory = testRunLayout.stepDirectory(CityTestRunLayout.WORKFLOW);
        Files.createDirectories(outputDirectory);
        Path reportPath = testRunLayout.manifestPath();
        JsonObject manifest = testRunLayout.legacy() ? null
                : loadOrCreateTestRunManifest(testRunLayout, runDir, runId, citySeedId,
                citySeedSnapshot, request, debugRoot);

        JsonObject report = new JsonObject();
        report.addProperty("schema", testRunLayout.legacy()
                ? "city_workflow_report" : "city_workflow_attempt");
        if (manifest != null) {
            JsonArray attempts = manifest.getAsJsonArray("attempts");
            report.addProperty("attemptIndex", attempts.size());
            attempts.add(report);
        }
        report.addProperty("workflowMode", "city_structure_landing_fast_loop");
        report.addProperty("runId", runId);
        report.addProperty("citySeedId", citySeedId);
        report.addProperty("startedAt", Instant.now().toString());
        report.addProperty("status", "running");
        report.addProperty("skipExisting", booleanValue(request, "skipExisting", true));
        report.addProperty("planWalls", booleanValue(request, "planWalls", false));
        report.addProperty("executeWalls", booleanValue(request, "executeWalls", false));
        JsonArray steps = new JsonArray();
        report.add("steps", steps);
        JsonObject artifacts = new JsonObject();
        artifacts.addProperty("workflowReport", debugRef(debugRoot, reportPath));
        if (manifest != null) {
            artifacts.addProperty("testRunManifest", debugRef(debugRoot, reportPath));
            artifacts.addProperty("testRunPackage", debugRef(debugRoot, testRunLayout.packageDirectory()));
        }
        report.add("artifacts", artifacts);
        long workflowStarted = System.nanoTime();
        CityWorkflowStepRunner workflow = new CityWorkflowStepRunner(report, steps,
                booleanValue(request, "skipExisting", true),
                artifact -> debugRef(debugRoot, artifact),
                currentReport -> writeTestRunState(reportPath, manifest, currentReport));
        workflow.writeReport();

        WorkflowContext ctx = new WorkflowContext(debugRoot, runDir, runId, citySeedId, request, report, workflow);

        if (request.has("enableLandUseLayer") || request.has("landUseIntentPlan")) {
            throw new IllegalArgumentException("CITY_BLUEPRINT_WORKFLOW_LAND_USE_OVERRIDE_FORBIDDEN: "
                    + "Blueprint workflow derives LandUse planning exclusively from cityBlueprint.outdoorPlan.");
        }
        boolean blueprintWorkflow = true;
        LandUseSettings landUseSettings = loadWorkflowLandUseSettings();
        boolean configuredLandUseLayer = hasValue(request, "enableLandUseLayer")
                ? booleanValue(request, "enableLandUseLayer", landUseSettings.enabledInWorkflow())
                : landUseSettings.enabledInWorkflow();
        report.addProperty("landUseControlSource", "city_blueprint");
        report.addProperty("landUseProfileId", landUseSettings.profileId());
        Path workflowTerrainField = cityStageDir(runDir, citySeedId, CityTestRunLayout.LAND_USE)
                .resolve("land_use_terrain_field.json");
        Path d3WorkflowArtifact = configuredLandUseLayer
                || level != null || Files.isRegularFile(workflowTerrainField)
                ? workflowTerrainField : d3PackagePath(runDir, citySeedId);

        if (!ctx.workflow().runStep("city_plan_d3", d3WorkflowArtifact, () -> handlePlanD3(
                debugRoot, runId, citySeedId,
                hasValue(request, "cellStepBlocks") ? intValue(request, "cellStepBlocks", 4) : null,
                hasValue(request, "patchScanPaddingBlocks")
                        ? intValue(request, "patchScanPaddingBlocks", DEFAULT_D3_PATCH_SCAN_PADDING_BLOCKS) : null,
                booleanValue(request, "preferGeneratorNativeTerrain", true),
                level))) {
            return ctx.workflow().finish(workflowStarted, "failed");
        }

        JsonObject workflowSeed = loadCitySeed(runDir, runId, citySeedId);
        if (requiresD3SiteReview(workflowSeed)) {
            Path decisionPath = d3SiteDecisionPath(runDir, citySeedId);
            if (!Files.isRegularFile(decisionPath)) {
                report.addProperty("siteReviewStatus", "awaiting_review");
                report.addProperty("nextAction", "city_review_d3_site");
                return ctx.workflow().finish(workflowStarted, "awaiting_site_review");
            }
            JsonObject siteReview = JsonParser.parseString(Files.readString(decisionPath)).getAsJsonObject();
            if ("reselect_required".equals(stringValue(siteReview, "decision"))) {
                report.addProperty("siteReviewStatus", "reselection_required");
                report.addProperty("nextAction", "realm_t4_patch_planning_create");
                return ctx.workflow().finish(workflowStarted, "reselection_required");
            }
            try {
                requireD3SiteDecision(runDir, workflowSeed, citySeedId);
            } catch (IllegalArgumentException ex) {
                if (ex.getMessage() != null && ex.getMessage().startsWith("CITY_D3_SITE_REVIEW_STALE")) {
                    report.addProperty("siteReviewStatus", "stale");
                    report.addProperty("nextAction", "city_review_d3_site");
                    return ctx.workflow().finish(workflowStarted, "awaiting_site_review");
                }
                throw ex;
            }
        }

        if (!workflowRunD4(ctx)) {
            String requestedStatus = stringValue(ctx.report(), "requestedWorkflowStatus");
            return ctx.workflow().finish(workflowStarted,
                    requestedStatus.isBlank() ? "failed" : requestedStatus);
        }

        Path workflowAnchorMap = cityStageDir(runDir, citySeedId, CityTestRunLayout.D4)
                .resolve("structure_anchor_map.json");
        Path workflowD5Plan = cityStageDir(runDir, citySeedId, CityTestRunLayout.D5)
                .resolve("reservation_mask_plan.json");
        if (!ctx.workflow().runStep("city_plan_d5",
                !blueprintWorkflow || workflowArtifactMatchesAnchorMap(workflowD5Plan, workflowAnchorMap)
                        ? workflowD5Plan : null,
                () -> handlePlanD5(debugRoot, runId, citySeedId,
                intValue(request, "wallMarginBlocks", 24),
                intValue(request, "wallCorridorHalfWidthBlocks", 4)))) {
            return ctx.workflow().finish(workflowStarted, "failed");
        }

        Path workflowD6Plan = cityStageDir(runDir, citySeedId, CityTestRunLayout.D6)
                .resolve("structure_materialization_plan.json");
        if (!ctx.workflow().runStep("city_plan_d6",
                !blueprintWorkflow || workflowArtifactMatchesAnchorMap(workflowD6Plan, workflowAnchorMap)
                        ? workflowD6Plan : null,
                () -> serverHolder.callOnServerThread(() -> handlePlanD6(
                        debugRoot, runId, citySeedId, serverHolder, level)))) {
            return ctx.workflow().finish(workflowStarted, "failed");
        }

        BlueprintOutdoorInputs workflowBlueprintInputs = blueprintWorkflow
                ? loadBlueprintOutdoorInputs(debugRoot, runDir, citySeedId) : null;
        boolean enableLandUseLayer = workflowBlueprintInputs == null
                ? configuredLandUseLayer
                : workflowBlueprintInputs.blueprint().outdoorPlan().mode() == CityBlueprint.OutdoorMode.GENERATE;
        report.addProperty("enableLandUseLayer", enableLandUseLayer);
        if (workflowBlueprintInputs != null) {
            report.addProperty("blueprintOutdoorMode",
                    workflowBlueprintInputs.blueprint().outdoorPlan().mode().name());
        }
        if (blueprintWorkflow || enableLandUseLayer) {
            if (!blueprintWorkflow && request.has("landUseIntentPlan")
                    && !request.get("landUseIntentPlan").isJsonNull()
                    && !request.get("landUseIntentPlan").isJsonObject()) {
                throw new IllegalArgumentException("LAND_USE_INTENT_OBJECT_REQUIRED");
            }
            Path landUseCompletion = cityStageDir(runDir, citySeedId, CityTestRunLayout.LAND_USE)
                    .resolve("city_land_use_planning_complete.json");
            Path landUseSkipArtifact = blueprintWorkflow
                    ? (enableLandUseLayer
                    && workflowBlueprintOutdoorArtifactsCurrent(debugRoot, runDir, citySeedId)
                    ? landUseCompletion : null)
                    : landUseCompletion;
            if (!ctx.workflow().runStep("city_plan_land_use",
                    landUseSkipArtifact,
                    () -> blueprintWorkflow
                            ? handlePlanBlueprintOutdoor(debugRoot, runId, citySeedId)
                            : handlePlanLandUse(debugRoot, runId, citySeedId,
                            request.has("landUseIntentPlan") && request.get("landUseIntentPlan").isJsonObject()
                                    ? request.getAsJsonObject("landUseIntentPlan") : null))) {
                return ctx.workflow().finish(workflowStarted, "failed");
            }
        }
        if (blueprintWorkflow && !enableLandUseLayer) {
            report.addProperty("blueprintOutdoorStatus", "preserved");
        }

        if (!booleanValue(request, "confirmWorldMutation", false)) {
            ctx.workflow().addStop("city_execute_d5", "needs_confirmation",
                    "WORKFLOW_CONFIRM_WORLD_MUTATION_REQUIRED",
                    "Pass confirmWorldMutation=true to activate masks/planned structures.");
            return ctx.workflow().finish(workflowStarted, "waiting_for_confirmation");
        }

        Path activeD5Registry = cityStageDir(runDir, citySeedId, CityTestRunLayout.D5)
                .resolve("active_planned_structure_registry.json");
        Path executeD5SkipArtifact = workflowD5ActivationCurrent(activeD5Registry,
                workflowD5Plan, workflowD6Plan,
                cityStageDir(runDir, citySeedId, CityTestRunLayout.LAND_USE)
                        .resolve("city_land_use_planning_complete.json"))
                && workflowD5RuntimeActivationCurrent(workflowD6Plan, runId, citySeedId)
                ? activeD5Registry : null;
        if (!ctx.workflow().runStep("city_execute_d5", executeD5SkipArtifact,
                () -> serverHolder.callOnServerThread(() -> handleExecuteD5(
                        debugRoot, serverRoot, runId, citySeedId, true, level,
                        blueprintWorkflow ? null : enableLandUseLayer)))) {
            return ctx.workflow().finish(workflowStarted, "failed");
        }

        if (booleanValue(request, "stopAfterActivation", false)) {
            ctx.workflow().addStop("worldgen_wait", "waiting_for_generation", "WAITING_FOR_GENERATION",
                    "D4 and all program-owned downstream stages are complete; active plans are waiting for chunk generation.");
            return ctx.workflow().finish(workflowStarted, "waiting_for_generation");
        }

        if (!ctx.workflow().runStep("city_execute_d7", null,
                () -> serverHolder.callOnServerThread(() -> handleExecuteD7(
                        debugRoot, runId, citySeedId, level.getSeed(), true, serverHolder, level)))) {
            return ctx.workflow().finish(workflowStarted, "failed");
        }
        JsonObject d7Step = steps.get(steps.size() - 1).getAsJsonObject();
        String d7Status = stringValue(d7Step, "responseStatus", "");
        String d7Reason = stringValue(d7Step, "reasonCode", "");
        if ("WAITING_FOR_WORLDGEN".equals(d7Reason) || "waiting_for_worldgen".equals(d7Status)) {
            ctx.workflow().addStop("worldgen_wait", "waiting_for_worldgen", "WAITING_FOR_WORLDGEN",
                    "D7 is generating or observing owner chunks through the bounded server queue; "
                            + "rerun this workflow with the same runId/citySeedId to poll progress.");
            return ctx.workflow().finish(workflowStarted, "waiting_for_worldgen");
        }

        if (booleanValue(request, "planWalls", false)) {
            Path wallPlanPath = cityStageDir(runDir, citySeedId, CityTestRunLayout.WALLS)
                    .resolve("city_wall_plan.json");
            Path wallSkipArtifact = workflowWallPlanMatchesRequest(wallPlanPath, request) ? wallPlanPath : null;
            if (!ctx.workflow().runStep("city_plan_city_walls", wallSkipArtifact,
                    () -> serverHolder.callOnServerThread(() -> handlePlanCityWalls(
                            debugRoot, runId, citySeedId, level,
                            intValue(request, "roadScanMarginBlocks", 8),
                            workflowWallOptions(request))))) {
                return ctx.workflow().finish(workflowStarted, "failed");
            }
        }

        if (booleanValue(request, "executeWalls", false)) {
            if (!ctx.workflow().runStep("city_execute_city_walls", null,
                    () -> serverHolder.callOnServerThread(() -> handleExecuteCityWalls(
                            debugRoot, runId, citySeedId, true, level,
                            booleanValue(request, "debugScan", true),
                            intValue(request, "debugScanStepBlocks", 1))))) {
                return ctx.workflow().finish(workflowStarted, "failed");
            }
        }

        return ctx.workflow().finish(workflowStarted, "completed");
    }

    private static boolean workflowRunD4(WorkflowContext ctx) throws IOException {
        return workflowRunD4Blueprint(ctx);
    }

    static boolean workflowArtifactMatchesAnchorMap(Path artifactPath, Path anchorMapPath) {
        if (!Files.isRegularFile(artifactPath) || !Files.isRegularFile(anchorMapPath)) return false;
        try {
            JsonObject artifact = JsonParser.parseString(Files.readString(artifactPath)).getAsJsonObject();
            JsonObject current = JsonParser.parseString(Files.readString(anchorMapPath)).getAsJsonObject();
            return artifact.has("sourceStructureAnchorMap")
                    && artifact.get("sourceStructureAnchorMap").isJsonObject()
                    && artifact.getAsJsonObject("sourceStructureAnchorMap").equals(current);
        } catch (RuntimeException | IOException ignored) {
            return false;
        }
    }

    static boolean workflowD5ActivationCurrent(Path activeRegistryPath,
                                               Path d5PlanPath,
                                               Path d6PlanPath,
                                               Path landUseCompletionPath) {
        if (!Files.isRegularFile(activeRegistryPath)
                || !Files.isRegularFile(d5PlanPath)
                || !Files.isRegularFile(d6PlanPath)) return false;
        try {
            JsonObject active = JsonParser.parseString(Files.readString(activeRegistryPath)).getAsJsonObject();
            if (!active.has("activationProvenance")
                    || !active.get("activationProvenance").isJsonObject()) return false;
            JsonObject provenance = active.getAsJsonObject("activationProvenance");
            return "city_d5_activation_provenance".equals(
                    stringValue(provenance, "schema", ""))
                    && sha256(Files.readString(d5PlanPath)).equals(
                    stringValue(provenance, "sourceD5Hash", ""))
                    && sha256(Files.readString(d6PlanPath)).equals(
                    stringValue(provenance, "sourceD6Hash", ""))
                    && optionalArtifactHash(landUseCompletionPath).equals(
                    stringValue(provenance, "sourceLandUseCompletionHash", ""));
        } catch (RuntimeException | IOException ignored) {
            return false;
        }
    }

    static boolean workflowD5RuntimeActivationCurrent(Path d6PlanPath,
                                                      String runId,
                                                      String citySeedId) {
        if (!Files.isRegularFile(d6PlanPath)) return false;
        try {
            JsonObject d6Plan = JsonParser.parseString(Files.readString(d6PlanPath)).getAsJsonObject();
            return CityReservationMaskRegistry.hasActivePlannedStructuresFor(
                    runId, citySeedId, stringValue(d6Plan, "cityId", citySeedId));
        } catch (RuntimeException | IOException ignored) {
            return false;
        }
    }

    private static String optionalArtifactHash(Path path) throws IOException {
        return Files.isRegularFile(path) ? sha256(Files.readString(path)) : "absent";
    }

    static boolean workflowBlueprintAnchorMapCurrent(Path anchorMapPath, Path blueprintDir) {
        Path contextPath = blueprintDir.resolve("city_blueprint_context.json");
        Path validationPath = blueprintDir.resolve("city_blueprint_validation_report.json");
        Path submissionPath = blueprintDir.resolve("city_blueprint_submission_trace.json");
        Path blueprintPath = blueprintDir.resolve("city_blueprint.json");
        if (!Files.isRegularFile(anchorMapPath)
                || !Files.isRegularFile(contextPath)
                || !Files.isRegularFile(validationPath)
                || !Files.isRegularFile(submissionPath)
                || !Files.isRegularFile(blueprintPath)) {
            return false;
        }
        try {
            JsonObject anchorMap = JsonParser.parseString(Files.readString(anchorMapPath)).getAsJsonObject();
            JsonObject context = JsonParser.parseString(Files.readString(contextPath)).getAsJsonObject();
            JsonObject validation = JsonParser.parseString(Files.readString(validationPath)).getAsJsonObject();
            JsonObject submission = JsonParser.parseString(Files.readString(submissionPath)).getAsJsonObject();
            if (!isBlueprintAnchorMap(anchorMap)) return false;
            JsonObject provenance = anchorMap.getAsJsonObject("cityBlueprintCompileProvenance");
            String contextId = stringValue(context, "contextId", "");
            String blueprintHash = sha256(Files.readString(blueprintPath));
            return !contextId.isBlank()
                    && CityBlueprintService.CONTEXT_SCHEMA.equals(stringValue(context, "schema", ""))
                    && stringValue(anchorMap, "cityId", "").equals(stringValue(context, "cityId", ""))
                    && contextId.equals(stringValue(provenance, "contextId", ""))
                    && blueprintHash.equals(stringValue(provenance, "sourceBlueprintHash", ""))
                    && booleanValue(validation, "valid", false)
                    && contextId.equals(stringValue(validation, "contextId", ""))
                    && "accepted".equals(stringValue(submission, "status", ""))
                    && contextId.equals(stringValue(submission, "contextId", ""))
                    && blueprintHash.equals(stringValue(submission, "cityBlueprintHash", ""));
        } catch (RuntimeException | IOException ignored) {
            return false;
        }
    }

    private static boolean workflowRunD4Blueprint(WorkflowContext ctx) throws IOException {
        Path blueprintDir = cityStageDir(ctx.runDir(), ctx.citySeedId(), CityTestRunLayout.BLUEPRINT);
        Path contextPath = blueprintDir.resolve("city_blueprint_context.json");
        Path blueprintPath = blueprintDir.resolve("city_blueprint.json");
        Path anchorMapPath = cityStageDir(ctx.runDir(), ctx.citySeedId(), CityTestRunLayout.D4)
                .resolve("structure_anchor_map.json");
        if (!Files.isRegularFile(contextPath)) {
            ctx.report().addProperty("requestedWorkflowStatus", "awaiting_city_blueprint");
            ctx.report().addProperty("blueprintStatus", "context_required");
            ctx.report().addProperty("nextAction", "city_prepare_d4_blueprint_context");
            ctx.workflow().addStop("city_compile_d4_blueprint", "awaiting_city_blueprint",
                    "CITY_BLUEPRINT_CONTEXT_NOT_FOUND",
                    "Prepare the frozen CityBlueprint context before continuing D4.");
            return false;
        }
        if (!Files.isRegularFile(blueprintPath)) {
            ctx.report().addProperty("requestedWorkflowStatus", "awaiting_city_blueprint");
            ctx.report().addProperty("blueprintStatus", "submission_required");
            ctx.report().addProperty("nextAction", "city_submit_d4_blueprint");
            ctx.workflow().addStop("city_compile_d4_blueprint", "awaiting_city_blueprint",
                    "CITY_BLUEPRINT_NOT_FOUND",
                    "Submit a complete CityBlueprint revision before continuing D4.");
            return false;
        }
        Path skipArtifact = workflowBlueprintAnchorMapCurrent(anchorMapPath, blueprintDir)
                ? anchorMapPath : null;
        return ctx.workflow().runStep("city_compile_d4_blueprint", skipArtifact,
                () -> handleCompileD4Blueprint(ctx.debugRoot(), ctx.runId(), ctx.citySeedId()));
    }

    private static boolean workflowRunD4KeyThenArray(WorkflowContext ctx) throws IOException {
        Path anchorMapPath = cityStageDir(ctx.runDir(), ctx.citySeedId(), CityTestRunLayout.D4)
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

        Path anchorPlanPath = cityStageDir(ctx.runDir(), ctx.citySeedId(), CityTestRunLayout.D4)
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
                    "variantSelectionMode", "round_robin"));
            stageTrace.add(arrayTrace);
            currentPlan = D4_STAGED_PLAN_COMPILER.mergeStructureAnchorPlans(currentPlan, expandedPlan,
                    stringValue(stagePlan.sourceDesignSlotPlan(), "cityId"), stageTrace);
            JsonObject mergedPlan = currentPlan.deepCopy();
            if (!ctx.workflow().runStep("city_plan_d4_merge_array_stage_" + safeFileName(arrayId), null, () -> {
                requireObject(ctx.request(), "terrasenseProfileSource", "city_plan_d4 merge array stage");
                JsonObject response = handlePlanD4(ctx.debugRoot(), ctx.runId(), ctx.citySeedId(),
                        ctx.request().getAsJsonObject("terrasenseProfileSource"),
                        ctx.request().getAsJsonObject("templateCatalogSource"), mergedPlan);
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
        Path anchorMapPath = cityStageDir(ctx.runDir(), ctx.citySeedId(), CityTestRunLayout.D4)
                .resolve("structure_anchor_map.json");
        if (booleanValue(ctx.request(), "skipExisting", true) && Files.exists(anchorMapPath)) {
            ctx.workflow().addSkippedStep("city_d4_array_layout_loop", anchorMapPath,
                    "Existing structure_anchor_map.json found.");
            return true;
        }
        CityD4StagedPlanCompiler.StagePlan[] stagePlanRef = new CityD4StagedPlanCompiler.StagePlan[1];
        if (!ctx.workflow().runStep("city_validate_d4_array_layout_loop_plan", null, () -> {
            requireObject(ctx.request(), "designSlotPlan", "city_run_workflow array_candidate_selection_loop");
            stagePlanRef[0] = D4_STAGED_PLAN_COMPILER.compile(
                    ctx.request().getAsJsonObject("designSlotPlan"));
            writeWorkflowD4StagePlan(ctx, stagePlanRef[0]);
            JsonObject response = new JsonObject();
            response.addProperty("ok", true);
            response.addProperty("planningMode", "array_candidate_selection_loop");
            response.addProperty("arrayStageCount", stagePlanRef[0].arraySlots().size());
            return response;
        })) {
            return false;
        }
        CityD4StagedPlanCompiler.StagePlan stagePlan = stagePlanRef[0];
        if (!workflowRunD4Session(ctx, stagePlan.keyDesignSlotPlan(), "city_d4_key_structure")) {
            return false;
        }
        String mode = "array_candidate_selection_loop";
        JsonObject arrayLayoutPlan = ctx.request().has("arrayLayoutPlan")
                && ctx.request().get("arrayLayoutPlan").isJsonObject()
                ? ctx.request().getAsJsonObject("arrayLayoutPlan").deepCopy()
                : D4_STAGED_PLAN_COMPILER.minimalArrayLayoutPlan(stagePlan.sourceDesignSlotPlan(), mode);
        if (!ctx.workflow().runStep("city_create_d4_array_layout_loop", null, () -> {
            requireObject(ctx.request(), "terrasenseProfileSource", "city_create_d4_array_layout_loop");
            return handleCreateD4ArrayLayoutLoop(ctx.debugRoot(), ctx.runId(), ctx.citySeedId(),
                    ctx.request().getAsJsonObject("terrasenseProfileSource"),
                    arrayLayoutPlan,
                    ctx.request().getAsJsonObject("templateCatalogSource"),
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
                        ctx.request().getAsJsonObject("templateCatalogSource"));
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
                    ctx.request().getAsJsonObject("templateCatalogSource"));
        });
    }

    private static boolean workflowRunD4StructureClusterGroups(WorkflowContext ctx) throws IOException {
        Path anchorMapPath = cityStageDir(ctx.runDir(), ctx.citySeedId(), CityTestRunLayout.D4)
                .resolve("structure_anchor_map.json");
        if (booleanValue(ctx.request(), "skipExisting", true) && Files.exists(anchorMapPath)) {
            ctx.workflow().addSkippedStep("city_d4_structure_cluster_groups", anchorMapPath,
                    "Existing structure_anchor_map.json found.");
            return true;
        }
        Path candidateSetPath = cityStageDir(ctx.runDir(), ctx.citySeedId(),
                CityTestRunLayout.D4_STRUCTURE_CLUSTER_GROUPS)
                .resolve("structure_cluster_group_candidate_set.json");
        if (!ctx.workflow().runStep("city_plan_d4_structure_cluster_groups", candidateSetPath, () -> {
            requireObject(ctx.request(), "terrasenseProfileSource", "city_plan_d4_structure_cluster_groups");
            requireObject(ctx.request(), "designSlotPlan", "city_plan_d4_structure_cluster_groups");
            return handlePlanD4StructureClusterGroups(ctx.debugRoot(), ctx.runId(), ctx.citySeedId(),
                    ctx.request().getAsJsonObject("terrasenseProfileSource"),
                    ctx.request().getAsJsonObject("designSlotPlan"),
                    ctx.request().getAsJsonObject("templateCatalogSource"),
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
                    ctx.request().getAsJsonObject("templateCatalogSource"),
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
        Path anchorMapPath = cityStageDir(ctx.runDir(), ctx.citySeedId(), CityTestRunLayout.D4)
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
                    ctx.request().getAsJsonObject("templateCatalogSource"),
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
                            ctx.request().getAsJsonObject("templateCatalogSource")))) {
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
                    ctx.request().getAsJsonObject("templateCatalogSource"),
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
        JsonObject fixedTemplatePlan = attachTemplateCatalog(ctx.debugRoot(), ctx.runDir(), arrayCandidatePlan,
                ctx.request().getAsJsonObject("templateCatalogSource"));
        CityStructureArrayCandidatePlanner.Result result = new CityStructureArrayCandidatePlanner()
                .plan(ctx.runDir(), reviewPackage, ctx.request().getAsJsonObject("terrasenseProfileSource"),
                        fixedTemplatePlan, occupiedAnchorMap, new JsonArray());

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
        artifacts.add("sourceTemplateCatalog", ctx.request().getAsJsonObject("templateCatalogSource").deepCopy());
        response.add("artifacts", artifacts);
        return response;
    }

    private static Path d4ArrayStageDir(Path runDir, String citySeedId, String arrayId) {
        CityTestRunLayout layout = CityTestRunLayout.open(runDir, citySeedId);
        if (layout.legacy()) {
            return runDir.resolve("city_d4_array_candidates_" + safeFileName(citySeedId)
                    + "_" + safeFileName(arrayId));
        }
        return layout.stepDirectory(CityTestRunLayout.D4_ARRAY_CANDIDATES)
                .resolve("arrays").resolve(safeFileName(arrayId));
    }

    private static Path d4ArrayLayoutDir(Path runDir, String citySeedId) {
        return cityStageDir(runDir, citySeedId, CityTestRunLayout.D4_ARRAY_LAYOUT);
    }

    private static Path d4DesignLoopDir(Path runDir, String citySeedId) {
        return cityStageDir(runDir, citySeedId, CityTestRunLayout.D4_DESIGN_LOOP);
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
        return loadLandUseConfiguration().settings();
    }

    private static LandUseConfiguration loadLandUseConfiguration() {
        if (FMLPaths.CONFIGDIR.get() == null) {
            return new LandUseConfiguration(
                    new LandUseSettings(LandUseSettings.SCHEMA, false, LandUseSettings.DEFAULT_PROFILE_ID),
                    LandUseRuleCatalog.defaults());
        }
        Path root = defaultLandUseConfigRoot();
        try {
            Path installedRoot = LandUseDefaultConfigBootstrap.ensureInstalled(root);
            LandUseSettings settings = new LandUseSettingsLoader().load(installedRoot);
            LandUseRuleCatalog rules = new LandUseRuleCatalogLoader().load(installedRoot, settings);
            return new LandUseConfiguration(settings, rules);
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

    private record LandUseConfiguration(LandUseSettings settings, LandUseRuleCatalog rules) {
    }









    private static BlueprintOutdoorInputs currentBlueprintOutdoorInputs(Path debugRoot,
                                                                         Path runDir,
                                                                         String citySeedId) throws IOException {
        Path anchorPath = cityStageDir(runDir, citySeedId, CityTestRunLayout.D4)
                .resolve("structure_anchor_map.json");
        if (!Files.isRegularFile(anchorPath)) return null;
        JsonObject anchorMap = JsonParser.parseString(Files.readString(anchorPath)).getAsJsonObject();
        return isBlueprintAnchorMap(anchorMap)
                ? loadBlueprintOutdoorInputs(debugRoot, runDir, citySeedId) : null;
    }


    private static boolean isBlueprintAnchorMap(JsonObject anchorMap) {
        if (anchorMap == null || !anchorMap.has("cityBlueprintCompileProvenance")
                || !anchorMap.get("cityBlueprintCompileProvenance").isJsonObject()) {
            return false;
        }
        return "programmatic_blueprint_compiler".equals(stringValue(
                anchorMap.getAsJsonObject("cityBlueprintCompileProvenance"), "selectionMode", ""));
    }

    private static BlueprintOutdoorInputs loadBlueprintOutdoorInputs(Path debugRoot,
                                                                      Path runDir,
                                                                      String citySeedId) throws IOException {
        Path blueprintDirectory = cityStageDir(runDir, citySeedId, CityTestRunLayout.BLUEPRINT);
        Path blueprintPath = blueprintDirectory.resolve("city_blueprint.json");
        Path snapshotPath = blueprintDirectory.resolve("city_blueprint_catalog_snapshot.json");
        Path contextPath = blueprintDirectory.resolve("city_blueprint_context.json");
        Path validationPath = blueprintDirectory.resolve("city_blueprint_validation_report.json");
        Path submissionPath = blueprintDirectory.resolve("city_blueprint_submission_trace.json");
        for (Path required : List.of(blueprintPath, snapshotPath, contextPath, validationPath, submissionPath)) {
            if (!Files.isRegularFile(required)) {
                throw new IllegalArgumentException("CITY_BLUEPRINT_OUTDOOR_INPUT_MISSING: "
                        + debugRef(debugRoot, required));
            }
        }
        String blueprintRaw = Files.readString(blueprintPath);
        String snapshotRaw = Files.readString(snapshotPath);
        JsonObject context = JsonParser.parseString(Files.readString(contextPath)).getAsJsonObject();
        JsonObject validation = JsonParser.parseString(Files.readString(validationPath)).getAsJsonObject();
        JsonObject submission = JsonParser.parseString(Files.readString(submissionPath)).getAsJsonObject();
        if (!CityBlueprintService.CONTEXT_SCHEMA.equals(stringValue(context, "schema", ""))
                || !citySeedId.equals(stringValue(context, "cityId", ""))
                || !booleanValue(validation, "valid", false)
                || !"accepted".equals(stringValue(submission, "status", ""))
                || !sha256(blueprintRaw).equals(stringValue(submission, "cityBlueprintHash", ""))) {
            throw new IllegalArgumentException("CITY_BLUEPRINT_OUTDOOR_NOT_ACCEPTED: "
                    + "the active Blueprint does not match its accepted submission artifacts.");
        }

        CityBlueprint blueprint = new CityBlueprintCodec().read(
                JsonParser.parseString(blueprintRaw).getAsJsonObject());
        if (!citySeedId.equals(blueprint.cityId())) {
            throw new IllegalArgumentException("CITY_BLUEPRINT_OUTDOOR_CITY_ID_MISMATCH: expected "
                    + citySeedId + " but found " + blueprint.cityId());
        }
        String snapshotHash = sha256(snapshotRaw);
        if (!CityBlueprintService.SNAPSHOT_SCHEMA.equals(blueprint.catalogSnapshotRef().schema())
                || !debugRef(debugRoot, snapshotPath).equals(blueprint.catalogSnapshotRef().path())
                || !snapshotHash.equals(blueprint.catalogSnapshotRef().contentHash())) {
            throw new IllegalArgumentException("CITY_BLUEPRINT_OUTDOOR_CATALOG_STALE");
        }
        JsonObject snapshot = JsonParser.parseString(snapshotRaw).getAsJsonObject();
        if (!CityBlueprintService.SNAPSHOT_SCHEMA.equals(stringValue(snapshot, "schema", ""))
                || !snapshot.has("templateCatalog") || !snapshot.get("templateCatalog").isJsonObject()
                || !snapshot.has("referenceCatalog") || !snapshot.get("referenceCatalog").isJsonObject()
                || !snapshot.has("terrainFieldRef") || !snapshot.get("terrainFieldRef").isJsonObject()) {
            throw new IllegalArgumentException("CITY_BLUEPRINT_OUTDOOR_CATALOG_STALE");
        }
        JsonObject referenceJson = snapshot.getAsJsonObject("referenceCatalog");
        CityTemplateCatalog templates = new CityTemplateCatalogLoader().load(
                snapshot.getAsJsonObject("templateCatalog"));
        CityBlueprintReferenceCatalog references = CityBlueprintReferenceCatalog.parse(referenceJson, templates);

        Path landUseDirectory = cityStageDir(runDir, citySeedId, CityTestRunLayout.LAND_USE);
        Path terrainPath = landUseDirectory.resolve("land_use_terrain_field.json");
        JsonObject terrainRef = snapshot.getAsJsonObject("terrainFieldRef");
        if (!Files.isRegularFile(terrainPath)
                || !LandUseTerrainField.SCHEMA.equals(
                stringValue(terrainRef, "schema", ""))
                || !debugRef(debugRoot, terrainPath).equals(stringValue(terrainRef, "path", ""))) {
            throw new IllegalArgumentException("CITY_BLUEPRINT_OUTDOOR_TERRAIN_STALE");
        }
        String terrainRaw = Files.readString(terrainPath);
        String terrainHash = sha256(terrainRaw);
        if (!terrainHash.equals(stringValue(terrainRef, "contentHash", ""))) {
            throw new IllegalArgumentException("CITY_BLUEPRINT_OUTDOOR_TERRAIN_STALE");
        }
        LandUseTerrainField terrain = new LandUseTerrainFieldCodec().fromJson(
                JsonParser.parseString(terrainRaw).getAsJsonObject());
        if (!citySeedId.equals(terrain.cityId())) {
            throw new IllegalArgumentException("CITY_BLUEPRINT_OUTDOOR_TERRAIN_CITY_ID_MISMATCH");
        }

        Path d5Path = cityStageDir(runDir, citySeedId, CityTestRunLayout.D5)
                .resolve("reservation_mask_plan.json");
        Path landscapeCapacityPath = cityStageDir(runDir, citySeedId, CityTestRunLayout.D4)
                .resolve("city_landscape_capacity_reservation_plan.json");
        Path d6Path = cityStageDir(runDir, citySeedId, CityTestRunLayout.D6)
                .resolve("structure_materialization_plan.json");
        if (!Files.isRegularFile(d5Path) || !Files.isRegularFile(d6Path)
                || !Files.isRegularFile(landscapeCapacityPath)) {
            throw new IllegalArgumentException("CITY_BLUEPRINT_OUTDOOR_D5_D6_MISSING");
        }
        JsonObject d5 = JsonParser.parseString(Files.readString(d5Path)).getAsJsonObject();
        JsonObject landscapeCapacity = JsonParser.parseString(
                Files.readString(landscapeCapacityPath)).getAsJsonObject();
        JsonObject d6 = JsonParser.parseString(Files.readString(d6Path)).getAsJsonObject();
        validateLockedMaterializationPlan(d6);
        if (!citySeedId.equals(stringValue(d6, "cityId", citySeedId))) {
            throw new IllegalArgumentException("CITY_BLUEPRINT_OUTDOOR_D6_CITY_ID_MISMATCH");
        }
        return new BlueprintOutdoorInputs(blueprint, references, terrain, d5, d6, landscapeCapacity,
                canonicalArtifactHash(new CityBlueprintCodec().write(blueprint)), snapshotHash,
                canonicalArtifactHash(referenceJson),
                canonicalArtifactHash(new LandUseTerrainFieldCodec().toJson(terrain)),
                canonicalArtifactHash(d6), blueprintPath, snapshotPath,
                terrainPath, d5Path, d6Path, landUseDirectory);
    }

    private static JsonObject blueprintOutdoorCompletion(BlueprintOutdoorInputs inputs,
                                                           CityOutdoorIntentPlan intentPlan,
                                                           CityUrbanSpacePlan urbanSpacePlan,
                                                           LandUseAreaPlan plan,
                                                           CityLandUseSurfacePrintPlan surfacePrintPlan) {
        JsonObject completion = new JsonObject();
        completion.addProperty("schema", BLUEPRINT_OUTDOOR_COMPLETION_SCHEMA);
        completion.addProperty("cityId", inputs.blueprint().cityId());
        completion.addProperty("planningSource", "city_blueprint");
        completion.addProperty("sourceBlueprintHash", inputs.blueprintHash());
        completion.addProperty("sourceCatalogSnapshotHash", inputs.catalogSnapshotHash());
        completion.addProperty("sourceReferenceCatalogHash", inputs.referenceCatalogHash());
        completion.addProperty("sourceTerrainFieldHash", inputs.terrainFieldHash());
        completion.addProperty("sourceD6Hash", inputs.d6Hash());
        completion.addProperty("outdoorIntentPlanHash", intentPlan.planHash());
        completion.addProperty("urbanSpacePlanHash", urbanSpacePlan.planHash());
        completion.addProperty("planHash", plan.planHash());
        completion.addProperty("surfacePrintPlanSchema", surfacePrintPlan.schema());
        completion.addProperty("surfacePrintPlanHash", surfacePrintPlan.planHash());
        completion.addProperty("ruleProfileHash", inputs.referenceCatalog().landUseRuleCatalog().profileHash());
        completion.addProperty("completedAt", Instant.now().toString());
        return completion;
    }

    static boolean workflowBlueprintOutdoorArtifactsCurrent(Path debugRoot,
                                                             Path runDir,
                                                             String citySeedId) {
        try {
            BlueprintOutdoorInputs inputs = loadBlueprintOutdoorInputs(debugRoot, runDir, citySeedId);
            if (inputs.blueprint().outdoorPlan().mode() != CityBlueprint.OutdoorMode.GENERATE) return false;
            Path areaPath = inputs.landUseDirectory().resolve("city_land_use_area_plan.json");
            Path surfacePath = inputs.landUseDirectory().resolve("city_land_use_surface_print_plan.json");
            if (!Files.isRegularFile(areaPath) || !Files.isRegularFile(surfacePath)) return false;
            LandUseAreaPlan plan = new LandUseAreaPlanCodec().fromJson(
                    JsonParser.parseString(Files.readString(areaPath)).getAsJsonObject());
            CityLandUseSurfacePrintPlan surface = new CityLandUseSurfacePrintPlanCodec().fromJson(
                    JsonParser.parseString(Files.readString(surfacePath)).getAsJsonObject());
            validateLandUseCompletionArtifacts(debugRoot, runDir, citySeedId, inputs.d6Plan(),
                    plan, surface, inputs);
            return true;
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private static void validateLandUseCompletionArtifacts(Path debugRoot,
                                                            Path runDir,
                                                            String citySeedId,
                                                            JsonObject materializationPlan,
                                                            LandUseAreaPlan plan,
                                                            CityLandUseSurfacePrintPlan surfacePrintPlan,
                                                            BlueprintOutdoorInputs blueprintInputs)
            throws IOException {
        Path directory = cityStageDir(runDir, citySeedId, CityTestRunLayout.LAND_USE);
        Path completionPath = directory.resolve("city_land_use_planning_complete.json");
        JsonObject completion = JsonParser.parseString(Files.readString(completionPath)).getAsJsonObject();
        String expectedCityId = stringValue(materializationPlan, "cityId", citySeedId);
        if (blueprintInputs == null) {
            validateLandUseCompletion(completion, plan, surfacePrintPlan, expectedCityId,
                    loadLandUseConfiguration().rules().profileHash(),
                    sha256(CityJson.GSON.toJson(materializationPlan)));
            return;
        }
        if (blueprintInputs.blueprint().outdoorPlan().mode() != CityBlueprint.OutdoorMode.GENERATE) {
            throw new IllegalArgumentException("CITY_BLUEPRINT_OUTDOOR_MODE_PRESERVE");
        }
        Path intentPath = directory.resolve("city_outdoor_intent_plan.json");
        Path urbanPath = directory.resolve("city_urban_space_plan.json");
        Path tracePath = directory.resolve("land_use_plan_trace.json");
        Path qualityPath = directory.resolve("quality_report.json");
        Path previewPath = directory.resolve("land_use_preview.png");
        for (Path required : List.of(intentPath, urbanPath, tracePath, qualityPath, previewPath)) {
            if (!Files.isRegularFile(required)) {
                throw new IllegalArgumentException("CITY_BLUEPRINT_OUTDOOR_PLAN_INCOMPLETE: missing "
                        + debugRef(debugRoot, required));
            }
        }
        JsonObject intent = JsonParser.parseString(Files.readString(intentPath)).getAsJsonObject();
        JsonObject urban = JsonParser.parseString(Files.readString(urbanPath)).getAsJsonObject();
        validateEmbeddedPlanHash(intent, CityOutdoorIntentPlan.SCHEMA,
                "CITY_BLUEPRINT_OUTDOOR_INTENT_STALE");
        validateEmbeddedPlanHash(urban, CityUrbanSpacePlan.SCHEMA, "CITY_BLUEPRINT_URBAN_SPACE_STALE");
        requireCompletionIdentity(intent, "cityId", expectedCityId);
        requireCompletionIdentity(intent, "mode", CityBlueprint.OutdoorMode.GENERATE.name());
        requireCompletionIdentity(intent, "sourceBlueprintHash", blueprintInputs.blueprintHash());
        requireCompletionIdentity(intent, "sourceD6Hash", blueprintInputs.d6Hash());
        requireCompletionIdentity(intent, "sourceTerrainFieldHash", blueprintInputs.terrainFieldHash());
        requireCompletionIdentity(intent, "sourceOutdoorCatalogHash", blueprintInputs.referenceCatalogHash());
        requireCompletionIdentity(intent, "ruleProfileHash",
                blueprintInputs.referenceCatalog().landUseRuleCatalog().profileHash());
        requireCompletionIdentity(urban, "cityId", expectedCityId);
        if (!expectedCityId.equals(surfacePrintPlan.cityId())
                || !plan.planHash().equals(surfacePrintPlan.sourceLandUsePlanHash())) {
            throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_SOURCE_MISMATCH");
        }

        Set<String> allowedFields = Set.of("schema", "cityId", "planningSource",
                "sourceBlueprintHash", "sourceCatalogSnapshotHash", "sourceReferenceCatalogHash",
                "sourceTerrainFieldHash", "sourceD6Hash", "outdoorIntentPlanHash", "urbanSpacePlanHash",
                "planHash", "surfacePrintPlanSchema", "surfacePrintPlanHash", "ruleProfileHash",
                "completedAt");
        if (!allowedFields.equals(completion.keySet())
                || !BLUEPRINT_OUTDOOR_COMPLETION_SCHEMA.equals(stringValue(completion, "schema", ""))
                || !"city_blueprint".equals(stringValue(completion, "planningSource", ""))) {
            throw new IllegalArgumentException("CITY_BLUEPRINT_OUTDOOR_COMPLETION_INVALID");
        }
        requireCompletionIdentity(completion, "cityId", expectedCityId);
        requireCompletionIdentity(completion, "sourceBlueprintHash", blueprintInputs.blueprintHash());
        requireCompletionIdentity(completion, "sourceCatalogSnapshotHash", blueprintInputs.catalogSnapshotHash());
        requireCompletionIdentity(completion, "sourceReferenceCatalogHash", blueprintInputs.referenceCatalogHash());
        requireCompletionIdentity(completion, "sourceTerrainFieldHash", blueprintInputs.terrainFieldHash());
        requireCompletionIdentity(completion, "sourceD6Hash", blueprintInputs.d6Hash());
        requireCompletionIdentity(completion, "outdoorIntentPlanHash", stringValue(intent, "planHash", ""));
        requireCompletionIdentity(completion, "urbanSpacePlanHash", stringValue(urban, "planHash", ""));
        requireCompletionIdentity(completion, "planHash", plan.planHash());
        requireCompletionIdentity(completion, "surfacePrintPlanSchema",
                CityLandUseSurfacePrintPlan.SCHEMA);
        requireCompletionIdentity(completion, "surfacePrintPlanHash", surfacePrintPlan.planHash());
        requireCompletionIdentity(completion, "ruleProfileHash",
                blueprintInputs.referenceCatalog().landUseRuleCatalog().profileHash());
        if (!LandUseRuleCatalog.RULE_VERSION.equals(plan.ruleVersion())
                || stringValue(completion, "completedAt", "").isBlank()) {
            throw new IllegalArgumentException("CITY_BLUEPRINT_OUTDOOR_COMPLETION_INVALID");
        }
    }

    private static void validateEmbeddedPlanHash(JsonObject artifact,
                                                  String expectedSchema,
                                                  String reasonCode) {
        String planHash = stringValue(artifact, "planHash", "");
        JsonObject canonical = artifact.deepCopy();
        canonical.remove("planHash");
        String actual = canonicalArtifactHash(canonical).substring("sha256:".length());
        if (!expectedSchema.equals(stringValue(artifact, "schema", ""))
                || planHash.isBlank() || !planHash.equals(actual)) {
            throw new IllegalArgumentException(reasonCode);
        }
    }

    private static void requireCompletionIdentity(JsonObject completion,
                                                   String field,
                                                   String expected) {
        if (expected == null || expected.isBlank()
                || !expected.equals(stringValue(completion, field, ""))) {
            throw new IllegalArgumentException("CITY_BLUEPRINT_OUTDOOR_IDENTITY_MISMATCH: " + field);
        }
    }

    private static String canonicalArtifactHash(JsonElement value) {
        return sha256(canonicalJson(value).toString());
    }

    private static JsonElement canonicalJson(JsonElement value) {
        if (value == null || value.isJsonNull() || value.isJsonPrimitive()) {
            return value == null ? JsonParser.parseString("null") : value.deepCopy();
        }
        if (value.isJsonArray()) {
            JsonArray result = new JsonArray();
            value.getAsJsonArray().forEach(element -> result.add(canonicalJson(element)));
            return result;
        }
        JsonObject result = new JsonObject();
        for (String key : new TreeSet<>(value.getAsJsonObject().keySet())) {
            result.add(key, canonicalJson(value.getAsJsonObject().get(key)));
        }
        return result;
    }

    private static void validateLandUseCompletion(JsonObject completion,
                                                   LandUseAreaPlan plan,
                                                  CityLandUseSurfacePrintPlan surfacePrintPlan,
                                                  String expectedCityId,
                                                  String expectedRuleProfileHash,
                                                  String expectedSourceD6Hash) {
        Set<String> allowedFields = Set.of("schema", "cityId", "planHash",
                "surfacePrintPlanHash", "ruleProfileHash", "sourceD6Hash", "completedAt");
        for (String key : completion.keySet()) {
            if (!allowedFields.contains(key)) {
                throw new IllegalArgumentException(
                        "CITY_LAND_USE_PLAN_INCOMPLETE: completion field is unsupported: " + key);
            }
        }
        if (!"city_land_use_planning_complete".equals(
                stringValue(completion, "schema", ""))) {
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
        if (surfacePrintPlan.planHash().isBlank()
                || !surfacePrintPlan.planHash().equals(stringValue(completion, "surfacePrintPlanHash", ""))) {
            throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_COMPLETION_MISMATCH: completion marker "
                    + "does not match the surface print plan.");
        }
        if (!expectedCityId.equals(surfacePrintPlan.cityId())
                || !plan.planHash().equals(surfacePrintPlan.sourceLandUsePlanHash())) {
            throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_SOURCE_MISMATCH");
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

    static JsonObject landUseBackfillJson(
            CityLandUseWorldgenRegistry.BackfillSummary result) {
        JsonObject json = new JsonObject();
        json.addProperty("schema", "city_land_use_owner_completion.v0.2");
        json.addProperty("cityId", result.cityId());
        json.addProperty("areaPlanHash", result.areaPlanHash());
        json.addProperty("surfacePrintPlanHash", result.surfacePrintPlanHash());
        json.addProperty("complete", result.complete());
        json.addProperty("status", result.status());
        json.addProperty("maxOwnerActionsPerTick", result.maxOwnerActionsPerTick());
        json.addProperty("synchronousChunkLoads", result.synchronousChunkLoads());
        json.addProperty("plannedOwnerCount", result.plannedOwnerCount());
        json.addProperty("appliedBeforeCount", result.appliedBeforeCount());
        json.addProperty("backfilledOwnerCount", result.backfilledOwnerCount());
        json.addProperty("appliedAfterCount", result.appliedAfterCount());
        JsonArray missing = new JsonArray();
        for (CityLandUseChunkStatusPreflight.OwnerChunk owner : result.missingOwners()) {
            JsonObject item = new JsonObject();
            item.addProperty("chunkX", owner.chunkX());
            item.addProperty("chunkZ", owner.chunkZ());
            missing.add(item);
        }
        json.add("missingOwners", missing);
        if (result.currentOwner() != null) {
            JsonObject current = new JsonObject();
            current.addProperty("chunkX", result.currentOwner().chunkX());
            current.addProperty("chunkZ", result.currentOwner().chunkZ());
            json.add("currentOwner", current);
        }
        JsonArray failures = new JsonArray();
        for (CityLandUseWorldgenRegistry.OwnerFailure failure : result.failures()) {
            JsonObject item = new JsonObject();
            item.addProperty("chunkX", failure.chunkX());
            item.addProperty("chunkZ", failure.chunkZ());
            item.addProperty("reasonCode", failure.reasonCode());
            item.addProperty("rollbackComplete", failure.rollbackComplete());
            failures.add(item);
        }
        json.add("failures", failures);
        json.add("foundationDiagnostics", result.foundationDiagnostics().deepCopy());
        return json;
    }









    private static BlockBounds unionBounds(BlockBounds first, BlockBounds second) {
        return new BlockBounds(Math.min(first.minX(), second.minX()), Math.min(first.minZ(), second.minZ()),
                Math.max(first.maxX(), second.maxX()), Math.max(first.maxZ(), second.maxZ()));
    }





    private static JsonObject countMapJson(Map<String, Integer> counts) {
        JsonObject result = new JsonObject();
        counts.forEach(result::addProperty);
        return result;
    }


    private static Path cityStageDir(Path runDir, String citySeedId, String stage) {
        return CityTestRunLayout.open(runDir, citySeedId).stepDirectory(stage);
    }

    private static JsonObject writeD4ArrayLayoutLoopArtifacts(Path debugRoot,
                                                              Path runDir,
                                                              String citySeedId,
                                                              JsonObject loopState,
                                                              CityLandformReviewPackage reviewPackage,
                                                              JsonObject templateCatalogSource) throws IOException {
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
        occupied.addProperty("schema", CityStructureArrayLayoutLoopPlanner.OCCUPIED_SCHEMA);
        occupied.addProperty("planningMode", stringValue(loopState, "planningMode",
                CityStructureArrayLayoutLoopPlanner.PLANNING_MODE));
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
        if (templateCatalogSource != null) {
            artifacts.add("sourceTemplateCatalog", templateCatalogSource.deepCopy());
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
                                                                        JsonObject templateCatalogSource) throws IOException {
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
        if (templateCatalogSource != null) {
            artifacts.add("sourceTemplateCatalog", templateCatalogSource.deepCopy());
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
        Path outputDirectory = cityStageDir(ctx.runDir(), ctx.citySeedId(), CityTestRunLayout.D4_STAGED);
        Files.createDirectories(outputDirectory);
        Path path = outputDirectory.resolve("d4_staged_plan.json");
        JsonObject obj = new JsonObject();
        obj.addProperty("schema", "city_d4_staged_plan");
        obj.addProperty("planningMode", "key_then_array");
        obj.add("sourceDesignSlotPlan", stagePlan.sourceDesignSlotPlan().deepCopy());
        obj.add("keyDesignSlotPlan", stagePlan.keyDesignSlotPlan().deepCopy());
        JsonArray arrays = new JsonArray();
        for (JsonObject slot : stagePlan.arraySlots()) {
            JsonObject array = new JsonObject();
            array.addProperty("slotId", stringValue(slot, "slotId"));
            array.addProperty("arrayCount", intValue(slot, "arrayCount", 0));
            array.addProperty("variantSelectionMode", stringValue(slot, "variantSelectionMode", "round_robin"));
            arrays.add(array);
        }
        obj.add("arrayStages", arrays);
        Files.writeString(path, CityJson.GSON.toJson(obj));
        ctx.report().getAsJsonObject("artifacts").addProperty("d4StagedPlan", debugRef(ctx.debugRoot(), path));
        ctx.workflow().writeReport();
    }

    private static void writeWorkflowD4StageTrace(WorkflowContext ctx, JsonArray stageTrace) throws IOException {
        Path outputDirectory = cityStageDir(ctx.runDir(), ctx.citySeedId(), CityTestRunLayout.D4_STAGED);
        Files.createDirectories(outputDirectory);
        Path path = outputDirectory.resolve("d4_staged_trace.json");
        JsonObject obj = new JsonObject();
        obj.addProperty("schema", "city_d4_staged_key_then_array_trace");
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
        return "city_wall_plan".equals(stringValue(existing, "schema", ""));
    }

    private static CityWallPlanner.Options workflowWallOptions(JsonObject request) {
        return new CityWallPlanner.Options(
                intValue(request, "wallUnitLengthBlocks", CityWallPlanner.DEFAULT_WALL_UNIT_LENGTH_BLOCKS),
                intValue(request, "nominalWallHeightBlocks", CityWallPlanner.DEFAULT_NOMINAL_WALL_HEIGHT_BLOCKS),
                intValue(request, "waterRunMinBlocks", CityWallPlanner.DEFAULT_WATER_RUN_MIN_BLOCKS),
                doubleValue(request, "waterFluidRatioMin", CityWallPlanner.DEFAULT_WATER_FLUID_RATIO_MIN),
                intValue(request, "heightSegmentMaxDeltaBlocks",
                        CityWallPlanner.DEFAULT_SEGMENT_MAX_DELTA_BLOCKS),
                intValue(request, "heightSteppedTransitionMaxDeltaBlocks",
                        CityWallPlanner.DEFAULT_STEPPED_TRANSITION_MAX_DELTA_BLOCKS),
                intValue(request, "naturalBoundaryMinDeltaBlocks",
                        CityWallPlanner.DEFAULT_NATURAL_BOUNDARY_MIN_DELTA_BLOCKS));
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

    private static CitySiteContext buildD3SiteContext(CitySiteContextBuilder builder, JsonObject seed,
                                                       RunMetadata metadata,
                                                       List<TerritoryCellRef> territoryCells) {
        String cityId = stringValue(seed, "citySeedId");
        String candidateId = stringValue(seed, "candidateId");
        if (candidateId.isBlank()) {
            candidateId = cityId;
        }
        return builder.buildWithFixedGridStep(
                cityId, stringValue(seed, "realmId"), metadata.dimensionId(),
                cityId, candidateId,
                blockCoord(seed, "x", metadata.cellStepBlocks()),
                blockCoord(seed, "z", metadata.cellStepBlocks()),
                stringValue(seed, "role"), stringValue(seed, "theoreticalScale"),
                intValue(seed, "planningRadiusCells", 64), metadata.cellStepBlocks(),
                D3_CELL_STEP_BLOCKS, territoryCells);
    }

    private static void requireMatchingRunWorldIdentity(Path runDir, ServerLevel level) throws IOException {
        if (level == null) {
            return;
        }
        requireMatchingRunWorldIdentity(runDir, level.dimension().location().toString(), level.getSeed());
    }

    private record BlueprintOutdoorInputs(CityBlueprint blueprint,
                                          CityBlueprintReferenceCatalog referenceCatalog,
                                          LandUseTerrainField terrainField,
                                          JsonObject d5MaskPlan,
                                          JsonObject d6Plan,
                                          JsonObject landscapeCapacityReservationPlan,
                                          String blueprintHash,
                                          String catalogSnapshotHash,
                                          String referenceCatalogHash,
                                          String terrainFieldHash,
                                          String d6Hash,
                                          Path blueprintPath,
                                          Path snapshotPath,
                                          Path terrainPath,
                                          Path d5MaskPath,
                                          Path d6Path,
                                          Path landUseDirectory) {
    }

    private static JsonObject loadTemplateCatalogJson(Path debugRoot, Path runDir,
                                                      JsonObject templateCatalogSource) throws IOException {
        if (templateCatalogSource == null) {
            throw new IllegalArgumentException("D4_TEMPLATE_CATALOG_SOURCE_REQUIRED");
        }
        if (templateCatalogSource.has("catalog") && templateCatalogSource.get("catalog").isJsonObject()) {
            JsonObject catalog = templateCatalogSource.getAsJsonObject("catalog").deepCopy();
            new CityTemplateCatalogLoader().load(catalog);
            return catalog;
        }
        String value = firstNonBlank(
                stringValue(templateCatalogSource, "catalogPath"),
                stringValue(templateCatalogSource, "templateCatalogPath"),
                stringValue(templateCatalogSource, "path"));
        if (value.isBlank()) {
            throw new IllegalArgumentException("D4_TEMPLATE_CATALOG_SOURCE_REQUIRED");
        }
        Path requested = Path.of(value);
        Path path;
        if (requested.isAbsolute()) {
            path = requested.normalize();
        } else {
            Path runCandidate = runDir.resolve(requested).normalize();
            path = Files.exists(runCandidate) ? runCandidate : debugRoot.resolve(requested).normalize();
        }
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("D4_TEMPLATE_CATALOG_NOT_FOUND: " + path);
        }
        JsonElement root = JsonParser.parseString(Files.readString(path));
        if (!root.isJsonObject()) {
            throw new IllegalArgumentException("D4_TEMPLATE_CATALOG_INVALID: " + path);
        }
        JsonObject catalog = root.getAsJsonObject();
        new CityTemplateCatalogLoader().load(catalog);
        return catalog;
    }

    private static JsonObject attachTemplateCatalog(Path debugRoot, Path runDir, JsonObject plan,
                                                    JsonObject templateCatalogSource) throws IOException {
        if (plan == null) {
            throw new IllegalArgumentException("D4 template plan is required.");
        }
        rejectConfiguredIdentity(plan);
        JsonObject result = plan.deepCopy();
        result.add("templateCatalog", loadTemplateCatalogJson(debugRoot, runDir, templateCatalogSource));
        return result;
    }

    private static void requireTemplateCatalogMatchesState(Path debugRoot, Path runDir, JsonObject state,
                                                           JsonObject templateCatalogSource) throws IOException {
        JsonObject supplied = loadTemplateCatalogJson(debugRoot, runDir, templateCatalogSource);
        JsonObject frozen = object(state, "templateCatalog");
        if (frozen.size() == 0 || !frozen.equals(supplied)) {
            throw new IllegalArgumentException("D4_TEMPLATE_CATALOG_STATE_MISMATCH");
        }
    }

    private static void rejectConfiguredIdentity(JsonElement element) {
        if (element == null || element.isJsonNull() || element.isJsonPrimitive()) {
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                rejectConfiguredIdentity(child);
            }
            return;
        }
        JsonObject object = element.getAsJsonObject();
        for (String key : List.of("structureId", "structureIds", "envelopeGroupKey",
                "selectedEnvelopeGroupKey", "availableEnvelopeGroupKeys", "structureEnvelopeFact")) {
            if (object.has(key)) {
                throw new IllegalArgumentException("CITY_CONFIGURED_STRUCTURE_FLOW_REMOVED: " + key);
            }
        }
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            rejectConfiguredIdentity(entry.getValue());
        }
    }

    private static JsonObject resolveTemplateAnchorPlan(JsonObject sourcePlan, JsonObject catalogJson) {
        if (sourcePlan == null || !sourcePlan.has("anchors") || !sourcePlan.get("anchors").isJsonArray()) {
            throw new IllegalArgumentException("structureAnchorPlan.anchors array is required.");
        }
        CityTemplateCatalog catalog = new CityTemplateCatalogLoader().load(catalogJson);
        JsonObject plan = sourcePlan.deepCopy();
        plan.remove("templateCatalogSource");
        for (JsonElement element : plan.getAsJsonArray("anchors")) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("D4_TEMPLATE_ANCHOR_INVALID");
            }
            JsonObject anchor = element.getAsJsonObject();
            for (String removed : List.of("structureId", "structureIds", "envelopeGroupKey",
                    "selectedEnvelopeGroupKey", "actualFootprint", "plannedFootprint",
                    "collisionEnvelope", "reservedEnvelope", "maskEnvelope", "templateHash",
                    "templateSize", "structureTemplate")) {
                if (anchor.has(removed)) {
                    throw new IllegalArgumentException("CITY_CONFIGURED_STRUCTURE_FLOW_REMOVED: " + removed);
                }
            }
            String templateId = firstNonBlank(stringValue(anchor, "templateId"),
                    stringValue(anchor, "templateRef"));
            String variant = firstNonBlank(stringValue(anchor, "variant"), stringValue(anchor, "variantId"));
            if (templateId.isBlank() || variant.isBlank()) {
                throw new IllegalArgumentException("D4_TEMPLATE_ID_VARIANT_REQUIRED");
            }
            CityTemplateCatalog.Template template = catalog.requireTemplate(templateId, variant);
            String suppliedRef = stringValue(anchor, "templateRef");
            if (!suppliedRef.isBlank() && !suppliedRef.equals(template.templateRef())) {
                throw new IllegalArgumentException("D4_TEMPLATE_REF_MISMATCH: " + suppliedRef);
            }
            CityTemplatePlacementGeometry.Rotation rotation = enumValue(anchor, "rotation",
                    template.allowedRotations().get(0), CityTemplatePlacementGeometry.Rotation.class);
            CityTemplatePlacementGeometry.Mirror mirror = enumValue(anchor, "mirror",
                    template.allowedMirrors().get(0), CityTemplatePlacementGeometry.Mirror.class);
            CityTemplatePlacementGeometry geometry = template.geometry(rotation, mirror);
            anchor.addProperty("templateId", template.templateId());
            anchor.addProperty("templateRef", template.templateRef());
            anchor.addProperty("templateHash", template.contentHash());
            anchor.addProperty("variantId", template.variantId());
            anchor.remove("variant");
            anchor.addProperty("rotation", rotation.name());
            anchor.addProperty("mirror", mirror.name());
            anchor.addProperty("terrainPosePolicy", template.terrainPosePolicy());
            anchor.addProperty("supportPolicy", template.supportPolicy());
            anchor.addProperty("materializationSource",
                    CityStructureMaterializationPlanner.TEMPLATE_MATERIALIZATION_SOURCE);
            JsonObject size = new JsonObject();
            size.addProperty("width", template.width());
            size.addProperty("height", template.height());
            size.addProperty("depth", template.depth());
            anchor.add("rawSize", size);
            anchor.addProperty("clearanceBlocks", template.clearanceBlocks());
            addTemplatePlacementPlan(anchor, template, geometry, requiredObject(anchor, "anchorBlock"));
        }
        return plan;
    }

    private static void addTemplatePlacementPlan(JsonObject anchor, CityTemplateCatalog.Template template,
                                                 CityTemplatePlacementGeometry geometry, JsonObject anchorJson) {
        BlockPoint worldAnchor = new BlockPoint(intValue(anchorJson, "x", 0), intValue(anchorJson, "z", 0));
        JsonObject placement = new JsonObject();
        for (String key : List.of("templateId", "templateRef", "templateHash", "variantId",
                "rotation", "mirror", "terrainPosePolicy")) {
            placement.add(key, anchor.get(key).deepCopy());
        }
        placement.add("anchorBlock", worldAnchor.asJson());
        JsonObject rawSize = new JsonObject();
        rawSize.addProperty("width", template.width());
        rawSize.addProperty("height", template.height());
        rawSize.addProperty("depth", template.depth());
        placement.add("templateSize", rawSize);
        JsonObject transformed = new JsonObject();
        JsonObject transformedSize = new JsonObject();
        transformedSize.addProperty("width", geometry.transformedSize().width());
        transformedSize.addProperty("height", geometry.transformedSize().height());
        transformedSize.addProperty("depth", geometry.transformedSize().depth());
        transformed.add("size", transformedSize);
        JsonArray entrances = new JsonArray();
        for (CityTemplatePlacementGeometry.TransformedRoadEntrance entrance : geometry.roadEntrances()) {
            JsonObject value = new JsonObject();
            value.addProperty("entranceId", entrance.entranceId());
            value.addProperty("direction", entrance.direction().name());
            value.add("relativePosition", entrance.relativePosition().asJson());
            value.add("worldPosition", entrance.worldPosition(worldAnchor).asJson());
            entrances.add(value);
        }
        transformed.add("roadEntrances", entrances);
        placement.add("transformed", transformed);
        anchor.add("templatePlacementPlan", placement);
    }

    private static void requireResolvedTemplateAnchorPlan(JsonObject plan) {
        if (plan == null || !plan.has("anchors") || !plan.get("anchors").isJsonArray()) {
            throw new IllegalArgumentException("structureAnchorPlan.anchors array is required.");
        }
        for (JsonElement element : plan.getAsJsonArray("anchors")) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("CITY_CONFIGURED_STRUCTURE_FLOW_REMOVED");
            }
            JsonObject anchor = element.getAsJsonObject();
            if (stringValue(anchor, "templateId").isBlank()
                    || stringValue(anchor, "templateRef").isBlank()
                    || stringValue(anchor, "templateHash").isBlank()
                    || firstNonBlank(stringValue(anchor, "variantId"), stringValue(anchor, "variant")).isBlank()
                    || !anchor.has("rawSize") || !anchor.get("rawSize").isJsonObject()) {
                throw new IllegalArgumentException("CITY_CONFIGURED_STRUCTURE_FLOW_REMOVED");
            }
        }
    }

    private static <T extends Enum<T>> T enumValue(JsonObject source, String key, T fallback, Class<T> type) {
        String value = stringValue(source, key);
        if (value.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("D4_TEMPLATE_TRANSFORM_INVALID: " + key + "=" + value, ex);
        }
    }

    static void requireMatchingRunWorldIdentity(Path runDir, String currentDimensionId,
                                                long currentWorldSeed) throws IOException {
        Path contextPath = runDir.resolve("world_survey_context.json");
        if (!Files.isRegularFile(contextPath)) {
            throw new IllegalArgumentException("CITY_RUN_WORLD_IDENTITY_SOURCE_MISSING: " + contextPath);
        }
        JsonObject context;
        try {
            JsonElement root = JsonParser.parseString(Files.readString(contextPath));
            if (!root.isJsonObject()) {
                throw new IllegalArgumentException("world_survey_context root must be an object");
            }
            context = root.getAsJsonObject();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("CITY_RUN_WORLD_IDENTITY_INVALID: " + contextPath, ex);
        }
        String expectedDimensionId = stringValue(context, "dimensionId").trim();
        String expectedWorldSeed = stringValue(context, "worldSeed").trim();
        if (expectedDimensionId.isBlank() || expectedWorldSeed.isBlank()) {
            throw new IllegalArgumentException("CITY_RUN_WORLD_IDENTITY_INVALID: dimensionId and worldSeed are required in "
                    + contextPath);
        }
        long parsedWorldSeed;
        try {
            parsedWorldSeed = Long.parseLong(expectedWorldSeed);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("CITY_RUN_WORLD_IDENTITY_INVALID: worldSeed must be a signed long in "
                    + contextPath, ex);
        }
        String actualDimensionId = currentDimensionId == null ? "" : currentDimensionId.trim();
        if (!expectedDimensionId.equals(actualDimensionId) || parsedWorldSeed != currentWorldSeed) {
            throw new IllegalArgumentException("CITY_RUN_WORLD_IDENTITY_MISMATCH: runDimension="
                    + expectedDimensionId + ", currentDimension=" + actualDimensionId
                    + ", runWorldSeed=" + parsedWorldSeed + ", currentWorldSeed=" + currentWorldSeed);
        }
    }

    private static JsonObject loadCitySeedForD4(Path runDir, String runId, String citySeedId) throws IOException {
        JsonObject seed = loadCitySeed(runDir, runId, citySeedId);
        Path packagePath = d3PackagePath(runDir, citySeedId);
        if (!Files.isRegularFile(packagePath)) {
            throw new IllegalArgumentException("D3 package not found. Run city_plan_d3 first: " + packagePath);
        }
        requireD3SiteDecision(runDir, seed, citySeedId);
        return seed;
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
                WorldMutationReport.SCHEMA,
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
        if (!CityStructureMaterializationPlanner.PLAN_SCHEMA.equals(
                stringValue(materializationPlan, "schema"))) {
            throw new IllegalArgumentException("CITY_CONFIGURED_STRUCTURE_FLOW_REMOVED");
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
                    || !item.has("actualFootprint") || !item.has("lockedActualFootprint")
                    || !item.has("lockedCollisionEnvelope") || !item.has("collisionEnvelope")
                    || !item.has("maskEnvelope") || !item.has("ownerChunks")
                    || !item.get("ownerChunks").isJsonArray()
                    || stringValue(item, "templateId").isBlank()
                    || stringValue(item, "templateRef").isBlank()
                    || stringValue(item, "templateHash").isBlank()
                    || stringValue(item, "variantId").isBlank()
                    || !item.has("rawSize") || !item.get("rawSize").isJsonObject()) {
                throw new IllegalArgumentException("D6 planned structure is not fully locked: "
                        + stringValue(item, "anchorId"));
            }
            for (String removed : List.of("pieceBoxes", "startSignature", "expectedStartSignature",
                    "lockedBBoxGroupKey", "actualBBoxGroupKey", "selectedEnvelopeGroupKey")) {
                if (item.has(removed)) {
                    throw new IllegalArgumentException("CITY_CONFIGURED_STRUCTURE_FLOW_REMOVED: " + removed);
                }
            }
            String templateDatumPolicy = stringValue(item, "templateDatumPolicy");
            if (!CityTemplateTerrainPosePolicy.STRUCTURE_START_BEARD_THIN.equals(
                    stringValue(item, "terrainPosePolicy"))
                    || !isSupportedTemplateDatumPolicy(templateDatumPolicy)) {
                throw new IllegalArgumentException("D6 template placement is missing a supported datum policy: "
                        + stringValue(item, "anchorId"));
            }
            BlockBounds body = bounds(item.getAsJsonObject("actualFootprint"));
            BlockBounds lockedBody = bounds(item.getAsJsonObject("lockedActualFootprint"));
            BlockBounds collision = bounds(item.getAsJsonObject("collisionEnvelope"));
            BlockBounds lockedCollision = bounds(item.getAsJsonObject("lockedCollisionEnvelope"));
            BlockBounds mask = bounds(item.getAsJsonObject("maskEnvelope"));
            if (!body.equals(lockedBody) || !collision.equals(lockedCollision)
                    || !contains(collision, body) || !contains(mask, collision)
                    || !ownerChunksMatch(body, item.getAsJsonArray("ownerChunks"))) {
                throw new IllegalArgumentException("D6_TEMPLATE_GEOMETRY_LAYER_INVALID: "
                        + stringValue(item, "anchorId"));
            }
        }
    }

    private static boolean contains(BlockBounds outer, BlockBounds inner) {
        return outer.minX() <= inner.minX() && outer.minZ() <= inner.minZ()
                && outer.maxX() >= inner.maxX() && outer.maxZ() >= inner.maxZ();
    }

    private static boolean ownerChunksMatch(BlockBounds body, JsonArray supplied) {
        Set<String> expected = new LinkedHashSet<>();
        for (int x = Math.floorDiv(body.minX(), 16); x <= Math.floorDiv(body.maxX(), 16); x++) {
            for (int z = Math.floorDiv(body.minZ(), 16); z <= Math.floorDiv(body.maxZ(), 16); z++) {
                expected.add(x + ":" + z);
            }
        }
        Set<String> actual = new LinkedHashSet<>();
        for (JsonElement element : supplied) {
            if (!element.isJsonObject()) {
                return false;
            }
            JsonObject chunk = element.getAsJsonObject();
            if (!chunk.has("x") || !chunk.has("z")
                    || !actual.add(chunk.get("x").getAsInt() + ":" + chunk.get("z").getAsInt())) {
                return false;
            }
        }
        return expected.equals(actual);
    }

    private static boolean isSupportedTemplateDatumPolicy(String policy) {
        return CityStructureMaterializationPlanner.TEMPLATE_DATUM_POLICY_GENERATOR_BASE_HEIGHT.equals(policy);
    }

    static void validateFootprintsWithinWallReservation(Path wallReservationPath,
                                                        JsonObject source,
                                                        String arrayKey,
                                                        String stage) throws IOException {
        if (wallReservationPath == null || !Files.exists(wallReservationPath)) {
            return;
        }
        JsonObject reservation = JsonParser.parseString(Files.readString(wallReservationPath)).getAsJsonObject();
        if (!CityWallReservationPlanner.SCHEMA.equals(stringValue(reservation, "schema", ""))) {
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
            throw new IllegalArgumentException("D5_LOCKED_FOOTPRINT_OUTSIDE_RESERVATION: " + stage
                    + " footprint for " + stringValue(item, "anchorId", "unknown_anchor")
                    + " exceeds D5 reservation coverage. Return to D5 and expand/rescan reservation.");
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

    private static JsonObject terrainAdaptationReport(JsonObject placedLedger) {
        JsonObject report = new JsonObject();
        report.addProperty("schema", "city_terrain_adaptation_report");
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
            item.addProperty("templateId", stringValue(source, "templateId"));
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

    private static Path d3PackagePath(Path runDir, String citySeedId) {
        return cityStageDir(runDir, citySeedId, CityTestRunLayout.D3)
                .resolve("city_landform_review_package.json");
    }

    private static Path d3SiteDecisionPath(Path runDir, String citySeedId) {
        return cityStageDir(runDir, citySeedId, CityTestRunLayout.D3)
                .resolve("city_site_review_decision.json");
    }

    private static boolean requiresD3SiteReview(JsonObject seed) {
        if (!"capital".equals(stringValue(seed, "role"))) {
            return false;
        }
        JsonObject source = seed.has("source") && seed.get("source").isJsonObject()
                ? seed.getAsJsonObject("source") : null;
        return source != null
                && "ai_candidate_selection".equals(stringValue(source, "siteSelectionMode"));
    }

    private static void requireD3SiteDecision(Path runDir, JsonObject seed, String citySeedId)
            throws IOException {
        if (!requiresD3SiteReview(seed)) {
            return;
        }
        Path packagePath = d3PackagePath(runDir, citySeedId);
        Path decisionPath = d3SiteDecisionPath(runDir, citySeedId);
        if (!Files.isRegularFile(decisionPath)) {
            throw new IllegalArgumentException("CITY_D3_SITE_REVIEW_REQUIRED: review the D3 site before D4");
        }
        JsonObject review = JsonParser.parseString(Files.readString(decisionPath)).getAsJsonObject();
        String packageIdentity = sha256(Files.readString(packagePath));
        String seedIdentity = sha256(CityJson.GSON.toJson(seed));
        if (!packageIdentity.equals(stringValue(review, "d3PackageIdentity"))
                || !seedIdentity.equals(stringValue(review, "citySeedIdentity"))) {
            throw new IllegalArgumentException("CITY_D3_SITE_REVIEW_STALE");
        }
        String decision = stringValue(review, "decision");
        if ("reselect_required".equals(decision)) {
            throw new IllegalArgumentException("CITY_D3_SITE_RESELECTION_REQUIRED: return to realm_t4 selection");
        }
        if (!"accept_selected_site".equals(decision)) {
            throw new IllegalArgumentException("CITY_D3_SITE_REVIEW_DECISION_INVALID");
        }
    }

    private static CityLandformReviewPackage loadD3Package(Path debugRoot, Path runDir, String citySeedId)
            throws IOException {
        Path d3PackagePath = d3PackagePath(runDir, citySeedId);
        if (!Files.exists(d3PackagePath)) {
            throw new IllegalArgumentException("D3 package not found. Run city_plan_d3 first: "
                    + debugRef(debugRoot, d3PackagePath));
        }
        String runId = runDir.getFileName().toString();
        loadCitySeedForD4(runDir, runId, citySeedId);
        return CityLandformReviewPackage.fromJson(
                CityBlueprintCompilerService.normalizeLegacySchemasForRead(
                        JsonParser.parseString(Files.readString(d3PackagePath)).getAsJsonObject()));
    }

    private static Path d4SessionDir(Path runDir, String citySeedId) {
        return cityStageDir(runDir, citySeedId, CityTestRunLayout.D4_CANDIDATE_SESSION);
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
        return cityStageDir(runDir, citySeedId, CityTestRunLayout.D4_CANDIDATES)
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
        return cityStageDir(runDir, citySeedId, CityTestRunLayout.D4_STRUCTURE_CLUSTER_GROUPS)
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
                ? cityStageDir(runDir, citySeedId, CityTestRunLayout.D4).resolve("structure_anchor_map.json")
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
        return cityStageDir(runDir, citySeedId, CityTestRunLayout.D4).resolve("structure_anchor_plan.json");
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

    private static BlockBounds wallSurfaceBackfillBounds(JsonObject wallReservationPlan) {
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

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", ex);
        }
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

    static JsonObject loadOrCreateTestRunManifest(CityTestRunLayout layout,
                                                   Path runDir,
                                                   String runId,
                                                   String citySeedId,
                                                   JsonObject citySeedSnapshot,
                                                   JsonObject request,
                                                   Path debugRoot) throws IOException {
        Path manifestPath = layout.manifestPath();
        JsonObject manifest;
        if (Files.isRegularFile(manifestPath)) {
            manifest = CityBlueprintCompilerService.normalizeLegacySchemasForRead(
                    JsonParser.parseString(Files.readString(manifestPath)).getAsJsonObject());
            if (!"city_test_run_manifest".equals(stringValue(manifest, "schema", ""))
                    || !runId.equals(stringValue(manifest, "runId", ""))
                    || !citySeedId.equals(stringValue(manifest, "citySeedId", ""))) {
                throw new IllegalArgumentException("CITY_TEST_RUN_MANIFEST_IDENTITY_MISMATCH: " + manifestPath);
            }
            if (!manifest.has("attempts") || !manifest.get("attempts").isJsonArray()) {
                throw new IllegalArgumentException("CITY_TEST_RUN_MANIFEST_ATTEMPTS_INVALID: " + manifestPath);
            }
        } else {
            manifest = new JsonObject();
            manifest.addProperty("schema", "city_test_run_manifest");
            manifest.addProperty("testRunId", layout.testRunId(runId, citySeedId));
            manifest.addProperty("runId", runId);
            manifest.addProperty("citySeedId", citySeedId);
            manifest.addProperty("workflowMode", "city_structure_landing_fast_loop");
            manifest.addProperty("createdAt", Instant.now().toString());
            manifest.add("attempts", new JsonArray());
            Path worldManifestPath = runDir.resolve("world_survey_manifest.json");
            if (Files.isRegularFile(worldManifestPath)) {
                JsonObject worldManifest = JsonParser.parseString(Files.readString(worldManifestPath))
                        .getAsJsonObject();
                JsonObject worldIdentity = worldManifest.has("config")
                        && worldManifest.get("config").isJsonObject()
                        ? worldManifest.getAsJsonObject("config").deepCopy() : new JsonObject();
                worldIdentity.addProperty("worldSurveyManifest", debugRef(debugRoot, worldManifestPath));
                manifest.add("worldIdentity", worldIdentity);
            } else {
                manifest.add("worldIdentity", new JsonObject());
            }
            manifest.add("citySeedSnapshot", citySeedSnapshot.deepCopy());
        }
        manifest.add("latestRequest", request.deepCopy());
        manifest.addProperty("updatedAt", Instant.now().toString());
        return manifest;
    }

    private static JsonObject standaloneRequest(String toolName, String runId, String citySeedId) {
        JsonObject request = new JsonObject();
        request.addProperty("toolName", toolName);
        request.addProperty("runId", runId);
        request.addProperty("citySeedId", citySeedId);
        return request;
    }

    private static JsonObject recordStandaloneTestRunState(Path debugRoot,
                                                            String runId,
                                                            String citySeedId,
                                                            JsonObject request,
                                                            String status,
                                                            String nextAction,
                                                            JsonObject response) throws IOException {
        Path runDir = debugRoot.resolve(runId);
        CityTestRunLayout layout = CityTestRunLayout.open(runDir, citySeedId);
        if (layout.legacy()) {
            return response;
        }
        JsonObject seed = loadCitySeed(runDir, runId, citySeedId);
        JsonObject manifest = loadOrCreateTestRunManifest(layout, runDir, runId, citySeedId,
                seed, request, debugRoot);
        JsonObject artifacts = response.has("artifacts") && response.get("artifacts").isJsonObject()
                ? response.getAsJsonObject("artifacts") : new JsonObject();
        artifacts.addProperty("testRunManifest", debugRef(debugRoot, layout.manifestPath()));
        artifacts.addProperty("testRunPackage", debugRef(debugRoot, layout.packageDirectory()));
        response.add("artifacts", artifacts);
        JsonObject state = new JsonObject();
        state.addProperty("status", status);
        state.addProperty("nextAction", nextAction);
        state.add("artifacts", artifacts.deepCopy());
        writeTestRunState(layout.manifestPath(), manifest, state);
        return response;
    }

    static void writeTestRunState(Path reportPath, JsonObject manifest, JsonObject attempt)
            throws IOException {
        if (manifest == null) {
            Files.writeString(reportPath, CityJson.GSON.toJson(attempt));
            return;
        }
        String status = stringValue(attempt, "status", "running");
        manifest.addProperty("status", status);
        manifest.addProperty("updatedAt", Instant.now().toString());
        if (hasValue(attempt, "nextAction")) {
            manifest.addProperty("nextAction", stringValue(attempt, "nextAction", ""));
        } else {
            String nextAction = defaultTestRunNextAction(status);
            if (nextAction.isBlank()) {
                manifest.remove("nextAction");
            } else {
                manifest.addProperty("nextAction", nextAction);
            }
        }
        if (attempt.has("artifacts") && attempt.get("artifacts").isJsonObject()) {
            manifest.add("artifacts", attempt.getAsJsonObject("artifacts").deepCopy());
        }
        writePlanningCompletion(reportPath, manifest);
    }

    private static String defaultTestRunNextAction(String status) {
        return switch (status) {
            case "waiting_for_confirmation", "waiting_for_worldgen" -> "city_run_workflow";
            case "failed" -> "inspect_failed_attempt";
            default -> "";
        };
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


    private record RunMetadata(int cellStepBlocks, String dimensionId) {
    }


    record MinecraftServerHolder(net.minecraft.server.MinecraftServer server) {
        <T> T callOnServerThread(Callable<T> action) throws Exception {
            if (server.isSameThread()) {
                return action.call();
            }
            CompletableFuture<T> future = new CompletableFuture<>();
            server.execute(() -> {
                try {
                    future.complete(action.call());
                } catch (Exception ex) {
                    future.completeExceptionally(ex);
                }
            });
            try {
                return future.get();
            } catch (ExecutionException ex) {
                Throwable cause = ex.getCause();
                if (cause instanceof Exception exception) {
                    throw exception;
                }
                throw new RuntimeException(cause);
            }
        }
    }
}
