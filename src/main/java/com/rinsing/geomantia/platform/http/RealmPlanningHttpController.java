package com.rinsing.geomantia.platform.http;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.gis.GisClassifierConfig;
import com.rinsing.geomantia.systems.gis.GisSampleConfig;
import com.rinsing.geomantia.systems.gis.adapter.minecraft.MinecraftPriorAtlasSampler;
import com.rinsing.geomantia.systems.gis.application.refresh.SampleMode;
import com.rinsing.geomantia.systems.gis.application.sample.AtlasSampler;
import com.rinsing.geomantia.systems.city.application.CityWallPlanner;
import com.rinsing.geomantia.systems.city.application.CityWallReservationPlanner;
import com.rinsing.geomantia.systems.city.application.CityTestRunLayout;
import com.rinsing.geomantia.systems.city.domain.blueprint.CityBlueprint;
import com.rinsing.geomantia.systems.city.infrastructure.world.CityWorldgenBlockObservationRegistry;
import com.rinsing.geomantia.platform.RealmPlanningServices;
import com.rinsing.geomantia.platform.WorldSurveyChatProgress;
import com.rinsing.geomantia.systems.realm_planning.RealmPlanningService;
import com.rinsing.geomantia.systems.realm_planning.PatchExplorerService;
import com.rinsing.geomantia.systems.realm_planning.RealmT4PatchPlanningService;
import com.rinsing.geomantia.systems.realm_planning.WorldSurveyResult;
import com.rinsing.geomantia.systems.realm_planning.WorldSurveyRunner;
import com.rinsing.geomantia.systems.realm_planning.adapter.minecraft.MinecraftTerrainPreviewProviderFactory;
import com.rinsing.geomantia.systems.realm_planning.application.terrain.PatchCandidateTerrainPreviewService;
import com.rinsing.geomantia.systems.realm_planning.application.terrain.RealmT4CoarseTerrainPreviewService;
import com.rinsing.geomantia.systems.realm_planning.application.terrain.TerrainScalePatchService;
import com.rinsing.geomantia.systems.realm_planning.application.terrain.TerrainPreviewAtlasSampler;
import com.rinsing.geomantia.systems.realm_planning.application.terrain.TerrainPreviewProviderSelection;
import com.rinsing.geomantia.systems.realm_planning.application.terrain.TerrainSamplingProvenance;
import com.rinsing.geomantia.systems.realm_planning.application.access.PlanningAreaAccessConfig;
import com.rinsing.geomantia.systems.city.application.queue.CityDesignQueue;
import com.sun.net.httpserver.HttpExchange;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

final class RealmPlanningHttpController implements AutoCloseable {
    private final MinecraftServer server;
    private final RealmPlanningService realmPlanningService;
    private final CityDesignQueue cityDesignQueue;
    private final CityPostD4AutoCompileQueue postD4AutoCompileQueue;

    RealmPlanningHttpController(MinecraftServer server) {
        this.server = server;
        this.realmPlanningService = RealmPlanningServices.forServer(server);
        this.cityDesignQueue = new CityDesignQueue(debugRoot(), cityDesignQueueConfigPath());
        this.postD4AutoCompileQueue = new CityPostD4AutoCompileQueue(debugRoot(), this::runPostD4AutoCompile,
                cityDesignQueue::onPostD4State);
    }

    void handleStatus(HttpExchange exchange) {
        handle(exchange, "GET", () -> callOnServerThread(realmPlanningService::status));
    }

