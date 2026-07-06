package com.rinsing.geomantia.platform.http;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.gis.GisClassifierConfig;
import com.rinsing.geomantia.systems.gis.GisSampleConfig;
import com.rinsing.geomantia.systems.gis.adapter.minecraft.MinecraftPriorAtlasSampler;
import com.rinsing.geomantia.systems.gis.application.refresh.SampleMode;
import com.rinsing.geomantia.systems.gis.application.sample.AtlasSampler;
import com.rinsing.geomantia.systems.city.application.CityWallPlanner;
import com.rinsing.geomantia.systems.city.application.CityWallReservationPlanner;
import com.rinsing.geomantia.systems.realm_planning.RealmPlanningService;
import com.rinsing.geomantia.systems.realm_planning.WorldSurveyResult;
import com.rinsing.geomantia.systems.realm_planning.WorldSurveyRunner;
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

final class RealmPlanningHttpController {
    private final MinecraftServer server;

    RealmPlanningHttpController(MinecraftServer server) {
        this.server = server;
    }

    void handleStatus(HttpExchange exchange) {
        handle(exchange, "GET", () -> service().status());
    }

    void handleWRefresh(HttpExchange exchange) {
        handle(exchange, "POST", () -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            return callOnServerThread(() -> {
                WorldSurveyExecution execution = runWorldSurvey(request);
                RealmPlanningService service = service();
                JsonObject response = service.runW(execution.result(), request.get("worldTheme"));
                if (booleanValue(request, "runTagAudit", false)) {
                    JsonObject audit = service.runTagAudit(execution.result().runId(), execution.sampler(),
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
            return service().prepareT1(
                    requiredString(request, "runId"),
                    arrayValue(request, "realmProfiles"),
                    intValue(request, "realmCount", 3),
                    stringValue(request, "targetContinentId", ""),
                    booleanValue(request, "allowAiDraftProfile", true));
        });
    }

    void handleT2SelectCoordinate(HttpExchange exchange) {
        handle(exchange, "POST", () -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            return service().selectT2(
                    requiredString(request, "runId"),
                    requiredString(request, "realmId"),
                    intValue(request, "gridX", 0),
                    intValue(request, "gridZ", 0),
                    arrayValue(request, "alternates"),
                    stringValue(request, "reason", ""),
                    stringValue(request, "selectedBy", "ai"),
                    booleanValue(request, "allowSnap", true));
        });
    }

    void handleT3Expand(HttpExchange exchange) {
        handle(exchange, "POST", () -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            return service().expandT3(
                    requiredString(request, "runId"),
                    stringValue(request, "normalizationGroup", ""),
                    booleanValue(request, "allowUnclaimedLand", false),
                    stringValue(request, "qualityMode", "strict"),
                    stringValue(request, "expansionModel", ""));
        });
    }

    void handleT4BuildRegistry(HttpExchange exchange) {
        handle(exchange, "POST", () -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            return service().buildT4(requiredString(request, "runId"));
        });
    }

