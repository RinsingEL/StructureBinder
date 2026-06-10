package com.rinsing.geomantia.platform.http;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.GeomantiaMod;
import com.rinsing.geomantia.systems.gis.GisClassifierConfig;
import com.rinsing.geomantia.systems.gis.GisSampleConfig;
import com.rinsing.geomantia.systems.gis.preview.AtlasJson;
import com.rinsing.geomantia.systems.gis.application.refresh.GisRefreshService;
import com.rinsing.geomantia.systems.gis.application.refresh.RefreshJob;
import com.rinsing.geomantia.systems.gis.application.refresh.RefreshPriority;
import com.rinsing.geomantia.systems.gis.application.refresh.RefreshResult;
import com.rinsing.geomantia.systems.gis.application.refresh.SampleMode;
import com.rinsing.geomantia.systems.gis.domain.region.AtlasRegion;
import com.rinsing.geomantia.systems.gis.domain.region.AtlasRegionStore;
import com.rinsing.geomantia.systems.gis.adapter.minecraft.MinecraftPriorAtlasSampler;
import com.rinsing.geomantia.systems.gis.testsupport.GisTestCase;
import com.rinsing.geomantia.systems.gis.testsupport.GisTestRunner;
import com.sun.net.httpserver.HttpExchange;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

final class GisHttpController {
    private final MinecraftServer server;

    GisHttpController(MinecraftServer server) {
        this.server = server;
    }

    void handleStatus(HttpExchange exchange) {
        handle(exchange, "GET", () -> callOnServerThread(this::statusResponse));
    }

