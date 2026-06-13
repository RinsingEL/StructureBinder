package com.rinsing.geomantia.platform.http;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.gis.GisClassifierConfig;
import com.rinsing.geomantia.systems.gis.GisSampleConfig;
import com.rinsing.geomantia.systems.gis.adapter.minecraft.MinecraftPriorAtlasSampler;
import com.rinsing.geomantia.systems.gis.application.refresh.SampleMode;
import com.rinsing.geomantia.systems.gis.application.sample.AtlasSampler;
import com.rinsing.geomantia.systems.realm_planning.RealmPlanningService;
import com.rinsing.geomantia.systems.realm_planning.WorldSurveyResult;
import com.rinsing.geomantia.systems.realm_planning.WorldSurveyRunner;
import com.sun.net.httpserver.HttpExchange;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

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
                            intValue(request, "tagAuditSlopeRadiusBlocks", 4));
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
                            intValue(request, "tagAuditSlopeRadiusBlocks", 4));
                    response.add("tagAuditReport", audit.getAsJsonObject("tagAuditReport"));
                    response.add("artifacts", audit.getAsJsonObject("artifacts"));
                }
                return response;
            });
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
                JsonObject response = service().runTagAudit(runId, new MinecraftPriorAtlasSampler(level),
                        intValue(request, "tagAuditSampleCount", 120),
                        intValue(request, "tagAuditRadiusBlocks", 32),
                        intValue(request, "tagAuditStrideBlocks", 4),
                        intValue(request, "tagAuditSlopeRadiusBlocks", 4));
                response.addProperty("restoredFromSealedRun", true);
                return response;
            });
        });
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