    void handleAcceptance(HttpExchange exchange) {
        handle(exchange, "POST", () -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            return callOnServerThread(() -> {
                WorldSurveyExecution execution = runWorldSurvey(request);
                RealmPlanningService service = service();
                JsonObject response = service.runAcceptance(execution.result(),
                        intValue(request, "realmCount", 3), arrayValue(request, "realmProfiles"),
                        booleanValue(request, "autoSelectCoordinates", true),
                        stringValue(request, "qualityMode", "strict"),
                        stringValue(request, "expansionModel", ""));
                if (booleanValue(request, "runTagAudit", false)) {
                    JsonObject audit = service.runTagAudit(execution.result().runId(), execution.sampler(),
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
            Integer cellStepBlocks = hasValue(request, "cellStepBlocks")
                    ? intValue(request, "cellStepBlocks", 4)
                    : null;
            return CityPlanningEndpointHandler.handlePlanD2(debugRoot(), runId, citySeedId, cellStepBlocks);
        });
    }

    void handleCityPlanD3(HttpExchange exchange) {
        handle(exchange, "POST", () -> callOnServerThread(() -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            String runId = requiredString(request, "runId");
            String citySeedId = requiredString(request, "citySeedId");
            Integer cellStepBlocks = hasValue(request, "cellStepBlocks")
                    ? intValue(request, "cellStepBlocks", 4)
                    : null;
            ServerPlayer player = resolvePlayer(stringValue(request, "playerName", ""));
            String dimensionId = stringValue(request, "dimensionId", "");
            if (dimensionId.isBlank()) {
                dimensionId = restoredRunDimensionId(runId);
            }
            ServerLevel level = resolveLevel(dimensionId, player);
            return CityPlanningEndpointHandler.handlePlanD3(debugRoot(), runId, citySeedId,
                    cellStepBlocks,
                    hasValue(request, "patchScanPaddingBlocks")
                            ? intValue(request, "patchScanPaddingBlocks",
                            CityPlanningEndpointHandler.DEFAULT_D3_PATCH_SCAN_PADDING_BLOCKS)
                            : null,
                    level);
        }));
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
            return CityPlanningEndpointHandler.handlePlanD4(debugRoot(), runId, citySeedId,
                    request.getAsJsonObject("terrasenseProfileSource"),
                    request.getAsJsonObject("structureAnchorPlan"),
                    request.has("structureEnvelopeFactsSource") && request.get("structureEnvelopeFactsSource").isJsonObject()
                            ? request.getAsJsonObject("structureEnvelopeFactsSource") : null);
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
            return CityPlanningEndpointHandler.handlePlanD4Candidates(debugRoot(), runId, citySeedId,
                    request.getAsJsonObject("terrasenseProfileSource"),
                    request.getAsJsonObject("designSlotPlan"),
                    request.has("structureEnvelopeFactsSource") && request.get("structureEnvelopeFactsSource").isJsonObject()
                            ? request.getAsJsonObject("structureEnvelopeFactsSource") : null);
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
            return CityPlanningEndpointHandler.handlePlanD4ArrayCandidates(debugRoot(), runId, citySeedId,
                    request.getAsJsonObject("terrasenseProfileSource"),
                    request.getAsJsonObject("arrayCandidatePlan"),
                    request.has("structureEnvelopeFactsSource") && request.get("structureEnvelopeFactsSource").isJsonObject()
                            ? request.getAsJsonObject("structureEnvelopeFactsSource") : null,
                    request.has("occupiedStructureAnchorMapSource")
                            && request.get("occupiedStructureAnchorMapSource").isJsonObject()
                            ? request.getAsJsonObject("occupiedStructureAnchorMapSource") : null,
                    request.has("occupiedEnvelopes") && request.get("occupiedEnvelopes").isJsonArray()
                            ? request.getAsJsonArray("occupiedEnvelopes") : new JsonArray());
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
                    request.has("structureEnvelopeFactsSource") && request.get("structureEnvelopeFactsSource").isJsonObject()
                            ? request.getAsJsonObject("structureEnvelopeFactsSource") : null,
                    request.has("anchorCandidateSetSource") && request.get("anchorCandidateSetSource").isJsonObject()
                            ? request.getAsJsonObject("anchorCandidateSetSource") : null);
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
            return CityPlanningEndpointHandler.handleCreateD4CandidateSession(debugRoot(), runId, citySeedId,
                    request.getAsJsonObject("terrasenseProfileSource"),
                    request.getAsJsonObject("designSlotPlan"),
                    request.has("structureEnvelopeFactsSource") && request.get("structureEnvelopeFactsSource").isJsonObject()
                            ? request.getAsJsonObject("structureEnvelopeFactsSource") : null,
                    stringValue(request, "sessionId", ""));
        });
    }

    void handleCityPlanD4NextCandidates(HttpExchange exchange) {
        handle(exchange, "POST", () -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            String runId = requiredString(request, "runId");
            String citySeedId = requiredString(request, "citySeedId");
            return CityPlanningEndpointHandler.handlePlanD4NextCandidates(debugRoot(), runId, citySeedId,
                    request.has("structureEnvelopeFactsSource") && request.get("structureEnvelopeFactsSource").isJsonObject()
                            ? request.getAsJsonObject("structureEnvelopeFactsSource") : null);
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
                    request.has("structureEnvelopeFactsSource") && request.get("structureEnvelopeFactsSource").isJsonObject()
                            ? request.getAsJsonObject("structureEnvelopeFactsSource") : null,
                    stringValue(request, "sessionId", ""));
        });
    }

    void handleCityProfileStructureEnvelopes(HttpExchange exchange) {
        handle(exchange, "POST", () -> callOnServerThread(() -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            String runId = requiredString(request, "runId");
            String citySeedId = requiredString(request, "citySeedId");
            if (!request.has("terrasenseProfileSource") || !request.get("terrasenseProfileSource").isJsonObject()) {
                throw new IllegalArgumentException("terrasenseProfileSource object is required.");
            }
            ServerPlayer player = resolvePlayer(stringValue(request, "playerName", ""));
            String dimensionId = stringValue(request, "dimensionId", "");
            if (dimensionId.isBlank()) {
                dimensionId = restoredRunDimensionId(runId);
            }
            ServerLevel level = resolveLevel(dimensionId, player);
            return CityPlanningEndpointHandler.handleProfileStructureEnvelopes(debugRoot(), runId, citySeedId,
                    request.getAsJsonObject("terrasenseProfileSource"),
                    request.has("structureIds") && request.get("structureIds").isJsonArray()
                            ? request.getAsJsonArray("structureIds") : new JsonArray(),
                    intValue(request, "sampleCount", 256),
                    new CityPlanningEndpointHandler.MinecraftServerHolder(server),
                    level);
        }));
    }

    void handleCityPlanD5(HttpExchange exchange) {
        handle(exchange, "POST", () -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            String runId = requiredString(request, "runId");
            String citySeedId = requiredString(request, "citySeedId");
            return CityPlanningEndpointHandler.handlePlanD5(debugRoot(), runId, citySeedId,
                    stringValue(request, "wallVersion", "v2"),
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
                                    CityWallReservationPlanner.DEFAULT_CONCAVITY_DEPTH_RATIO_MIN)));
        });
    }

    void handleCityExecuteD5(HttpExchange exchange) {
        handle(exchange, "POST", () -> callOnServerThread(() -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            String runId = requiredString(request, "runId");
            String citySeedId = requiredString(request, "citySeedId");
            boolean confirmWorldMutation = booleanValue(request, "confirmWorldMutation", false);
            String roadProvider = stringValue(request, "roadProvider", "auto");
            ServerPlayer player = resolvePlayer(stringValue(request, "playerName", ""));
            String dimensionId = stringValue(request, "dimensionId", "");
            if (dimensionId.isBlank()) {
                dimensionId = restoredRunDimensionId(runId);
            }
            ServerLevel level = resolveLevel(dimensionId, player);
            JsonObject response = CityPlanningEndpointHandler.handleExecuteD5(debugRoot(),
                    server.getWorldPath(LevelResource.ROOT),
                    runId, citySeedId, confirmWorldMutation, level, roadProvider);
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

    void handleCityExecuteD7(HttpExchange exchange) {
        handle(exchange, "POST", () -> callOnServerThread(() -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
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
            boolean debugLateMaterialize = booleanValue(request, "debugLateMaterialize", false);
            JsonObject response = CityPlanningEndpointHandler.handleExecuteD7(debugRoot(), runId, citySeedId,
                    worldSeed,
                    executeStructurePlacement,
                    debugLateMaterialize,
                    new CityPlanningEndpointHandler.MinecraftServerHolder(server),
                    level);
            if (executeStructurePlacement && debugLateMaterialize) {
                server.saveAllChunks(true, true, true);
                response.addProperty("worldSaveRequested", true);
            } else {
                response.addProperty("worldSaveRequested", false);
            }
            return response;
        }));
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
                    intValue(request, "wallMarginBlocks", 24),
                    intValue(request, "segmentLengthBlocks", 15),
                    intValue(request, "gateWidthBlocks", 9),
                    stringValue(request, "wallVersion", "v2"),
                    level,
                    intValue(request, "roadScanMarginBlocks", 8),
                    intValue(request, "roadProtectionMarginBlocks", 2),
                    intValue(request, "maxFoundationDepthBlocks", 8),
                    intValue(request, "maxSegmentHeightDeltaBlocks", 7),
                    new CityWallPlanner.V3Options(
                            intValue(request, "gateClusterRadiusBlocks",
                                    CityWallPlanner.DEFAULT_GATE_CLUSTER_RADIUS_BLOCKS),
                            intValue(request, "terrainFitUnitLengthBlocks",
                                    CityWallPlanner.DEFAULT_TERRAIN_FIT_UNIT_LENGTH_BLOCKS),
                            stringValue(request, "wallTerrainPolicy",
                                    CityWallPlanner.DEFAULT_WALL_TERRAIN_POLICY),
                            intValue(request, "flatMaxDeltaBlocks",
                                    CityWallPlanner.DEFAULT_FLAT_MAX_DELTA_BLOCKS),
                            intValue(request, "steppedMaxDeltaBlocks",
                                    CityWallPlanner.DEFAULT_STEPPED_MAX_DELTA_BLOCKS),
                            intValue(request, "mountainProbeDistanceBlocks",
                                    CityWallPlanner.DEFAULT_MOUNTAIN_PROBE_DISTANCE_BLOCKS),
                            intValue(request, "naturalBoundaryMinDeltaBlocks",
                                    CityWallPlanner.DEFAULT_NATURAL_BOUNDARY_MIN_DELTA_BLOCKS),
                            booleanValue(request, "embeddedSlopeTower", true),
                            stringValue(request, "wallDesignPolicy",
                                    CityWallPlanner.DEFAULT_WALL_DESIGN_POLICY),
                            intValue(request, "minGateSpacingBlocks",
                                    CityWallPlanner.DEFAULT_MIN_GATE_SPACING_BLOCKS),
                            intValue(request, "minGateRoadLengthBlocks",
                                    CityWallPlanner.DEFAULT_MIN_GATE_ROAD_LENGTH_BLOCKS),
                            intValue(request, "naturalWaterBoundaryMinAreaBlocks",
                                    CityWallPlanner.DEFAULT_NATURAL_WATER_BOUNDARY_MIN_AREA_BLOCKS),
                            intValue(request, "roadProjectionMaxDistanceBlocks",
                                    CityWallPlanner.DEFAULT_ROAD_PROJECTION_MAX_DISTANCE_BLOCKS)),
                    new CityWallPlanner.V4Options(
                            intValue(request, "wallUnitLengthBlocks",
                                    CityWallPlanner.DEFAULT_WALL_UNIT_LENGTH_BLOCKS),
                            intValue(request, "waterRunMinUnits",
                                    CityWallPlanner.DEFAULT_WATER_RUN_MIN_UNITS),
                            intValue(request, "waterRetreatMaxCells",
                                    CityWallPlanner.DEFAULT_WATER_RETREAT_MAX_CELLS),
                            intValue(request, "structureWallBreathingRoomBlocks",
                                    intValue(request, "wallMarginBlocks",
                                            CityWallPlanner.DEFAULT_STRUCTURE_WALL_BREATHING_ROOM_BLOCKS)),
                            intValue(request, "heightDatumClampBlocks",
                                    CityWallPlanner.DEFAULT_HEIGHT_DATUM_CLAMP_BLOCKS),
                            intValue(request, "localMedianWindowUnits",
                                    CityWallPlanner.DEFAULT_LOCAL_MEDIAN_WINDOW_UNITS)),
                    new CityWallPlanner.V5Options(
                            intValue(request, "wallUnitLengthBlocks",
                                    CityWallPlanner.DEFAULT_V5_WALL_UNIT_LENGTH_BLOCKS),
                            intValue(request, "nominalWallHeightBlocks",
                                    CityWallPlanner.DEFAULT_V5_NOMINAL_WALL_HEIGHT_BLOCKS),
                            intValue(request, "waterRunMinBlocks",
                                    CityWallPlanner.DEFAULT_V5_WATER_RUN_MIN_BLOCKS),
                            doubleValue(request, "waterFluidRatioMin",
                                    CityWallPlanner.DEFAULT_V5_WATER_FLUID_RATIO_MIN),
                            intValue(request, "heightSegmentMaxDeltaBlocks",
                                    CityWallPlanner.DEFAULT_V5_SEGMENT_MAX_DELTA_BLOCKS),
                            intValue(request, "heightSteppedTransitionMaxDeltaBlocks",
                                    CityWallPlanner.DEFAULT_V5_STEPPED_TRANSITION_MAX_DELTA_BLOCKS),
                            intValue(request, "naturalBoundaryMinDeltaBlocks",
                                    CityWallPlanner.DEFAULT_V5_NATURAL_BOUNDARY_MIN_DELTA_BLOCKS)));
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
            JsonObject response = CityPlanningEndpointHandler.handleRunWorkflow(debugRoot(),
                    server.getWorldPath(LevelResource.ROOT),
                    runId, citySeedId, request,
                    new CityPlanningEndpointHandler.MinecraftServerHolder(server),
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
        }));
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
                JsonObject response = service().runTagAudit(runId, new MinecraftPriorAtlasSampler(level),
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
                WorldSurveyRunner.ResumePolicy.fromContractName(stringValue(request, "resumePolicy", "use_cache"))
        );
        AtlasSampler sampler = new MinecraftPriorAtlasSampler(level);
        WorldSurveyResult result = new WorldSurveyRunner(debugRoot(), GisClassifierConfig.defaults()).run(config, sampler);
        return new WorldSurveyExecution(result, sampler);
    }

    private RealmPlanningService service() {
        return new RealmPlanningService(debugRoot());
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
        return server.getServerDirectory().toPath().resolve("realm_debug");
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

    private record WorldSurveyExecution(WorldSurveyResult result, AtlasSampler sampler) {
    }
}