    void handleRefresh(HttpExchange exchange) {
        handle(exchange, "POST", () -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            return callOnServerThread(() -> runRefresh(request));
        });
    }

    void handleTestRun(HttpExchange exchange) {
        handle(exchange, "POST", () -> {
            JsonObject request = GisHttpUtil.readJsonObject(exchange);
            String caseId = stringValue(request, "caseId", "mixed");
            return callOnServerThread(() -> runTestCase(caseId));
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

    private JsonObject statusResponse() {
        JsonObject response = new JsonObject();
        response.addProperty("ok", true);
        response.addProperty("modId", GeomantiaMod.MOD_ID);
        response.addProperty("api", "geomantia-gis-debug");
        response.addProperty("serverDirectory", server.getServerDirectory().toPath().toAbsolutePath().toString());
        response.addProperty("debugRoot", debugRoot().toAbsolutePath().toString());
        JsonArray players = new JsonArray();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            JsonObject item = new JsonObject();
            item.addProperty("name", player.getGameProfile().getName());
            item.addProperty("dimensionId", player.serverLevel().dimension().location().toString());
            item.addProperty("blockX", player.blockPosition().getX());
            item.addProperty("blockY", player.blockPosition().getY());
            item.addProperty("blockZ", player.blockPosition().getZ());
            players.add(item);
        }
        response.add("players", players);
        return response;
    }

    private JsonObject runRefresh(JsonObject request) throws Exception {
        int radiusChunks = intValue(request, "radiusChunks", 8);
        if (radiusChunks < 1 || radiusChunks > 64) {
            throw new IllegalArgumentException("radiusChunks must be between 1 and 64.");
        }
        SampleMode sampleMode = sampleModeValue(request);
        ServerPlayer player = resolvePlayer(stringValue(request, "playerName", ""));
        ServerLevel level = resolveLevel(stringValue(request, "dimensionId", ""), player);
        BlockPos center = resolveCenter(request, player);

        GisSampleConfig sampleConfig = GisSampleConfig.defaults();
        GisRefreshService service = new GisRefreshService(sampleConfig, GisClassifierConfig.defaults(),
                new AtlasRegionStore(sampleConfig), new MinecraftPriorAtlasSampler(level));
        RefreshResult result = service.refresh(level.dimension().location().toString(),
                center.getX(), center.getZ(), radiusChunks, sampleMode, RefreshPriority.DEBUG, debugRoot());
        return refreshResponse(result);
    }

    private JsonObject runTestCase(String caseId) throws Exception {
        if (caseId == null || caseId.isBlank()) {
            throw new IllegalArgumentException("caseId is required.");
        }
        GisTestRunner runner = new GisTestRunner(GisSampleConfig.defaults(), GisClassifierConfig.defaults());
        GisTestRunner.GisTestReport report = runner.runCase(GisTestCase.byId(caseId.trim()), debugRoot());
        JsonObject response = AtlasJson.GSON.toJsonTree(report.asJson()).getAsJsonObject();
        response.addProperty("ok", report.passed());
        response.addProperty("runDirectory", debugRoot().resolve(report.runId()).toAbsolutePath().toString());
        return response;
    }

    private JsonObject refreshResponse(RefreshResult result) {
        RefreshJob job = result.job();
        AtlasRegion region = result.region();
        JsonObject response = new JsonObject();
        response.addProperty("ok", "completed".equals(job.status().contractName()));
        response.addProperty("runId", job.jobId());
        response.addProperty("status", job.status().contractName());
        response.addProperty("sampleMode", job.sampleMode().contractName());
        response.addProperty("dimensionId", job.dimensionId());
        response.addProperty("centerBlockX", job.centerBlockX());
        response.addProperty("centerBlockZ", job.centerBlockZ());
        response.addProperty("radiusChunks", job.radiusChunks());
        response.addProperty("completedCells", job.completedCells());
        response.addProperty("totalCells", job.totalCells());
        response.addProperty("currentRing", job.currentRing());
        response.addProperty("startedAt", job.startedAt());
        response.addProperty("finishedAt", job.finishedAt());
        response.addProperty("errorMessage", job.errorMessage());
        response.addProperty("runDirectory", result.runDirectory().toAbsolutePath().toString());
        response.add("dirtyRegions", stringArray(job.dirtyRegions()));
        response.add("region", regionResponse(region));
        response.add("cellCounts", mapResponse(result.cellCounts()));
        response.add("landformCounts", mapResponse(result.landformCounts()));
        response.add("patchCounts", patchCounts(result));
        response.add("artifacts", refreshArtifacts());
        return response;
    }

    private JsonObject regionResponse(AtlasRegion region) {
        JsonObject response = new JsonObject();
        response.addProperty("regionId", region.regionId());
        response.addProperty("dimensionId", region.dimensionId());
        response.addProperty("regionX", region.regionX());
        response.addProperty("regionZ", region.regionZ());
        response.addProperty("blockMinX", region.blockMinX());
        response.addProperty("blockMinZ", region.blockMinZ());
        response.addProperty("sizeChunks", region.sizeChunks());
        response.addProperty("cellStepBlocks", region.cellStepBlocks());
        response.addProperty("cellsPerSide", region.cellsPerSide());
        response.addProperty("status", region.status().contractName());
        return response;
    }

    private JsonObject patchCounts(RefreshResult result) {
        JsonObject response = new JsonObject();
        response.addProperty("total", result.patches().size());
        int maxPatchCells = result.patches().stream().mapToInt(patch -> patch.cellCount()).max().orElse(0);
        response.addProperty("maxPatchCells", maxPatchCells);
        return response;
    }

    private JsonObject refreshArtifacts() {
        JsonObject response = new JsonObject();
        response.addProperty("progress", "progress.png");
        response.addProperty("progressManifest", "progress_manifest.json");
        response.addProperty("previewManifest", "preview/preview_manifest.json");
        return response;
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
        throw new IllegalArgumentException("centerBlockX and centerBlockZ are required when no player is online.");
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
        return server.getServerDirectory().toPath().resolve("gis_debug");
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

    private static SampleMode sampleModeValue(JsonObject object) {
        String raw = stringValue(object, "sampleMode", SampleMode.PRIOR.contractName());
        for (SampleMode mode : SampleMode.values()) {
            if (mode.contractName().equalsIgnoreCase(raw.trim())) {
                return mode;
            }
        }
        throw new IllegalArgumentException("sampleMode must be one of: prior, observedIfLoaded, verifySurface.");
    }

    private static boolean hasValue(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull();
    }

    private static JsonObject mapResponse(Map<String, Integer> values) {
        JsonObject response = new JsonObject();
        values.forEach(response::addProperty);
        return response;
    }

    private static JsonArray stringArray(Iterable<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
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
}
