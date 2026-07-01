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
import com.rinsing.geomantia.systems.city.infrastructure.world.CityWallPlacementBackend;
import com.rinsing.geomantia.systems.city.infrastructure.world.MinecraftCityStructureMaterializationBackend;
import com.rinsing.geomantia.systems.city.infrastructure.world.MinecraftCityStructureEnvelopeSampler;
import com.rinsing.geomantia.systems.city.infrastructure.world.MinecraftCityWorldgenStatusInspector;
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
import java.util.Comparator;
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
        if (!Files.exists(planPath)) {
            rejectLegacyArtifacts(d6Dir, "D6");
            throw new IllegalArgumentException("D6 artifacts not found. Run city_plan_d6 first: "
                    + debugRef(debugRoot, d6Dir));
        }
        JsonObject materializationPlan = JsonParser.parseString(Files.readString(planPath)).getAsJsonObject();
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
                        materializationPlan, outputDirectory);

        JsonObject response = result.asJson();
        JsonObject artifacts = new JsonObject();
        artifacts.addProperty("placedStructureLedger", debugRef(debugRoot, ledgerPath));
        artifacts.addProperty("structureMaterializationTrace", debugRef(debugRoot, tracePath));
        artifacts.addProperty("inferredFunctionAreaMap", debugRef(debugRoot, inferredPath));
        artifacts.addProperty("placedStructurePreview", debugRef(debugRoot, previewPath));
        artifacts.addProperty("qualityReport", debugRef(debugRoot, qualityPath));
        artifacts.addProperty("sourceStructureMaterializationPlan", debugRef(debugRoot, planPath));
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
                CityWallPlanner.V3Options.defaults());
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
                maxFoundationDepthBlocks, maxSegmentHeightDeltaBlocks, CityWallPlanner.V3Options.defaults());
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
        Path runDir = debugRoot.resolve(runId);
        loadCitySeed(runDir, runId, citySeedId);
        Path d7Dir = runDir.resolve("city_d7_" + safeFileName(citySeedId));
        Path d5Dir = runDir.resolve("city_d5_" + safeFileName(citySeedId));
        Path ledgerPath = d7Dir.resolve("placed_structure_ledger.json");
        if (!Files.exists(ledgerPath)) {
            throw new IllegalArgumentException("D7 placed_structure_ledger.json not found. Run city_execute_d7 first: "
                    + debugRef(debugRoot, ledgerPath));
        }
        JsonObject ledger = JsonParser.parseString(Files.readString(ledgerPath)).getAsJsonObject();
        String normalizedWallVersion = CityWallReservationPlanner.normalizeWallVersion(wallVersion);
        JsonObject wallPlan;
        Path outputDirectory = runDir.resolve("city_walls_" + safeFileName(citySeedId));
        JsonObject actualRoadMask = null;
        Path roadMaskPath = outputDirectory.resolve("actual_road_mask.json");
        Path wallReservationPath = d5Dir.resolve("wall_reservation_plan.json");
        if (CityWallReservationPlanner.V1_DEBUG.equals(normalizedWallVersion)) {
            wallPlan = new CityWallPlanner().plan(ledger, wallMarginBlocks, segmentLengthBlocks, gateWidthBlocks);
        } else {
            if (!Files.exists(wallReservationPath)) {
                throw new IllegalArgumentException("wall_reservation_plan.json not found. Run city_plan_d5 with wallVersion=v2 first: "
                        + debugRef(debugRoot, wallReservationPath));
            }
            JsonObject wallReservationPlan = JsonParser.parseString(Files.readString(wallReservationPath))
                    .getAsJsonObject();
            actualRoadMask = new CityRoadMaskScanner().scan(level, wallReservationPlan, roadScanMarginBlocks);
            if (CityWallReservationPlanner.V3.equals(normalizedWallVersion)) {
                wallPlan = new CityWallPlanner().planV3(ledger, wallReservationPlan, actualRoadMask, gateWidthBlocks,
                        roadProtectionMarginBlocks, maxFoundationDepthBlocks, maxSegmentHeightDeltaBlocks,
                        wallV3Options);
            } else {
                wallPlan = new CityWallPlanner().planV2(ledger, wallReservationPlan, actualRoadMask, gateWidthBlocks,
                        roadProtectionMarginBlocks, maxFoundationDepthBlocks, maxSegmentHeightDeltaBlocks);
            }
        }
        Path planPath = new CityWallPlanner().writeArtifacts(wallPlan, outputDirectory);
        if (actualRoadMask != null) {
            Files.writeString(roadMaskPath, CityJson.GSON.toJson(actualRoadMask));
        }
        Path previewPath = new CityWallPreviewRenderer().render(wallPlan, ledger, outputDirectory);
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
        if (Files.exists(wallReservationPath)) {
            artifacts.addProperty("sourceWallReservationPlan", debugRef(debugRoot, wallReservationPath));
        }
        if (actualRoadMask != null) {
            artifacts.addProperty("actualRoadMask", debugRef(debugRoot, roadMaskPath));
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
        if (!booleanValue(roadProviderState, "useWorldEditDebugFallback", true)) {
            JsonObject skipped = new JsonObject();
            skipped.addProperty("schemaVersion", "city_deferred_road_postprocess_report.v0.1");
            skipped.addProperty("status", "skipped");
            skipped.addProperty("reasonCode", "ROADWEAVER_REGISTERED");
            skipped.addProperty("message", "RoadWeaver owns road generation; WorldEdit debug road fallback skipped.");
            skipped.addProperty("roadPostprocessSource", "roadweaver");
            skipped.addProperty("boundarySource", "actual_footprint_union");
            skipped.add("roadProviderState", roadProviderState.deepCopy());
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
        obj.addProperty("useWorldEditDebugFallback", true);
        obj.addProperty("reasonCode", "ROAD_PROVIDER_STATE_MISSING");
        obj.addProperty("message", "Missing D5 road provider state; using legacy WorldEdit debug fallback.");
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

    private static CityLandformReviewPackage loadD3Package(Path debugRoot, Path runDir, String citySeedId)
            throws IOException {
        Path d3PackagePath = runDir.resolve("city_d3_" + safeFileName(citySeedId))
                .resolve("city_landform_review_package.json");
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

    private static String safeFileName(String raw) {
        return raw.replaceAll("[^A-Za-z0-9._-]", "_");
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

    private record RunMetadata(int cellStepBlocks, String dimensionId) {
    }

    private record CityInputs(FunctionZoneMap zoneMap, BuildableAreaMap buildableAreaMap,
                              Path zoneMapPath, Path terrainStatsPath, Path buildablePath) {
    }

    record MinecraftServerHolder(net.minecraft.server.MinecraftServer server) {
    }
}