    void handleWRefresh(HttpExchange exchange) {
        handle(exchange, "POST", () -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            PreparedWorldSurvey prepared = callOnServerThread(() -> prepareWorldSurvey(request));
            WorldSurveyExecution execution = runWorldSurvey(prepared);
            return callOnServerThread(() -> {
                JsonObject response = realmPlanningService.runW(execution.result(), request.get("worldTheme"));
                response.add("terrainProvider", execution.terrainProvider().asJson());
                response.addProperty("executionMode", "api_worker_complete_scan");
                response.addProperty("serverThreadBlocked", false);
                if (booleanValue(request, "runTagAudit", false)) {
                    JsonObject audit = realmPlanningService.runTagAudit(execution.result().runId(), execution.sampler(),
                            intValue(request, "tagAuditSampleCount", 120),
                            intValue(request, "tagAuditRadiusBlocks", 32),
                            intValue(request, "tagAuditStrideBlocks", 4),
                            intValue(request, "tagAuditSlopeRadiusBlocks", 4),
                            stringValue(request, "tagAuditSampleSeed", ""));
                    response.add("tagAuditReport", audit.getAsJsonObject("tagAuditReport"));
                    response.add("artifacts", audit.getAsJsonObject("artifacts"));
                }
                return response;
            });
        });
    }

    void handleT1Prepare(HttpExchange exchange) {
        handle(exchange, "POST", () -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            return callOnServerThread(() -> realmPlanningService.prepareT1(
                    requiredString(request, "runId"),
                    arrayValue(request, "realmProfiles"),
                    intValue(request, "realmCount", 3),
                    stringValue(request, "targetContinentId", ""),
                    booleanValue(request, "allowAiDraftProfile", true)));
        });
    }

    void handleT2SelectCoordinate(HttpExchange exchange) {
        handle(exchange, "POST", () -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            String runId = requiredString(request, "runId");
            String realmId = requiredString(request, "realmId");
            int gridX;
            int gridZ;
            String selectionRef = stringValue(request, "patchSelectionRef", "");
            if (!selectionRef.isBlank()) {
                JsonObject selection = new PatchExplorerService(debugRoot())
                        .resolveT2Selection(runId, realmId, selectionRef);
                JsonObject anchor = selection.getAsJsonObject("suggestedAnchor");
                int worldSurveyStep = restoredRunCellStepBlocks(runId);
                gridX = Math.floorDiv(anchor.get("blockX").getAsInt(), worldSurveyStep);
                gridZ = Math.floorDiv(anchor.get("blockZ").getAsInt(), worldSurveyStep);
            } else {
                if (!hasValue(request, "gridX") || !hasValue(request, "gridZ")) {
                    throw new IllegalArgumentException("gridX/gridZ or patchSelectionRef is required.");
                }
                gridX = intValue(request, "gridX", 0);
                gridZ = intValue(request, "gridZ", 0);
            }
            String reason = stringValue(request, "reason", "");
            if (!selectionRef.isBlank()) {
                reason = reason + (reason.isBlank() ? "" : "; ") + "patchSelectionRef=" + selectionRef;
            }
            String finalReason = reason;
            requireRealmCoreOutsideInitialActivityArea(runId, gridX, gridZ);
            return callOnServerThread(() -> realmPlanningService.selectT2(
                    runId,
                    realmId,
                    gridX,
                    gridZ,
                    arrayValue(request, "alternates"),
                    finalReason,
                    stringValue(request, "selectedBy", "ai"),
                    booleanValue(request, "allowSnap", true)));
        });
    }

    void handleT3Expand(HttpExchange exchange) {
        handle(exchange, "POST", () -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            return callOnServerThread(() -> realmPlanningService.expandT3(
                    requiredString(request, "runId"),
                    stringValue(request, "normalizationGroup", ""),
                    booleanValue(request, "allowUnclaimedLand", false),
                    stringValue(request, "qualityMode", "strict"),
                    stringValue(request, "expansionModel", "")));
        });
    }

    void handleT4BuildRegistry(HttpExchange exchange) {
        handle(exchange, "POST", () -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            String runId = requiredString(request, "runId");
            JsonObject response = callOnServerThread(() -> realmPlanningService.buildT4(runId));
            response.add("cityDesignQueue", cityDesignQueue.refresh(runId,
                    stringValue(request, "cityQueueOrderingMode", "")));
            return response;
        });
    }

    void handleT4PatchPlanningCreate(HttpExchange exchange) {
        handle(exchange, "POST", () -> realmT4PatchPlanningService()
                .create(GisHttpUtil.readJsonObject(exchange)));
    }

    void handleT4PatchPlanningSelectCapital(HttpExchange exchange) {
        handle(exchange, "POST", () -> realmT4PatchPlanningService()
                .selectCapital(GisHttpUtil.readJsonObject(exchange)));
    }

    void handleT4PatchPlanningAddCity(HttpExchange exchange) {
        handle(exchange, "POST", () -> realmT4PatchPlanningService()
                .add(GisHttpUtil.readJsonObject(exchange)));
    }

    void handleT4PatchPlanningFinalize(HttpExchange exchange) {
        handle(exchange, "POST", () -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            String runId = requiredString(request, "runId");
            JsonObject response = callOnServerThread(() -> realmT4PatchPlanningService().finalizePlanning(request));
            if (cityDesignQueue.registryCoversAllRealms(runId)) {
                response.add("cityDesignQueue", cityDesignQueue.refresh(runId,
                        stringValue(request, "cityQueueOrderingMode", "")));
            } else {
                response.addProperty("cityDesignQueueStatus", "awaiting_remaining_realms");
            }
            return response;
        });
    }

    void handleCityDesignQueueRefresh(HttpExchange exchange) {
        handle(exchange, "POST", () -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            return cityDesignQueue.refresh(requiredString(request, "runId"),
                    stringValue(request, "orderingMode", ""));
        });
    }

    void handleCityDesignQueueStatus(HttpExchange exchange) {
        handle(exchange, "POST", () -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            return cityDesignQueue.status(requiredString(request, "runId"));
        });
    }

    void handlePatchExplorerOpen(HttpExchange exchange) {
        handle(exchange, "POST", () -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            String scopeType = stringValue(request, "scopeType", "").toLowerCase(java.util.Locale.ROOT);
            TerrainPreviewRuntime runtime = scopeType.startsWith("realm_t")
                    ? terrainPreviewRuntime(requiredString(request, "runId"),
                            booleanValue(request, "preferGeneratorNativeTerrain", true),
                            stringValue(request, "dimensionId", ""), stringValue(request, "playerName", ""))
                    : null;
            RealmT4CoarseTerrainPreviewService.Result terrainPreview = ensureRealmT4TerrainPreview(request, runtime);
            PatchExplorerService explorer = new PatchExplorerService(debugRoot());
            String terrainPatchProviderIdentity = runtime == null ? "" : String.join("|",
                    runtime.dimensionId(),
                    Boolean.toString(booleanValue(request, "preferGeneratorNativeTerrain", true)),
                    runtime.selection().providerId(), runtime.selection().sourceKind(),
                    Boolean.toString(runtime.selection().fastPath()), runtime.selection().fallbackReason(),
                    runtime.selection().sourceFingerprint(), runtime.selection().samplingSemantics());
            JsonObject response = runtime == null ? explorer.open(request)
                    : explorer.open(request, terrainPatchProviderIdentity,
                            (runId, refinedScopeType, scopeId, refinementIdentity, sourceCells) ->
                            new TerrainScalePatchService().analyze(runtime.dimensionId(), refinementIdentity,
                                    sourceCells, runtime.selection()));
            if (terrainPreview != null) {
                response.addProperty("terrainPreviewCacheHit", terrainPreview.cacheHit());
                response.add("terrainPreviewProvider",
                        terrainPreview.evidence().getAsJsonObject("provider").deepCopy());
                JsonObject artifacts = response.getAsJsonObject("artifacts");
                artifacts.addProperty("coarseTerrainEvidence",
                        relativeArtifact(debugRoot(), terrainPreview.evidencePath()));
                artifacts.addProperty("heightWaterPreview",
                        relativeArtifact(debugRoot(), terrainPreview.previewPath()));
            }
            return response;
        });
    }

    private RealmT4CoarseTerrainPreviewService.Result ensureRealmT4TerrainPreview(JsonObject request,
            TerrainPreviewRuntime runtime)
            throws Exception {
        if (!"realm_t4".equalsIgnoreCase(stringValue(request, "scopeType", ""))) {
            return null;
        }
        String runId = requiredString(request, "runId");
        String realmId = stringValue(request, "scopeId", stringValue(request, "realmId", ""));
        if (realmId.isBlank()) {
            throw new IllegalArgumentException("scopeId or realmId is required for realm_t4.");
        }
        if (runtime == null) {
            throw new IllegalArgumentException("PATCH_EXPLORER_TERRAIN_RUNTIME_REQUIRED");
        }
        return new RealmT4CoarseTerrainPreviewService(debugRoot()).ensure(
                runId, realmId, runtime.dimensionId(), runtime.selection());
    }

    void handlePatchExplorerShowCandidates(HttpExchange exchange) {
        handle(exchange, "POST", () -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            PatchExplorerService explorer = new PatchExplorerService(debugRoot());
            PatchExplorerService.SessionTerrainContext context = explorer.sessionTerrainContext(request);
            TerrainPreviewRuntime runtime = terrainPreviewRuntime(requiredString(request, "runId"),
                    context.preferGeneratorNativeTerrain(), "", "");
            PatchCandidateTerrainPreviewService service =
                    new PatchCandidateTerrainPreviewService(debugRoot());
            return explorer.showCandidates(request, (runId, realmId, scopeIdentity, target, level) ->
                    service.ensure(runId, realmId, runtime.dimensionId(), scopeIdentity,
                            target, level, runtime.selection()));
        });
    }

    void handlePatchExplorerSelectCandidate(HttpExchange exchange) {
        handle(exchange, "POST", () -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            PatchExplorerService explorer = new PatchExplorerService(debugRoot());
            PatchExplorerService.SessionTerrainContext context = explorer.sessionTerrainContext(request);
            TerrainPreviewRuntime runtime = terrainPreviewRuntime(requiredString(request, "runId"),
                    context.preferGeneratorNativeTerrain(), "", "");
            PatchCandidateTerrainPreviewService service =
                    new PatchCandidateTerrainPreviewService(debugRoot());
            return explorer.selectCandidate(request, (runId, realmId, scopeIdentity, target, level) ->
                    service.ensure(runId, realmId, runtime.dimensionId(), scopeIdentity,
                            target, level, runtime.selection()));
        });
    }

    private TerrainPreviewRuntime terrainPreviewRuntime(String runId, boolean preferGeneratorNative,
            String requestedDimensionId, String playerName) throws Exception {
        return callOnServerThread(() -> {
            ServerPlayer player = resolvePlayer(playerName);
            String dimensionId = requestedDimensionId;
            if (dimensionId.isBlank()) {
                dimensionId = restoredRunDimensionId(runId);
            }
            ServerLevel level = resolveLevel(dimensionId, player);
            String normalizedDimension = level.dimension().location().toString();
            String fallbackFingerprint = String.join("|", "minecraft_prior", normalizedDimension,
                    Long.toString(level.getSeed()), level.getChunkSource().getGenerator().getClass().getName());
            TerrainPreviewProviderSelection selection = MinecraftTerrainPreviewProviderFactory
                    .createSelector(level, new MinecraftPriorAtlasSampler(level), fallbackFingerprint)
                    .select(preferGeneratorNative);
            return new TerrainPreviewRuntime(normalizedDimension, selection);
        });
    }

    void handleAcceptance(HttpExchange exchange) {
        handle(exchange, "POST", () -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            return callOnServerThread(() -> {
                WorldSurveyExecution execution = runWorldSurvey(request);
                JsonObject response = realmPlanningService.runAcceptance(execution.result(),
                        intValue(request, "realmCount", 3), arrayValue(request, "realmProfiles"),
                        booleanValue(request, "autoSelectCoordinates", true),
                        stringValue(request, "qualityMode", "strict"),
                        stringValue(request, "expansionModel", ""));
                response.add("terrainProvider", execution.terrainProvider().asJson());
                if (booleanValue(request, "runTagAudit", false)) {
                    JsonObject audit = realmPlanningService.runTagAudit(execution.result().runId(), execution.sampler(),
                            intValue(request, "tagAuditSampleCount", 120),
                            intValue(request, "tagAuditRadiusBlocks", 32),
                            intValue(request, "tagAuditStrideBlocks", 4),
                            intValue(request, "tagAuditSlopeRadiusBlocks", 4),
                            stringValue(request, "tagAuditSampleSeed", ""));
                    response.add("tagAuditReport", audit.getAsJsonObject("tagAuditReport"));
                    response.add("artifacts", audit.getAsJsonObject("artifacts"));
                }
                return response;
            });
        });
    }

    void handleCityPlanD2(HttpExchange exchange) {
        handle(exchange, "POST", () -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            String runId = requiredString(request, "runId");
            String citySeedId = requiredString(request, "citySeedId");
            cityDesignQueue.requireCurrentIfManaged(runId, citySeedId);
            Integer cellStepBlocks = hasValue(request, "cellStepBlocks")
                    ? intValue(request, "cellStepBlocks", 4)
                    : null;
            return CityPlanningEndpointHandler.handlePlanD2(debugRoot(), runId, citySeedId, cellStepBlocks);
        });
    }

    void handleCityPlanD3(HttpExchange exchange) {
        handle(exchange, "POST", () -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            String runId = requiredString(request, "runId");
            String citySeedId = requiredString(request, "citySeedId");
            cityDesignQueue.requireCurrentIfManaged(runId, citySeedId);
            Integer cellStepBlocks = hasValue(request, "cellStepBlocks")
                    ? intValue(request, "cellStepBlocks", 4)
                    : null;
            String playerName = stringValue(request, "playerName", "");
            String dimensionId = stringValue(request, "dimensionId", "");
            if (dimensionId.isBlank()) {
                dimensionId = restoredRunDimensionId(runId);
            }
            String resolvedDimensionId = dimensionId;
            ServerLevel level = callOnServerThread(() -> resolveLevel(
                    resolvedDimensionId, resolvePlayer(playerName)));
            return CityPlanningEndpointHandler.handlePlanD3(debugRoot(), runId, citySeedId,
                    cellStepBlocks,
                    hasValue(request, "patchScanPaddingBlocks")
                            ? intValue(request, "patchScanPaddingBlocks",
                            CityPlanningEndpointHandler.DEFAULT_D3_PATCH_SCAN_PADDING_BLOCKS)
                            : null,
                    booleanValue(request, "preferGeneratorNativeTerrain", true),
                    level);
        });
    }

    void handleCityReviewD3Site(HttpExchange exchange) {
        handle(exchange, "POST", () -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            cityDesignQueue.requireCurrentIfManaged(requiredString(request, "runId"),
                    requiredString(request, "citySeedId"));
            return CityPlanningEndpointHandler.handleReviewD3Site(debugRoot(),
                    requiredString(request, "runId"),
                    requiredString(request, "citySeedId"),
                    requiredString(request, "decision"),
                    stringValue(request, "decisionReason", ""),
                    stringValue(request, "reviewedBy", "ai"));
        });
    }

    void handleCityPrepareD4BlueprintContext(HttpExchange exchange) {
        handle(exchange, "POST", () -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            cityDesignQueue.requireCurrentIfManaged(requiredString(request, "runId"),
                    requiredString(request, "citySeedId"));
            return CityPlanningEndpointHandler.handlePrepareD4BlueprintContext(debugRoot(),
                    requiredString(request, "runId"), requiredString(request, "citySeedId"),
                    requiredObject(request, "terrasenseProfileSource"),
                    requiredObject(request, "templateCatalogSource"),
                    requiredObject(request, "blueprintReferenceCatalog"));
        });
    }

    void handleCitySubmitD4Blueprint(HttpExchange exchange) {
        handle(exchange, "POST", () -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            String runId = requiredString(request, "runId");
            String citySeedId = requiredString(request, "citySeedId");
            cityDesignQueue.requireCurrentIfManaged(runId, citySeedId);
            JsonObject response = CityPlanningEndpointHandler.handleSubmitD4Blueprint(debugRoot(),
                    runId, citySeedId, requiredString(request, "contextId"),
                    requiredObject(request, "cityBlueprint"));
            if (booleanValue(response, "ok", false)
                    && booleanValue(request, "autoAdvanceAfterD4", true)) {
                response.add("postD4AutoCompile", postD4AutoCompileQueue.enqueue(runId, citySeedId));
            }
            return response;
        });
    }

    void handleCityPostD4AutoCompileStatus(HttpExchange exchange) {
        handle(exchange, "POST", () -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            return postD4AutoCompileQueue.status(requiredString(request, "runId"),
                    requiredString(request, "citySeedId"));
        });
    }

    void handleCityPostD4AutoCompileRetry(HttpExchange exchange) {
        handle(exchange, "POST", () -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            String runId = requiredString(request, "runId");
            String citySeedId = requiredString(request, "citySeedId");
            cityDesignQueue.requireCurrentIfManaged(runId, citySeedId);
            JsonObject response = new JsonObject();
            response.addProperty("ok", true);
            response.addProperty("operation", "city_post_d4_auto_compile_retry");
            response.add("postD4AutoCompile", postD4AutoCompileQueue.enqueue(runId, citySeedId));
            return response;
        });
    }

    private JsonObject runPostD4AutoCompile(String runId, String citySeedId) throws Exception {
        String dimensionId = restoredRunDimensionId(runId);
        ServerLevel level = callOnServerThread(() -> resolveLevel(dimensionId, null));
        JsonObject request = postD4AutoCompileWorkflowRequest(runId, citySeedId);
        return CityPlanningEndpointHandler.handleRunWorkflow(debugRoot(),
                server.getWorldPath(LevelResource.ROOT), runId, citySeedId, request,
                new CityPlanningEndpointHandler.MinecraftServerHolder(server), level);
    }

    static JsonObject postD4AutoCompileWorkflowRequest(String runId, String citySeedId) {
        JsonObject request = new JsonObject();
        request.addProperty("runId", runId);
        request.addProperty("citySeedId", citySeedId);
        request.addProperty("skipExisting", true);
        request.addProperty("confirmWorldMutation", true);
        request.addProperty("stopAfterActivation", true);
        request.addProperty("planWalls", false);
        request.addProperty("executeWalls", false);
        return request;
    }

    void handleCityCompileD4Blueprint(HttpExchange exchange) {
        handle(exchange, "POST", () -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            return CityPlanningEndpointHandler.handleCompileD4Blueprint(debugRoot(),
                    requiredString(request, "runId"), requiredString(request, "citySeedId"));
        });
    }

    void handleCityPlanD4(HttpExchange exchange) {
        handle(exchange, "POST", () -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            String runId = requiredString(request, "runId");
            String citySeedId = requiredString(request, "citySeedId");
            rejectLegacyCityFields(request, "patchGroupPlan", "zoneChoices", "functionType", "functionTag",
                    "function_candidates");
            if (!request.has("terrasenseProfileSource") || !request.get("terrasenseProfileSource").isJsonObject()) {
                throw new IllegalArgumentException("terrasenseProfileSource object is required.");
            }
            if (!request.has("structureAnchorPlan") || !request.get("structureAnchorPlan").isJsonObject()) {
                throw new IllegalArgumentException("structureAnchorPlan object is required.");
            }
            if (!request.has("templateCatalogSource") || !request.get("templateCatalogSource").isJsonObject()) {
                throw new IllegalArgumentException("templateCatalogSource object is required.");
            }
            return CityPlanningEndpointHandler.handlePlanD4(debugRoot(), runId, citySeedId,
                    request.getAsJsonObject("terrasenseProfileSource"),
                    request.getAsJsonObject("templateCatalogSource"),
                    request.getAsJsonObject("structureAnchorPlan"));
        });
    }

    void handleCityPlanD4Candidates(HttpExchange exchange) {
        handle(exchange, "POST", () -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            String runId = requiredString(request, "runId");
            String citySeedId = requiredString(request, "citySeedId");
            rejectLegacyCityFields(request, "patchGroupPlan", "zoneChoices", "functionType", "functionTag",
                    "function_candidates");
            if (!request.has("terrasenseProfileSource") || !request.get("terrasenseProfileSource").isJsonObject()) {
                throw new IllegalArgumentException("terrasenseProfileSource object is required.");
            }
            if (!request.has("designSlotPlan") || !request.get("designSlotPlan").isJsonObject()) {
                throw new IllegalArgumentException("designSlotPlan object is required.");
            }
            JsonObject designSlotPlan = new PatchExplorerService(debugRoot()).resolveD4DesignSlotPlan(
                    runId, citySeedId, request.getAsJsonObject("designSlotPlan"));
            return CityPlanningEndpointHandler.handlePlanD4Candidates(debugRoot(), runId, citySeedId,
                    request.getAsJsonObject("terrasenseProfileSource"),
                    designSlotPlan,
                    request.has("templateCatalogSource") && request.get("templateCatalogSource").isJsonObject()
                            ? request.getAsJsonObject("templateCatalogSource") : null);
        });
    }

    void handleCityPlanD4ArrayCandidates(HttpExchange exchange) {
        handle(exchange, "POST", () -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            String runId = requiredString(request, "runId");
            String citySeedId = requiredString(request, "citySeedId");
            rejectLegacyCityFields(request, "patchGroupPlan", "zoneChoices", "functionType", "functionTag",
                    "function_candidates");
            if (!request.has("terrasenseProfileSource") || !request.get("terrasenseProfileSource").isJsonObject()) {
                throw new IllegalArgumentException("terrasenseProfileSource object is required.");
            }
            if (!request.has("arrayCandidatePlan") || !request.get("arrayCandidatePlan").isJsonObject()) {
                throw new IllegalArgumentException("arrayCandidatePlan object is required.");
            }
            JsonObject arrayCandidatePlan = new PatchExplorerService(debugRoot()).resolveD4ArrayPlan(
                    runId, citySeedId, request.getAsJsonObject("arrayCandidatePlan"));
            return CityPlanningEndpointHandler.handlePlanD4ArrayCandidates(debugRoot(), runId, citySeedId,
                    request.getAsJsonObject("terrasenseProfileSource"),
                    arrayCandidatePlan,
                    request.has("templateCatalogSource") && request.get("templateCatalogSource").isJsonObject()
                            ? request.getAsJsonObject("templateCatalogSource") : null,
                    request.has("occupiedStructureAnchorMapSource")
                            && request.get("occupiedStructureAnchorMapSource").isJsonObject()
                            ? request.getAsJsonObject("occupiedStructureAnchorMapSource") : null,
                    request.has("occupiedEnvelopes") && request.get("occupiedEnvelopes").isJsonArray()
                            ? request.getAsJsonArray("occupiedEnvelopes") : new JsonArray());
        });
    }

    void handleCityCreateD4ArrayLayoutLoop(HttpExchange exchange) {
        handle(exchange, "POST", () -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            String runId = requiredString(request, "runId");
            String citySeedId = requiredString(request, "citySeedId");
            rejectLegacyCityFields(request, "patchGroupPlan", "zoneChoices", "functionType", "functionTag",
                    "function_candidates");
            if (!request.has("terrasenseProfileSource") || !request.get("terrasenseProfileSource").isJsonObject()) {
                throw new IllegalArgumentException("terrasenseProfileSource object is required.");
            }
            if (!request.has("arrayLayoutPlan") || !request.get("arrayLayoutPlan").isJsonObject()) {
                throw new IllegalArgumentException("arrayLayoutPlan object is required.");
            }
            JsonObject arrayLayoutPlan = new PatchExplorerService(debugRoot()).resolveD4ArrayLayoutPlan(
                    runId, citySeedId, request.getAsJsonObject("arrayLayoutPlan"));
            return CityPlanningEndpointHandler.handleCreateD4ArrayLayoutLoop(debugRoot(), runId, citySeedId,
                    request.getAsJsonObject("terrasenseProfileSource"),
                    arrayLayoutPlan,
                    request.has("templateCatalogSource") && request.get("templateCatalogSource").isJsonObject()
                            ? request.getAsJsonObject("templateCatalogSource") : null,
                    request.has("baseStructureAnchorPlanSource")
                            && request.get("baseStructureAnchorPlanSource").isJsonObject()
                            ? request.getAsJsonObject("baseStructureAnchorPlanSource") : null,
                    request.has("occupiedStructureAnchorMapSource")
                            && request.get("occupiedStructureAnchorMapSource").isJsonObject()
                            ? request.getAsJsonObject("occupiedStructureAnchorMapSource") : null);
        });
    }

    void handleCityCreateD4DesignLoopState(HttpExchange exchange) {
        handle(exchange, "POST", () -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            String runId = requiredString(request, "runId");
            String citySeedId = requiredString(request, "citySeedId");
            rejectLegacyCityFields(request, "patchGroupPlan", "zoneChoices", "functionType", "functionTag",
                    "function_candidates");
            JsonObject options = request.has("designLoopOptions") && request.get("designLoopOptions").isJsonObject()
                    ? request.getAsJsonObject("designLoopOptions") : request;
            return CityPlanningEndpointHandler.handleCreateD4DesignLoopState(debugRoot(), runId, citySeedId,
                    options,
                    request.has("baseStructureAnchorMapSource")
                            && request.get("baseStructureAnchorMapSource").isJsonObject()
                            ? request.getAsJsonObject("baseStructureAnchorMapSource") : null);
        });
    }

    void handleCityReadD4DesignLoopState(HttpExchange exchange) {
        handle(exchange, "POST", () -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            String runId = requiredString(request, "runId");
            String citySeedId = requiredString(request, "citySeedId");
            rejectLegacyCityFields(request, "patchGroupPlan", "zoneChoices", "functionType", "functionTag",
                    "function_candidates");
            return CityPlanningEndpointHandler.handleReadD4DesignLoopState(debugRoot(), runId, citySeedId,
                    request.has("designLoopStateSource") && request.get("designLoopStateSource").isJsonObject()
                            ? request.getAsJsonObject("designLoopStateSource") : null);
        });
    }

    void handleCityAppendD4DesignLoopRound(HttpExchange exchange) {
        handle(exchange, "POST", () -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            String runId = requiredString(request, "runId");
            String citySeedId = requiredString(request, "citySeedId");
            rejectLegacyCityFields(request, "patchGroupPlan", "zoneChoices", "functionType", "functionTag",
                    "function_candidates");
            if (!request.has("designLoopRound") || !request.get("designLoopRound").isJsonObject()) {
                throw new IllegalArgumentException("designLoopRound object is required.");
            }
            return CityPlanningEndpointHandler.handleAppendD4DesignLoopRound(debugRoot(), runId, citySeedId,
                    stringValue(request, "stateId", ""),
                    request.getAsJsonObject("designLoopRound"),
                    request.has("designLoopStateSource") && request.get("designLoopStateSource").isJsonObject()
                            ? request.getAsJsonObject("designLoopStateSource") : null);
        });
    }

    void handleCityWriteD4DesignLoopState(HttpExchange exchange) {
        handle(exchange, "POST", () -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            String runId = requiredString(request, "runId");
            String citySeedId = requiredString(request, "citySeedId");
            rejectLegacyCityFields(request, "patchGroupPlan", "zoneChoices", "functionType", "functionTag",
                    "function_candidates");
            if (!request.has("designLoopState") || !request.get("designLoopState").isJsonObject()) {
                throw new IllegalArgumentException("designLoopState object is required.");
            }
            return CityPlanningEndpointHandler.handleWriteD4DesignLoopState(debugRoot(), runId, citySeedId,
                    stringValue(request, "stateId", ""), request.getAsJsonObject("designLoopState"));
        });
    }

    void handleCityExecuteD4ArrayLayoutItem(HttpExchange exchange) {
        handle(exchange, "POST", () -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            String runId = requiredString(request, "runId");
            String citySeedId = requiredString(request, "citySeedId");
            rejectLegacyCityFields(request, "patchGroupPlan", "zoneChoices", "functionType", "functionTag",
                    "function_candidates");
            if (!request.has("terrasenseProfileSource") || !request.get("terrasenseProfileSource").isJsonObject()) {
                throw new IllegalArgumentException("terrasenseProfileSource object is required.");
            }
            if (!request.has("nextArrayLayoutPlanItem") || !request.get("nextArrayLayoutPlanItem").isJsonObject()) {
                throw new IllegalArgumentException("nextArrayLayoutPlanItem object is required.");
            }
            return CityPlanningEndpointHandler.handleExecuteD4ArrayLayoutItem(debugRoot(), runId, citySeedId,
                    request.getAsJsonObject("terrasenseProfileSource"),
                    stringValue(request, "stateId", ""),
                    request.getAsJsonObject("nextArrayLayoutPlanItem"),
                    request.has("arrayLayoutLoopStateSource")
                            && request.get("arrayLayoutLoopStateSource").isJsonObject()
                            ? request.getAsJsonObject("arrayLayoutLoopStateSource") : null,
                    request.has("templateCatalogSource") && request.get("templateCatalogSource").isJsonObject()
                            ? request.getAsJsonObject("templateCatalogSource") : null);
        });
    }

    void handleCityQueryD4ArrayExpansionSpace(HttpExchange exchange) {
        handle(exchange, "POST", () -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            String runId = requiredString(request, "runId");
            String citySeedId = requiredString(request, "citySeedId");
            rejectLegacyCityFields(request, "patchGroupPlan", "zoneChoices", "functionType", "functionTag",
                    "function_candidates");
            if (!request.has("arrayExpansionRequest") || !request.get("arrayExpansionRequest").isJsonObject()) {
                throw new IllegalArgumentException("arrayExpansionRequest object is required.");
            }
            JsonObject expansionRequest = new PatchExplorerService(debugRoot()).resolveD4ExpansionRequest(
                    runId, citySeedId, request.getAsJsonObject("arrayExpansionRequest"));
            return CityPlanningEndpointHandler.handleQueryD4ArrayExpansionSpace(debugRoot(), runId, citySeedId,
                    stringValue(request, "stateId", ""), expansionRequest,
                    request.has("arrayLayoutLoopStateSource") && request.get("arrayLayoutLoopStateSource").isJsonObject()
                            ? request.getAsJsonObject("arrayLayoutLoopStateSource") : null);
        });
    }

    void handleCityPlanD4ArrayExpansionCandidates(HttpExchange exchange) {
        handle(exchange, "POST", () -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            String runId = requiredString(request, "runId");
            String citySeedId = requiredString(request, "citySeedId");
            rejectLegacyCityFields(request, "patchGroupPlan", "zoneChoices", "functionType", "functionTag",
                    "function_candidates");
            if (!request.has("terrasenseProfileSource") || !request.get("terrasenseProfileSource").isJsonObject()) {
                throw new IllegalArgumentException("terrasenseProfileSource object is required.");
            }
            if (!request.has("arrayExpansionRequest") || !request.get("arrayExpansionRequest").isJsonObject()) {
                throw new IllegalArgumentException("arrayExpansionRequest object is required.");
            }
            JsonObject expansionRequest = new PatchExplorerService(debugRoot()).resolveD4ExpansionRequest(
                    runId, citySeedId, request.getAsJsonObject("arrayExpansionRequest"));
            return CityPlanningEndpointHandler.handlePlanD4ArrayExpansionCandidates(debugRoot(), runId, citySeedId,
                    request.getAsJsonObject("terrasenseProfileSource"), stringValue(request, "stateId", ""),
                    expansionRequest,
                    request.has("arrayLayoutLoopStateSource") && request.get("arrayLayoutLoopStateSource").isJsonObject()
                            ? request.getAsJsonObject("arrayLayoutLoopStateSource") : null,
                    request.has("templateCatalogSource") && request.get("templateCatalogSource").isJsonObject()
                            ? request.getAsJsonObject("templateCatalogSource") : null);
        });
    }

    void handleCitySelectD4ArrayExpansionCandidate(HttpExchange exchange) {
        handle(exchange, "POST", () -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            String runId = requiredString(request, "runId");
            String citySeedId = requiredString(request, "citySeedId");
            rejectLegacyCityFields(request, "patchGroupPlan", "zoneChoices", "functionType", "functionTag",
                    "function_candidates");
            return CityPlanningEndpointHandler.handleSelectD4ArrayExpansionCandidate(debugRoot(), runId, citySeedId,
                    stringValue(request, "stateId", ""), stringValue(request, "candidateId", ""),
                    booleanValue(request, "autoSelectHighestScore", false), stringValue(request, "selectionReason", ""),
                    request.has("arrayExpansionCandidateSetSource")
                            && request.get("arrayExpansionCandidateSetSource").isJsonObject()
                            ? request.getAsJsonObject("arrayExpansionCandidateSetSource") : null,
                    request.has("arrayLayoutLoopStateSource") && request.get("arrayLayoutLoopStateSource").isJsonObject()
                            ? request.getAsJsonObject("arrayLayoutLoopStateSource") : null,
                    request.has("templateCatalogSource") && request.get("templateCatalogSource").isJsonObject()
                            ? request.getAsJsonObject("templateCatalogSource") : null);
        });
    }

    void handleCityFinalizeD4ArrayLayoutLoop(HttpExchange exchange) {
        handle(exchange, "POST", () -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            String runId = requiredString(request, "runId");
            String citySeedId = requiredString(request, "citySeedId");
            rejectLegacyCityFields(request, "patchGroupPlan", "zoneChoices", "functionType", "functionTag",
                    "function_candidates");
            if (!request.has("terrasenseProfileSource") || !request.get("terrasenseProfileSource").isJsonObject()) {
                throw new IllegalArgumentException("terrasenseProfileSource object is required.");
            }
            return CityPlanningEndpointHandler.handleFinalizeD4ArrayLayoutLoop(debugRoot(), runId, citySeedId,
                    request.getAsJsonObject("terrasenseProfileSource"),
                    stringValue(request, "stateId", ""),
                    request.has("arrayLayoutLoopStateSource")
                            && request.get("arrayLayoutLoopStateSource").isJsonObject()
                            ? request.getAsJsonObject("arrayLayoutLoopStateSource") : null,
                    request.has("templateCatalogSource") && request.get("templateCatalogSource").isJsonObject()
                            ? request.getAsJsonObject("templateCatalogSource") : null);
        });
    }

    void handleCityPlanDressing(HttpExchange exchange) {
        handle(exchange, "POST", () -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            String runId = requiredString(request, "runId");
            String citySeedId = requiredString(request, "citySeedId");
            rejectLegacyCityFields(request, "patchGroupPlan", "zoneChoices", "functionType", "functionTag",
                    "function_candidates");
            if (request.has("dressingBrushPlan")
                    || "city_dressing_brush_plan".equals(stringValue(request, "schema", ""))) {
                throw new IllegalArgumentException("CITY_DRESSING_LEGACY_SCHEMA_REMOVED: "
                        + "plan_city_dressing accepts decorationProgramPlan only.");
            }
            if (!request.has("decorationProgramPlan") || !request.get("decorationProgramPlan").isJsonObject()) {
                throw new IllegalArgumentException("CITY_DECORATION_PROGRAM_PLAN_REQUIRED: "
                        + "decorationProgramPlan object is required.");
            }
            return CityPlanningEndpointHandler.handlePlanCityDressing(debugRoot(), runId, citySeedId,
                    request.getAsJsonObject("decorationProgramPlan"));
        });
    }

    void handleCityPlanDecorationAnchorCandidates(HttpExchange exchange) {
        handle(exchange, "POST", () -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            String runId = requiredString(request, "runId");
            String citySeedId = requiredString(request, "citySeedId");
            rejectLegacyCityFields(request, "patchGroupPlan", "zoneChoices", "functionType", "functionTag",
                    "function_candidates");
            if (!request.has("decorationProgramPlan") || !request.get("decorationProgramPlan").isJsonObject()) {
                throw new IllegalArgumentException("CITY_DECORATION_PROGRAM_PLAN_REQUIRED: "
                        + "decorationProgramPlan object is required.");
            }
            String programId = requiredString(request, "programId");
            int candidateCount = decorationAnchorCandidateCount(request);
            return CityPlanningEndpointHandler.handlePlanDecorationAnchorCandidates(debugRoot(), runId, citySeedId,
                    request.getAsJsonObject("decorationProgramPlan"), programId, candidateCount);
        });
    }

    static int decorationAnchorCandidateCount(JsonObject request) {
        int candidateCount = intValue(request, "candidateCount", 5);
        if (candidateCount < 1 || candidateCount > 8) {
            throw new IllegalArgumentException("CITY_DECORATION_CANDIDATE_COUNT_INVALID: "
                    + "candidateCount must be between 1 and 8.");
        }
        return candidateCount;
    }

    void handleCityQueryDecorationCatalog(HttpExchange exchange) {
        handle(exchange, "GET", CityPlanningEndpointHandler::handleQueryDecorationCatalog);
    }

    void handleCityProbeDecorationTerrain(HttpExchange exchange) {
        handle(exchange, "POST", () -> callOnServerThread(() -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            String runId = requiredString(request, "runId");
            String citySeedId = requiredString(request, "citySeedId");
            ServerPlayer player = resolvePlayer(stringValue(request, "playerName", ""));
            String dimensionId = stringValue(request, "dimensionId", "");
            if (dimensionId.isBlank()) {
                dimensionId = restoredRunDimensionId(runId);
            }
            ServerLevel level = resolveLevel(dimensionId, player);
            return CityPlanningEndpointHandler.handleProbeDecorationTerrain(debugRoot(), runId, citySeedId, level);
        }));
    }

    void handleCityQueryStructureCatalog(HttpExchange exchange) {
        handle(exchange, "POST", () -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            rejectLegacyCityFields(request, "functionTags", "function_tags", "function_candidates");
            if (!request.has("terrasenseProfileSource") || !request.get("terrasenseProfileSource").isJsonObject()) {
                throw new IllegalArgumentException("terrasenseProfileSource object is required.");
            }
            return CityPlanningEndpointHandler.handleQueryStructureCatalog(debugRoot(),
                    request.getAsJsonObject("terrasenseProfileSource"), request);
        });
    }

    void handleCityQueryTemplateMetadata(HttpExchange exchange) {
        handle(exchange, "POST", () -> callOnServerThread(() -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            if (!request.has("templateRefs") || !request.get("templateRefs").isJsonArray()) {
                throw new IllegalArgumentException("templateRefs array is required.");
            }
            ServerPlayer player = resolvePlayer(stringValue(request, "playerName", ""));
            ServerLevel level = resolveLevel(stringValue(request, "dimensionId", ""), player);
            return CityPlanningEndpointHandler.handleQueryTemplateMetadata(level,
                    request.getAsJsonArray("templateRefs"));
        }));
    }

    void handleCityPlanD4StructureClusterGroups(HttpExchange exchange) {
        handle(exchange, "POST", () -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            String runId = requiredString(request, "runId");
            String citySeedId = requiredString(request, "citySeedId");
            rejectLegacyCityFields(request, "patchGroupPlan", "zoneChoices", "functionType", "functionTag",
                    "function_candidates");
            if (!request.has("terrasenseProfileSource") || !request.get("terrasenseProfileSource").isJsonObject()) {
                throw new IllegalArgumentException("terrasenseProfileSource object is required.");
            }
            if (!request.has("designSlotPlan") || !request.get("designSlotPlan").isJsonObject()) {
                throw new IllegalArgumentException("designSlotPlan object is required.");
            }
            JsonObject designSlotPlan = new PatchExplorerService(debugRoot()).resolveD4DesignSlotPlan(
                    runId, citySeedId, request.getAsJsonObject("designSlotPlan"));
            return CityPlanningEndpointHandler.handlePlanD4StructureClusterGroups(debugRoot(), runId, citySeedId,
                    request.getAsJsonObject("terrasenseProfileSource"),
                    designSlotPlan,
                    request.has("templateCatalogSource") && request.get("templateCatalogSource").isJsonObject()
                            ? request.getAsJsonObject("templateCatalogSource") : null,
                    hasValue(request, "groupCount") ? intValue(request, "groupCount", 5) : null,
                    hasValue(request, "candidatesPerSlot") ? intValue(request, "candidatesPerSlot", 5) : null,
                    hasValue(request, "beamWidth") ? intValue(request, "beamWidth", 25) : null);
        });
    }

    void handleCitySelectD4Candidates(HttpExchange exchange) {
        handle(exchange, "POST", () -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            String runId = requiredString(request, "runId");
            String citySeedId = requiredString(request, "citySeedId");
            rejectLegacyCityFields(request, "patchGroupPlan", "zoneChoices", "functionType", "functionTag",
                    "function_candidates");
            if (!request.has("terrasenseProfileSource") || !request.get("terrasenseProfileSource").isJsonObject()) {
                throw new IllegalArgumentException("terrasenseProfileSource object is required.");
            }
            if (!request.has("anchorSelectionPlan") || !request.get("anchorSelectionPlan").isJsonObject()) {
                throw new IllegalArgumentException("anchorSelectionPlan object is required.");
            }
            return CityPlanningEndpointHandler.handleSelectD4Candidates(debugRoot(), runId, citySeedId,
                    request.getAsJsonObject("terrasenseProfileSource"),
                    request.getAsJsonObject("anchorSelectionPlan"),
                    request.has("templateCatalogSource") && request.get("templateCatalogSource").isJsonObject()
                            ? request.getAsJsonObject("templateCatalogSource") : null,
                    request.has("anchorCandidateSetSource") && request.get("anchorCandidateSetSource").isJsonObject()
                            ? request.getAsJsonObject("anchorCandidateSetSource") : null);
        });
    }

    void handleCitySelectD4StructureClusterGroup(HttpExchange exchange) {
        handle(exchange, "POST", () -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            String runId = requiredString(request, "runId");
            String citySeedId = requiredString(request, "citySeedId");
            rejectLegacyCityFields(request, "patchGroupPlan", "zoneChoices", "functionType", "functionTag",
                    "function_candidates");
            if (!request.has("terrasenseProfileSource") || !request.get("terrasenseProfileSource").isJsonObject()) {
                throw new IllegalArgumentException("terrasenseProfileSource object is required.");
            }
            return CityPlanningEndpointHandler.handleSelectD4StructureClusterGroup(debugRoot(), runId, citySeedId,
                    request.getAsJsonObject("terrasenseProfileSource"),
                    requiredString(request, "groupCandidateId"),
                    request.has("templateCatalogSource") && request.get("templateCatalogSource").isJsonObject()
                            ? request.getAsJsonObject("templateCatalogSource") : null,
                    request.has("structureClusterGroupCandidateSetSource")
                            && request.get("structureClusterGroupCandidateSetSource").isJsonObject()
                            ? request.getAsJsonObject("structureClusterGroupCandidateSetSource") : null);
        });
    }

    void handleCityCreateD4CandidateSession(HttpExchange exchange) {
        handle(exchange, "POST", () -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            String runId = requiredString(request, "runId");
            String citySeedId = requiredString(request, "citySeedId");
            rejectLegacyCityFields(request, "patchGroupPlan", "zoneChoices", "functionType", "functionTag",
                    "function_candidates");
            if (!request.has("terrasenseProfileSource") || !request.get("terrasenseProfileSource").isJsonObject()) {
                throw new IllegalArgumentException("terrasenseProfileSource object is required.");
            }
            if (!request.has("designSlotPlan") || !request.get("designSlotPlan").isJsonObject()) {
                throw new IllegalArgumentException("designSlotPlan object is required.");
            }
            JsonObject designSlotPlan = new PatchExplorerService(debugRoot()).resolveD4DesignSlotPlan(
                    runId, citySeedId, request.getAsJsonObject("designSlotPlan"));
            return CityPlanningEndpointHandler.handleCreateD4CandidateSession(debugRoot(), runId, citySeedId,
                    request.getAsJsonObject("terrasenseProfileSource"),
                    designSlotPlan,
                    request.has("templateCatalogSource") && request.get("templateCatalogSource").isJsonObject()
                            ? request.getAsJsonObject("templateCatalogSource") : null,
                    stringValue(request, "sessionId", ""));
        });
    }

    void handleCityPlanD4NextCandidates(HttpExchange exchange) {
        handle(exchange, "POST", () -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            String runId = requiredString(request, "runId");
            String citySeedId = requiredString(request, "citySeedId");
            return CityPlanningEndpointHandler.handlePlanD4NextCandidates(debugRoot(), runId, citySeedId,
                    request.has("templateCatalogSource") && request.get("templateCatalogSource").isJsonObject()
                            ? request.getAsJsonObject("templateCatalogSource") : null);
        });
    }

    void handleCitySelectD4Candidate(HttpExchange exchange) {
        handle(exchange, "POST", () -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            String runId = requiredString(request, "runId");
            String citySeedId = requiredString(request, "citySeedId");
            return CityPlanningEndpointHandler.handleSelectD4Candidate(debugRoot(), runId, citySeedId,
                    stringValue(request, "sessionId", ""),
                    requiredString(request, "slotId"),
                    requiredString(request, "candidateId"),
                    stringValue(request, "anchorId", ""),
                    stringValue(request, "selectionReason", ""),
                    booleanValue(request, "quickPreflight", false));
        });
    }

    void handleCityFinalizeD4CandidateSession(HttpExchange exchange) {
        handle(exchange, "POST", () -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            String runId = requiredString(request, "runId");
            String citySeedId = requiredString(request, "citySeedId");
            if (!request.has("terrasenseProfileSource") || !request.get("terrasenseProfileSource").isJsonObject()) {
                throw new IllegalArgumentException("terrasenseProfileSource object is required.");
            }
            return CityPlanningEndpointHandler.handleFinalizeD4CandidateSession(debugRoot(), runId, citySeedId,
                    request.getAsJsonObject("terrasenseProfileSource"),
                    request.has("templateCatalogSource") && request.get("templateCatalogSource").isJsonObject()
                            ? request.getAsJsonObject("templateCatalogSource") : null,
                    stringValue(request, "sessionId", ""));
        });
    }

    void handleCityPlanD5(HttpExchange exchange) {
        handle(exchange, "POST", () -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            String runId = requiredString(request, "runId");
            String citySeedId = requiredString(request, "citySeedId");
            return CityPlanningEndpointHandler.handlePlanD5(debugRoot(), runId, citySeedId,
                    intValue(request, "wallMarginBlocks", 24),
                    intValue(request, "wallCorridorHalfWidthBlocks", 4));
        });
    }

    void handleCityExecuteD5(HttpExchange exchange) {
        handle(exchange, "POST", () -> callOnServerThread(() -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            String runId = requiredString(request, "runId");
            String citySeedId = requiredString(request, "citySeedId");
            boolean confirmWorldMutation = booleanValue(request, "confirmWorldMutation", false);
            ServerPlayer player = resolvePlayer(stringValue(request, "playerName", ""));
            String dimensionId = stringValue(request, "dimensionId", "");
            if (dimensionId.isBlank()) {
                dimensionId = restoredRunDimensionId(runId);
            }
            ServerLevel level = resolveLevel(dimensionId, player);
            JsonObject response = CityPlanningEndpointHandler.handleExecuteD5(debugRoot(),
                    server.getWorldPath(LevelResource.ROOT),
                    runId, citySeedId, confirmWorldMutation, level);
            response.addProperty("worldSaveRequested", false);
            return response;
        }));
    }

    void handleCityPlanD6(HttpExchange exchange) {
        handle(exchange, "POST", () -> callOnServerThread(() -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            String runId = requiredString(request, "runId");
            String citySeedId = requiredString(request, "citySeedId");
            rejectLegacyCityFields(request, "terrasenseProfileSource", "structureChoicePlan",
                    "fixedPlacementSelectionPlan", "functionZoneMap", "buildableAreaMap",
                    "plannedFixedPlacementMap", "structurePoolMap");
            ServerPlayer player = resolvePlayer(stringValue(request, "playerName", ""));
            String dimensionId = stringValue(request, "dimensionId", "");
            if (dimensionId.isBlank()) {
                dimensionId = restoredRunDimensionId(runId);
            }
            ServerLevel level = resolveLevel(dimensionId, player);
            return CityPlanningEndpointHandler.handlePlanD6(debugRoot(), runId, citySeedId,
                    new CityPlanningEndpointHandler.MinecraftServerHolder(server),
                    level);
        }));
    }

    void handleCityPlanLandUse(HttpExchange exchange) {
        handle(exchange, "POST", () -> handleCityPlanLandUseRequest(
                debugRoot(), GisHttpUtil.readJsonObject(exchange)));
    }

    static JsonObject handleCityPlanLandUseRequest(Path debugRoot, JsonObject request) throws IOException {
        String runId = requiredString(request, "runId");
        String citySeedId = requiredString(request, "citySeedId");
        boolean acceptedBlueprint = hasAcceptedBlueprintD6(debugRoot, runId, citySeedId);
        if (request.has("landUseIntentPlan")) {
            throw new IllegalArgumentException("CITY_BLUEPRINT_WORKFLOW_LAND_USE_OVERRIDE_FORBIDDEN: "
                    + "current CityBlueprint is the only outdoor design authority.");
        }
        if (acceptedBlueprint) {
            return CityPlanningEndpointHandler.handlePlanBlueprintOutdoor(debugRoot, runId, citySeedId);
        }
        throw new IllegalArgumentException("CITY_BLUEPRINT_WORKFLOW_REQUIRED: prepare, submit and compile "
                + "current CityBlueprint before planning city outdoor space.");
    }

    private static boolean hasAcceptedBlueprintD6(Path debugRoot,
                                                   String runId,
                                                   String citySeedId) throws IOException {
        Path runDir = debugRoot.resolve(runId);
        CityTestRunLayout layout = CityTestRunLayout.open(runDir, citySeedId);
        Path blueprintDir = layout.stepDirectory(CityTestRunLayout.BLUEPRINT);
        Path blueprintPath = blueprintDir.resolve("city_blueprint.json");
        Path validationPath = blueprintDir.resolve("city_blueprint_validation_report.json");
        Path submissionPath = blueprintDir.resolve("city_blueprint_submission_trace.json");
        Path d6Path = layout.stepDirectory(CityTestRunLayout.D6)
                .resolve("structure_materialization_plan.json");
        boolean blueprintExists = Files.isRegularFile(blueprintPath);
        boolean validationExists = Files.isRegularFile(validationPath);
        boolean submissionExists = Files.isRegularFile(submissionPath);
        if (!blueprintExists && !validationExists && !submissionExists) {
            return false;
        }
        if (!blueprintExists || !validationExists || !submissionExists) {
            throw new IllegalArgumentException("CITY_BLUEPRINT_LAND_USE_ROUTE_INCOMPLETE: Blueprint, validation "
                    + "and submission artifacts must exist together.");
        }
        if (!Files.isRegularFile(d6Path)) {
            throw new IllegalArgumentException("CITY_BLUEPRINT_LAND_USE_D6_MISSING: run city_plan_d6 first.");
        }
        try {
            JsonObject blueprint = JsonParser.parseString(Files.readString(blueprintPath)).getAsJsonObject();
            JsonObject validation = JsonParser.parseString(Files.readString(validationPath)).getAsJsonObject();
            JsonObject submission = JsonParser.parseString(Files.readString(submissionPath)).getAsJsonObject();
            JsonObject d6 = JsonParser.parseString(Files.readString(d6Path)).getAsJsonObject();
            boolean accepted = CityBlueprint.SCHEMA.equals(stringValue(blueprint, "schema", ""))
                    && citySeedId.equals(stringValue(blueprint, "cityId", ""))
                    && booleanValue(validation, "valid", false)
                    && "accepted".equals(stringValue(submission, "status", ""))
                    && intValue(submission, "aiCityDesignSubmissionCount", 0) == 1;
            if (!accepted) {
                throw new IllegalArgumentException("CITY_BLUEPRINT_LAND_USE_ROUTE_NOT_ACCEPTED: "
                        + "Blueprint authority exists but is not an accepted current submission.");
            }
            if (!citySeedId.equals(stringValue(d6, "cityId", ""))
                    || !booleanValue(d6, "locked", false)) {
                throw new IllegalArgumentException("CITY_BLUEPRINT_LAND_USE_D6_NOT_LOCKED: "
                        + "accepted Blueprint requires its locked D6 plan.");
            }
            return true;
        } catch (RuntimeException exception) {
            if (exception instanceof IllegalArgumentException
                    && exception.getMessage() != null
                    && exception.getMessage().startsWith("CITY_BLUEPRINT_LAND_USE_")) {
                throw exception;
            }
            throw new IllegalArgumentException("CITY_BLUEPRINT_LAND_USE_ROUTE_INVALID: "
                    + "cannot parse Blueprint/D6 routing artifacts.", exception);
        }
    }

    void handleCityExecuteD7(HttpExchange exchange) {
        handle(exchange, "POST", () -> callOnServerThread(() -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            if (request.has("debugLateMaterialize")) {
                throw new IllegalArgumentException("CITY_CONFIGURED_STRUCTURE_FLOW_REMOVED");
            }
            String runId = requiredString(request, "runId");
            String citySeedId = requiredString(request, "citySeedId");
            boolean executeStructurePlacement = booleanValue(request, "executeStructurePlacement", false);
            ServerPlayer player = resolvePlayer(stringValue(request, "playerName", ""));
            String dimensionId = stringValue(request, "dimensionId", "");
            if (dimensionId.isBlank()) {
                dimensionId = restoredRunDimensionId(runId);
            }
            ServerLevel level = resolveLevel(dimensionId, player);
            long worldSeed = longValue(request, "worldSeed", level.getSeed());
            JsonObject response = CityPlanningEndpointHandler.handleExecuteD7(debugRoot(), runId, citySeedId,
                    worldSeed,
                    executeStructurePlacement,
                    new CityPlanningEndpointHandler.MinecraftServerHolder(server),
                    level);
            response.addProperty("worldSaveRequested", false);
            return response;
        }));
    }

    void handleCityQueryWorldgenObservations(HttpExchange exchange) {
        handle(exchange, "POST", () -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            return CityWorldgenBlockObservationRegistry.query(
                    server.getWorldPath(LevelResource.ROOT),
                    requiredString(request, "dimensionId"),
                    requiredInt(request, "chunkX"),
                    requiredInt(request, "chunkZ"),
                    stringValue(request, "phase", ""),
                    intValue(request, "limit", 10),
                    booleanValue(request, "includeBlocks", true));
        });
    }

    void handleCityPlanCityWalls(HttpExchange exchange) {
        handle(exchange, "POST", () -> callOnServerThread(() -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            String runId = requiredString(request, "runId");
            String citySeedId = requiredString(request, "citySeedId");
            ServerPlayer player = resolvePlayer(stringValue(request, "playerName", ""));
            String dimensionId = stringValue(request, "dimensionId", "");
            if (dimensionId.isBlank()) {
                dimensionId = restoredRunDimensionId(runId);
            }
            ServerLevel level = resolveLevel(dimensionId, player);
            return CityPlanningEndpointHandler.handlePlanCityWalls(debugRoot(), runId, citySeedId,
                    level,
                    intValue(request, "roadScanMarginBlocks", 8),
                    new CityWallPlanner.Options(
                            intValue(request, "wallUnitLengthBlocks",
                                    CityWallPlanner.DEFAULT_WALL_UNIT_LENGTH_BLOCKS),
                            intValue(request, "nominalWallHeightBlocks",
                                    CityWallPlanner.DEFAULT_NOMINAL_WALL_HEIGHT_BLOCKS),
                            intValue(request, "waterRunMinBlocks",
                                    CityWallPlanner.DEFAULT_WATER_RUN_MIN_BLOCKS),
                            doubleValue(request, "waterFluidRatioMin",
                                    CityWallPlanner.DEFAULT_WATER_FLUID_RATIO_MIN),
                            intValue(request, "heightSegmentMaxDeltaBlocks",
                                    CityWallPlanner.DEFAULT_SEGMENT_MAX_DELTA_BLOCKS),
                            intValue(request, "heightSteppedTransitionMaxDeltaBlocks",
                                    CityWallPlanner.DEFAULT_STEPPED_TRANSITION_MAX_DELTA_BLOCKS),
                            intValue(request, "naturalBoundaryMinDeltaBlocks",
                                    CityWallPlanner.DEFAULT_NATURAL_BOUNDARY_MIN_DELTA_BLOCKS)));
        }));
    }

    void handleCityExecuteCityWalls(HttpExchange exchange) {
        handle(exchange, "POST", () -> callOnServerThread(() -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            String runId = requiredString(request, "runId");
            String citySeedId = requiredString(request, "citySeedId");
            boolean confirmWorldMutation = booleanValue(request, "confirmWorldMutation", false);
            ServerPlayer player = resolvePlayer(stringValue(request, "playerName", ""));
            String dimensionId = stringValue(request, "dimensionId", "");
            if (dimensionId.isBlank()) {
                dimensionId = restoredRunDimensionId(runId);
            }
            ServerLevel level = resolveLevel(dimensionId, player);
            JsonObject response = CityPlanningEndpointHandler.handleExecuteCityWalls(debugRoot(), runId, citySeedId,
                    confirmWorldMutation, level,
                    booleanValue(request, "debugScan", false),
                    intValue(request, "debugScanStepBlocks", 1));
            if (response.get("ok").getAsBoolean()) {
                server.saveAllChunks(true, true, true);
                response.addProperty("worldSaveRequested", true);
            } else {
                response.addProperty("worldSaveRequested", false);
            }
            return response;
        }));
    }

    void handleCityRunWorkflow(HttpExchange exchange) {
        handle(exchange, "POST", () -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            String runId = requiredString(request, "runId");
            String citySeedId = requiredString(request, "citySeedId");
            String playerName = stringValue(request, "playerName", "");
            String dimensionId = stringValue(request, "dimensionId", "");
            if (dimensionId.isBlank()) {
                dimensionId = restoredRunDimensionId(runId);
            }
            String resolvedDimensionId = dimensionId;
            ServerLevel level = callOnServerThread(() -> resolveLevel(
                    resolvedDimensionId, resolvePlayer(playerName)));
            CityPlanningEndpointHandler.MinecraftServerHolder serverHolder =
                    new CityPlanningEndpointHandler.MinecraftServerHolder(server);
            JsonObject response = CityPlanningEndpointHandler.handleRunWorkflow(debugRoot(),
                    server.getWorldPath(LevelResource.ROOT),
                    runId, citySeedId, request,
                    serverHolder,
                    level);
            boolean saveAfter = booleanValue(request, "executeWalls", false)
                    && response.has("ok")
                    && response.get("ok").getAsBoolean()
                    && "completed".equals(stringValue(response, "status", ""));
            if (saveAfter) {
                server.saveAllChunks(true, true, true);
            }
            response.addProperty("worldSaveRequested", saveAfter);
            return response;
        });
    }

    void handleTagAudit(HttpExchange exchange) {
        handle(exchange, "POST", () -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            return callOnServerThread(() -> {
                String runId = requiredString(request, "runId");
                ServerPlayer player = resolvePlayer(stringValue(request, "playerName", ""));
                String dimensionId = stringValue(request, "dimensionId", "");
                if (dimensionId.isBlank()) {
                    dimensionId = restoredRunDimensionId(runId);
                }
                ServerLevel level = resolveLevel(dimensionId, player);
                JsonObject response = realmPlanningService.runTagAudit(runId, new MinecraftPriorAtlasSampler(level),
                        intValue(request, "tagAuditSampleCount", 120),
                        intValue(request, "tagAuditRadiusBlocks", 32),
                        intValue(request, "tagAuditStrideBlocks", 4),
                        intValue(request, "tagAuditSlopeRadiusBlocks", 4),
                        stringValue(request, "tagAuditSampleSeed", ""));
                response.addProperty("restoredFromSealedRun", true);
                return response;
            });
        });
    }

    void handleDebugCommand(HttpExchange exchange) {
        handle(exchange, "POST", () -> callOnServerThread(() -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            if (!booleanValue(request, "confirmCommandExecution", false)) {
                throw new IllegalArgumentException("confirmCommandExecution=true is required for realm_debug_command.");
            }
            String normalizedCommand = RealmDebugCommandSupport.normalizeCommand(requiredString(request, "command"));
            RealmDebugCommandSupport.requireSafeOrExplicit(normalizedCommand,
                    booleanValue(request, "allowUnsafeCommand", false));

            String requestedMode = RealmDebugCommandSupport.normalizeSourceMode(stringValue(request, "sourceMode", "auto"));
            String playerName = stringValue(request, "playerName", "");
            ServerPlayer player = playerName.isBlank()
                    ? server.getPlayerList().getPlayers().stream().findFirst().orElse(null)
                    : resolvePlayer(playerName);
            String dimensionId = stringValue(request, "dimensionId", "");
            ServerLevel level = resolveLevel(dimensionId, player);
            CommandSourceStack source = commandSource(requestedMode, player, level);
            String actualMode = player != null && !"server".equals(requestedMode) ? "player" : "server";
            int result = server.getCommands().performPrefixedCommand(source,
                    RealmDebugCommandSupport.prefixedCommand(normalizedCommand));
            boolean saveAfter = booleanValue(request, "saveAfter", false);
            if (saveAfter) {
                server.saveAllChunks(true, true, true);
            }

            JsonObject response = new JsonObject();
            response.addProperty("ok", result > 0);
            response.addProperty("status", result > 0 ? "executed" : "completed_without_success");
            response.addProperty("reasonCode", result > 0 ? "DEBUG_COMMAND_EXECUTED" : "DEBUG_COMMAND_NO_SUCCESS");
            response.addProperty("command", "/" + normalizedCommand);
            response.addProperty("normalizedCommand", normalizedCommand);
            response.addProperty("result", result);
            response.addProperty("sourceMode", actualMode);
            response.addProperty("requestedSourceMode", requestedMode);
            response.addProperty("playerName", player == null ? "" : player.getGameProfile().getName());
            response.addProperty("dimensionId", level.dimension().location().toString());
            response.addProperty("worldSaveRequested", saveAfter);
            return response;
        }));
    }

    private CommandSourceStack commandSource(String sourceMode, ServerPlayer player, ServerLevel level) {
        if ("player".equals(sourceMode) && player == null) {
            throw new IllegalArgumentException("sourceMode=player requires an online playerName or an online player.");
        }
        if (player != null && !"server".equals(sourceMode)) {
            return player.createCommandSourceStack()
                    .withPermission(4)
                    .withLevel(level)
                    .withPosition(Vec3.atLowerCornerOf(player.blockPosition()));
        }
        return server.createCommandSourceStack()
                .withLevel(level)
                .withPosition(Vec3.atLowerCornerOf(level.getSharedSpawnPos()))
                .withPermission(4);
    }

    private void handle(HttpExchange exchange, String method, JsonAction action) {
        try {
            if (!GisHttpUtil.requireMethod(exchange, method)) {
                return;
            }
            GisHttpUtil.sendJson(exchange, 200, action.execute());
        } catch (IllegalArgumentException ex) {
            sendError(exchange, 400, ex);
        } catch (Exception ex) {
            sendError(exchange, 500, ex);
        }
    }

    private WorldSurveyExecution runWorldSurvey(JsonObject request) throws Exception {
        return runWorldSurvey(prepareWorldSurvey(request));
    }

    private WorldSurveyExecution runWorldSurvey(PreparedWorldSurvey prepared) throws Exception {
        WorldSurveyResult result = new WorldSurveyRunner(debugRoot(), GisClassifierConfig.defaults()).run(
                prepared.config(), prepared.sampler(), prepared.progressListener());
        return new WorldSurveyExecution(result, prepared.sampler(), prepared.terrainProvider());
    }

    private PreparedWorldSurvey prepareWorldSurvey(JsonObject request) {
        int planningRadiusBlocks = intValue(request, "planningRadiusBlocks", 0);
        if (planningRadiusBlocks <= 0) {
            int radiusChunks = intValue(request, "radiusChunks", 512);
            if (radiusChunks < 1 || radiusChunks > 8192) {
                throw new IllegalArgumentException("radiusChunks must be between 1 and 8192.");
            }
            planningRadiusBlocks = radiusChunks * 16;
        }
        if (planningRadiusBlocks < 512 || planningRadiusBlocks > 262144) {
            throw new IllegalArgumentException("planningRadiusBlocks must be between 512 and 262144.");
        }
        int cellStepBlocks = intValue(request, "cellStepBlocks", WorldSurveyRunner.DEFAULT_CELL_STEP_BLOCKS);
        int microSampleStrideBlocks = intValue(request, "microSampleStrideBlocks",
                RealmPlanningService.DEFAULT_MICRO_SAMPLE_STRIDE_BLOCKS);
        int localSlopeRadiusBlocks = intValue(request, "localSlopeRadiusBlocks",
                WorldSurveyRunner.DEFAULT_LOCAL_SLOPE_RADIUS_BLOCKS);
        SampleMode sampleMode = sampleModeValue(request);
        ServerPlayer player = resolvePlayer(stringValue(request, "playerName", ""));
        ServerLevel level = resolveLevel(stringValue(request, "dimensionId", ""), player);
        BlockPos center = resolveCenter(request, player);
        String runId = stringValue(request, "runId", "");
        MinecraftPriorAtlasSampler minecraftSampler = new MinecraftPriorAtlasSampler(level);
        String fallbackFingerprint = String.join("|", "minecraft_prior",
                level.dimension().location().toString(), Long.toString(level.getSeed()),
                level.getChunkSource().getGenerator().getClass().getName());
        boolean preferGeneratorNative = booleanValue(request, "preferGeneratorNativeTerrain", true);
        var selector = MinecraftTerrainPreviewProviderFactory.createSelector(level, minecraftSampler,
                fallbackFingerprint);
        TerrainPreviewProviderSelection providerSelection = sampleMode == SampleMode.PRIOR
                ? selector.select(preferGeneratorNative)
                : selector.selectFallback("sample_mode_requires_minecraft_sampler");
        TerrainSamplingProvenance terrainProvider = TerrainSamplingProvenance.fromSelection(
                preferGeneratorNative, providerSelection);
        WorldSurveyRunner.Config config = new WorldSurveyRunner.Config(
                runId,
                level.dimension().location().toString(),
                Long.toString(level.getSeed()),
                level.getWorldBorder().getSize(),
                center.getX(),
                center.getZ(),
                planningRadiusBlocks,
                cellStepBlocks,
                microSampleStrideBlocks,
                localSlopeRadiusBlocks,
                sampleMode,
                WorldSurveyRunner.ResumePolicy.fromContractName(stringValue(request, "resumePolicy", "use_cache")),
                terrainProvider
        );
        AtlasSampler sampler = providerSelection.fastPath()
                ? new TerrainPreviewAtlasSampler(providerSelection) : minecraftSampler;
        WorldSurveyRunner.ProgressListener chatProgress = WorldSurveyChatProgress.forPlayer(player);
        WorldSurveyRunner.ProgressListener serverSafeProgress = update ->
                server.execute(() -> chatProgress.onProgress(update));
        return new PreparedWorldSurvey(config, sampler, terrainProvider, serverSafeProgress);
    }

    private ServerPlayer resolvePlayer(String playerName) {
        if (playerName != null && !playerName.isBlank()) {
            ServerPlayer player = server.getPlayerList().getPlayerByName(playerName.trim());
            if (player == null) {
                throw new IllegalArgumentException("Unknown playerName: " + playerName);
            }
            return player;
        }
        return server.getPlayerList().getPlayers().stream().findFirst().orElse(null);
    }

    private ServerLevel resolveLevel(String dimensionId, ServerPlayer player) {
        if (dimensionId != null && !dimensionId.isBlank()) {
            for (ServerLevel level : server.getAllLevels()) {
                if (level.dimension().location().toString().equals(dimensionId.trim())) {
                    return level;
                }
            }
            throw new IllegalArgumentException("Unknown dimensionId: " + dimensionId);
        }
        if (player != null) {
            return player.serverLevel();
        }
        return server.overworld();
    }

    private BlockPos resolveCenter(JsonObject request, ServerPlayer player) {
        boolean hasX = hasValue(request, "centerBlockX");
        boolean hasZ = hasValue(request, "centerBlockZ");
        if (hasX != hasZ) {
            throw new IllegalArgumentException("centerBlockX and centerBlockZ must be provided together.");
        }
        if (hasX) {
            return new BlockPos(intValue(request, "centerBlockX", 0), 0, intValue(request, "centerBlockZ", 0));
        }
        if (player != null) {
            return player.blockPosition();
        }
        return new BlockPos(0, 0, 0);
    }

    private <T> T callOnServerThread(Callable<T> action) throws Exception {
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

    private Path debugRoot() {
        return server.getServerDirectory().toPath().resolve("realm_debug").toAbsolutePath().normalize();
    }

    private RealmT4PatchPlanningService realmT4PatchPlanningService() throws IOException {
        return new RealmT4PatchPlanningService(debugRoot(),
                realmPlanningService::synchronizeT4RegistryArtifacts, planningAreaAccessConfig());
    }

    private PlanningAreaAccessConfig planningAreaAccessConfig() throws IOException {
        return PlanningAreaAccessConfig.loadOrCreate(server.getServerDirectory().toPath()
                .resolve("config").resolve("geomantia").resolve("planning_area_access.json"));
    }

    private Path cityDesignQueueConfigPath() {
        return server.getServerDirectory().toPath().resolve("config").resolve("geomantia")
                .resolve("city_design_queue.json");
    }

    private void requireRealmCoreOutsideInitialActivityArea(String runId, int gridX, int gridZ) throws IOException {
        PlanningAreaAccessConfig config = planningAreaAccessConfig();
        if (!config.enabled() || !config.managedDimensions().contains(restoredRunDimensionId(runId))) return;
        int step = restoredRunCellStepBlocks(runId);
        long blockX = (long) gridX * step;
        long blockZ = (long) gridZ * step;
        long radius = config.firstCityMinimumDistanceBlocks();
        if (blockX * blockX + blockZ * blockZ < radius * radius) {
            throw new IllegalArgumentException("T2_REALM_CORE_INSIDE_INITIAL_ACTIVITY_AREA: minimumDistanceBlocks="
                    + radius);
        }
    }

    @Override
    public void close() {
        postD4AutoCompileQueue.close();
    }

    static String relativeArtifact(Path debugRoot, Path artifact) {
        Path normalizedRoot = debugRoot.toAbsolutePath().normalize();
        Path normalizedArtifact = artifact.toAbsolutePath().normalize();
        if (!normalizedArtifact.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("Artifact resolves outside debugRoot: " + normalizedArtifact);
        }
        return normalizedRoot.relativize(normalizedArtifact).toString().replace('\\', '/');
    }

    private String restoredRunDimensionId(String runId) throws IOException {
        Path manifestPath = debugRoot().resolve(runId).resolve("world_survey_manifest.json");
        if (!Files.exists(manifestPath)) {
            return "";
        }
        JsonObject manifest = com.google.gson.JsonParser.parseString(Files.readString(manifestPath)).getAsJsonObject();
        if (manifest.has("config") && manifest.get("config").isJsonObject()) {
            return stringValue(manifest.getAsJsonObject("config"), "dimensionId", "");
        }
        return "";
    }

    private int restoredRunCellStepBlocks(String runId) throws IOException {
        Path contextPath = debugRoot().resolve(runId).resolve("world_survey_context.json");
        if (!Files.isRegularFile(contextPath)) {
            throw new IllegalArgumentException("REALM_WORLD_SURVEY_CONTEXT_MISSING");
        }
        JsonObject context = JsonParser.parseString(Files.readString(contextPath)).getAsJsonObject();
        int step = intValue(context, "cellStepBlocks", 0);
        if (step <= 0) {
            throw new IllegalArgumentException("REALM_WORLD_SURVEY_CELL_STEP_INVALID");
        }
        return step;
    }

    private static SampleMode sampleModeValue(JsonObject object) {
        String raw = stringValue(object, "sampleMode", SampleMode.PRIOR.contractName());
        for (SampleMode mode : SampleMode.values()) {
            if (mode.contractName().equalsIgnoreCase(raw.trim())) {
                return mode;
            }
        }
        throw new IllegalArgumentException("sampleMode must be one of: prior, observedIfLoaded, verifySurface.");
    }

    private static String requiredString(JsonObject object, String key) {
        String value = stringValue(object, key, "");
        if (value.isBlank()) {
            throw new IllegalArgumentException(key + " is required.");
        }
        return value;
    }

    private static JsonObject requiredObject(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonObject()) {
            throw new IllegalArgumentException(key + " object is required.");
        }
        return object.getAsJsonObject(key);
    }

    private static int intValue(JsonObject object, String key, int defaultValue) {
        if (!hasValue(object, key)) {
            return defaultValue;
        }
        try {
            return object.get(key).getAsInt();
        } catch (Exception ex) {
            throw new IllegalArgumentException(key + " must be an integer.");
        }
    }

    private static int requiredInt(JsonObject object, String key) {
        if (!hasValue(object, key)) {
            throw new IllegalArgumentException(key + " is required.");
        }
        return intValue(object, key, 0);
    }

    private static long longValue(JsonObject object, String key, long defaultValue) {
        if (!hasValue(object, key)) {
            return defaultValue;
        }
        try {
            return object.get(key).getAsLong();
        } catch (Exception ex) {
            throw new IllegalArgumentException(key + " must be a long.");
        }
    }

    private static double doubleValue(JsonObject object, String key, double defaultValue) {
        if (!hasValue(object, key)) {
            return defaultValue;
        }
        try {
            return object.get(key).getAsDouble();
        } catch (Exception ex) {
            throw new IllegalArgumentException(key + " must be a number.");
        }
    }

    private static boolean booleanValue(JsonObject object, String key, boolean defaultValue) {
        if (!hasValue(object, key)) {
            return defaultValue;
        }
        try {
            return object.get(key).getAsBoolean();
        } catch (Exception ex) {
            throw new IllegalArgumentException(key + " must be a boolean.");
        }
    }

    private static String stringValue(JsonObject object, String key, String defaultValue) {
        if (!hasValue(object, key)) {
            return defaultValue;
        }
        try {
            return object.get(key).getAsString();
        } catch (Exception ex) {
            throw new IllegalArgumentException(key + " must be a string.");
        }
    }

    private static JsonArray arrayValue(JsonObject object, String key) {
        if (!hasValue(object, key)) {
            return null;
        }
        if (!object.get(key).isJsonArray()) {
            throw new IllegalArgumentException(key + " must be an array.");
        }
        return object.getAsJsonArray(key);
    }

    private static boolean hasValue(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull();
    }

    private static void rejectLegacyCityFields(JsonObject request, String... fields) {
        for (String field : fields) {
            if (hasValue(request, field)) {
                throw new IllegalArgumentException("LEGACY_CITY_FUNCTION_ZONE_FLOW_REMOVED: field "
                        + field + " is no longer accepted by the City D3-D6 structure landing flow.");
            }
        }
    }

    private static void sendError(HttpExchange exchange, int code, Exception ex) {
        try {
            GisHttpUtil.sendError(exchange, code, ex.getMessage());
        } catch (IOException ioException) {
            ioException.printStackTrace();
        }
    }

    @FunctionalInterface
    private interface JsonAction {
        JsonObject execute() throws Exception;
    }

    private record WorldSurveyExecution(WorldSurveyResult result, AtlasSampler sampler,
            TerrainSamplingProvenance terrainProvider) {
    }

    private record PreparedWorldSurvey(WorldSurveyRunner.Config config, AtlasSampler sampler,
            TerrainSamplingProvenance terrainProvider,
            WorldSurveyRunner.ProgressListener progressListener) {
    }

    private record TerrainPreviewRuntime(String dimensionId, TerrainPreviewProviderSelection selection) {
    }
}
