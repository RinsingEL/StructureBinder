package com.user.terra_script.server.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.user.terra_script.config.CityGenerationConfig;
import com.user.terra_script.world.city.stage.CityGroupPathUtil;
import com.user.terra_script.world.city.stage.GroupStepStateUtil;
import com.user.terra_script.world.city.stage.c6.CityC6Validation;
import com.user.terra_script.world.city.stage.c7.CityC7Validation;
import com.user.terra_script.server.http.HttpUtil;
import com.user.terra_script.world.NationGenManager;
import com.user.terra_script.world.StructureInjector;
import com.user.terra_script.world.city.CityConfig;
import com.user.terra_script.world.city.CityInstance;
import com.user.terra_script.world.city.CityManager;
import com.user.terra_script.world.city.stage.c4.CitySemanticStages;
import com.user.terra_script.world.city.stage.c4.CityC5ModulePreviewExporter;
import com.user.terra_script.world.city.stage.c4.CityC5GroupTerrainPreviewExporter;
import com.user.terra_script.world.city.stage.c1.CityStage1BinaryIO;
import com.user.terra_script.world.city.stage.c1.CityStage1Processor;
import com.user.terra_script.world.city.stage.c1.CitySurvivalBoundaryExporter;
import com.user.terra_script.world.city.stage.c1.CitySurvivalBoundaryPlanner;
import com.user.terra_script.world.city.stage.c1.intent.CityC1ImageIntentModels;
import com.user.terra_script.world.city.stage.c1.intent.CityC1ImageIntentService;
import com.user.terra_script.world.city.stage.c1.intent.CityC1GeometryIntentService;
import com.user.terra_script.world.city.stage.c2.CityC2ScanBinaryIO;
import com.user.terra_script.world.city.stage.c2.CityC3PolygonPreviewExporter;
import com.user.terra_script.world.city.stage.c2.CityC2SatellitePreviewExporter;
import com.user.terra_script.world.city.stage.c2.CityC3OwnershipIO;
import com.user.terra_script.world.city.stage.c6.C6FillStyle;
import com.user.terra_script.world.city.stage.c6.CityC6Stages;
import com.user.terra_script.world.city.stage.c6.CityC6BuildAreaPreviewExporter;
import com.user.terra_script.world.city.stage.c6.CityC6RectPlacementPreviewExporter;
import com.user.terra_script.world.city.stage.c6.CityC6GroupPreviewExporter;
import com.user.terra_script.world.city.stage.c7.CityC7Stages;
import com.user.terra_script.world.city.stage.c8.CityC8Stages;
import com.user.terra_script.world.city.stage.c8.CityC8ArrangementPreviewExporter;
import com.user.terra_script.world.city.stage.c8.CityC8SubmitDebugTrace;
import com.user.terra_script.world.city.stage.c8.CityC8SubmitPreviewExporter;
import com.user.terra_script.world.city.stage.c8.CityJigsawSolverDebugTrace;
import com.user.terra_script.world.city.stage.c8.CityJigsawSolverPreviewExporter;
import com.user.terra_script.world.city.stage.c9.CityC9Stages;
import com.user.terra_script.world.city.stage.c9.CityC9BuildQueue;
import com.user.terra_script.world.city.execution.GroupSurfaceClearService;
import com.user.terra_script.world.city.execution.ServerLevelBuildWorldAccess;
import com.user.terra_script.world.city.execution.SolvedPlacementExecutionService;
import com.user.terra_script.world.city.stage.c8.CityVanillaJigsawAdapterService;
import com.user.terra_script.world.city.stage.c9.CityC9PlacementPreviewExporter;
import com.user.terra_script.domain.world.scan.ScanPixel;
import com.user.terra_script.domain.world.scan.service.SatelliteScanner;
import com.user.terra_script.runtime.context.RuntimeLogContext;
import com.user.terra_script.runtime.log.RuntimeLogEvent;
import com.user.terra_script.runtime.log.RuntimeLogger;
import com.user.terra_script.world.city.CityBuildQueueExecutor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.nio.file.Path;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

public class CityController {
    private static final int CENTER_WATER_PROBE_RADIUS = 8;
    private static final int CENTER_WATER_PROBE_STEP = 2;
    private static final double CENTER_WATER_RATIO_THRESHOLD = 0.60;
    private static final int C2_SCAN_DEFAULT_STEP = 1;
    private static final int C2_SCAN_DEFAULT_PADDING_BLOCKS = 64;
    // Serialize heavy C2 scans across all cities to avoid concurrent scanner pressure.
    private static final ReentrantLock C2_SCAN_LOCK = new ReentrantLock(true);
    private static final AtomicReference<String> C2_ACTIVE_CITY = new AtomicReference<>(null);
    private static final long C2_MEMORY_CACHE_TTL_MS = 10 * 60 * 1000L;
    private static final Map<String, C2CacheEntry> C2_SCAN_CACHE = new ConcurrentHashMap<>();
    private static final SolvedPlacementExecutionService SOLVED_PLACEMENT_EXECUTION_SERVICE = SolvedPlacementExecutionService.createDefault();
    private static final Gson DEBUG_GSON = new Gson();

    private final Gson gson = new Gson();
    private final MinecraftServer mcServer;

    public CityController(MinecraftServer mcServer) {
        this.mcServer = mcServer;
    }

    public void handleCityC1ImageIntentPrepare(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = body == null || body.isBlank()
                    ? new JsonObject()
                    : JsonParser.parseString(body).getAsJsonObject();
            CityC1ImageIntentModels.PrepareRequest request = CityC1ImageIntentModels.PrepareRequest.fromJson(json);
            JsonObject res = CityC1ImageIntentService.prepare(mcServer, request);
            HttpUtil.sendResponse(exchange, 200, gson.toJson(res));
        } catch (IllegalArgumentException e) {
            HttpUtil.sendResponse(exchange, 400, "{\"error\": \"" + escapeJson(e.getMessage()) + "\"}");
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleCityC1ImageIntentImport(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = body == null || body.isBlank()
                    ? new JsonObject()
                    : JsonParser.parseString(body).getAsJsonObject();
            JsonObject res = CityC1ImageIntentService.importIntentImage(mcServer, json);
            HttpUtil.sendResponse(exchange, 200, gson.toJson(res));
        } catch (IllegalArgumentException e) {
            HttpUtil.sendResponse(exchange, 400, "{\"error\": \"" + escapeJson(e.getMessage()) + "\"}");
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleCityC1ImageIntentData(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = body == null || body.isBlank()
                    ? new JsonObject()
                    : JsonParser.parseString(body).getAsJsonObject();
            String cityId = json.has("city_id") ? json.get("city_id").getAsString() : null;
            JsonObject res = CityC1ImageIntentService.data(mcServer, cityId);
            HttpUtil.sendResponse(exchange, 200, gson.toJson(res));
        } catch (IllegalArgumentException e) {
            HttpUtil.sendResponse(exchange, 400, "{\"error\": \"" + escapeJson(e.getMessage()) + "\"}");
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleCityC1GeometryPrepare(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = body == null || body.isBlank()
                    ? new JsonObject()
                    : JsonParser.parseString(body).getAsJsonObject();
            CityC1ImageIntentModels.PrepareRequest request = CityC1ImageIntentModels.PrepareRequest.fromJson(json);
            JsonObject res = CityC1GeometryIntentService.prepare(mcServer, request);
            HttpUtil.sendResponse(exchange, 200, gson.toJson(res));
        } catch (IllegalArgumentException e) {
            HttpUtil.sendResponse(exchange, 400, "{\"error\": \"" + escapeJson(e.getMessage()) + "\"}");
        } catch (java.io.FileNotFoundException e) {
            HttpUtil.sendResponse(exchange, 404, "{\"error\": \"" + escapeJson(e.getMessage()) + "\"}");
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleCityC1GeometryImport(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = body == null || body.isBlank()
                    ? new JsonObject()
                    : JsonParser.parseString(body).getAsJsonObject();
            JsonObject res = CityC1GeometryIntentService.importGeometry(mcServer, json);
            HttpUtil.sendResponse(exchange, 200, gson.toJson(res));
        } catch (IllegalArgumentException e) {
            HttpUtil.sendResponse(exchange, 400, "{\"error\": \"" + escapeJson(e.getMessage()) + "\"}");
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleCityC1GeometryPatch(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = body == null || body.isBlank()
                    ? new JsonObject()
                    : JsonParser.parseString(body).getAsJsonObject();
            JsonObject res = CityC1GeometryIntentService.patchGeometry(mcServer, json);
            HttpUtil.sendResponse(exchange, 200, gson.toJson(res));
        } catch (IllegalArgumentException e) {
            HttpUtil.sendResponse(exchange, 400, "{\"error\": \"" + escapeJson(e.getMessage()) + "\"}");
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleCityC1GeometryData(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = body == null || body.isBlank()
                    ? new JsonObject()
                    : JsonParser.parseString(body).getAsJsonObject();
            String cityId = json.has("city_id") ? json.get("city_id").getAsString() : null;
            JsonObject res = CityC1GeometryIntentService.data(mcServer, cityId);
            HttpUtil.sendResponse(exchange, 200, gson.toJson(res));
        } catch (IllegalArgumentException e) {
            HttpUtil.sendResponse(exchange, 400, "{\"error\": \"" + escapeJson(e.getMessage()) + "\"}");
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleCityHeightmap(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String cityId = json.has("city_id") ? json.get("city_id").getAsString() : null;
            if (cityId == null || cityId.isBlank()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Missing city_id\"}");
                return;
            }

            int minX = json.has("min_x") ? json.get("min_x").getAsInt() : Integer.MIN_VALUE;
            int minZ = json.has("min_z") ? json.get("min_z").getAsInt() : Integer.MIN_VALUE;
            int w = json.has("width") ? json.get("width").getAsInt() : -1;
            int h = json.has("height") ? json.get("height").getAsInt() : -1;

            CityStage1BinaryIO.HeightData data = CityStage1BinaryIO.loadHeightData(cityId);
            if (data == null) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"Heightmap not found for: " + cityId + "\"}");
                return;
            }

            if (minX == Integer.MIN_VALUE || minZ == Integer.MIN_VALUE || w <= 0 || h <= 0) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"min_x/min_z/width/height required\"}");
                return;
            }

            int startX = minX - data.originX;
            int startZ = minZ - data.originZ;
            if (startX < 0 || startZ < 0 || startX + w > data.width || startZ + h > data.height) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Requested window out of bounds\"}");
                return;
            }

            JsonObject res = new JsonObject();
            res.addProperty("origin_x", minX);
            res.addProperty("origin_z", minZ);
            res.addProperty("width", w);
            res.addProperty("height", h);

            JsonArray rows = new JsonArray();
            for (int z = 0; z < h; z++) {
                JsonArray row = new JsonArray();
                for (int x = 0; x < w; x++) {
                    row.add(data.heightMap[startX + x][startZ + z]);
                }
                rows.add(row);
            }
            res.add("heights", rows);

            HttpUtil.sendResponse(exchange, 200, gson.toJson(res));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleCityStage1Data(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String cityId = json.has("city_id") ? json.get("city_id").getAsString() : null;
            if (cityId == null || cityId.isBlank()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Missing city_id\"}");
                return;
            }

            var data = NationGenManager.Stage1Manager.load(cityId);
            if (data == null) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"Stage1 not found for: " + cityId + "\"}");
                return;
            }

            HttpUtil.sendResponse(exchange, 200, gson.toJson(data));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleCityC2Generate(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String cityId = json.has("city_id") ? json.get("city_id").getAsString() : null;
            if (cityId == null || cityId.isBlank()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Missing city_id\"}");
                return;
            }
            String taskId = "city_c2_" + cityId;
            RuntimeLogger logger = RuntimeLogger.forServer(
                    mcServer,
                    RuntimeLogContext.builder()
                            .domain("city")
                            .scope("task")
                            .taskId(taskId)
                            .stageId("C2")
                            .cityId(cityId)
                            .build()
            );
            JsonObject requestDetails = new JsonObject();
            requestDetails.addProperty("city_id", cityId);
            if (json.has("scan_step")) requestDetails.addProperty("scan_step", json.get("scan_step").getAsInt());
            if (json.has("scan_padding_blocks")) requestDetails.addProperty("scan_padding_blocks", json.get("scan_padding_blocks").getAsInt());
            logger.info(RuntimeLogEvent.TASK_STARTED, "Starting city C2 generation.", requestDetails);
            if (mcServer == null || mcServer.overworld() == null) {
                JsonObject errorDetails = new JsonObject();
                errorDetails.addProperty("city_id", cityId);
                logger.error(RuntimeLogEvent.TASK_FAILED, "Minecraft server/overworld unavailable.", errorDetails);
                HttpUtil.sendResponse(exchange, 500, "{\"error\": \"Minecraft server/overworld unavailable\"}");
                return;
            }

            long waitStart = System.currentTimeMillis();
            C2_SCAN_LOCK.lock();
            long waitedMs = Math.max(0L, System.currentTimeMillis() - waitStart);
            C2_ACTIVE_CITY.set(cityId);
            try {
                var result = NationGenManager.Stage1Manager.computeAndSave(mcServer.overworld(), cityId);
                if (result == null) {
                    HttpUtil.sendResponse(exchange, 404, "{\"error\": \"City not found: " + cityId + "\"}");
                    return;
                }

                CityInstance city = CityManager.get().getCity(cityId);
                if (city == null) {
                    HttpUtil.sendResponse(exchange, 404, "{\"error\": \"City not found after C2 compute: " + cityId + "\"}");
                    return;
                }

                int scanStep = json.has("scan_step") ? Math.max(1, json.get("scan_step").getAsInt()) : C2_SCAN_DEFAULT_STEP;
                int scanPadding = json.has("scan_padding_blocks")
                        ? Math.max(0, json.get("scan_padding_blocks").getAsInt())
                        : C2_SCAN_DEFAULT_PADDING_BLOCKS;
                CityScanBounds scanBounds = computeCityScanBounds(city, scanPadding);
                C2ScanResolved resolved = resolveC2ScanData(
                        cityId,
                        scanBounds,
                        scanStep,
                        mcServer.overworld()
                );
                ScanPixel[][] scanned = resolved.map;
                File c2ScanFile = resolved.file;

                JsonObject preview = CityC2SatellitePreviewExporter.export(
                        mcServer,
                        cityId,
                        scanned,
                        scanBounds.minX,
                        scanBounds.minZ,
                        scanStep,
                        scanBounds.width,
                        scanBounds.height,
                        city.config.centerX,
                        city.config.centerZ,
                        city.claimedChunks
                );

                JsonObject res = new JsonObject();
                res.addProperty("status", "ok");
                res.addProperty("step", "C2");
                res.addProperty("city_id", cityId);
                res.addProperty("district_count", result.buildableStats != null ? result.buildableStats.size() : 0);
                res.addProperty("buildable_group_count", result.buildableGroups != null ? result.buildableGroups.size() : 0);
                res.addProperty("scan_step", scanStep);
                res.addProperty("scan_padding_blocks", scanPadding);
                res.addProperty("scan_data_file", c2ScanFile.getName());
                res.addProperty("scan_serialized", true);
                res.addProperty("scan_queue_wait_ms", waitedMs);
                res.addProperty("scan_cache_hit", resolved.cacheHit);
                res.addProperty("scan_cache_source", resolved.cacheSource);
                res.addProperty("task_id", taskId);
                res.addProperty("state", "SUCCEEDED");
                res.add("satellite_preview", preview);
                res.addProperty("ai_should_pause", true);
                res.addProperty("next_action", "STOP_CURRENT_STEP_AND_REVIEW_C2_SATELLITE_PREVIEW");
                res.addProperty("message", "C2 completed and city-local satellite preview is generated. Pause current step, save, and review preview before continuing.");
                logger.info(RuntimeLogEvent.TASK_COMPLETED, "City C2 generation completed.", res);
                HttpUtil.sendResponse(exchange, 200, gson.toJson(res));
            } finally {
                C2_ACTIVE_CITY.set(null);
                C2_SCAN_LOCK.unlock();
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            JsonObject errorDetails = new JsonObject();
            errorDetails.addProperty("error", e.getMessage() != null ? e.getMessage() : "Invalid city request");
            RuntimeLogger.forServer(
                    mcServer,
                    RuntimeLogContext.builder()
                            .domain("city")
                            .scope("task")
                            .stageId("C2")
                            .build()
            ).error(RuntimeLogEvent.TASK_FAILED, "City C2 generation failed.", errorDetails);
            HttpUtil.sendResponse(exchange, 400, "{\"error\": \"" + escapeJson(e.getMessage() != null ? e.getMessage() : "Invalid city request") + "\"}");
        } catch (Exception e) {
            JsonObject errorDetails = new JsonObject();
            errorDetails.addProperty("error", e.getMessage() != null ? e.getMessage() : "unknown");
            RuntimeLogger.forServer(
                    mcServer,
                    RuntimeLogContext.builder()
                            .domain("city")
                            .scope("task")
                            .stageId("C2")
                            .build()
            ).error(RuntimeLogEvent.TASK_FAILED, "City C2 generation crashed.", errorDetails);
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleCityStage2Data(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String cityId = json.has("city_id") ? json.get("city_id").getAsString() : null;
            if (cityId == null || cityId.isBlank()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Missing city_id\"}");
                return;
            }

            var data = NationGenManager.Stage2Manager.load(cityId);
            if (data == null) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"Stage2 not found for: " + cityId + "\"}");
                return;
            }

            HttpUtil.sendResponse(exchange, 200, gson.toJson(data));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleCityC3Generate(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String cityId = json.has("city_id") ? json.get("city_id").getAsString() : null;
            if (cityId == null || cityId.isBlank()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Missing city_id\"}");
                return;
            }

            var result = NationGenManager.Stage2Manager.computeAndSave(cityId);
            if (result == null) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"Stage1 not found for: " + cityId + "\"}");
                return;
            }

            CityInstance city = CityManager.get().getCity(cityId);
            CityC2ScanBinaryIO.C2ScanData scanData = CityC2ScanBinaryIO.load(cityId);
            Path cityDir = resolveCityDir(cityId);
            CityC3OwnershipIO.OwnershipData ownership = CityC3OwnershipIO.compute(city);
            Path ownershipFile = CityC3OwnershipIO.save(cityDir, ownership);
            JsonObject polygonPreview = CityC3PolygonPreviewExporter.export(mcServer, cityId, city, scanData, ownership);

            JsonObject res = new JsonObject();
            res.addProperty("status", "ok");
            res.addProperty("step", "C3");
            res.addProperty("city_id", cityId);
            res.addProperty("intent_count", result.intents != null ? result.intents.size() : 0);
            if (ownershipFile != null) {
                res.addProperty("ownership_file", ownershipFile.toString());
                res.addProperty("ownership_step", ownership != null ? ownership.step : -1);
            }
            res.add("polygon_preview", polygonPreview);
            res.addProperty("ai_should_pause", true);
            res.addProperty("next_action", "STOP_CURRENT_STEP_AND_REVIEW_C3_POLYGON_PREVIEW");
            res.addProperty("message", "C3 completed and polygon preview is generated from C2 step scan data.");
            HttpUtil.sendResponse(exchange, 200, gson.toJson(res));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleCityForbidden(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String cityId = json.has("city_id") ? json.get("city_id").getAsString() : null;
            if (cityId == null || cityId.isBlank()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Missing city_id\"}");
                return;
            }

            var data = CityStage1BinaryIO.loadForbidden(cityId);
            if (data == null) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"Forbidden data not found for: " + cityId + "\"}");
                return;
            }

            HttpUtil.sendResponse(exchange, 200, gson.toJson(data));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleCityBuildableGroups(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String cityId = json.has("city_id") ? json.get("city_id").getAsString() : null;
            if (cityId == null || cityId.isBlank()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Missing city_id\"}");
                return;
            }

            var data = CityStage1BinaryIO.loadBuildableGroups(cityId);
            if (data == null) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"Buildable groups not found for: " + cityId + "\"}");
                return;
            }

            HttpUtil.sendResponse(exchange, 200, gson.toJson(data));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleCityC4Generate(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String cityId = json.has("city_id") ? json.get("city_id").getAsString() : null;
            if (cityId == null || cityId.isBlank()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Missing city_id\"}");
                return;
            }

            CityInstance city = CityManager.get().getCity(cityId);
            if (city == null) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"City not found: " + cityId + "\"}");
                return;
            }

            Path cityDir = resolveCityDir(cityId);
            CitySemanticStages.FunctionWhitelist whitelist = CitySemanticStages.loadFunctionWhitelist(cityDir);
            if (whitelist == null || !whitelist.ok) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Missing/invalid C4 whitelist. Run city_c4_whitelist_generate before C4.\"}");
                return;
            }

            CitySemanticStages.C4Plan plan = CitySemanticStages.generateC4(city, whitelist);
            CitySemanticStages.saveC4(cityDir, plan);

            JsonObject res = new JsonObject();
            res.addProperty("status", "ok");
            res.addProperty("step", "C4");
            res.addProperty("city_id", cityId);
            res.addProperty("district_count", plan.district_functions != null ? plan.district_functions.size() : 0);
            res.addProperty("function_whitelist_version", plan.function_whitelist_version);
            res.addProperty("whitelist_file", cityDir.resolve(CitySemanticStages.C4_WHITELIST_FILE).toString());
            res.addProperty("file", cityDir.resolve(CitySemanticStages.C4_FILE).toString());
            res.addProperty("validated_file", cityDir.resolve(CitySemanticStages.C4_VALIDATED_FILE).toString());
            HttpUtil.sendResponse(exchange, 200, gson.toJson(res));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleCityC4WhitelistGenerate(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String cityId = json.has("city_id") ? json.get("city_id").getAsString() : null;
            if (cityId == null || cityId.isBlank()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Missing city_id\"}");
                return;
            }

            CityInstance city = CityManager.get().getCity(cityId);
            if (city == null) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"City not found: " + cityId + "\"}");
                return;
            }

            List<String> primary = readStringList(json, "primary_functions");
            List<String> secondary = readStringList(json, "secondary_functions");
            if (primary.isEmpty() || secondary.isEmpty()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"primary_functions and secondary_functions are required and cannot be empty.\"}");
                return;
            }

            CitySemanticStages.FunctionWhitelist whitelist = new CitySemanticStages.FunctionWhitelist();
            whitelist.city_id = cityId;
            whitelist.generated_at_epoch_ms = System.currentTimeMillis();
            whitelist.version = json.has("version") ? json.get("version").getAsString() : "ai_dynamic_v1";
            whitelist.source = json.has("source") ? json.get("source").getAsString() : "mcp_ai";
            whitelist.rationale = json.has("rationale") ? json.get("rationale").getAsString() : "";
            whitelist.primary_functions = primary;
            whitelist.secondary_functions = secondary;

            Path cityDir = resolveCityDir(cityId);
            CitySemanticStages.saveFunctionWhitelist(cityDir, whitelist);
            CitySemanticStages.FunctionWhitelist saved = CitySemanticStages.loadFunctionWhitelist(cityDir);

            JsonObject res = new JsonObject();
            res.addProperty("status", "ok");
            res.addProperty("step", "C4_WHITELIST");
            res.addProperty("city_id", cityId);
            res.addProperty("function_whitelist_version", saved != null ? saved.version : whitelist.version);
            res.addProperty("primary_count", saved != null && saved.primary_functions != null ? saved.primary_functions.size() : primary.size());
            res.addProperty("secondary_count", saved != null && saved.secondary_functions != null ? saved.secondary_functions.size() : secondary.size());
            res.addProperty("file", cityDir.resolve(CitySemanticStages.C4_WHITELIST_FILE).toString());
            HttpUtil.sendResponse(exchange, 200, gson.toJson(res));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleCityC4WhitelistData(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String cityId = json.has("city_id") ? json.get("city_id").getAsString() : null;
            if (cityId == null || cityId.isBlank()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Missing city_id\"}");
                return;
            }
            Path cityDir = resolveCityDir(cityId);
            CitySemanticStages.FunctionWhitelist whitelist = CitySemanticStages.loadFunctionWhitelist(cityDir);
            if (whitelist == null) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"C4 whitelist not found for: " + cityId + "\"}");
                return;
            }
            HttpUtil.sendResponse(exchange, 200, gson.toJson(whitelist));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleCityC4Data(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String cityId = json.has("city_id") ? json.get("city_id").getAsString() : null;
            if (cityId == null || cityId.isBlank()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Missing city_id\"}");
                return;
            }
            Path cityDir = resolveCityDir(cityId);
            CitySemanticStages.C4Plan plan = CitySemanticStages.loadC4(cityDir);
            if (plan == null) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"C4 data not found for: " + cityId + "\"}");
                return;
            }
            HttpUtil.sendResponse(exchange, 200, gson.toJson(plan));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleCityC5Generate(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String cityId = json.has("city_id") ? json.get("city_id").getAsString() : null;
            if (cityId == null || cityId.isBlank()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Missing city_id\"}");
                return;
            }
            CityInstance city = CityManager.get().getCity(cityId);
            if (city == null) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"City not found: " + cityId + "\"}");
                return;
            }

            Path cityDir = resolveCityDir(cityId);
            CitySemanticStages.C4Plan c4Plan = CitySemanticStages.loadC4Validated(cityDir);
            if (c4Plan == null) {
                c4Plan = CitySemanticStages.loadC4(cityDir);
            }
            if (c4Plan == null) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"C4 data missing. Run city_c4_whitelist_generate then city_c4_generate first.\"}");
                return;
            }

            CitySemanticStages.MergePolicy policy = parseC5MergePolicy(json);
            CitySemanticStages.C5Groups groups = CitySemanticStages.generateC5(city, c4Plan, policy);
            CitySemanticStages.saveC5(cityDir, groups);
            CityC2ScanBinaryIO.C2ScanData scanData = CityC2ScanBinaryIO.load(cityId);
            CityC3OwnershipIO.OwnershipData ownership = CityC3OwnershipIO.load(cityDir);
            JsonObject modulePreview = CityC5ModulePreviewExporter.export(mcServer, cityId, city, groups, scanData, ownership);

            JsonObject res = new JsonObject();
            res.addProperty("status", "ok");
            res.addProperty("step", "C5");
            res.addProperty("city_id", cityId);
            res.addProperty("review_required", true);
            res.addProperty("next_action", "REVIEW_C5_PREVIEW_AND_MERGE_RESULT");
            res.addProperty("group_count", groups.groups != null ? groups.groups.size() : 0);
            res.addProperty("cross_layer_merge", groups.policy != null && groups.policy.cross_layer_merge);
            res.add("policy", gson.toJsonTree(groups.policy));
            res.addProperty("merge_log_count", groups.merge_log != null ? groups.merge_log.size() : 0);
            res.addProperty("file", cityDir.resolve(CitySemanticStages.C5_FILE).toString());
            res.addProperty("merge_log_file", cityDir.resolve(CitySemanticStages.C5_MERGE_LOG_FILE).toString());
            res.addProperty("c5_groups_file", cityDir.resolve(CitySemanticStages.C5_FILE).toString());
            res.addProperty("c5_merge_log_file", cityDir.resolve(CitySemanticStages.C5_MERGE_LOG_FILE).toString());
            res.add("module_preview", modulePreview);
            HttpUtil.sendResponse(exchange, 200, gson.toJson(res));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleCityC5Data(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String cityId = json.has("city_id") ? json.get("city_id").getAsString() : null;
            if (cityId == null || cityId.isBlank()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Missing city_id\"}");
                return;
            }
            Path cityDir = resolveCityDir(cityId);
            CitySemanticStages.C5Groups groups = CitySemanticStages.loadC5(cityDir);
            if (groups == null) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"C5 data not found for: " + cityId + "\"}");
                return;
            }
            CityC2ScanBinaryIO.C2ScanData scanData = CityC2ScanBinaryIO.load(cityId);
            CityC3OwnershipIO.OwnershipData ownership = CityC3OwnershipIO.load(cityDir);
            CityInstance city = CityManager.get().getCity(cityId);
            JsonObject modulePreview = null;
            if (mcServer != null && city != null && scanData != null && ownership != null) {
                modulePreview = CityC5ModulePreviewExporter.export(mcServer, cityId, city, groups, scanData, ownership);
            }

            JsonObject res = new JsonObject();
            res.addProperty("status", "ok");
            res.addProperty("step", "C5");
            res.addProperty("city_id", cityId);
            res.addProperty("review_required", true);
            res.addProperty("next_action", "REVIEW_C5_PREVIEW_AND_MERGE_RESULT");
            res.addProperty("group_count", groups.groups != null ? groups.groups.size() : 0);
            res.addProperty("merge_log_count", groups.merge_log != null ? groups.merge_log.size() : 0);
            res.add("policy", gson.toJsonTree(groups.policy));
            res.add("groups", gson.toJsonTree(groups.groups));
            res.add("merge_stats", gson.toJsonTree(groups.merge_stats));
            res.add("quality", gson.toJsonTree(groups.quality));
            res.add("merge_log", gson.toJsonTree(groups.merge_log));
            res.addProperty("c5_groups_file", cityDir.resolve(CitySemanticStages.C5_FILE).toString());
            res.addProperty("c5_merge_log_file", cityDir.resolve(CitySemanticStages.C5_MERGE_LOG_FILE).toString());
            JsonObject mergeSummary = new JsonObject();
            mergeSummary.addProperty("group_count", groups.groups != null ? groups.groups.size() : 0);
            mergeSummary.addProperty("merge_log_count", groups.merge_log != null ? groups.merge_log.size() : 0);
            mergeSummary.addProperty("fragment_reduction_ratio", groups.merge_stats != null ? groups.merge_stats.fragment_reduction_ratio : 0.0);
            mergeSummary.addProperty("disconnected_groups", groups.quality != null ? groups.quality.disconnected_groups : 0);
            mergeSummary.addProperty("low_compactness_groups", groups.quality != null ? groups.quality.low_compactness_groups : 0);
            res.add("merge_log_summary", mergeSummary);
            if (modulePreview != null) {
                res.add("module_preview", modulePreview);
                res.add("preview_references", modulePreview.deepCopy());
            }
            HttpUtil.sendResponse(exchange, 200, gson.toJson(res));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleCityC6Generate(HttpExchange exchange) throws IOException {
        handleCityC6RectPrepare(exchange);
    }

    public void handleCityC6RectPrepare(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String cityId = json.has("city_id") ? json.get("city_id").getAsString() : null;
            String groupId = readOptionalString(json, "group_id");
            System.out.println("[C6] city_c6_rect_prepare request city_id=" + cityId + " group_id=" + groupId);
            if (cityId == null || cityId.isBlank()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Missing city_id\"}");
                return;
            }
            C6FillStyle fillStyle = C6FillStyle.parseOrDefault(
                    json.has("fill_style") ? json.get("fill_style").getAsString() : null,
                    C6FillStyle.PLAZA_RING
            );

            CityInstance city = CityManager.get().getCity(cityId);
            if (city == null) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"City not found: " + cityId + "\"}");
                return;
            }

            Path cityDir = resolveCityDir(cityId);
            CitySemanticStages.C5Groups c5 = CitySemanticStages.loadC5(cityDir);
            if (c5 == null) {
                CitySemanticStages.C4Plan c4 = CitySemanticStages.loadC4Validated(cityDir);
                if (c4 == null) c4 = CitySemanticStages.loadC4(cityDir);
                if (c4 == null) {
                    HttpUtil.sendResponse(exchange, 400, "{\"error\": \"C4 data missing. Run city_c4_whitelist_generate then city_c4_generate first.\"}");
                    return;
                }
                c5 = CitySemanticStages.generateC5(city, c4, CitySemanticStages.MergePolicy.defaults());
                CitySemanticStages.saveC5(cityDir, c5);
            }

            CityStage1BinaryIO.HeightData heightData = CityStage1BinaryIO.loadHeightData(cityId);
            CityC2ScanBinaryIO.C2ScanData c2ScanData = CityC2ScanBinaryIO.load(cityId);
            List<List<CityStage1Processor.BlockCoord>> buildableGroups = CityStage1BinaryIO.loadBuildableGroups(cityId);
            if (heightData == null || buildableGroups == null) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"Stage1 binary data not found for: " + cityId + "\"}");
                return;
            }
            List<CityStage1Processor.ForbiddenBlock> forbiddenBlocks = CityStage1BinaryIO.loadForbidden(cityId);
            CityC3OwnershipIO.OwnershipData ownership = CityC3OwnershipIO.load(cityDir);

            CityC6Stages.C6Bundle bundle = CityC6Stages.generate(city, c5, heightData, c2ScanData, buildableGroups, ownership, fillStyle);
            System.out.println("[C6] city_c6_rect_prepare generated city_id=" + cityId
                    + " group_id=" + (groupId != null ? groupId : "all")
                    + " areas=" + (bundle.summary != null && bundle.summary.areas != null ? bundle.summary.areas.size() : 0)
                    + " plans=" + (bundle.layout != null && bundle.layout.plans != null ? bundle.layout.plans.size() : 0));
            CityC6Stages.C6Layout existingLayout = CityC6Stages.loadLayout(cityDir);
            CityC6Stages.C6RectCandidates existingCandidates = CityC6Stages.loadCandidates(cityDir);
            CityC6Stages.C6RectValidation existingValidation = CityC6Stages.loadValidation(cityDir);
            if (existingLayout != null && existingLayout.plans != null && !existingLayout.plans.isEmpty()) {
                for (CityC6Stages.LayoutPlan existingPlan : existingLayout.plans) {
                    CityC6Stages.LayoutPlan freshPlan = CityC6Stages.findPlanByGroup(bundle.layout, existingPlan != null ? existingPlan.group_id : null);
                    if (freshPlan != null && existingPlan != null && existingPlan.validated) {
                        freshPlan.primary_modules = existingPlan.primary_modules != null ? existingPlan.primary_modules : new ArrayList<>();
                        freshPlan.validated = true;
                        freshPlan.decision_mode = existingPlan.decision_mode;
                        freshPlan.accepted_attempt_index = existingPlan.accepted_attempt_index;
                        freshPlan.notes = existingPlan.notes;
                    }
                }
            }
            if (existingCandidates != null) bundle.candidates = existingCandidates;
            if (existingValidation != null) bundle.validation = existingValidation;
            if (bundle.decision_input != null && bundle.decision_input.groups != null) {
                for (CityC6Stages.GroupDecisionInput item : bundle.decision_input.groups) {
                    item.current_attempt_count = CityC6Stages.currentAttemptCount(bundle.candidates, item.group_id);
                    if (groupId == null || groupId.equals(item.group_id)) {
                        System.out.println("[C6] prepare guidance group=" + item.group_id
                                + " candidate_count=" + (item.rect_guidance != null ? item.rect_guidance.candidate_count : 0)
                                + " edge_buffer=" + (item.rect_guidance != null ? item.rect_guidance.edge_buffer_blocks : 0)
                                + " growth_buffer=" + (item.rect_guidance != null ? item.rect_guidance.growth_buffer_blocks : 0)
                                + " expansion_side=" + (item.rect_guidance != null ? item.rect_guidance.requires_expansion_side : "")
                                + " connector_reserve=" + (item.rect_guidance != null ? item.rect_guidance.connector_reserve_by_side : null));
                    }
                }
            }
            CityC6Stages.save(cityDir, bundle);
            if (c2ScanData != null && ownership != null) {
                CityC5GroupTerrainPreviewExporter.export(mcServer, cityId, c5, c2ScanData, ownership);
            }
            JsonObject c6Preview = CityC6BuildAreaPreviewExporter.export(
                    mcServer,
                    cityId,
                    city,
                    heightData,
                    bundle.index_by_block,
                    forbiddenBlocks,
                    bundle.summary
            );
            JsonObject rectPreview = CityC6RectPlacementPreviewExporter.export(
                    mcServer,
                    cityId,
                    heightData,
                    bundle.summary,
                    bundle.layout,
                    bundle.index_by_block
            );
            JsonObject groupPreview = CityC6GroupPreviewExporter.export(
                    mcServer,
                    cityId,
                    heightData,
                    c2ScanData,
                    bundle.summary,
                    bundle.decision_input,
                    bundle.candidates,
                    bundle.validation,
                    bundle.index_by_block
            );
            CityC6Stages.saveDecisionInput(cityDir, bundle.decision_input);

            JsonObject res = new JsonObject();
            res.addProperty("status", "ok");
            res.addProperty("step", "C6_prepare");
            res.addProperty("city_id", cityId);
            if (groupId != null) res.addProperty("group_id", groupId);
            res.addProperty("fill_style", fillStyle.name());
            res.addProperty("area_count", bundle.summary != null && bundle.summary.areas != null ? bundle.summary.areas.size() : 0);
            res.addProperty("plan_count", bundle.layout != null && bundle.layout.plans != null ? bundle.layout.plans.size() : 0);
            res.addProperty("indexed_block_count", bundle.indexed_block_count);
            res.addProperty("summary_file", cityDir.resolve(CityC6Stages.C6_SUMMARY_FILE).toString());
            res.addProperty("layout_file", cityDir.resolve(CityC6Stages.C6_LAYOUT_FILE).toString());
            res.addProperty("decision_input_file", cityDir.resolve(CityC6Stages.C6_RECT_DECISION_INPUT_FILE).toString());
            res.addProperty("candidates_file", cityDir.resolve(CityC6Stages.C6_RECT_CANDIDATES_FILE).toString());
            res.addProperty("validation_file", cityDir.resolve(CityC6Stages.C6_RECT_VALIDATION_FILE).toString());
            res.addProperty("index_file", cityDir.resolve(CityC6Stages.C6_INDEX_FILE).toString());
            res.add("build_area_preview", c6Preview);
            res.add("rect_placement_preview", rectPreview);
            res.add("group_previews", groupPreview);
            res.add("decision_input", gson.toJsonTree(filterDecisionInputByGroup(bundle.decision_input, groupId)));
            System.out.println("[C6] city_c6_rect_prepare response city_id=" + cityId
                    + " group_id=" + (groupId != null ? groupId : "all")
                    + " indexed_block_count=" + bundle.indexed_block_count);
            HttpUtil.sendResponse(exchange, 200, gson.toJson(res));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleCityC6RectSubmit(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String cityId = readOptionalString(json, "city_id");
            String groupId = readOptionalString(json, "group_id");
            String decisionMode = readOptionalString(json, "decision_mode");
            int attemptIndex = json.has("attempt_index") ? json.get("attempt_index").getAsInt() : 0;
            System.out.println("[C6] city_c6_rect_submit request city_id=" + cityId
                    + " group_id=" + groupId
                    + " decision_mode=" + decisionMode
                    + " attempt_index=" + attemptIndex);
            if (cityId == null || groupId == null || decisionMode == null) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Missing city_id/group_id/decision_mode\"}");
                return;
            }

            Path cityDir = resolveCityDir(cityId);
            CityC6Stages.C6Summary summary = CityC6Stages.loadSummary(cityDir);
            CityC6Stages.C6Layout layout = CityC6Stages.loadLayout(cityDir);
            CityC6Stages.C6RectDecisionInput input = CityC6Stages.loadDecisionInput(cityDir);
            CityC6Stages.C6RectCandidates candidates = CityC6Stages.loadCandidates(cityDir);
            CityC6Stages.C6RectValidation validation = CityC6Stages.loadValidation(cityDir);
            Map<Long, Integer> indexByBlock = CityC6Stages.loadIndex(cityDir);
            CityStage1BinaryIO.HeightData heightData = CityStage1BinaryIO.loadHeightData(cityId);
            CityC2ScanBinaryIO.C2ScanData c2ScanData = CityC2ScanBinaryIO.load(cityId);
            if (summary == null || layout == null || input == null || indexByBlock == null || heightData == null) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"Required C6 prepare data not found. Run city_c6_rect_prepare first.\"}");
                return;
            }

            CityC6Stages.BuildAreaSummary area = findBestAreaByGroup(summary, groupId);
            if (area == null) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"C6 group not found: " + groupId + "\"}");
                return;
            }

            int currentAttemptCount = CityC6Stages.currentAttemptCount(candidates, groupId);
            if (attemptIndex < 1) attemptIndex = currentAttemptCount + 1;
            if (attemptIndex != currentAttemptCount + 1 && attemptIndex <= CityC6Stages.RECT_ATTEMPT_LIMIT) {
                HttpUtil.sendResponse(exchange, 409, "{\"error\": \"attempt_index must be sequential\"}");
                return;
            }

            CityC6Stages.GroupRectCandidate candidate = new CityC6Stages.GroupRectCandidate();
            candidate.group_id = groupId;
            candidate.build_area_id = area.build_area_id;
            candidate.attempt_index = attemptIndex;
            candidate.decision_mode = decisionMode;
            candidate.rects = parseSubmittedRects(json);

            CityC6Stages.GroupRectValidation validationItem = CityC6Validation.validateSubmission(
                    area,
                    CityC6Stages.findDecisionGroup(input, groupId),
                    candidate,
                    indexByBlock,
                    CityC6Stages.RECT_ATTEMPT_LIMIT
            );
            System.out.println("[C6] submit validation group=" + groupId
                    + " attempt=" + attemptIndex
                    + " accepted=" + validationItem.accepted
                    + " reason=" + validationItem.reason
                    + " structure_compatible_rects=" + validationItem.structure_compatible_rects
                    + " matching_main_template_count=" + validationItem.matching_main_template_count);

            boolean finalized = false;
            if ("keep_current".equals(decisionMode)) {
                CityC6Stages.LayoutPlan existingPlan = CityC6Stages.findPlanByGroup(layout, groupId);
                finalized = existingPlan != null && existingPlan.validated;
                if (!finalized) {
                    validationItem.accepted = false;
                    validationItem.continue_allowed = attemptIndex < CityC6Stages.RECT_ATTEMPT_LIMIT;
                    validationItem.decision_terminal = !validationItem.continue_allowed;
                    validationItem.reason = "keep_current_without_validated_layout";
                }
            } else if ("no_primary_module".equals(decisionMode)) {
                CityC6Stages.applyAcceptedDecision(layout, area, candidate);
                finalized = true;
            } else if (validationItem.accepted) {
                CityC6Stages.applyAcceptedDecision(layout, area, candidate);
                finalized = true;
            }
            validationItem.finalized_into_layout = finalized;

            CityC6Stages.upsertCandidate(candidates, candidate);
            CityC6Stages.upsertValidation(validation, validationItem);
            CityC6Stages.updateAttemptCount(input, groupId, Math.max(currentAttemptCount, attemptIndex));
            CityC6Stages.saveCandidates(cityDir, candidates);
            CityC6Stages.saveValidation(cityDir, validation);
            CityC6Stages.saveDecisionInput(cityDir, input);
            if (finalized) {
                CityC6Stages.saveLayout(cityDir, layout);
            }

            JsonObject groupPreview = CityC6GroupPreviewExporter.export(
                    mcServer,
                    cityId,
                    heightData,
                    c2ScanData,
                    summary,
                    input,
                    candidates,
                    validation,
                    indexByBlock
            );
            Path validationFile = CityC6Validation.save(cityDir, groupId, validationItem);

            JsonObject res = new JsonObject();
            res.addProperty("status", validationItem.accepted || finalized ? "ok" : "invalid");
            res.addProperty("step", "C6_decide_validate");
            res.addProperty("city_id", cityId);
            res.addProperty("group_id", groupId);
            res.addProperty("attempt_index", attemptIndex);
            res.addProperty("decision_mode", decisionMode);
            res.addProperty("continue_allowed", validationItem.continue_allowed);
            res.addProperty("decision_terminal", validationItem.decision_terminal);
            res.addProperty("finalized_into_layout", finalized);
            res.addProperty("validation_file", validationFile.toString());
            res.add("validation", gson.toJsonTree(validationItem));
            res.add("group_previews", groupPreview);
            int statusCode = (validationItem.accepted || finalized) ? 200 : (validationItem.decision_terminal ? 409 : 422);
            System.out.println("[C6] city_c6_rect_submit response group=" + groupId
                    + " status=" + statusCode
                    + " finalized=" + finalized
                    + " continue_allowed=" + validationItem.continue_allowed
                    + " reason=" + validationItem.reason);
            HttpUtil.sendResponse(exchange, statusCode, gson.toJson(res));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleCityC6Data(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String cityId = json.has("city_id") ? json.get("city_id").getAsString() : null;
            String groupId = readOptionalString(json, "group_id");
            if (cityId == null || cityId.isBlank()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Missing city_id\"}");
                return;
            }
            Path cityDir = resolveCityDir(cityId);
            CityC6Stages.C6Summary summary = CityC6Stages.loadSummary(cityDir);
            CityC6Stages.C6Layout layout = CityC6Stages.loadLayout(cityDir);
            CityC6Stages.C6RectDecisionInput input = CityC6Stages.loadDecisionInput(cityDir);
            CityC6Stages.C6RectCandidates candidates = CityC6Stages.loadCandidates(cityDir);
            CityC6Stages.C6RectValidation validation = CityC6Stages.loadValidation(cityDir);
            if (summary == null && layout == null && input == null) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"C6 data not found for: " + cityId + "\"}");
                return;
            }
            JsonObject res = new JsonObject();
            res.addProperty("step", "C6");
            res.addProperty("ok", true);
            res.add("summary", gson.toJsonTree(filterC6SummaryByGroup(summary, groupId)));
            res.add("layout", gson.toJsonTree(filterC6LayoutByGroup(layout, groupId)));
            res.add("decision_input", gson.toJsonTree(filterDecisionInputByGroup(input, groupId)));
            res.add("candidates", gson.toJsonTree(filterCandidatesByGroup(candidates, groupId)));
            res.add("validation", gson.toJsonTree(filterValidationByGroup(validation, groupId)));
            HttpUtil.sendResponse(exchange, 200, gson.toJson(res));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleCityC7Generate(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String cityId = json.has("city_id") ? json.get("city_id").getAsString() : null;
            String groupId = readOptionalString(json, "group_id");
            Boolean strictTagOverride = json.has("strict_tag_source") ? json.get("strict_tag_source").getAsBoolean() : null;
            boolean strictTagSource = CityGenerationConfig.resolveStrictTagSource(strictTagOverride);
            if (cityId == null || cityId.isBlank()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Missing city_id\"}");
                return;
            }

            Path cityDir = resolveCityDir(cityId);
            CityC6Stages.C6Layout c6Layout = CityC6Stages.loadLayout(cityDir);
            if (c6Layout == null) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"C6 layout not found for: " + cityId + "\"}");
                return;
            }

            CityC7Stages.C7Selection selection = json.has("arrangements")
                    ? CityC7Stages.fromDecisionRequest(cityId, c6Layout, json, strictTagSource)
                    : CityC7Stages.generate(cityId, c6Layout, strictTagSource);
            CityC7Stages.C7Selection responseSelection = filterC7SelectionByGroup(selection, groupId);
            Path outputFile = cityDir.resolve(CityC7Stages.C7_FILE);
            if (groupId != null && !groupId.isBlank()) {
                Path groupDir = resolveGroupDir(cityDir, groupId);
                outputFile = groupDir.resolve("c7_selection.json");
                java.nio.file.Files.writeString(outputFile, gson.toJson(responseSelection));
            } else {
                CityC7Stages.save(cityDir, selection);
            }

            JsonObject res = new JsonObject();
            res.addProperty("status", "ok");
            res.addProperty("step", "C7");
            res.addProperty("city_id", cityId);
            if (groupId != null) res.addProperty("group_id", groupId);
            res.addProperty("selection_count", responseSelection.selections != null ? responseSelection.selections.size() : 0);
            res.addProperty("arrangement_count", responseSelection.arrangements != null ? responseSelection.arrangements.size() : 0);
            res.addProperty("foreman_plan_count", responseSelection.foreman_plans != null ? responseSelection.foreman_plans.size() : 0);
            res.addProperty("phase_count", responseSelection.phase_list != null ? responseSelection.phase_list.size() : 0);
            res.addProperty("catalog_source", responseSelection.catalog_source);
            res.addProperty("decision_source", responseSelection.decision_source);
            res.addProperty("selection_mode", responseSelection.selection_mode);
            res.addProperty("strict_tag_source", responseSelection.strict_tag_source);
            res.addProperty("filtered_candidate_count", responseSelection.filtered_candidate_count);
            if (responseSelection.strict_filter_failure_reason != null) {
                res.addProperty("strict_filter_failure_reason", responseSelection.strict_filter_failure_reason);
            }
            res.addProperty("file", outputFile.toString());
            if (groupId != null && !groupId.isBlank()) {
                CityC7Validation.Report validation = CityC7Validation.generate(cityId, groupId, responseSelection);
                Path groupDir = resolveGroupDir(cityDir, groupId);
                Path validationFile = CityC7Validation.save(cityDir, groupId, validation);
                GroupStepStateUtil.State state = GroupStepStateUtil.update(groupDir, "C7", !validation.ok, "c7_validation_failed_three_times");
                res.addProperty("artifact_dir", groupDir.toString());
                res.addProperty("validation_file", validationFile.toString());
                res.addProperty("retry_count", state.failure_count);
                res.addProperty("blocked_after_failures", state.blocked);
                res.add("validation", gson.toJsonTree(validation));
                if (state.blocked || !validation.ok) {
                    res.addProperty("status", "invalid");
                    HttpUtil.sendResponse(exchange, state.blocked ? 409 : 422, gson.toJson(res));
                    return;
                }
            }
            HttpUtil.sendResponse(exchange, 200, gson.toJson(res));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleCityC7Data(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String cityId = json.has("city_id") ? json.get("city_id").getAsString() : null;
            String groupId = readOptionalString(json, "group_id");
            if (cityId == null || cityId.isBlank()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Missing city_id\"}");
                return;
            }
            Path cityDir = resolveCityDir(cityId);
            if (groupId != null && !groupId.isBlank()) {
                Path file = resolveGroupDir(cityDir, groupId).resolve("c7_selection.json");
                if (!java.nio.file.Files.exists(file)) {
                    HttpUtil.sendResponse(exchange, 404, "{\"error\": \"C7 group data not found for: " + cityId + " / " + groupId + "\"}");
                    return;
                }
                HttpUtil.sendResponse(exchange, 200, java.nio.file.Files.readString(file));
                return;
            }
            CityC7Stages.C7Selection selection = CityC7Stages.load(cityDir);
            if (selection == null) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"C7 data not found for: " + cityId + "\"}");
                return;
            }
            HttpUtil.sendResponse(exchange, 200, gson.toJson(selection));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }
    public void handleCityC8Generate(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String cityId = json.has("city_id") ? json.get("city_id").getAsString() : null;
            String groupId = readOptionalString(json, "group_id");
            if (cityId == null || cityId.isBlank()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Missing city_id\"}");
                return;
            }

            Path cityDir = resolveCityDir(cityId);
            CityC6Stages.C6Summary c6Summary = CityC6Stages.loadSummary(cityDir);
            CityC6Stages.C6Layout c6Layout = CityC6Stages.loadLayout(cityDir);
            Map<Long, Integer> c6Index = CityC6Stages.loadIndex(cityDir);
            CityStage1BinaryIO.HeightData heightData = CityStage1BinaryIO.loadHeightData(cityId);
            if (c6Summary == null || c6Layout == null || c6Index == null || c6Index.isEmpty() || heightData == null) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"Required C6/heightmap data not found for: " + cityId + "\"}");
                return;
            }

            CityC2ScanBinaryIO.C2ScanData c2ScanData = CityC2ScanBinaryIO.load(cityId);
            CityC7Stages.C7Selection c7Selection = loadC8GenerationSelection(cityDir, groupId);
            System.out.println("[C8] handleCityC8Generate city=" + cityId
                    + " group=" + safe(groupId)
                    + " c7_selection_generated_at=" + (c7Selection != null ? c7Selection.generated_at_epoch_ms : -1)
                    + " c7_selection_count=" + (c7Selection != null && c7Selection.selections != null ? c7Selection.selections.size() : -1));
            CityC8Stages.C8Plan plan = CityC8Stages.generate(
                    cityId,
                    c6Summary,
                    c6Layout,
                    c7Selection,
                    mcServer != null ? mcServer.overworld() : null,
                    heightData,
                    c2ScanData,
                    c6Index
            );
            if (groupId == null || groupId.isBlank()) {
                CityC8Stages.save(cityDir, plan);
            }

            Set<Integer> targetAreaIds = collectAreaIdsForGroup(c6Summary, groupId);
            CityC8Stages.C8Plan responsePlan = filterC8PlanByGroup(plan, groupId, targetAreaIds);
            Path outputFile = cityDir.resolve(CityC8Stages.C8_PLAN_FILE);
            if (groupId != null && !groupId.isBlank()) {
                Path groupDir = resolveGroupDir(cityDir, groupId);
                outputFile = groupDir.resolve("c8_foundation.json");
                java.nio.file.Files.writeString(outputFile, gson.toJson(responsePlan));
                String written = java.nio.file.Files.readString(outputFile);
                System.out.println("[C8] wrote_group_file=" + outputFile
                        + " bytes=" + written.length()
                        + " contains_plains_butcher_shop_1=" + written.contains("minecraft:village/plains/houses/plains_butcher_shop_1")
                        + " contains_missing_catalog_meta=" + written.contains("missing_catalog_meta"));
                CityC8Stages.saveDebug(cityDir, groupId, responsePlan);
            }

            JsonObject preview = groupId != null && !groupId.isBlank()
                    ? CityC8ArrangementPreviewExporter.export(mcServer, cityId, groupId, heightData, c2ScanData, c6Summary, c6Layout, c6Index, responsePlan)
                    : new JsonObject();

            JsonObject res = new JsonObject();
            res.addProperty("status", "ok");
            res.addProperty("step", "C8");
            res.addProperty("city_id", cityId);
            if (groupId != null) res.addProperty("group_id", groupId);
            res.addProperty("foundation_count", responsePlan.foundations != null ? responsePlan.foundations.size() : 0);
            CityC8Stages.FoundationItem responseFoundation = responsePlan.foundations != null && !responsePlan.foundations.isEmpty() ? responsePlan.foundations.get(0) : null;
            res.addProperty("session_state", responseFoundation != null ? responseFoundation.session_state : "READY_FOR_AI");
            res.addProperty("queued_node_count", responseFoundation != null && responseFoundation.node_queue != null ? responseFoundation.node_queue.size() : 0);
            res.addProperty("validated_node_count", responseFoundation != null && responseFoundation.validated_nodes != null ? responseFoundation.validated_nodes.size() : 0);
            if (responseFoundation != null && responseFoundation.active_node != null) {
                res.add("active_node", gson.toJsonTree(responseFoundation.active_node));
            }
            res.addProperty("file", outputFile.toString());
            if (groupId != null && !groupId.isBlank()) res.add("arrangement_preview", preview);
            HttpUtil.sendResponse(exchange, 200, gson.toJson(res));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleCityC8Data(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String cityId = json.has("city_id") ? json.get("city_id").getAsString() : null;
            String groupId = readOptionalString(json, "group_id");
            if (cityId == null || cityId.isBlank()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Missing city_id\"}");
                return;
            }

            Path cityDir = resolveCityDir(cityId);
            if (groupId != null && !groupId.isBlank()) {
                Path file = resolveGroupDir(cityDir, groupId).resolve("c8_foundation.json");
                if (!java.nio.file.Files.exists(file)) {
                    HttpUtil.sendResponse(exchange, 404, "{\"error\": \"C8 group data not found for: " + cityId + " / " + groupId + "\"}");
                    return;
                }
                HttpUtil.sendResponse(exchange, 200, java.nio.file.Files.readString(file));
                return;
            }
            CityC8Stages.C8Plan plan = CityC8Stages.load(cityDir);
            if (plan == null) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"C8 data not found for: " + cityId + "\"}");
                return;
            }
            HttpUtil.sendResponse(exchange, 200, gson.toJson(plan));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleCityC8Submit(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String cityId = readOptionalString(json, "city_id");
            String groupId = readOptionalString(json, "group_id");
            String buildAreaId = readOptionalString(json, "build_area_id");
            boolean applyNow = json.has("apply_now") && !json.get("apply_now").isJsonNull() && json.get("apply_now").getAsBoolean();
            if (cityId == null || cityId.isBlank()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Missing city_id\"}");
                return;
            }
            Path cityDir = resolveCityDir(cityId);
            CityC8Stages.C8Plan plan = loadNormalizedC8Plan(cityDir, groupId);
            CityC6Stages.C6Summary c6Summary = CityC6Stages.loadSummary(cityDir);
            Map<Long, Integer> c6Index = CityC6Stages.loadIndex(cityDir);
            CityStage1BinaryIO.HeightData heightData = CityStage1BinaryIO.loadHeightData(cityId);
            CityC2ScanBinaryIO.C2ScanData c2ScanData = CityC2ScanBinaryIO.load(cityId);
            if (plan == null || c6Summary == null || c6Index == null || c6Index.isEmpty()) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"Required C8/C6 data not found\"}");
                return;
            }

            CityC8Stages.FoundationItem foundation = findFoundation(plan, groupId, buildAreaId);
            if (foundation == null) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"Target C8 foundation not found\"}");
                return;
            }
            CityC6Stages.BuildAreaSummary area = findArea(c6Summary, foundation.build_area_numeric_id, foundation.group_id, foundation.build_area_id);
            if (area == null) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"Target C6 area not found\"}");
                return;
            }
            CityC8Stages.AreaGeometry geometry = CityC8Stages.buildAreaGeometry(
                    area,
                    CityC8Stages.collectAreaBlockKeys(c6Index, foundation.build_area_numeric_id)
            );
            List<CityC8Stages.PlacementNode> existingPlacements = collectExistingPlacements(foundation);
            CityC8SubmitDebugTrace debugTrace = applyNow
                    ? CityC8SubmitDebugTrace.create(mcServer, cityDir, cityId, foundation.group_id != null ? foundation.group_id : groupId)
                    : null;

            CityC8Stages.NodeDecision decision = new CityC8Stages.NodeDecision();
            decision.node_id = readOptionalString(json, "node_id");
            decision.selected_template_id = readOptionalString(json, "selected_template_id");
            decision.selected_connector_dir = readOptionalString(json, "selected_connector_dir");
            boolean hasDecisionMutation = false;
            if (json.has("selected_rotation") && !json.get("selected_rotation").isJsonNull()) {
                decision.selected_rotation = json.get("selected_rotation").getAsInt();
                hasDecisionMutation = true;
            }
            if (json.has("x") && !json.get("x").isJsonNull()) {
                decision.x = json.get("x").getAsInt();
                hasDecisionMutation = true;
            }
            if (json.has("z") && !json.get("z").isJsonNull()) {
                decision.z = json.get("z").getAsInt();
                hasDecisionMutation = true;
            }
            if (json.has("terrain_relax_profile") && json.get("terrain_relax_profile").isJsonObject()) {
                decision.terrain_relax_profile = parseTerrainRelaxProfile(json.getAsJsonObject("terrain_relax_profile"));
                hasDecisionMutation = true;
            }
            if (decision.selected_template_id != null && !decision.selected_template_id.isBlank()) hasDecisionMutation = true;
            if (decision.selected_connector_dir != null && !decision.selected_connector_dir.isBlank()) hasDecisionMutation = true;
            if (debugTrace != null) {
                debugTrace.addStep(
                        "01_request_context",
                        "装载 C8 提交请求上下文",
                        "确认本次 start/普通节点提交使用的建造区、当前节点与已有结构背景。",
                        "读取当前 group 的 C8 foundation、C6 geometry 与本次提交节点请求参数。",
                        "通过当前 group 的 C8/C6 落盘产物恢复 active node、已有 placement 与建造区轮廓，为后续校验和执行做输入准备。",
                        "已定位本次 C8 提交节点 `" + safe(decision.node_id) + "`，准备对模板 `" + safe(decision.selected_template_id) + "` 发起校验与落地。",
                        "ok",
                        buildC8SubmitRequestEvidence(foundation, geometry, decision, existingPlacements, applyNow)
                );
            }

            CityC8Stages.NodeSubmitResult submitResult;
            CityC8Stages.NodeTask existingValidatedNode = !hasDecisionMutation && applyNow
                    ? findValidatedNode(foundation, decision.node_id)
                    : null;
            CityC8Stages.PlacementNode existingValidatedPlacement = !hasDecisionMutation && applyNow
                    ? findPlacementNode(foundation, decision.node_id)
                    : null;
            if (existingValidatedNode != null && existingValidatedPlacement != null) {
                submitResult = new CityC8Stages.NodeSubmitResult();
                submitResult.ok = true;
                submitResult.node = existingValidatedNode;
                submitResult.placement = existingValidatedPlacement;
            } else {
                submitResult = CityC8Stages.submitNodeDecision(foundation, geometry, decision, heightData, c2ScanData);
                saveC8Plan(cityDir, groupId, plan);
            }

            StructureInjector.PlacementBounds runtimeBounds = null;
            if (mcServer != null && mcServer.overworld() != null && submitResult != null && submitResult.placement != null) {
                runtimeBounds = StructureInjector.placementBounds(
                        mcServer.overworld(),
                        submitResult.placement.template_id,
                        new BlockPos(submitResult.placement.x, submitResult.placement.y, submitResult.placement.z),
                        toRotation(submitResult.placement.rotation)
                );
            }
            if (debugTrace != null) {
                debugTrace.addStep(
                        "02_validation_result",
                        "记录 C8 节点校验结果",
                        "确认本次节点提交是否通过 C8 校验，并给出期望结构 bounds 与落点。",
                        "读取 submit_result、placement 与 runtime 结构 bounds，确认当前 start/节点理论上应该如何落地。",
                        "对通过校验的节点额外计算 runtime placementBounds，避免后续排查只能看到点位看不到完整期望矩形。",
                        submitResult.ok
                                ? "当前节点已通过 C8 校验，可以继续进入执行层落地。"
                                : "当前节点未通过 C8 校验，本次提交会停在校验阶段。",
                        submitResult.ok ? "ok" : "invalid",
                        buildC8SubmitValidationEvidence(submitResult, runtimeBounds)
                );
            }

            JsonObject res = new JsonObject();
            res.addProperty("status", submitResult.ok ? "ok" : "invalid");
            res.addProperty("step", "C8_SUBMIT");
            res.addProperty("city_id", cityId);
            if (groupId != null) res.addProperty("group_id", groupId);
            res.addProperty("build_area_id", foundation.build_area_id);
            res.addProperty("apply_now", applyNow);
            res.add("submit_result", gson.toJsonTree(submitResult));
            res.add("queue_summary", gson.toJsonTree(foundation.queue_summary));
            res.add("active_node", gson.toJsonTree(foundation.active_node));
            res.add("validated_nodes", gson.toJsonTree(foundation.validated_nodes));
            res.add("failed_attempts", gson.toJsonTree(foundation.failed_attempts));
            if (!submitResult.ok || !applyNow) {
                if (debugTrace != null) {
                    finalizeC8SubmitDebugArtifacts(
                            res,
                            debugTrace,
                            cityDir,
                            cityId,
                            foundation.group_id != null ? foundation.group_id : groupId,
                            foundation.build_area_id,
                            heightData,
                            c2ScanData,
                            geometry,
                            existingPlacements,
                            submitResult != null ? submitResult.placement : null,
                            runtimeBounds,
                            null
                    );
                    debugTrace.logFinal(submitResult.ok ? "C8 节点提交完成。" : "C8 节点提交在校验阶段失败。", submitResult.ok, buildC8SubmitValidationEvidence(submitResult, runtimeBounds));
                }
                HttpUtil.sendResponse(exchange, submitResult.ok ? 200 : 422, gson.toJson(res));
                return;
            }
            if (mcServer == null || mcServer.overworld() == null) {
                res.addProperty("status", "runtime_invalid");
                res.addProperty("error", "Minecraft server/overworld unavailable");
                res.add("placement_execution", buildApplyFailureEvidence("missing_server_level", "Minecraft server/overworld unavailable"));
                if (debugTrace != null) {
                    debugTrace.addStep(
                            "03_apply_result",
                            "执行 C8 落地",
                            "把通过校验的节点提交给执行层落地。",
                            "当前请求要求立即落地，但缺少可用的 server level。",
                            "apply_now 依赖服务端世界对象执行 runtime validator、terrain preparation 与结构放置。",
                            "当前 world/server 不可用，因此本次未进入执行层。",
                            "invalid",
                            buildApplyFailureEvidence("missing_server_level", "Minecraft server/overworld unavailable")
                    );
                    finalizeC8SubmitDebugArtifacts(
                            res,
                            debugTrace,
                            cityDir,
                            cityId,
                            foundation.group_id != null ? foundation.group_id : groupId,
                            foundation.build_area_id,
                            heightData,
                            c2ScanData,
                            geometry,
                            existingPlacements,
                            submitResult.placement,
                            runtimeBounds,
                            null
                    );
                    debugTrace.logFinal("C8 节点提交未能进入执行层。", false, buildApplyFailureEvidence("missing_server_level", "Minecraft server/overworld unavailable"));
                }
                HttpUtil.sendResponse(exchange, 500, gson.toJson(res));
                return;
            }

            final JsonObject[] preSnapshotHolder = new JsonObject[1];
            CountDownLatch preSnapshotLatch = new CountDownLatch(1);
            StructureInjector.PlacementBounds finalRuntimeBounds = runtimeBounds;
            mcServer.execute(() -> {
                try {
                    preSnapshotHolder[0] = capturePlacementWorldStats(mcServer.overworld(), submitResult.placement, finalRuntimeBounds);
                } finally {
                    preSnapshotLatch.countDown();
                }
            });
            preSnapshotLatch.await();

            CityC8Stages.PlacementNode parentPlacement = submitResult.placement != null && submitResult.placement.parent_node_id != null
                    ? findPlacementNode(foundation, submitResult.placement.parent_node_id)
                    : null;
            CityC9BuildQueue.BuildQueue existingQueue = CityC9BuildQueue.loadOrCreate(cityDir, cityId);
            CityC9BuildQueue.BuildTask task = SolvedPlacementExecutionService.buildTask(cityId, groupId, foundation, submitResult.placement);
            final SolvedPlacementExecutionService.ExecutionResult[] holder = new SolvedPlacementExecutionService.ExecutionResult[1];
            CountDownLatch latch = new CountDownLatch(1);
            mcServer.execute(() -> {
                try {
                    holder[0] = SOLVED_PLACEMENT_EXECUTION_SERVICE.execute(
                            new ServerLevelBuildWorldAccess(mcServer.overworld()),
                            existingQueue,
                            task,
                            parentPlacement,
                            null,
                            null
                    );
                } finally {
                    latch.countDown();
                }
            });
            latch.await();

            SolvedPlacementExecutionService.ExecutionResult execution = holder[0];
            JsonObject executionJson = new JsonObject();
            executionJson.addProperty("outcome", execution != null && execution.result != null ? execution.result.outcome().name().toLowerCase(java.util.Locale.ROOT) : "skipped");
            executionJson.addProperty("task_status", execution != null && execution.task != null ? safe(execution.task.status) : "");
            executionJson.addProperty("runtime_error_code", execution != null && execution.task != null ? execution.task.last_error : null);
            executionJson.addProperty("runtime_error_message", execution != null && execution.task != null ? execution.task.last_error_message : null);
            if (execution != null && execution.result != null && execution.result.terrainPreparation() != null) {
                executionJson.add("terrain_preparation", execution.result.terrainPreparation().toJson());
            }
            res.add("placement_execution", executionJson);
            boolean executionOk = execution != null
                    && execution.result != null
                    && execution.result.outcome() == com.user.terra_script.world.city.execution.TaskExecutionResult.Outcome.COMPLETED;
            res.addProperty("status", executionOk ? "ok" : "runtime_invalid");
            final JsonObject[] postSnapshotHolder = new JsonObject[1];
            CountDownLatch postSnapshotLatch = new CountDownLatch(1);
            mcServer.execute(() -> {
                try {
                    postSnapshotHolder[0] = capturePlacementWorldStats(mcServer.overworld(), submitResult.placement, finalRuntimeBounds);
                } finally {
                    postSnapshotLatch.countDown();
                }
            });
            postSnapshotLatch.await();
            if (debugTrace != null) {
                debugTrace.addStep(
                        "03_apply_result",
                        "执行 C8 落地",
                        "把通过校验的节点交给执行层完成清地、校验与结构放置。",
                        "复用现有 BuildExecutionPipeline 与 SolvedPlacementExecutionService，保留当前 apply_now 主链语义。",
                        "执行层会继续做 runtime validator、terrain preparation、模板放置与 post cleanup，再回写最终结果。",
                        executionOk ? "当前节点已通过执行层并完成落地。" : "当前节点已进入执行层，但未完成最终落地。",
                        executionOk ? "ok" : "invalid",
                        buildC8SubmitApplyEvidence(executionJson, runtimeBounds)
                );
                debugTrace.addStep(
                        "04_world_snapshot",
                        "记录落地后世界快照",
                        "在同一结构 bounds 内记录落地前后世界块统计，帮助排查“只落半截”的结构问题。",
                        "分别在执行层前后读取目标结构 bounds 内的世界块分布、非空气数量、jigsaw 数量与分轴切片统计。",
                        "通过同一 bounds 的 before/after 快照对比，快速判断是模板未完整放下、被 terrain preparation 清掉，还是只是视觉误判。",
                        "已记录当前结构 bounds 的落地前后世界快照，可继续用于定位 start 半截结构。",
                        "ok",
                        buildC8SubmitWorldSnapshotEvidence(preSnapshotHolder[0], postSnapshotHolder[0], runtimeBounds)
                );
                finalizeC8SubmitDebugArtifacts(
                        res,
                        debugTrace,
                        cityDir,
                        cityId,
                        foundation.group_id != null ? foundation.group_id : groupId,
                        foundation.build_area_id,
                        heightData,
                        c2ScanData,
                        geometry,
                        existingPlacements,
                        submitResult.placement,
                        runtimeBounds,
                        execution
                );
                debugTrace.logFinal(executionOk ? "C8 节点提交与落地完成。" : "C8 节点提交已到执行层，但落地未完成。", executionOk, buildC8SubmitApplyEvidence(executionJson, runtimeBounds));
            }
            HttpUtil.sendResponse(exchange, executionOk ? 200 : 422, gson.toJson(res));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleCityC8Retry(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String cityId = readOptionalString(json, "city_id");
            String groupId = readOptionalString(json, "group_id");
            String buildAreaId = readOptionalString(json, "build_area_id");
            String nodeId = readOptionalString(json, "node_id");
            if (cityId == null || cityId.isBlank() || nodeId == null || nodeId.isBlank()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Missing city_id or node_id\"}");
                return;
            }
            Path cityDir = resolveCityDir(cityId);
            CityC8Stages.C8Plan plan = loadNormalizedC8Plan(cityDir, groupId);
            if (plan == null) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"C8 plan not found\"}");
                return;
            }
            CityC8Stages.FoundationItem foundation = findFoundation(plan, groupId, buildAreaId);
            if (foundation == null) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"Target C8 foundation not found\"}");
                return;
            }
            CityC8Stages.NodeTask retried = CityC8Stages.retryNode(foundation, nodeId);
            if (retried == null) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"Target node not found\"}");
                return;
            }
            saveC8Plan(cityDir, groupId, plan);

            JsonObject res = new JsonObject();
            res.addProperty("status", "ok");
            res.addProperty("step", "C8_RETRY");
            res.addProperty("city_id", cityId);
            if (groupId != null) res.addProperty("group_id", groupId);
            res.add("node", gson.toJsonTree(retried));
            res.add("queue_summary", gson.toJsonTree(foundation.queue_summary));
            res.add("active_node", gson.toJsonTree(foundation.active_node));
            HttpUtil.sendResponse(exchange, 200, gson.toJson(res));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleCityGroupSurfaceClear(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String cityId = readOptionalString(json, "city_id");
            String groupId = readOptionalString(json, "group_id");
            String buildAreaId = readOptionalString(json, "build_area_id");
            boolean clearSolids = !json.has("clear_solids") || json.get("clear_solids").isJsonNull() || json.get("clear_solids").getAsBoolean();
            boolean preserveExistingPlacements = !json.has("preserve_existing_placements")
                    || json.get("preserve_existing_placements").isJsonNull()
                    || json.get("preserve_existing_placements").getAsBoolean();
            if (cityId == null || cityId.isBlank() || groupId == null || groupId.isBlank()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Missing city_id or group_id\"}");
                return;
            }
            if (mcServer == null || mcServer.overworld() == null) {
                HttpUtil.sendResponse(exchange, 500, "{\"error\": \"Minecraft server/overworld unavailable\"}");
                return;
            }

            Path cityDir = resolveCityDir(cityId);
            CityC8Stages.C8Plan plan = loadNormalizedC8Plan(cityDir, groupId);
            CityC6Stages.C6Summary c6Summary = CityC6Stages.loadSummary(cityDir);
            Map<Long, Integer> c6Index = CityC6Stages.loadIndex(cityDir);
            if (plan == null || c6Summary == null || c6Index == null || c6Index.isEmpty()) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"Required C8/C6 data not found\"}");
                return;
            }

            CityC8Stages.FoundationItem foundation = findFoundation(plan, groupId, buildAreaId);
            if (foundation == null) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"Target C8 foundation not found\"}");
                return;
            }
            CityC6Stages.BuildAreaSummary area = findArea(c6Summary, foundation.build_area_numeric_id, foundation.group_id, foundation.build_area_id);
            if (area == null) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"Target C6 area not found\"}");
                return;
            }

            CityC8Stages.AreaGeometry geometry = CityC8Stages.buildAreaGeometry(
                    area,
                    CityC8Stages.collectAreaBlockKeys(c6Index, foundation.build_area_numeric_id)
            );
            if (geometry == null || !geometry.valid) {
                HttpUtil.sendResponse(exchange, 422, "{\"error\": \"Target build area geometry invalid\"}");
                return;
            }

            int minY = json.has("min_y") && !json.get("min_y").isJsonNull()
                    ? json.get("min_y").getAsInt()
                    : foundation.base_y - 1;
            int maxY = json.has("max_y") && !json.get("max_y").isJsonNull()
                    ? json.get("max_y").getAsInt()
                    : Math.max(minY + 48, foundation.base_y + 48);

            String runId = String.valueOf(System.currentTimeMillis());
            RuntimeLogger logger = RuntimeLogger.forServer(
                    mcServer,
                    RuntimeLogContext.builder()
                            .domain("city")
                            .scope("task")
                            .taskId("city_group_surface_clear_" + runId)
                            .stageId("GROUP_SURFACE_CLEAR")
                            .cityId(cityId)
                            .build()
            );
            JsonObject startDetails = new JsonObject();
            startDetails.addProperty("city_id", cityId);
            startDetails.addProperty("group_id", groupId);
            startDetails.addProperty("build_area_id", foundation.build_area_id);
            startDetails.addProperty("min_y", minY);
            startDetails.addProperty("max_y", maxY);
            startDetails.addProperty("clear_solids", clearSolids);
            startDetails.addProperty("preserve_existing_placements", preserveExistingPlacements);
            logger.info(RuntimeLogEvent.TASK_STARTED, "开始执行 group 级地表清理。", startDetails);

            List<StructureInjector.PlacementBounds> excludedBounds = preserveExistingPlacements
                    ? resolvePlacementBounds(mcServer.overworld(), collectExistingPlacements(foundation))
                    : List.of();
            GroupSurfaceClearService.ClearResult clearResult = new GroupSurfaceClearService().clear(
                    new ServerLevelBuildWorldAccess(mcServer.overworld()),
                    geometry,
                    minY,
                    maxY,
                    clearSolids,
                    excludedBounds
            );

            Path artifactDir = resolveGroupDir(cityDir, foundation.group_id != null ? foundation.group_id : groupId)
                    .resolve("group_surface_clear");
            java.nio.file.Files.createDirectories(artifactDir);
            Path artifactFile = artifactDir.resolve(runId + ".json");
            JsonObject artifact = clearResult.toJson();
            artifact.addProperty("city_id", cityId);
            artifact.addProperty("group_id", groupId);
            artifact.addProperty("build_area_id", foundation.build_area_id);
            artifact.addProperty("preserve_existing_placements", preserveExistingPlacements);
            artifact.addProperty("artifact_file", artifactFile.toString());
            java.nio.file.Files.writeString(artifactFile, gson.toJson(artifact), StandardCharsets.UTF_8);

            JsonObject res = new JsonObject();
            res.addProperty("status", "ok");
            res.addProperty("step", "GROUP_SURFACE_CLEAR");
            res.addProperty("city_id", cityId);
            res.addProperty("group_id", groupId);
            res.addProperty("build_area_id", foundation.build_area_id);
            res.addProperty("min_y", minY);
            res.addProperty("max_y", maxY);
            res.addProperty("clear_solids", clearSolids);
            res.addProperty("preserve_existing_placements", preserveExistingPlacements);
            res.addProperty("artifact_file", artifactFile.toString());
            res.add("clear_result", clearResult.toJson());

            logger.info(RuntimeLogEvent.TASK_COMPLETED, "group 级地表清理完成。", artifact);
            HttpUtil.sendResponse(exchange, 200, gson.toJson(res));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleCityJigsawSolve(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String cityId = readOptionalString(json, "city_id");
            String groupId = readOptionalString(json, "group_id");
            String buildAreaId = readOptionalString(json, "build_area_id");
            String parentNodeId = readOptionalString(json, "parent_node_id");
            String parentConnectorId = readOptionalString(json, "parent_connector_id");
            String selectedTemplateId = readOptionalString(json, "selected_template_id");
            String selectedConnectorDir = readOptionalString(json, "selected_connector_dir");
            boolean applyNow = json.has("apply_now") && !json.get("apply_now").isJsonNull() && json.get("apply_now").getAsBoolean();
            if (cityId == null || cityId.isBlank()
                    || parentNodeId == null || parentNodeId.isBlank()
                    || parentConnectorId == null || parentConnectorId.isBlank()
                    || selectedTemplateId == null || selectedTemplateId.isBlank()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Missing city_id, parent_node_id, parent_connector_id or selected_template_id\"}");
                return;
            }

            Path cityDir = resolveCityDir(cityId);
            CityC8Stages.C8Plan plan = loadNormalizedC8Plan(cityDir, groupId);
            CityC6Stages.C6Summary c6Summary = CityC6Stages.loadSummary(cityDir);
            Map<Long, Integer> c6Index = CityC6Stages.loadIndex(cityDir);
            if (plan == null || c6Summary == null || c6Index == null || c6Index.isEmpty()) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"Required C8/C6 data not found\"}");
                return;
            }

            CityC8Stages.FoundationItem foundation = findFoundation(plan, groupId, buildAreaId);
            if (foundation == null) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"Target C8 foundation not found\"}");
                return;
            }
            CityC6Stages.BuildAreaSummary area = findArea(c6Summary, foundation.build_area_numeric_id, foundation.group_id, foundation.build_area_id);
            if (area == null) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"Target C6 area not found\"}");
                return;
            }
            CityC8Stages.AreaGeometry geometry = CityC8Stages.buildAreaGeometry(
                    area,
                    CityC8Stages.collectAreaBlockKeys(c6Index, foundation.build_area_numeric_id)
            );
            CityC8Stages.PlacementNode parentPlacement = findPlacementNode(foundation, parentNodeId);
            if (parentPlacement == null) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"Parent placement not found in current C8 foundation\"}");
                return;
            }

            List<CityC8Stages.PlacementNode> existingPlacements = collectExistingPlacements(foundation);
            CityStage1BinaryIO.HeightData heightData = CityStage1BinaryIO.loadHeightData(cityId);
            CityC2ScanBinaryIO.C2ScanData c2ScanData = CityC2ScanBinaryIO.load(cityId);
            CityJigsawSolverDebugTrace debugTrace = CityJigsawSolverDebugTrace.create(
                    mcServer,
                    cityDir,
                    cityId,
                    foundation.group_id != null ? foundation.group_id : groupId
            );
            Integer selectedRotation = null;
            if (json.has("selected_rotation") && !json.get("selected_rotation").isJsonNull()) {
                selectedRotation = json.get("selected_rotation").getAsInt();
            }
            debugTrace.addStep(
                    "01_request_context",
                    "装载求解请求上下文",
                    "确认本次求解使用的城市、功能区、parent 节点和 child 模板。",
                    "读取当前 group 的 C8 foundation、C6 area geometry、parent placement 与已有结构上下文。",
                    "通过 C8/C6 已落盘产物恢复建造区 polygon、parent placement 和已有 placement 列表，为后续 jigsaw 求解准备输入。",
                    "已定位 parent 节点 `" + safe(parentNodeId) + "`，并准备对模板 `" + safe(selectedTemplateId) + "` 发起独立 jigsaw 求解。",
                    "ok",
                    buildJigsawRequestEvidence(
                            foundation,
                            geometry,
                            parentPlacement,
                            existingPlacements,
                            parentConnectorId,
                            selectedTemplateId,
                            selectedConnectorDir,
                            selectedRotation,
                            applyNow
                    )
            );
            CityVanillaJigsawAdapterService.SolveResult solveResult = CityVanillaJigsawAdapterService.solve(
                    mcServer != null ? mcServer.overworld() : null,
                    cityId,
                    geometry,
                    existingPlacements,
                    parentPlacement,
                    parentConnectorId,
                    selectedTemplateId,
                    selectedConnectorDir,
                    selectedRotation,
                    heightData,
                    c2ScanData
            );
            debugTrace.addStep(
                    "02_runtime_jigsaws",
                    "扫描 parent 模板中的 runtime 拼图方块",
                    "找出 parent 模板在当前放置姿态下真实存在的 jigsaw 方块。",
                    "读取当前 parent placement 对应的真实模板，并扫描模板放置后实际存在的 jigsaw 方块。",
                    "直接调用 StructureTemplate.filterBlocks(..., Blocks.JIGSAW) 获取 runtime jigsaw，再根据已放置 rotation 反推模板局部坐标，生成 runtime connector id。",
                    buildRuntimeJigsawResultZh(solveResult),
                    buildRuntimeJigsawStatus(solveResult),
                    buildRuntimeJigsawEvidence(gson, solveResult)
            );
            debugTrace.addStep(
                    "03_parent_connector_resolution",
                    "解析本次请求使用的 parent connector",
                    "确认请求中的 connector 是否能在 runtime 模板里命中真实 parent jigsaw。",
                    "对比请求的 parent_connector_id、runtime 扫描结果和 catalog 投影结果，决定本次求解到底使用哪一个 parent connector。",
                    "优先使用 runtime 模板反推出的 connector id 直接命中；若 runtime 未命中，再尝试用 catalog connector 的局部坐标和 facing 投影到世界坐标后做二次定位。",
                    buildParentResolutionResultZh(parentConnectorId, solveResult),
                    buildParentResolutionStatus(solveResult),
                    buildParentResolutionEvidence(solveResult, parentConnectorId)
            );
            debugTrace.addStep(
                    "04_vanilla_piece_result",
                    "构造临时模板池并调用 vanilla jigsaw 生成 child",
                    "验证当前 parent connector 与 child 模板是否能在 vanilla depth=1 语义下生成单个 child piece。",
                    "先读取 parent runtime jigsaw 自带的原始模板池，再把 AI 指定的 child 模板收束成该池里的唯一候选，并尝试生成一个 child piece；若成功，再继续解析 child connector。",
                    "优先读取 parent runtime jigsaw NBT 中的原始 pool，并在 pool 原始元素里筛出 AI 指定模板对应的元素，保留原版 projection / element 语义后，再调用 JigsawPlacement.addPieces(...) 做 depth=1 vanilla child 生成；生成成功后继续按 runtime/catalog 口径解析 child connector。",
                    buildVanillaPieceResultZh(solveResult),
                    buildVanillaPieceStatus(solveResult),
                    buildVanillaPieceEvidence(solveResult)
            );
            debugTrace.addStep(
                    "05_validation_result",
                    "校验 child 结构矩形是否可接受",
                    "确认生成出的 child 结构矩形能否通过当前建造区边界与求解结果校验。",
                    "读取生成出的 child bounds、origin、rotation，并判断本次求解是在何处被接受或拒绝。",
                    "对生成出的结构矩形使用 containsFootprint(...) 做建造区边界判断，并结合 solve_result 的 reject_reason 汇总当前求解阶段结论。",
                    buildValidationResultZh(solveResult),
                    buildValidationStatus(solveResult),
                    buildValidationEvidence(solveResult)
            );

            JsonObject res = new JsonObject();
            res.addProperty("step", "JIGSAW_SOLVER");
            res.addProperty("city_id", cityId);
            if (groupId != null) res.addProperty("group_id", groupId);
            res.addProperty("build_area_id", foundation.build_area_id);
            res.addProperty("mode", applyNow ? "solve_and_place" : "solve");
            res.add("solve_result", gson.toJsonTree(solveResult));
            if (!solveResult.ok || solveResult.placement == null) {
                res.addProperty("status", "invalid");
                finalizeJigsawDebugArtifacts(
                        res,
                        debugTrace,
                        cityDir,
                        cityId,
                        foundation.group_id != null ? foundation.group_id : groupId,
                        foundation.build_area_id,
                        heightData,
                        c2ScanData,
                        geometry,
                        existingPlacements,
                        parentPlacement,
                        parentConnectorId,
                        solveResult,
                        null,
                        applyNow
                );
                debugTrace.logFinal("Jigsaw 求解未通过。", false, buildValidationEvidence(solveResult));
                HttpUtil.sendResponse(exchange, 422, gson.toJson(res));
                return;
            }

            CityC8Stages.NodeSubmitResult persistResult = CityC8Stages.persistSolvedPlacement(
                    foundation,
                    parentPlacement,
                    parentConnectorId,
                    selectedTemplateId,
                    selectedConnectorDir,
                    solveResult.placement
            );
            if (!persistResult.ok) {
                res.addProperty("status", "invalid");
                res.addProperty("error", safe(persistResult.error_message));
                finalizeJigsawDebugArtifacts(
                        res,
                        debugTrace,
                        cityDir,
                        cityId,
                        foundation.group_id != null ? foundation.group_id : groupId,
                        foundation.build_area_id,
                        heightData,
                        c2ScanData,
                        geometry,
                        existingPlacements,
                        parentPlacement,
                        parentConnectorId,
                        solveResult,
                        null,
                        applyNow
                );
                debugTrace.logFinal("Jigsaw 求解成功，但写回 C8 会话树失败。", false, gson.toJsonTree(persistResult).getAsJsonObject());
                HttpUtil.sendResponse(exchange, 422, gson.toJson(res));
                return;
            }
            saveC8Plan(cityDir, groupId, plan);
            res.add("queue_summary", gson.toJsonTree(foundation.queue_summary));
            res.add("active_node", gson.toJsonTree(foundation.active_node));
            res.add("validated_nodes", gson.toJsonTree(foundation.validated_nodes));
            res.add("failed_attempts", gson.toJsonTree(foundation.failed_attempts));

            if (!applyNow) {
                res.addProperty("status", "ok");
                finalizeJigsawDebugArtifacts(
                        res,
                        debugTrace,
                        cityDir,
                        cityId,
                        foundation.group_id != null ? foundation.group_id : groupId,
                        foundation.build_area_id,
                        heightData,
                        c2ScanData,
                        geometry,
                        existingPlacements,
                        parentPlacement,
                        parentConnectorId,
                        solveResult,
                        null,
                        false
                );
                debugTrace.logFinal("Jigsaw 求解完成。", true, buildValidationEvidence(solveResult));
                HttpUtil.sendResponse(exchange, 200, gson.toJson(res));
                return;
            }
            if (solveResult.descriptor == null || solveResult.descriptor.piece_placer == null) {
                debugTrace.addStep(
                        "06_apply_result",
                        "执行求解后落地",
                        "在 apply_now 模式下把已求解结果交给执行层落地。",
                        "本次请求要求立即落地，但当前求解结果没有可执行的 vanilla piece descriptor。",
                        "solve_and_place 依赖前一步生成的 vanilla piece descriptor，把 child piece 交给现有执行层继续做 chunk gate、runtime validator 和 terrain preparation。",
                        "当前求解虽然返回了 placement，但没有形成可执行的 vanilla piece descriptor，因此未进入执行层。",
                        "invalid",
                        buildApplyFailureEvidence("missing_vanilla_piece_descriptor", "missing_vanilla_piece_descriptor")
                );
                res.addProperty("status", "invalid");
                res.addProperty("error", "missing_vanilla_piece_descriptor");
                finalizeJigsawDebugArtifacts(
                        res,
                        debugTrace,
                        cityDir,
                        cityId,
                        foundation.group_id != null ? foundation.group_id : groupId,
                        foundation.build_area_id,
                        heightData,
                        c2ScanData,
                        geometry,
                        existingPlacements,
                        parentPlacement,
                        parentConnectorId,
                        solveResult,
                        null,
                        true
                );
                debugTrace.logFinal("Jigsaw 求解成功，但未能生成可执行的落地描述。", false, buildApplyFailureEvidence("missing_vanilla_piece_descriptor", "missing_vanilla_piece_descriptor"));
                HttpUtil.sendResponse(exchange, 422, gson.toJson(res));
                return;
            }
            if (mcServer == null || mcServer.overworld() == null) {
                debugTrace.addStep(
                        "06_apply_result",
                        "执行求解后落地",
                        "在 apply_now 模式下把已求解结果交给执行层落地。",
                        "本次请求要求立即落地，但当前运行环境没有可用的 Minecraft server/overworld。",
                        "solve_and_place 需要把求解结果派发回主线程，并通过 ServerLevelBuildWorldAccess 进入现有执行层；没有 server/overworld 时无法继续。",
                        "当前运行环境不可用，因此未进入执行层。",
                        "invalid",
                        buildApplyFailureEvidence("missing_server_level", "Minecraft server/overworld unavailable")
                );
                res.addProperty("status", "invalid");
                res.addProperty("error", "Minecraft server/overworld unavailable");
                finalizeJigsawDebugArtifacts(
                        res,
                        debugTrace,
                        cityDir,
                        cityId,
                        foundation.group_id != null ? foundation.group_id : groupId,
                        foundation.build_area_id,
                        heightData,
                        c2ScanData,
                        geometry,
                        existingPlacements,
                        parentPlacement,
                        parentConnectorId,
                        solveResult,
                        null,
                        true
                );
                debugTrace.logFinal("Jigsaw 求解成功，但运行环境不可用，无法落地。", false, buildApplyFailureEvidence("missing_server_level", "Minecraft server/overworld unavailable"));
                HttpUtil.sendResponse(exchange, 500, gson.toJson(res));
                return;
            }

            CityC9BuildQueue.BuildQueue existingQueue = CityC9BuildQueue.loadOrCreate(cityDir, cityId);
            CityC9BuildQueue.BuildTask task = SolvedPlacementExecutionService.buildTask(cityId, groupId, foundation, solveResult.placement);
            final SolvedPlacementExecutionService.ExecutionResult[] holder = new SolvedPlacementExecutionService.ExecutionResult[1];
            CountDownLatch latch = new CountDownLatch(1);
            mcServer.execute(() -> {
                try {
                    holder[0] = SOLVED_PLACEMENT_EXECUTION_SERVICE.execute(
                            new ServerLevelBuildWorldAccess(mcServer.overworld()),
                            existingQueue,
                            task,
                            parentPlacement,
                            solveResult.descriptor,
                            null
                    );
                } finally {
                    latch.countDown();
                }
            });
            latch.await();

            SolvedPlacementExecutionService.ExecutionResult execution = holder[0];
            JsonObject executionJson = new JsonObject();
            executionJson.addProperty("outcome", execution != null && execution.result != null ? execution.result.outcome().name().toLowerCase(java.util.Locale.ROOT) : "skipped");
            executionJson.addProperty("task_status", execution != null && execution.task != null ? safe(execution.task.status) : "");
            executionJson.addProperty("runtime_error_code", execution != null && execution.task != null ? execution.task.last_error : null);
            executionJson.addProperty("runtime_error_message", execution != null && execution.task != null ? execution.task.last_error_message : null);
            if (execution != null && execution.result != null && execution.result.terrainPreparation() != null) {
                executionJson.add("terrain_preparation", execution.result.terrainPreparation().toJson());
            }
            debugTrace.addStep(
                    "06_apply_result",
                    "执行求解后落地",
                    "在 apply_now 模式下把已求解结果交给执行层落地。",
                    "把 solver 产出的 placement 和 vanilla piece descriptor 派发到现有执行层，继续做 runtime 校验、地形预处理和结构落地。",
                    "通过 SolvedPlacementExecutionService 复用 BuildExecutionPipeline，在不改 C9 默认主链的前提下完成 chunk gate、runtime validator、terrain preparation 和 piece 放置。",
                    buildApplyResultZh(execution),
                    buildApplyStatus(execution),
                    executionJson
            );
            res.add("placement_execution", executionJson);
            res.addProperty("status", execution != null
                    && execution.result != null
                    && execution.result.outcome() == com.user.terra_script.world.city.execution.TaskExecutionResult.Outcome.COMPLETED ? "ok" : "runtime_invalid");
            finalizeJigsawDebugArtifacts(
                    res,
                    debugTrace,
                    cityDir,
                    cityId,
                    foundation.group_id != null ? foundation.group_id : groupId,
                    foundation.build_area_id,
                    heightData,
                    c2ScanData,
                    geometry,
                    existingPlacements,
                    parentPlacement,
                    parentConnectorId,
                    solveResult,
                    execution,
                    true
            );
            debugTrace.logFinal(
                    execution != null
                            && execution.result != null
                            && execution.result.outcome() == com.user.terra_script.world.city.execution.TaskExecutionResult.Outcome.COMPLETED
                            ? "Jigsaw 求解并落地完成。"
                            : "Jigsaw 求解完成，但落地未通过。",
                    execution != null
                            && execution.result != null
                            && execution.result.outcome() == com.user.terra_script.world.city.execution.TaskExecutionResult.Outcome.COMPLETED,
                    executionJson
            );
            HttpUtil.sendResponse(exchange, execution != null
                    && execution.result != null
                    && execution.result.outcome() == com.user.terra_script.world.city.execution.TaskExecutionResult.Outcome.COMPLETED ? 200 : 422, gson.toJson(res));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            HttpUtil.sendResponse(exchange, 500, "{\"error\": \"Interrupted while solving jigsaw placement\"}");
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleCityC9Generate(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String cityId = json.has("city_id") ? json.get("city_id").getAsString() : null;
            String groupId = readOptionalString(json, "group_id");
            if (cityId == null || cityId.isBlank()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Missing city_id\"}");
                return;
            }
            Boolean deprecatedApplyBlocks = json.has("apply_blocks") ? json.get("apply_blocks").getAsBoolean() : null;
            CityC9Stages.Mode mode = CityC9Stages.Mode.parse(readOptionalString(json, "mode"), deprecatedApplyBlocks);
            boolean applyBlocks = mode == CityC9Stages.Mode.APPLY_NOW;
            int maxBlocks = json.has("max_blocks") ? Math.max(1, Math.min(200000, json.get("max_blocks").getAsInt())) : 25000;

            Path cityDir = resolveCityDir(cityId);
            CityC6Stages.C6Summary c6Summary = CityC6Stages.loadSummary(cityDir);
            CityC6Stages.C6Layout c6Layout = CityC6Stages.loadLayout(cityDir);
            Map<Long, Integer> c6Index = CityC6Stages.loadIndex(cityDir);
            CityStage1BinaryIO.HeightData heightData = CityStage1BinaryIO.loadHeightData(cityId);
            CityC2ScanBinaryIO.C2ScanData c2ScanData = CityC2ScanBinaryIO.load(cityId);
            if (c6Summary == null || c6Index == null || c6Index.isEmpty()) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"C6 summary/index not found for: " + cityId + "\"}");
                return;
            }

            CityC8Stages.C8Plan c8Plan = loadNormalizedC8Plan(cityDir, groupId);
            if (c8Plan == null) {
                if (c6Layout == null || heightData == null) {
                    HttpUtil.sendResponse(exchange, 404, "{\"error\": \"C8 missing and required data to regenerate C8 not found for: " + cityId + "\"}");
                    return;
                }
                CityC7Stages.C7Selection c7Selection = loadC8GenerationSelection(cityDir, groupId);
                c8Plan = CityC8Stages.generate(
                        cityId,
                        c6Summary,
                        c6Layout,
                        c7Selection,
                        mcServer != null ? mcServer.overworld() : null,
                        heightData,
                        c2ScanData,
                        c6Index
                );
                if (groupId == null || groupId.isBlank()) {
                    CityC8Stages.save(cityDir, c8Plan);
                }
            }

            Set<Integer> targetAreaIds = collectAreaIdsForGroup(c6Summary, groupId);
            CityC6Stages.C6Summary responseSummary = filterC6SummaryByGroup(c6Summary, groupId);
            Map<Long, Integer> responseIndex = filterIndexByAreaIds(c6Index, targetAreaIds);
            CityC8Stages.C8Plan responsePlan = filterC8PlanByGroup(c8Plan, groupId, targetAreaIds);
            CityC9BuildQueue.BuildQueue existingQueue = CityC9BuildQueue.loadOrCreate(cityDir, cityId);
            final CityC9Stages.C9Result[] holder = new CityC9Stages.C9Result[1];
            holder[0] = CityC9Stages.generate(cityId, responseSummary, responseIndex, responsePlan, mode, maxBlocks, existingQueue);
            System.out.println("[C9] generate city=" + cityId
                    + " group=" + safe(groupId)
                    + " mode=" + mode.name()
                    + " max_blocks=" + maxBlocks
                    + " foundation_count=" + (responsePlan != null && responsePlan.foundations != null ? responsePlan.foundations.size() : -1)
                    + " queue_total_before_save=" + (holder[0] != null && holder[0].queue != null && holder[0].queue.tasks != null ? holder[0].queue.tasks.size() : -1)
                    + " enqueued_tasks_count=" + (holder[0] != null && holder[0].placement != null ? holder[0].placement.enqueued_tasks_count : -1));
            if (mode != CityC9Stages.Mode.DRY_RUN && holder[0] != null && holder[0].queue != null) {
                CityC9BuildQueue.save(cityDir, holder[0].queue);
                if (mcServer != null) {
                    CityBuildQueueExecutor.refreshCityQueue(mcServer, cityId);
                }
            }
            if (mode == CityC9Stages.Mode.APPLY_NOW) {
                if (mcServer == null || mcServer.overworld() == null) {
                    HttpUtil.sendResponse(exchange, 500, "{\"error\": \"Minecraft server/overworld unavailable\"}");
                    return;
                }
                CountDownLatch latch = new CountDownLatch(1);
                mcServer.execute(() -> {
                    try {
                        CityBuildQueueExecutor.ExecutionReport execution = CityBuildQueueExecutor.executeLoadedTasksNow(mcServer, cityId, groupId, maxBlocks);
                        System.out.println("[C9] apply_now city=" + cityId
                                + " group=" + safe(groupId)
                                + " max_blocks=" + maxBlocks
                                + " attempted=" + execution.attempted
                                + " completed=" + execution.completed
                                + " blocked=" + execution.blocked
                                + " retried=" + execution.retried);
                        if (holder[0] != null && holder[0].placement != null) {
                            holder[0].placement.applied_tasks_count = execution.completed;
                            holder[0].placement.changed_blocks_total = execution.completed;
                        }
                        try {
                            CityC9BuildQueue.BuildQueue latestQueue = CityC9BuildQueue.loadOrCreate(cityDir, cityId);
                            if (holder[0] != null) {
                                holder[0].queue = groupId != null && !groupId.isBlank()
                                        ? CityC9BuildQueue.filtered(latestQueue, groupId)
                                        : latestQueue;
                                holder[0].queue_summary = CityC9BuildQueue.summarize(holder[0].queue, null);
                                if (holder[0].placement != null) {
                                    CityC9Stages.syncPlacementWithQueue(holder[0].placement, holder[0].queue);
                                }
                                System.out.println("[C9] queue_after_apply city=" + cityId
                                        + " group=" + safe(groupId)
                                        + " planned=" + (holder[0].queue_summary != null ? holder[0].queue_summary.planned_count : -1)
                                        + " ready=" + (holder[0].queue_summary != null ? holder[0].queue_summary.ready_count : -1)
                                        + " inflight=" + (holder[0].queue_summary != null ? holder[0].queue_summary.inflight_count : -1)
                                        + " done=" + (holder[0].queue_summary != null ? holder[0].queue_summary.done_count : -1)
                                        + " blocked=" + (holder[0].queue_summary != null ? holder[0].queue_summary.blocked_count : -1)
                                        + " total=" + (holder[0].queue_summary != null ? holder[0].queue_summary.total_count : -1));
                            }
                        } catch (Exception ignored) {
                        }
                    } catch (Exception ignored) {
                    } finally {
                        latch.countDown();
                    }
                });
                latch.await();
            }
            CityC9Stages.C9Result result = holder[0];
            CityC9Stages.C9Result responseResult = filterC9ResultByAreas(result, targetAreaIds);
            if (responseResult != null && result != null && result.queue != null) {
                responseResult.queue = groupId != null && !groupId.isBlank()
                        ? CityC9BuildQueue.filtered(result.queue, groupId)
                        : result.queue;
                responseResult.queue_summary = CityC9BuildQueue.summarize(responseResult.queue, null);
            }
            Path placementFile = cityDir.resolve(CityC9Stages.C9_PLACEMENT_FILE);
            Path decorationFile = cityDir.resolve(CityC9Stages.C9_DECORATION_FILE);
            JsonObject placementPreview = new JsonObject();
            if (groupId != null && !groupId.isBlank()) {
                Path groupDir = resolveGroupDir(cityDir, groupId);
                placementFile = groupDir.resolve("c9_placement.json");
                decorationFile = groupDir.resolve("c9_decoration.json");
                if (responseResult.placement != null) java.nio.file.Files.writeString(placementFile, gson.toJson(responseResult.placement));
                if (responseResult.decoration != null) java.nio.file.Files.writeString(decorationFile, gson.toJson(responseResult.decoration));
                placementPreview = CityC9PlacementPreviewExporter.export(
                        mcServer,
                        cityId,
                        groupId,
                        mode.name().toLowerCase(java.util.Locale.ROOT),
                        heightData,
                        c2ScanData,
                        c6Summary,
                        c6Layout,
                        responseIndex,
                        responsePlan,
                        responseResult != null ? responseResult.placement : null,
                        responseResult != null ? responseResult.queue : null
                );
            } else {
                CityC9Stages.save(cityDir, result);
            }

            JsonObject res = new JsonObject();
            res.addProperty("status", "ok");
            res.addProperty("step", "C9");
            res.addProperty("city_id", cityId);
            if (groupId != null) res.addProperty("group_id", groupId);
            res.addProperty("mode", mode.name().toLowerCase(java.util.Locale.ROOT));
            res.addProperty("apply_blocks", applyBlocks);
            if (deprecatedApplyBlocks != null) {
                res.addProperty("deprecated_apply_blocks_mapped", true);
            }
            res.addProperty("processed_areas", responseResult != null && responseResult.placement != null ? responseResult.placement.processed_areas : 0);
            res.addProperty("changed_blocks_total", responseResult != null && responseResult.placement != null ? responseResult.placement.changed_blocks_total : 0);
            res.addProperty("enqueued_tasks_count", responseResult != null && responseResult.placement != null ? responseResult.placement.enqueued_tasks_count : 0);
            res.addProperty("applied_tasks_count", responseResult != null && responseResult.placement != null ? responseResult.placement.applied_tasks_count : 0);
            if (responseResult != null && responseResult.queue_summary != null) {
                res.add("queue_summary", gson.toJsonTree(responseResult.queue_summary));
            }
            res.addProperty("placement_file", placementFile.toString());
            res.addProperty("decoration_file", decorationFile.toString());
            if (groupId != null && !groupId.isBlank()) {
                res.add("placement_preview", placementPreview);
            }
            HttpUtil.sendResponse(exchange, 200, gson.toJson(res));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            HttpUtil.sendResponse(exchange, 500, "{\"error\": \"Interrupted while generating C9\"}");
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleCityC9Data(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String cityId = json.has("city_id") ? json.get("city_id").getAsString() : null;
            String groupId = readOptionalString(json, "group_id");
            if (cityId == null || cityId.isBlank()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Missing city_id\"}");
                return;
            }
            Path cityDir = resolveCityDir(cityId);
            if (groupId != null && !groupId.isBlank()) {
                Path groupDir = resolveGroupDir(cityDir, groupId);
                Path placementFile = groupDir.resolve("c9_placement.json");
                Path decorationFile = groupDir.resolve("c9_decoration.json");
                CityC9BuildQueue.BuildQueue queue = CityC9BuildQueue.filtered(CityC9BuildQueue.load(cityDir), groupId);
                if (!java.nio.file.Files.exists(placementFile) && !java.nio.file.Files.exists(decorationFile) && queue == null) {
                    HttpUtil.sendResponse(exchange, 404, "{\"error\": \"C9 group data not found for: " + cityId + " / " + groupId + "\"}");
                    return;
                }
                JsonObject res = new JsonObject();
                res.addProperty("step", "C9");
                res.addProperty("ok", true);
                if (java.nio.file.Files.exists(placementFile)) {
                    CityC9Stages.C9Placement placement = new Gson().fromJson(java.nio.file.Files.readString(placementFile), CityC9Stages.C9Placement.class);
                    CityC9Stages.syncPlacementWithQueue(placement, queue);
                    java.nio.file.Files.writeString(placementFile, gson.toJson(placement));
                    res.add("placement", gson.toJsonTree(placement));
                }
                if (java.nio.file.Files.exists(decorationFile)) res.add("decoration", JsonParser.parseString(java.nio.file.Files.readString(decorationFile)));
                res.add("queue", gson.toJsonTree(queue));
                res.add("queue_summary", gson.toJsonTree(CityC9BuildQueue.summarize(queue, null)));
                HttpUtil.sendResponse(exchange, 200, gson.toJson(res));
                return;
            }
            Path cityRoot = resolveCityDir(cityId);
            CityC9Stages.C9Placement placement = CityC9Stages.loadPlacement(cityRoot);
            CityC9Stages.C9Decoration decoration = CityC9Stages.loadDecoration(cityRoot);
            CityC9BuildQueue.BuildQueue queue = CityC9BuildQueue.load(cityRoot);
            if (placement == null && decoration == null && queue == null) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"C9 data not found for: " + cityId + "\"}");
                return;
            }
            JsonObject res = new JsonObject();
            res.addProperty("step", "C9");
            res.addProperty("ok", true);
            res.add("placement", gson.toJsonTree(placement));
            res.add("decoration", gson.toJsonTree(decoration));
            CityC9BuildQueue.BuildQueue responseQueue = groupId != null && !groupId.isBlank()
                    ? CityC9BuildQueue.filtered(queue, groupId)
                    : queue;
            res.add("queue", gson.toJsonTree(responseQueue));
            res.add("queue_summary", gson.toJsonTree(CityC9BuildQueue.summarize(responseQueue, null)));
            HttpUtil.sendResponse(exchange, 200, gson.toJson(res));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleCityC9QueueData(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String cityId = json.has("city_id") ? json.get("city_id").getAsString() : null;
            String groupId = readOptionalString(json, "group_id");
            if (cityId == null || cityId.isBlank()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Missing city_id\"}");
                return;
            }
            Path cityDir = resolveCityDir(cityId);
            CityC9BuildQueue.BuildQueue queue = CityC9BuildQueue.load(cityDir);
            if (queue == null) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"C9 queue not found for: " + cityId + "\"}");
                return;
            }
            CityC9BuildQueue.BuildQueue responseQueue = groupId != null && !groupId.isBlank()
                    ? CityC9BuildQueue.filtered(queue, groupId)
                    : queue;
            JsonObject res = new JsonObject();
            res.addProperty("step", "C9_QUEUE");
            res.addProperty("ok", true);
            res.add("queue", gson.toJsonTree(responseQueue));
            res.add("queue_summary", gson.toJsonTree(CityC9BuildQueue.summarize(responseQueue, null)));
            HttpUtil.sendResponse(exchange, 200, gson.toJson(res));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleCityC6PaveStone(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String cityId = json.has("city_id") ? json.get("city_id").getAsString() : null;
            if (cityId == null || cityId.isBlank()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Missing city_id\"}");
                return;
            }

            String groupId = json.has("group_id") ? json.get("group_id").getAsString() : null;
            String buildAreaId = json.has("build_area_id") ? json.get("build_area_id").getAsString() : null;
            boolean squareOnly = json.has("square_only") && json.get("square_only").getAsBoolean();
            int squareSize = json.has("square_size") ? Math.max(3, Math.min(32, json.get("square_size").getAsInt())) : 8;
            int squareCount = json.has("square_count") ? Math.max(1, Math.min(128, json.get("square_count").getAsInt())) : 16;

            Path cityDir = resolveCityDir(cityId);
            CityC6Stages.C6Summary summary = CityC6Stages.loadSummary(cityDir);
            Map<Long, Integer> index = CityC6Stages.loadIndex(cityDir);
            if (summary == null || index == null || index.isEmpty()) {
                HttpUtil.sendResponse(exchange, 404, "{\"error\": \"C6 summary/index not found for: " + cityId + "\"}");
                return;
            }
            if (mcServer == null) {
                HttpUtil.sendResponse(exchange, 500, "{\"error\": \"Minecraft server unavailable\"}");
                return;
            }
            ServerLevel level = mcServer.overworld();
            if (level == null) {
                HttpUtil.sendResponse(exchange, 500, "{\"error\": \"Overworld unavailable\"}");
                return;
            }

            Set<Integer> targetAreaIds = new HashSet<>();
            for (CityC6Stages.BuildAreaSummary area : summary.areas) {
                if (area == null) continue;
                if (buildAreaId != null && !buildAreaId.isBlank() && !buildAreaId.equals(area.build_area_id)) continue;
                if (groupId != null && !groupId.isBlank() && !groupId.equals(area.group_id)) continue;
                targetAreaIds.add(area.build_area_numeric_id);
            }
            if (targetAreaIds.isEmpty()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"No matched C6 area by group_id/build_area_id\"}");
                return;
            }

            Map<Integer, CityC6Stages.BuildAreaSummary> areaSummaryById = new java.util.HashMap<>();
            for (CityC6Stages.BuildAreaSummary area : summary.areas) {
                if (area == null) continue;
                areaSummaryById.put(area.build_area_numeric_id, area);
            }

            Set<Long> targetBlocks = new HashSet<>();
            for (Map.Entry<Long, Integer> entry : index.entrySet()) {
                Integer areaId = entry.getValue();
                if (areaId == null || !targetAreaIds.contains(areaId)) continue;
                targetBlocks.add(entry.getKey());
            }
            if (targetBlocks.isEmpty()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"No target blocks found for matched area(s)\"}");
                return;
            }

            final int[] scanned = {0};
            final int[] changed = {0};
            final int[] placedSquares = {0};
            CountDownLatch latch = new CountDownLatch(1);
            mcServer.execute(() -> {
                try {
                    if (!squareOnly) {
                        for (long key : targetBlocks) {
                            scanned[0]++;
                            int x = (int) (key >> 32);
                            int z = (int) (long) key;
                            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                            if (y <= level.getMinBuildHeight()) continue;
                            BlockPos pos = new BlockPos(x, y, z);
                            if (!level.getBlockState(pos).is(Blocks.STONE)) {
                                level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
                                changed[0]++;
                            }
                        }
                    } else {
                        List<long[]> candidateCenters = new java.util.ArrayList<>();
                        for (Integer areaId : targetAreaIds) {
                            CityC6Stages.BuildAreaSummary area = areaSummaryById.get(areaId);
                            if (area == null || area.centroid == null) continue;
                            int cx = (int) Math.round(area.centroid.x);
                            int cz = (int) Math.round(area.centroid.z);
                            candidateCenters.add(new long[]{cx, cz});
                        }

                        int ring = 0;
                        int maxRing = 24;
                        int spacing = squareSize + 2;
                        while (placedSquares[0] < squareCount && ring <= maxRing) {
                            for (long[] base : candidateCenters) {
                                if (placedSquares[0] >= squareCount) break;
                                int bx = (int) base[0];
                                int bz = (int) base[1];
                                int[][] offsets = {
                                        {0, 0},
                                        {ring, 0}, {-ring, 0}, {0, ring}, {0, -ring},
                                        {ring, ring}, {ring, -ring}, {-ring, ring}, {-ring, -ring}
                                };
                                for (int[] off : offsets) {
                                    if (placedSquares[0] >= squareCount) break;
                                    int cx = bx + off[0] * spacing;
                                    int cz = bz + off[1] * spacing;
                                    if (!paintSquare(level, targetBlocks, cx, cz, squareSize, scanned, changed)) continue;
                                    placedSquares[0]++;
                                }
                            }
                            ring++;
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
            try {
                latch.await();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                HttpUtil.sendResponse(exchange, 500, "{\"error\": \"Interrupted while paving\"}");
                return;
            }

            JsonObject res = new JsonObject();
            res.addProperty("status", "ok");
            res.addProperty("step", "C6");
            res.addProperty("action", "pave_stone");
            res.addProperty("city_id", cityId);
            if (groupId != null && !groupId.isBlank()) res.addProperty("group_id", groupId);
            if (buildAreaId != null && !buildAreaId.isBlank()) res.addProperty("build_area_id", buildAreaId);
            res.addProperty("matched_area_count", targetAreaIds.size());
            res.addProperty("scanned_blocks", scanned[0]);
            res.addProperty("changed_blocks", changed[0]);
            res.addProperty("square_only", squareOnly);
            if (squareOnly) {
                res.addProperty("square_size", squareSize);
                res.addProperty("requested_square_count", squareCount);
                res.addProperty("placed_square_count", placedSquares[0]);
            }
            HttpUtil.sendResponse(exchange, 200, gson.toJson(res));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    private static boolean paintSquare(
            ServerLevel level,
            Set<Long> targetBlocks,
            int centerX,
            int centerZ,
            int size,
            int[] scanned,
            int[] changed
    ) {
        int half = size / 2;
        int minX = centerX - half;
        int minZ = centerZ - half;
        int maxX = minX + size - 1;
        int maxZ = minZ + size - 1;

        int total = 0;
        int inside = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                total++;
                if (targetBlocks.contains(packBlock(x, z))) inside++;
            }
        }
        if (inside < Math.max(1, (int) (total * 0.80))) return false;

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!targetBlocks.contains(packBlock(x, z))) continue;
                scanned[0]++;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                if (y <= level.getMinBuildHeight()) continue;
                BlockPos pos = new BlockPos(x, y, z);
                if (!level.getBlockState(pos).is(Blocks.STONE)) {
                    level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
                    changed[0]++;
                }
            }
        }
        return true;
    }

    private static long packBlock(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    public void handleCreateCity(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            String body = HttpUtil.readBody(exchange);
            CityConfig config = gson.fromJson(body, CityConfig.class);

            if (config == null || config.territoryId == null || config.territoryId.isBlank()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Invalid parameters (territoryId)\"}");
                return;
            }

            if (!config.allowWaterCity) {
                ServerLevel level = mcServer != null ? mcServer.overworld() : null;
                if (level != null) {
                    CenterWaterProbe probe = probeCenterWater(level, config.centerX, config.centerZ);
                    if (probe.centerIsWater || probe.waterRatio >= CENTER_WATER_RATIO_THRESHOLD) {
                        String error = String.format(
                                "Center appears to be water-heavy (center_is_water=%s, water_ratio=%.2f). " +
                                        "Pick another center or set allow_water_city=true.",
                                probe.centerIsWater,
                                probe.waterRatio
                        );
                        HttpUtil.sendResponse(exchange, 400, "{\"error\": \"" + escapeJson(error) + "\"}");
                        return;
                    }
                }
            }

            CityInstance city = CityManager.get().createCity(config);

            JsonObject res = new JsonObject();
            res.addProperty("status", "created");
            res.addProperty("city_id", city.id);
            res.addProperty("actual_size", city.claimedChunks.size());
            res.addProperty("blocks_total", city.claimedChunks.size() * 256);

            CityConfig.LayerLayout layout = city.getLayerLayout();
            int weightSum = 0;
            for (CityConfig.LayerConfig layer : layout.layers) {
                weightSum += Math.max(1, layer.weight);
            }
            res.addProperty("weight_sum", weightSum);

            int[] chunkPerLayer = new int[layout.layers.size()];
            for (CityInstance.LayerAssignment assignment : city.claimedChunks.values()) {
                if (assignment == null) continue;
                int idx = Math.max(0, Math.min(layout.layers.size() - 1, assignment.layerIndex));
                chunkPerLayer[idx]++;
            }

            JsonArray layerStats = new JsonArray();
            int totalChunks = Math.max(1, city.claimedChunks.size());
            for (int i = 0; i < layout.layers.size(); i++) {
                CityConfig.LayerConfig layer = layout.layerAt(i);
                int chunks = chunkPerLayer[i];
                JsonObject obj = new JsonObject();
                obj.addProperty("id", layer.name != null ? layer.name.toLowerCase().replace(' ', '_') : ("layer_" + i));
                obj.addProperty("name", layer.name);
                obj.addProperty("type", layer.type);
                obj.addProperty("weight", Math.max(1, layer.weight));
                obj.addProperty("is_wall", layer.isWall || layer.wallLayer || layer.wall != null);
                obj.addProperty("chunks", chunks);
                obj.addProperty("blocks", chunks * 256);
                obj.addProperty("ratio", chunks / (double) totalChunks);
                layerStats.add(obj);
            }
            res.add("layers", layerStats);

            HttpUtil.sendResponse(exchange, 200, gson.toJson(res));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    public void handleCitySurvivalC1Generate(HttpExchange exchange) throws IOException {
        if (!HttpUtil.requireMethod(exchange, "POST")) return;
        try {
            com.user.terra_script.world.TerritoryManager.restoreT3ResultsFromDisk(mcServer);
            String body = HttpUtil.readBody(exchange);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            CityConfig config = gson.fromJson(json, CityConfig.class);
            if (config == null || config.territoryId == null || config.territoryId.isBlank()) {
                HttpUtil.sendResponse(exchange, 400, "{\"error\": \"Invalid parameters (territoryId)\"}");
                return;
            }

            String cityId = "city_" + config.centerX + "_" + config.centerZ;
            CitySurvivalBoundaryPlanner.Request request = new CitySurvivalBoundaryPlanner.Request();
            request.cityId = cityId;
            request.territoryId = config.territoryId;
            request.centerX = config.centerX;
            request.centerZ = config.centerZ;
            request.targetChunkCount = config.targetChunkCount;
            request.density = config.density;
            request.existingCityId = cityId;
            if (json.has("search_radius_chunks")) {
                request.searchRadiusChunks = Math.max(1, json.get("search_radius_chunks").getAsInt());
            }

            ServerLevel level = mcServer != null ? mcServer.overworld() : null;
            CitySurvivalBoundaryPlanner.Result boundary = CitySurvivalBoundaryPlanner.plan(
                    request,
                    new RuntimeSurvivalChunkAccess(level, cityId)
            );
            if (boundary.choices.isEmpty()) {
                JsonObject res = new JsonObject();
                res.addProperty("status", boundary.status);
                res.addProperty("city_id", cityId);
                res.add("validation", CitySurvivalBoundaryExporter.toValidationJson(boundary));
                HttpUtil.sendResponse(exchange, 409, gson.toJson(res));
                return;
            }

            CityInstance city = CityManager.get().registerSurvivalBoundary(config, boundary);
            JsonObject artifacts = CitySurvivalBoundaryExporter.export(mcServer, boundary);

            JsonObject res = new JsonObject();
            res.addProperty("status", "created");
            res.addProperty("step", "C1_SURVIVAL_BOUNDARY");
            res.addProperty("city_id", city.id);
            res.addProperty("actual_size", city.claimedChunks.size());
            res.addProperty("blocks_total", city.claimedChunks.size() * 256);
            res.addProperty("boundary_status", boundary.status);
            res.addProperty("fallback_used", boundary.fallbackUsed);
            res.addProperty("reanchored", boundary.reanchored);
            res.add("risk_tags", DEBUG_GSON.toJsonTree(boundary.riskTags));
            res.add("warnings", DEBUG_GSON.toJsonTree(boundary.warnings));
            res.add("validation", CitySurvivalBoundaryExporter.toValidationJson(boundary));
            res.add("artifacts", artifacts);
            HttpUtil.sendResponse(exchange, 200, gson.toJson(res));
        } catch (Exception e) {
            HttpUtil.handleError(exchange, e);
        }
    }

    private static CenterWaterProbe probeCenterWater(ServerLevel level, int centerX, int centerZ) {
        int waterSamples = 0;
        int totalSamples = 0;
        boolean centerIsWater = isSurfaceWater(level, centerX, centerZ);

        for (int dx = -CENTER_WATER_PROBE_RADIUS; dx <= CENTER_WATER_PROBE_RADIUS; dx += CENTER_WATER_PROBE_STEP) {
            for (int dz = -CENTER_WATER_PROBE_RADIUS; dz <= CENTER_WATER_PROBE_RADIUS; dz += CENTER_WATER_PROBE_STEP) {
                int x = centerX + dx;
                int z = centerZ + dz;
                if (isSurfaceWater(level, x, z)) {
                    waterSamples++;
                }
                totalSamples++;
            }
        }
        double ratio = totalSamples <= 0 ? 0.0 : (waterSamples / (double) totalSamples);
        return new CenterWaterProbe(centerIsWater, waterSamples, totalSamples, ratio);
    }

    private static boolean isSurfaceWater(ServerLevel level, int x, int z) {
        int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos pos = new BlockPos(x, surfaceY - 1, z);
        return !level.getFluidState(pos).isEmpty();
    }

    private static final class RuntimeSurvivalChunkAccess implements CitySurvivalBoundaryPlanner.ChunkAccess {
        private final ServerLevel level;
        private final String existingCityId;

        private RuntimeSurvivalChunkAccess(ServerLevel level, String existingCityId) {
            this.level = level;
            this.existingCityId = existingCityId;
        }

        @Override
        public boolean isWithinSovereignty(long chunkKey, String territoryId) {
            return com.user.terra_script.world.TerritoryManager.isChunkWithinSovereignty(chunkKey, territoryId);
        }

        @Override
        public String cityIdAt(long chunkKey) {
            String cityId = CityManager.get().getCityIdAt(chunkKey);
            if (cityId != null && cityId.equals(existingCityId)) return null;
            return cityId;
        }

        @Override
        public double terrainRisk(int chunkX, int chunkZ) {
            if (level == null) return 0.0;
            int worldX = chunkX * 16 + 8;
            int worldZ = chunkZ * 16 + 8;
            int centerHeight = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ);
            int maxDelta = 0;
            int[][] offsets = {{8, 0}, {-8, 0}, {0, 8}, {0, -8}};
            for (int[] offset : offsets) {
                int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, worldX + offset[0], worldZ + offset[1]);
                maxDelta = Math.max(maxDelta, Math.abs(h - centerHeight));
            }
            return Math.min(1.0, maxDelta / 24.0);
        }

        @Override
        public boolean waterLike(int chunkX, int chunkZ) {
            if (level == null) return false;
            int worldX = chunkX * 16 + 8;
            int worldZ = chunkZ * 16 + 8;
            return isSurfaceWater(level, worldX, worldZ);
        }

        @Override
        public boolean roughLike(int chunkX, int chunkZ) {
            return terrainRisk(chunkX, chunkZ) >= 0.45;
        }
    }

    private static String escapeJson(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static List<String> readStringList(JsonObject json, String key) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        if (json == null || key == null || key.isBlank() || !json.has(key) || !json.get(key).isJsonArray()) {
            return new java.util.ArrayList<>();
        }
        JsonArray arr = json.getAsJsonArray(key);
        for (int i = 0; i < arr.size(); i++) {
            if (arr.get(i) == null || arr.get(i).isJsonNull()) continue;
            String value = arr.get(i).getAsString();
            if (value == null) continue;
            String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
            if (normalized.isBlank()) continue;
            out.add(normalized);
        }
        return new java.util.ArrayList<>(out);
    }

    private static CitySemanticStages.MergePolicy parseC5MergePolicy(JsonObject json) {
        CitySemanticStages.MergePolicy policy = CitySemanticStages.MergePolicy.defaults();
        if (json == null) return policy;
        if (json.has("cross_layer_merge")) {
            policy.cross_layer_merge = json.get("cross_layer_merge").getAsBoolean();
        }
        if (json.has("adjacency_mode")) {
            policy.adjacency_mode = json.get("adjacency_mode").getAsString();
        }
        if (json.has("min_district_area_chunks")) {
            policy.min_district_area_chunks = Math.max(0, json.get("min_district_area_chunks").getAsInt());
        }
        if (json.has("allow_cross_function_absorb_for_tiny")) {
            policy.allow_cross_function_absorb_for_tiny = json.get("allow_cross_function_absorb_for_tiny").getAsBoolean();
        }
        if (json.has("split_disconnected_group")) {
            policy.split_disconnected_group = json.get("split_disconnected_group").getAsBoolean();
        }
        if (json.has("min_compactness")) {
            double value = json.get("min_compactness").getAsDouble();
            if (!Double.isFinite(value)) value = policy.min_compactness;
            policy.min_compactness = Math.max(0.0, Math.min(1.0, value));
        }
        return policy;
    }

    private Path resolveCityDir(String cityId) {
        if (mcServer != null) {
            return mcServer.getWorldPath(LevelResource.ROOT)
                    .resolve("terra_script")
                    .resolve("cities")
                    .resolve(cityId);
        }
        return java.nio.file.Paths.get("terra_script", "cities", cityId);
    }

    private static final class CenterWaterProbe {
        final boolean centerIsWater;
        final int waterSamples;
        final int totalSamples;
        final double waterRatio;

        CenterWaterProbe(boolean centerIsWater, int waterSamples, int totalSamples, double waterRatio) {
            this.centerIsWater = centerIsWater;
            this.waterSamples = waterSamples;
            this.totalSamples = totalSamples;
            this.waterRatio = waterRatio;
        }
    }

    private static CityScanBounds computeCityScanBounds(CityInstance city, int paddingBlocks) {
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        if (city != null && city.claimedChunks != null && !city.claimedChunks.isEmpty()) {
            for (Long key : city.claimedChunks.keySet()) {
                if (key == null) continue;
                int cx = net.minecraft.world.level.ChunkPos.getX(key);
                int cz = net.minecraft.world.level.ChunkPos.getZ(key);
                int cMinX = cx << 4;
                int cMinZ = cz << 4;
                int cMaxX = cMinX + 15;
                int cMaxZ = cMinZ + 15;
                if (cMinX < minX) minX = cMinX;
                if (cMinZ < minZ) minZ = cMinZ;
                if (cMaxX > maxX) maxX = cMaxX;
                if (cMaxZ > maxZ) maxZ = cMaxZ;
            }
        } else {
            int centerX = city != null && city.config != null ? city.config.centerX : 0;
            int centerZ = city != null && city.config != null ? city.config.centerZ : 0;
            minX = centerX - 128;
            minZ = centerZ - 128;
            maxX = centerX + 128;
            maxZ = centerZ + 128;
        }

        minX -= paddingBlocks;
        minZ -= paddingBlocks;
        maxX += paddingBlocks;
        maxZ += paddingBlocks;
        int width = Math.max(16, maxX - minX + 1);
        int height = Math.max(16, maxZ - minZ + 1);
        return new CityScanBounds(minX, minZ, width, height);
    }

    private static final class CityScanBounds {
        final int minX;
        final int minZ;
        final int width;
        final int height;

        private CityScanBounds(int minX, int minZ, int width, int height) {
            this.minX = minX;
            this.minZ = minZ;
            this.width = width;
            this.height = height;
        }
    }

    private static C2ScanResolved resolveC2ScanData(
            String cityId,
            CityScanBounds bounds,
            int scanStep,
            ServerLevel level
    ) throws Exception {
        long now = System.currentTimeMillis();
        C2CacheEntry mem = C2_SCAN_CACHE.get(cityId);
        if (mem != null
                && (now - mem.cachedAtMs) <= C2_MEMORY_CACHE_TTL_MS
                && isMatchingScan(mem.data, bounds, scanStep)
                && mem.data.map != null
                && mem.data.map.length > 0
                && mem.data.map[0] != null) {
            return new C2ScanResolved(mem.data.map, CityC2ScanBinaryIO.dataFile(cityId), true, "memory");
        }

        CityC2ScanBinaryIO.C2ScanData disk = CityC2ScanBinaryIO.load(cityId);
        if (isMatchingScan(disk, bounds, scanStep) && disk.map != null && disk.map.length > 0 && disk.map[0] != null) {
            C2_SCAN_CACHE.put(cityId, new C2CacheEntry(disk, now));
            return new C2ScanResolved(disk.map, CityC2ScanBinaryIO.dataFile(cityId), true, "disk");
        }

        ScanPixel[][] scanned = SatelliteScanner.scanRegionAsync(
                level,
                bounds.minX,
                bounds.minZ,
                bounds.width,
                bounds.height,
                scanStep
        ).get(10, TimeUnit.MINUTES);
        File file = CityC2ScanBinaryIO.save(
                cityId,
                bounds.minX,
                bounds.minZ,
                bounds.width,
                bounds.height,
                scanStep,
                scanned
        );
        C2_SCAN_CACHE.put(cityId, new C2CacheEntry(
                new CityC2ScanBinaryIO.C2ScanData(bounds.minX, bounds.minZ, bounds.width, bounds.height, scanStep, scanned),
                now
        ));
        return new C2ScanResolved(scanned, file, false, "scan");
    }

    private static boolean isMatchingScan(CityC2ScanBinaryIO.C2ScanData data, CityScanBounds bounds, int step) {
        if (data == null || bounds == null) return false;
        return data.step == step
                && data.originX == bounds.minX
                && data.originZ == bounds.minZ
                && data.widthBlocks == bounds.width
                && data.heightBlocks == bounds.height;
    }

    private static final class C2CacheEntry {
        final CityC2ScanBinaryIO.C2ScanData data;
        final long cachedAtMs;

        C2CacheEntry(CityC2ScanBinaryIO.C2ScanData data, long cachedAtMs) {
            this.data = data;
            this.cachedAtMs = cachedAtMs;
        }
    }

    private static final class C2ScanResolved {
        final ScanPixel[][] map;
        final File file;
        final boolean cacheHit;
        final String cacheSource;

        C2ScanResolved(ScanPixel[][] map, File file, boolean cacheHit, String cacheSource) {
            this.map = map;
            this.file = file;
            this.cacheHit = cacheHit;
            this.cacheSource = cacheSource;
        }
    }

    private static String readOptionalString(JsonObject json, String key) {
        if (json == null || key == null || key.isBlank() || !json.has(key) || json.get(key).isJsonNull()) return null;
        String value = json.get(key).getAsString();
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Path resolveGroupDir(Path cityDir, String groupId) throws Exception {
        return CityGroupPathUtil.resolveGroupDir(cityDir, groupId);
    }

    private static CityC6Stages.C6Summary filterC6SummaryByGroup(CityC6Stages.C6Summary summary, String groupId) {
        if (summary == null || groupId == null || groupId.isBlank()) return summary;
        CityC6Stages.C6Summary copy = new CityC6Stages.C6Summary();
        copy.ok = summary.ok;
        copy.city_id = summary.city_id;
        copy.rules_version = summary.rules_version;
        copy.fill_style = summary.fill_style;
        copy.generated_at_epoch_ms = summary.generated_at_epoch_ms;
        for (CityC6Stages.BuildAreaSummary area : summary.areas) {
            if (area != null && groupId.equals(area.group_id)) copy.areas.add(area);
        }
        return copy;
    }

    private static CityC6Stages.C6Layout filterC6LayoutByGroup(CityC6Stages.C6Layout layout, String groupId) {
        if (layout == null || groupId == null || groupId.isBlank()) return layout;
        CityC6Stages.C6Layout copy = new CityC6Stages.C6Layout();
        copy.ok = layout.ok;
        copy.city_id = layout.city_id;
        copy.fill_style = layout.fill_style;
        copy.version = layout.version;
        copy.generated_at_epoch_ms = layout.generated_at_epoch_ms;
        for (CityC6Stages.LayoutPlan plan : layout.plans) {
            if (plan != null && groupId.equals(plan.group_id)) copy.plans.add(plan);
        }
        return copy;
    }


    private static CityC6Stages.C6RectDecisionInput filterDecisionInputByGroup(CityC6Stages.C6RectDecisionInput input, String groupId) {
        if (input == null || groupId == null || groupId.isBlank()) return input;
        CityC6Stages.C6RectDecisionInput copy = new CityC6Stages.C6RectDecisionInput();
        copy.ok = input.ok;
        copy.city_id = input.city_id;
        copy.rect_limit = input.rect_limit;
        copy.min_total_primary_area_ratio = input.min_total_primary_area_ratio;
        copy.generated_at_epoch_ms = input.generated_at_epoch_ms;
        for (CityC6Stages.GroupDecisionInput item : input.groups) {
            if (item != null && groupId.equals(item.group_id)) copy.groups.add(item);
        }
        return copy;
    }

    private static CityC6Stages.C6RectCandidates filterCandidatesByGroup(CityC6Stages.C6RectCandidates candidates, String groupId) {
        if (candidates == null || groupId == null || groupId.isBlank()) return candidates;
        CityC6Stages.C6RectCandidates copy = new CityC6Stages.C6RectCandidates();
        copy.ok = candidates.ok;
        copy.city_id = candidates.city_id;
        copy.updated_at_epoch_ms = candidates.updated_at_epoch_ms;
        for (CityC6Stages.GroupRectCandidate item : candidates.items) {
            if (item != null && groupId.equals(item.group_id)) copy.items.add(item);
        }
        return copy;
    }

    private static CityC6Stages.C6RectValidation filterValidationByGroup(CityC6Stages.C6RectValidation validation, String groupId) {
        if (validation == null || groupId == null || groupId.isBlank()) return validation;
        CityC6Stages.C6RectValidation copy = new CityC6Stages.C6RectValidation();
        copy.ok = validation.ok;
        copy.city_id = validation.city_id;
        copy.updated_at_epoch_ms = validation.updated_at_epoch_ms;
        for (CityC6Stages.GroupRectValidation item : validation.items) {
            if (item != null && groupId.equals(item.group_id)) copy.items.add(item);
        }
        return copy;
    }

    private static CityC6Stages.BuildAreaSummary findBestAreaByGroup(CityC6Stages.C6Summary summary, String groupId) {
        if (summary == null || summary.areas == null || groupId == null) return null;
        CityC6Stages.BuildAreaSummary best = null;
        for (CityC6Stages.BuildAreaSummary area : summary.areas) {
            if (area == null || !groupId.equals(area.group_id)) continue;
            if (best == null || area.area_blocks > best.area_blocks) best = area;
        }
        return best;
    }

    private static List<CityC6Stages.RectDecision> parseSubmittedRects(JsonObject json) {
        List<CityC6Stages.RectDecision> rects = new ArrayList<>();
        if (json == null || !json.has("rects") || !json.get("rects").isJsonArray()) return rects;
        for (var element : json.getAsJsonArray("rects")) {
            if (!element.isJsonObject()) continue;
            JsonObject obj = element.getAsJsonObject();
            CityC6Stages.RectDecision rect = new CityC6Stages.RectDecision();
            rect.rect_id = readOptionalString(obj, "rect_id");
            rect.role = readOptionalString(obj, "role");
            if (rect.role == null) rect.role = "primary";
            rect.cx = obj.has("cx") ? obj.get("cx").getAsInt() : 0;
            rect.cz = obj.has("cz") ? obj.get("cz").getAsInt() : 0;
            rect.w = obj.has("w") ? obj.get("w").getAsInt() : 0;
            rect.h = obj.has("h") ? obj.get("h").getAsInt() : 0;
            rect.minX = obj.has("minX") ? obj.get("minX").getAsInt() : 0;
            rect.minZ = obj.has("minZ") ? obj.get("minZ").getAsInt() : 0;
            rect.maxX = obj.has("maxX") ? obj.get("maxX").getAsInt() : 0;
            rect.maxZ = obj.has("maxZ") ? obj.get("maxZ").getAsInt() : 0;
            CityC6Validation.normalizeRect(rect);
            rects.add(rect);
        }
        return rects;
    }

    private static Set<Integer> collectAreaIdsForGroup(CityC6Stages.C6Summary summary, String groupId) {
        Set<Integer> ids = new java.util.LinkedHashSet<>();
        if (summary == null || summary.areas == null) return ids;
        for (CityC6Stages.BuildAreaSummary area : summary.areas) {
            if (area != null && (groupId == null || groupId.isBlank() || groupId.equals(area.group_id))) ids.add(area.build_area_numeric_id);
        }
        return ids;
    }

    private static Map<Long, Integer> filterIndexByAreaIds(Map<Long, Integer> index, Set<Integer> areaIds) {
        if (index == null || areaIds == null || areaIds.isEmpty()) return index;
        Map<Long, Integer> filtered = new java.util.LinkedHashMap<>();
        for (Map.Entry<Long, Integer> entry : index.entrySet()) {
            if (entry.getValue() != null && areaIds.contains(entry.getValue())) filtered.put(entry.getKey(), entry.getValue());
        }
        return filtered;
    }

    private static CityC7Stages.C7Selection filterC7SelectionByGroup(CityC7Stages.C7Selection selection, String groupId) {
        if (selection == null || groupId == null || groupId.isBlank()) return selection;
        CityC7Stages.C7Selection copy = new CityC7Stages.C7Selection();
        copy.version = selection.version;
        copy.ok = selection.ok;
        copy.city_id = selection.city_id;
        copy.generated_at_epoch_ms = selection.generated_at_epoch_ms;
        copy.catalog_source = selection.catalog_source;
        copy.decision_source = selection.decision_source;
        copy.selection_mode = selection.selection_mode;
        copy.strict_tag_source = selection.strict_tag_source;
        copy.filtered_candidate_count = selection.filtered_candidate_count;
        copy.strict_filter_failure_reason = selection.strict_filter_failure_reason;
        copy.puzzle_depth = selection.puzzle_depth;
        if (selection.foreman_plans != null) {
            for (CityC7Stages.ForemanPlan plan : selection.foreman_plans) {
                if (plan != null && groupId.equals(plan.group_id)) copy.foreman_plans.add(plan);
            }
        }
        copy.foreman_plan = !copy.foreman_plans.isEmpty() ? copy.foreman_plans.get(0) : null;
        if (copy.foreman_plan != null && copy.foreman_plan.phase_list != null) {
            copy.phase_list.addAll(copy.foreman_plan.phase_list);
        }
        if (selection.selections != null) {
            for (CityC7Stages.TemplateSelectionItem item : selection.selections) {
                if (item != null && groupId.equals(item.group_id)) copy.selections.add(item);
            }
        }
        if (selection.arrangements != null) {
            for (CityC7Stages.GroupArrangementDecision item : selection.arrangements) {
                if (item != null && groupId.equals(item.group_id)) copy.arrangements.add(item);
            }
        }
        return copy;
    }

    private static CityC7Stages.C7Selection loadC7Selection(Path cityDir, String groupId) throws Exception {
        if (groupId != null && !groupId.isBlank()) {
            Path groupFile = resolveGroupDir(cityDir, groupId).resolve("c7_selection.json");
            if (java.nio.file.Files.exists(groupFile)) {
                return new Gson().fromJson(java.nio.file.Files.readString(groupFile), CityC7Stages.C7Selection.class);
            }
        }
        return CityC7Stages.load(cityDir);
    }

    private static CityC7Stages.C7Selection loadC8GenerationSelection(Path cityDir, String groupId) throws Exception {
        if (groupId != null && !groupId.isBlank()) {
            CityC7Stages.C7Selection groupSelection = loadC7Selection(cityDir, groupId);
            if (groupSelection != null) return groupSelection;
        }
        return CityC7Stages.load(cityDir);
    }

    private static CityC8Stages.C8Plan loadC9GenerationPlan(Path cityDir, String groupId) throws Exception {
        if (cityDir == null) return null;
        if (groupId != null && !groupId.isBlank()) {
            Path groupFile = resolveGroupDir(cityDir, groupId).resolve("c8_foundation.json");
            if (java.nio.file.Files.exists(groupFile)) {
                CityC8Stages.C8Plan groupPlan = new Gson().fromJson(java.nio.file.Files.readString(groupFile), CityC8Stages.C8Plan.class);
                if (groupPlan != null) return groupPlan;
            }
        }
        return CityC8Stages.load(cityDir);
    }

    private CityC8Stages.C8Plan loadNormalizedC8Plan(Path cityDir, String groupId) throws Exception {
        CityC8Stages.C8Plan plan = loadC9GenerationPlan(cityDir, groupId);
        if (plan == null || mcServer == null || mcServer.overworld() == null) {
            return plan;
        }
        if (CityC8Stages.normalizeRuntimeStartHeights(mcServer.overworld(), plan)) {
            saveC8Plan(cityDir, groupId, plan);
        }
        return plan;
    }

    private static CityC8Stages.C8Plan filterC8PlanByGroup(CityC8Stages.C8Plan plan, String groupId, Set<Integer> areaIds) {
        if (plan == null || groupId == null || groupId.isBlank()) return plan;
        CityC8Stages.C8Plan copy = new CityC8Stages.C8Plan();
        copy.ok = plan.ok;
        copy.city_id = plan.city_id;
        copy.version = plan.version;
        copy.generated_at_epoch_ms = plan.generated_at_epoch_ms;
        for (CityC8Stages.FoundationItem item : plan.foundations) {
            if (item != null && (groupId.equals(item.group_id) || areaIds.contains(item.build_area_numeric_id))) copy.foundations.add(item);
        }
        return copy;
    }

    private static CityC9Stages.C9Result filterC9ResultByAreas(CityC9Stages.C9Result result, Set<Integer> areaIds) {
        if (result == null || areaIds == null || areaIds.isEmpty()) return result;
        CityC9Stages.C9Result copy = new CityC9Stages.C9Result();
        copy.placement = new CityC9Stages.C9Placement();
        copy.decoration = new CityC9Stages.C9Decoration();
        if (result.placement != null) {
            copy.placement.city_id = result.placement.city_id;
            copy.placement.generated_at_epoch_ms = result.placement.generated_at_epoch_ms;
            copy.placement.mode = result.placement.mode;
            copy.placement.apply_blocks = result.placement.apply_blocks;
            copy.placement.max_blocks = result.placement.max_blocks;
            copy.placement.enqueued_tasks_count = result.placement.enqueued_tasks_count;
            copy.placement.applied_tasks_count = result.placement.applied_tasks_count;
            for (CityC9Stages.PlacementItem item : result.placement.items) {
                if (item != null && areaIds.contains(item.build_area_numeric_id)) {
                    copy.placement.items.add(item);
                    copy.placement.changed_blocks_total += item.changed_blocks;
                }
            }
            copy.placement.processed_areas = copy.placement.items.size();
        }
        if (result.decoration != null) {
            copy.decoration.city_id = result.decoration.city_id;
            copy.decoration.generated_at_epoch_ms = result.decoration.generated_at_epoch_ms;
            for (CityC9Stages.DecorationItem item : result.decoration.items) {
                if (item != null) copy.decoration.items.add(item);
            }
        }
        copy.queue = result.queue;
        copy.queue_summary = result.queue_summary;
        return copy;
    }

    private static void finalizeJigsawDebugArtifacts(
            JsonObject response,
            CityJigsawSolverDebugTrace debugTrace,
            Path cityDir,
            String cityId,
            String groupId,
            String buildAreaId,
            CityStage1BinaryIO.HeightData heightData,
            CityC2ScanBinaryIO.C2ScanData c2ScanData,
            CityC8Stages.AreaGeometry geometry,
            List<CityC8Stages.PlacementNode> existingPlacements,
            CityC8Stages.PlacementNode parentPlacement,
            String parentConnectorId,
            CityVanillaJigsawAdapterService.SolveResult solveResult,
            SolvedPlacementExecutionService.ExecutionResult execution,
            boolean applyNow
    ) throws Exception {
        if (debugTrace == null) return;
        Map<String, String> previewPaths = CityJigsawSolverPreviewExporter.export(
                cityDir,
                cityId,
                groupId,
                debugTrace.debugRunId(),
                buildAreaId,
                heightData,
                c2ScanData,
                geometry,
                existingPlacements,
                parentPlacement,
                parentConnectorId,
                solveResult,
                execution,
                applyNow
        );
        for (Map.Entry<String, String> entry : previewPaths.entrySet()) {
            debugTrace.attachPreview(entry.getKey(), entry.getValue());
        }
        debugTrace.writeTrace();
        if (response != null) {
            response.addProperty("debug_run_id", debugTrace.debugRunId());
            response.addProperty("debug_artifact_dir", debugTrace.relativeArtifactDir());
            response.add("debug_trace", debugTrace.toJson());
            response.add("debug_preview_steps", debugTrace.previewStepsJson());
        }
    }

    private static void finalizeC8SubmitDebugArtifacts(
            JsonObject response,
            CityC8SubmitDebugTrace debugTrace,
            Path cityDir,
            String cityId,
            String groupId,
            String buildAreaId,
            CityStage1BinaryIO.HeightData heightData,
            CityC2ScanBinaryIO.C2ScanData c2ScanData,
            CityC8Stages.AreaGeometry geometry,
            List<CityC8Stages.PlacementNode> existingPlacements,
            CityC8Stages.PlacementNode placement,
            StructureInjector.PlacementBounds placementBounds,
            SolvedPlacementExecutionService.ExecutionResult execution
    ) throws Exception {
        if (debugTrace == null) return;
        if (placement != null && geometry != null && geometry.valid) {
            Map<String, String> previewPaths = CityC8SubmitPreviewExporter.export(
                    cityDir,
                    cityId,
                    groupId,
                    debugTrace.debugRunId(),
                    buildAreaId,
                    heightData,
                    c2ScanData,
                    geometry,
                    existingPlacements,
                    placement,
                    placementBounds,
                    execution
            );
            for (Map.Entry<String, String> entry : previewPaths.entrySet()) {
                debugTrace.attachPreview(entry.getKey(), entry.getValue());
            }
        }
        debugTrace.writeTrace();
        if (response != null) {
            response.addProperty("debug_run_id", debugTrace.debugRunId());
            response.addProperty("debug_artifact_dir", debugTrace.relativeArtifactDir());
            response.addProperty("debug_trace_file", debugTrace.relativeArtifactDir() + "/trace.json");
            response.add("debug_preview_steps", debugTrace.previewStepsJson());
        }
    }

    private static JsonObject buildC8SubmitRequestEvidence(
            CityC8Stages.FoundationItem foundation,
            CityC8Stages.AreaGeometry geometry,
            CityC8Stages.NodeDecision decision,
            List<CityC8Stages.PlacementNode> existingPlacements,
            boolean applyNow
    ) {
        JsonObject out = new JsonObject();
        if (foundation != null) {
            out.addProperty("group_id", safe(foundation.group_id));
            out.addProperty("build_area_id", safe(foundation.build_area_id));
            out.addProperty("build_area_numeric_id", foundation.build_area_numeric_id);
            out.addProperty("base_y", foundation.base_y);
        }
        if (decision != null) {
            out.addProperty("node_id", safe(decision.node_id));
            out.addProperty("selected_template_id", safe(decision.selected_template_id));
            out.addProperty("selected_connector_dir", safe(decision.selected_connector_dir));
            if (decision.selected_rotation != null) out.addProperty("selected_rotation", decision.selected_rotation);
            if (decision.x != null) out.addProperty("requested_x", decision.x);
            if (decision.z != null) out.addProperty("requested_z", decision.z);
        }
        out.addProperty("apply_now", applyNow);
        out.addProperty("existing_placement_count", existingPlacements != null ? existingPlacements.size() : 0);
        if (geometry != null) {
            out.addProperty("geometry_valid", geometry.valid);
            out.addProperty("geometry_min_x", geometry.min_x);
            out.addProperty("geometry_min_z", geometry.min_z);
            out.addProperty("geometry_max_x", geometry.max_x);
            out.addProperty("geometry_max_z", geometry.max_z);
        }
        return out;
    }

    private static JsonObject buildC8SubmitValidationEvidence(
            CityC8Stages.NodeSubmitResult submitResult,
            StructureInjector.PlacementBounds placementBounds
    ) {
        JsonObject out = new JsonObject();
        if (submitResult != null) {
            out.addProperty("ok", submitResult.ok);
            out.addProperty("error_code", safe(submitResult.error_code));
            out.addProperty("error_message", safe(submitResult.error_message));
            if (submitResult.node != null) {
                out.add("node", DEBUG_GSON.toJsonTree(submitResult.node));
            }
            if (submitResult.placement != null) {
                out.add("placement", DEBUG_GSON.toJsonTree(submitResult.placement));
            }
        }
        if (placementBounds != null) {
            out.add("runtime_placement_bounds", placementBoundsToJson(placementBounds));
        }
        return out;
    }

    private static JsonObject buildC8SubmitApplyEvidence(JsonObject executionJson, StructureInjector.PlacementBounds placementBounds) {
        JsonObject out = new JsonObject();
        if (executionJson != null) {
            out.add("placement_execution", executionJson.deepCopy());
        }
        if (placementBounds != null) {
            out.add("runtime_placement_bounds", placementBoundsToJson(placementBounds));
        }
        return out;
    }

    private static JsonObject buildC8SubmitWorldSnapshotEvidence(
            JsonObject beforeSnapshot,
            JsonObject afterSnapshot,
            StructureInjector.PlacementBounds placementBounds
    ) {
        JsonObject out = new JsonObject();
        if (placementBounds != null) {
            out.add("runtime_placement_bounds", placementBoundsToJson(placementBounds));
        }
        if (beforeSnapshot != null) out.add("before", beforeSnapshot.deepCopy());
        if (afterSnapshot != null) out.add("after", afterSnapshot.deepCopy());
        return out;
    }

    private static JsonObject placementBoundsToJson(StructureInjector.PlacementBounds bounds) {
        JsonObject out = new JsonObject();
        if (bounds == null) return out;
        out.addProperty("min_x", bounds.minX);
        out.addProperty("min_y", bounds.minY);
        out.addProperty("min_z", bounds.minZ);
        out.addProperty("max_x_exclusive", bounds.maxXExclusive);
        out.addProperty("max_y_exclusive", bounds.maxYExclusive);
        out.addProperty("max_z_exclusive", bounds.maxZExclusive);
        return out;
    }

    private static JsonObject capturePlacementWorldStats(
            ServerLevel level,
            CityC8Stages.PlacementNode placement,
            StructureInjector.PlacementBounds fallbackBounds
    ) {
        JsonObject out = new JsonObject();
        if (level == null || placement == null) return out;
        StructureInjector.PlacementBounds bounds = fallbackBounds != null
                ? fallbackBounds
                : StructureInjector.placementBounds(
                level,
                placement.template_id,
                new BlockPos(placement.x, placement.y, placement.z),
                toRotation(placement.rotation)
        );
        if (bounds == null) return out;
        out.addProperty("template_id", safe(placement.template_id));
        out.addProperty("origin_x", placement.x);
        out.addProperty("origin_y", placement.y);
        out.addProperty("origin_z", placement.z);
        out.addProperty("rotation", placement.rotation);
        out.add("placement_bounds", placementBoundsToJson(bounds));

        int nonAirCount = 0;
        int jigsawCount = 0;
        Map<String, Integer> blockCounts = new java.util.LinkedHashMap<>();
        JsonArray samples = new JsonArray();
        JsonArray byX = new JsonArray();
        JsonArray byZ = new JsonArray();
        for (int x = bounds.minX; x < bounds.maxXExclusive; x++) {
            int sliceCount = 0;
            for (int y = bounds.minY; y < bounds.maxYExclusive; y++) {
                for (int z = bounds.minZ; z < bounds.maxZExclusive; z++) {
                    BlockState state = level.getBlockState(new BlockPos(x, y, z));
                    if (state.isAir()) continue;
                    nonAirCount++;
                    sliceCount++;
                    if (state.is(Blocks.JIGSAW)) jigsawCount++;
                    ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                    String blockId = key != null ? key.toString() : "minecraft:unknown";
                    blockCounts.put(blockId, blockCounts.getOrDefault(blockId, 0) + 1);
                    if (samples.size() < 10) {
                        JsonObject sample = new JsonObject();
                        sample.addProperty("x", x);
                        sample.addProperty("y", y);
                        sample.addProperty("z", z);
                        sample.addProperty("block_id", blockId);
                        samples.add(sample);
                    }
                }
            }
            JsonObject slice = new JsonObject();
            slice.addProperty("x", x);
            slice.addProperty("non_air_count", sliceCount);
            byX.add(slice);
        }
        for (int z = bounds.minZ; z < bounds.maxZExclusive; z++) {
            int sliceCount = 0;
            for (int y = bounds.minY; y < bounds.maxYExclusive; y++) {
                for (int x = bounds.minX; x < bounds.maxXExclusive; x++) {
                    if (!level.getBlockState(new BlockPos(x, y, z)).isAir()) {
                        sliceCount++;
                    }
                }
            }
            JsonObject slice = new JsonObject();
            slice.addProperty("z", z);
            slice.addProperty("non_air_count", sliceCount);
            byZ.add(slice);
        }
        JsonArray topBlocks = new JsonArray();
        blockCounts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(12)
                .forEach(entry -> {
                    JsonObject item = new JsonObject();
                    item.addProperty("block_id", entry.getKey());
                    item.addProperty("count", entry.getValue());
                    topBlocks.add(item);
                });
        out.addProperty("non_air_block_count", nonAirCount);
        out.addProperty("jigsaw_block_count", jigsawCount);
        out.add("top_block_counts", topBlocks);
        out.add("sample_non_air_blocks", samples);
        out.add("slice_non_air_by_x", byX);
        out.add("slice_non_air_by_z", byZ);
        return out;
    }

    private static JsonObject buildJigsawRequestEvidence(
            CityC8Stages.FoundationItem foundation,
            CityC8Stages.AreaGeometry geometry,
            CityC8Stages.PlacementNode parentPlacement,
            List<CityC8Stages.PlacementNode> existingPlacements,
            String parentConnectorId,
            String selectedTemplateId,
            String selectedConnectorDir,
            Integer selectedRotation,
            boolean applyNow
    ) {
        JsonObject out = new JsonObject();
        if (foundation != null) {
            out.addProperty("group_id", foundation.group_id);
            out.addProperty("build_area_id", foundation.build_area_id);
            out.addProperty("build_area_numeric_id", foundation.build_area_numeric_id);
        }
        out.addProperty("parent_connector_id", parentConnectorId);
        out.addProperty("selected_template_id", selectedTemplateId);
        out.addProperty("selected_connector_dir", selectedConnectorDir);
        if (selectedRotation != null) out.addProperty("selected_rotation", selectedRotation);
        out.addProperty("apply_now", applyNow);
        out.addProperty("existing_placement_count", existingPlacements != null ? existingPlacements.size() : 0);
        if (geometry != null) {
            out.addProperty("geometry_valid", geometry.valid);
            out.addProperty("geometry_min_x", geometry.min_x);
            out.addProperty("geometry_min_z", geometry.min_z);
            out.addProperty("geometry_max_x", geometry.max_x);
            out.addProperty("geometry_max_z", geometry.max_z);
        }
        if (parentPlacement != null) {
            JsonObject parent = new JsonObject();
            parent.addProperty("node_id", parentPlacement.node_id);
            parent.addProperty("template_id", parentPlacement.template_id);
            parent.addProperty("x", parentPlacement.x);
            parent.addProperty("y", parentPlacement.y);
            parent.addProperty("z", parentPlacement.z);
            parent.addProperty("rotation", parentPlacement.rotation);
            if (parentPlacement.footprint_min_x != null) parent.addProperty("footprint_min_x", parentPlacement.footprint_min_x);
            if (parentPlacement.footprint_min_z != null) parent.addProperty("footprint_min_z", parentPlacement.footprint_min_z);
            if (parentPlacement.footprint_max_x != null) parent.addProperty("footprint_max_x", parentPlacement.footprint_max_x);
            if (parentPlacement.footprint_max_z != null) parent.addProperty("footprint_max_z", parentPlacement.footprint_max_z);
            out.add("parent_placement", parent);
        }
        return out;
    }

    private static String buildRuntimeJigsawStatus(CityVanillaJigsawAdapterService.SolveResult solveResult) {
        return solveResult != null && solveResult.runtime_parent_connectors != null && !solveResult.runtime_parent_connectors.isEmpty()
                ? "ok"
                : "warning";
    }

    private static String buildRuntimeJigsawResultZh(CityVanillaJigsawAdapterService.SolveResult solveResult) {
        int count = solveResult != null && solveResult.runtime_parent_connectors != null ? solveResult.runtime_parent_connectors.size() : 0;
        if (count <= 0) return "当前 parent 模板中没有扫到可用于调试展示的 runtime jigsaw。";
        int horizontal = 0;
        int vertical = 0;
        for (CityVanillaJigsawAdapterService.RuntimeConnectorCandidate candidate : solveResult.runtime_parent_connectors) {
            String front = candidate != null ? safe(candidate.front) : "";
            if ("north".equals(front) || "south".equals(front) || "east".equals(front) || "west".equals(front)) horizontal++;
            if ("up".equals(front) || "down".equals(front)) vertical++;
        }
        return "共找到 " + count + " 个 runtime jigsaw，其中水平连接器 " + horizontal + " 个，垂直 jigsaw " + vertical + " 个。";
    }

    private static JsonObject buildRuntimeJigsawEvidence(Gson gson, CityVanillaJigsawAdapterService.SolveResult solveResult) {
        JsonObject out = new JsonObject();
        int count = solveResult != null && solveResult.runtime_parent_connectors != null ? solveResult.runtime_parent_connectors.size() : 0;
        out.addProperty("runtime_parent_connector_count", count);
        out.add("runtime_parent_connectors", gson.toJsonTree(
                solveResult != null && solveResult.runtime_parent_connectors != null ? solveResult.runtime_parent_connectors : List.of()
        ));
        return out;
    }

    private static String buildParentResolutionStatus(CityVanillaJigsawAdapterService.SolveResult solveResult) {
        if (solveResult != null && solveResult.debug != null && solveResult.debug.parent_connector_source != null && !solveResult.debug.parent_connector_source.isBlank()) {
            return "ok";
        }
        String reject = solveResult != null ? safe(solveResult.reject_reason) : "";
        if (reject.startsWith("parent_connector_") || "missing_parent_catalog_meta".equals(reject)) return "invalid";
        return "warning";
    }

    private static String buildParentResolutionResultZh(String parentConnectorId, CityVanillaJigsawAdapterService.SolveResult solveResult) {
        if (solveResult != null && solveResult.debug != null && solveResult.debug.parent_connector_source != null) {
            if ("runtime_template".equals(solveResult.debug.parent_connector_source)) {
                return "请求的 parent connector `" + safe(parentConnectorId) + "` 已直接命中 runtime 模板中的真实 jigsaw。";
            }
            if ("catalog_projection".equals(solveResult.debug.parent_connector_source)) {
                return "请求的 parent connector `" + safe(parentConnectorId) + "` 通过 catalog 局部坐标投影后，成功定位到 runtime 模板中的 jigsaw。";
            }
        }
        String reject = solveResult != null ? safe(solveResult.reject_reason) : "";
        if ("parent_connector_not_found_in_template".equals(reject)) {
            return "catalog 中请求的 connector 无法在当前 runtime 模板里对齐到真实 jigsaw，当前仍处于 connector 漂移排查路径。";
        }
        if ("parent_connector_not_found_in_catalog".equals(reject)) {
            return "请求的 parent connector 在当前 catalog 中不存在，未能进入 runtime 模板定位。";
        }
        return "当前 parent connector 解析没有形成可确认的命中结果。";
    }

    private static JsonObject buildParentResolutionEvidence(CityVanillaJigsawAdapterService.SolveResult solveResult, String parentConnectorId) {
        JsonObject out = new JsonObject();
        out.addProperty("requested_parent_connector_id", parentConnectorId);
        if (solveResult != null) out.addProperty("reject_reason", safe(solveResult.reject_reason));
        if (solveResult != null && solveResult.debug != null) {
            out.add("debug", DEBUG_GSON.toJsonTree(solveResult.debug));
        }
        return out;
    }

    private static String buildVanillaPieceStatus(CityVanillaJigsawAdapterService.SolveResult solveResult) {
        if (solveResult != null && solveResult.debug != null && Boolean.TRUE.equals(solveResult.debug.piece_generated)) return "ok";
        if (solveResult != null && "vertical_jigsaw_solver_pending".equals(solveResult.reject_reason)) return "invalid";
        if (solveResult != null && "no_valid_jigsaw_solution".equals(solveResult.reject_reason)) return "invalid";
        return "warning";
    }

    private static String buildVanillaPieceResultZh(CityVanillaJigsawAdapterService.SolveResult solveResult) {
        if (solveResult != null && solveResult.debug != null && Boolean.TRUE.equals(solveResult.debug.piece_generated)) {
            if (solveResult.debug.manual_attach_summary != null && solveResult.debug.manual_attach_summary.summary_zh != null
                    && !solveResult.debug.manual_attach_summary.summary_zh.isBlank()) {
                return combineVanillaStepSummary(
                        solveResult.debug.manual_attach_summary.summary_zh,
                        solveResult.debug.vanilla_piece_debug_summary_zh
                );
            }
            return combineVanillaStepSummary(
                    "vanilla depth=1 已生成 child piece，并继续尝试解析 child connector 与结构矩形。",
                    solveResult.debug.vanilla_piece_debug_summary_zh
            );
        }
        if (solveResult != null && "no_valid_jigsaw_solution".equals(solveResult.reject_reason)) {
            if (solveResult.debug != null && solveResult.debug.manual_attach_summary != null
                    && solveResult.debug.manual_attach_summary.summary_zh != null
                    && !solveResult.debug.manual_attach_summary.summary_zh.isBlank()) {
                return combineVanillaStepSummary(
                        solveResult.debug.manual_attach_summary.summary_zh,
                        solveResult.debug.vanilla_piece_debug_summary_zh
                );
            }
            return combineVanillaStepSummary(
                    "已经准备好 startPos、target 与经 runtime parent pool 收束后的候选元素，但 vanilla depth=1 没有生成有效 child piece。",
                    solveResult != null && solveResult.debug != null ? solveResult.debug.vanilla_piece_debug_summary_zh : null
            );
        }
        if (solveResult != null && "runtime_parent_pool_missing".equals(solveResult.reject_reason)) {
            return "当前 parent runtime jigsaw 没有解析出可用的原始模板池，本次未进入 vanilla child 生成。";
        }
        if (solveResult != null && "selected_template_not_in_runtime_parent_pool".equals(solveResult.reject_reason)) {
            return "AI 指定模板不在当前 parent runtime jigsaw 的原始模板池中，本次未进入 vanilla child 生成。";
        }
        if (solveResult != null && "vertical_jigsaw_solver_pending".equals(solveResult.reject_reason)) {
            if (solveResult.debug != null && solveResult.debug.manual_attach_summary != null
                    && solveResult.debug.manual_attach_summary.summary_zh != null
                    && !solveResult.debug.manual_attach_summary.summary_zh.isBlank()) {
                return solveResult.debug.manual_attach_summary.summary_zh;
            }
            return "当前 connector 为垂直 jigsaw，已分流到独立 vertical solver，占位暂未实现。";
        }
        return "当前没有进入或没有完成 vanilla child piece 生成阶段。";
    }

    private static JsonObject buildVanillaPieceEvidence(CityVanillaJigsawAdapterService.SolveResult solveResult) {
        JsonObject out = new JsonObject();
        if (solveResult != null) out.addProperty("reject_reason", safe(solveResult.reject_reason));
        if (solveResult != null && solveResult.debug != null) {
            out.add("debug", DEBUG_GSON.toJsonTree(solveResult.debug));
            out.addProperty("parent_target", safe(solveResult.debug.parent_target));
            JsonObject startPos = new JsonObject();
            if (solveResult.debug.start_pos_x != null) startPos.addProperty("x", solveResult.debug.start_pos_x);
            if (solveResult.debug.start_pos_y != null) startPos.addProperty("y", solveResult.debug.start_pos_y);
            if (solveResult.debug.start_pos_z != null) startPos.addProperty("z", solveResult.debug.start_pos_z);
            out.add("start_pos", startPos);
            out.addProperty("pool_template_id", safe(solveResult.debug.pool_template_id));
            out.addProperty("runtime_parent_pool_id", safe(solveResult.debug.runtime_parent_pool_id));
            out.addProperty("runtime_parent_pool_source", safe(solveResult.debug.runtime_parent_pool_source));
            out.addProperty("vanilla_pool_source", safe(solveResult.debug.vanilla_pool_source));
            if (solveResult.debug.runtime_parent_pool_entry_count != null) {
                out.addProperty("runtime_parent_pool_entry_count", solveResult.debug.runtime_parent_pool_entry_count);
            }
            if (solveResult.debug.runtime_selected_pool_entry_count != null) {
                out.addProperty("runtime_selected_pool_entry_count", solveResult.debug.runtime_selected_pool_entry_count);
            }
            out.add("manual_child_connector_candidates", DEBUG_GSON.toJsonTree(solveResult.debug.manual_child_connector_candidates));
            out.add("manual_attach_summary", DEBUG_GSON.toJsonTree(solveResult.debug.manual_attach_summary));
            out.addProperty("first_blocker_stage", safe(solveResult.debug.first_blocker_stage));
            if (solveResult.debug.vanilla_stub_generated != null) out.addProperty("vanilla_stub_generated", solveResult.debug.vanilla_stub_generated);
            if (solveResult.debug.piece_generated != null) out.addProperty("piece_generated", solveResult.debug.piece_generated);
            if (solveResult.debug.vanilla_piece_count != null) out.addProperty("vanilla_piece_count", solveResult.debug.vanilla_piece_count);
            if (solveResult.debug.vanilla_pool_element_piece_count != null) {
                out.addProperty("vanilla_pool_element_piece_count", solveResult.debug.vanilla_pool_element_piece_count);
            }
            if (solveResult.debug.selected_vanilla_piece_index != null) {
                out.addProperty("selected_vanilla_piece_index", solveResult.debug.selected_vanilla_piece_index);
            }
            out.addProperty("vanilla_piece_extraction_stage", safe(solveResult.debug.vanilla_piece_extraction_stage));
            out.addProperty("vanilla_piece_debug_summary_zh", safe(solveResult.debug.vanilla_piece_debug_summary_zh));
            out.add("vanilla_piece_types", DEBUG_GSON.toJsonTree(solveResult.debug.vanilla_piece_types));
            out.add("vanilla_piece_items", DEBUG_GSON.toJsonTree(solveResult.debug.vanilla_piece_items));
            out.addProperty("incoming_child_connector_id", safe(solveResult.incoming_child_connector_id));
        }
        return out;
    }

    private static String combineVanillaStepSummary(String primary, String detail) {
        if (primary == null || primary.isBlank()) return detail;
        if (detail == null || detail.isBlank()) return primary;
        if (primary.contains(detail)) return primary;
        return primary + " 补充诊断：" + detail;
    }

    private static String buildValidationStatus(CityVanillaJigsawAdapterService.SolveResult solveResult) {
        if (solveResult != null && solveResult.ok) return "ok";
        if (solveResult != null && "out_of_area".equals(solveResult.reject_reason)) return "invalid";
        return "warning";
    }

    private static String buildValidationResultZh(CityVanillaJigsawAdapterService.SolveResult solveResult) {
        if (solveResult != null && solveResult.ok) {
            return "当前 child 结构已经通过求解阶段的 bounds 与建造区校验，可以继续进入返回或落地阶段。";
        }
        if (solveResult != null && "out_of_area".equals(solveResult.reject_reason)) {
            return "当前 child 结构矩形已生成，但 footprint 超出了当前建造区 polygon。";
        }
        if (solveResult != null && "vertical_jigsaw_solver_pending".equals(solveResult.reject_reason)) {
            return "当前请求命中了垂直 jigsaw，已转交独立 vertical solver 路由，占位暂未实现。";
        }
        if (solveResult != null && "no_valid_jigsaw_solution".equals(solveResult.reject_reason)) {
            return "由于上一步没有生成有效 child piece，本次没有进入最终 bounds/area 接受路径。";
        }
        return "当前求解未形成可接受的 child 校验结果，需要结合前序步骤继续排查。";
    }

    private static JsonObject buildValidationEvidence(CityVanillaJigsawAdapterService.SolveResult solveResult) {
        JsonObject out = new JsonObject();
        if (solveResult != null) {
            out.addProperty("ok", solveResult.ok);
            out.addProperty("reject_reason", safe(solveResult.reject_reason));
            out.add("resolved_bounds", DEBUG_GSON.toJsonTree(solveResult.resolved_bounds));
            out.addProperty("incoming_parent_connector_id", safe(solveResult.incoming_parent_connector_id));
            out.addProperty("incoming_child_connector_id", safe(solveResult.incoming_child_connector_id));
        }
        return out;
    }

    private static String buildApplyStatus(SolvedPlacementExecutionService.ExecutionResult execution) {
        if (execution != null && execution.result != null
                && execution.result.outcome() == com.user.terra_script.world.city.execution.TaskExecutionResult.Outcome.COMPLETED) {
            return "ok";
        }
        return "invalid";
    }

    private static String buildApplyResultZh(SolvedPlacementExecutionService.ExecutionResult execution) {
        if (execution != null && execution.result != null
                && execution.result.outcome() == com.user.terra_script.world.city.execution.TaskExecutionResult.Outcome.COMPLETED) {
            return "solver 产出的 child 结构已通过执行层，并完成当前 apply_now 落地。";
        }
        return "solver 产出的 child 结构已经进入执行层，但 runtime 校验、地形预处理或落地阶段未完成。";
    }

    private static JsonObject buildApplyFailureEvidence(String errorCode, String errorMessage) {
        JsonObject out = new JsonObject();
        out.addProperty("runtime_error_code", errorCode);
        out.addProperty("runtime_error_message", errorMessage);
        return out;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static List<CityC8Stages.PlacementNode> collectExistingPlacements(CityC8Stages.FoundationItem foundation) {
        List<CityC8Stages.PlacementNode> placements = new ArrayList<>();
        if (foundation == null) return placements;
        if (foundation.validated_nodes != null) {
            for (CityC8Stages.NodeTask task : foundation.validated_nodes) {
                if (task != null && task.placement != null) {
                    placements.add(task.placement);
                }
            }
        }
        if (placements.isEmpty() && foundation.placements != null) {
            placements.addAll(foundation.placements);
        }
        return placements;
    }

    private static List<StructureInjector.PlacementBounds> resolvePlacementBounds(
            ServerLevel level,
            List<CityC8Stages.PlacementNode> placements
    ) {
        List<StructureInjector.PlacementBounds> out = new ArrayList<>();
        if (level == null || placements == null || placements.isEmpty()) return out;
        for (CityC8Stages.PlacementNode placement : placements) {
            if (placement == null || placement.template_id == null || placement.template_id.isBlank()) continue;
            StructureInjector.PlacementBounds bounds = StructureInjector.placementBounds(
                    level,
                    placement.template_id,
                    new BlockPos(placement.x, placement.y, placement.z),
                    toRotation(placement.rotation)
            );
            if (bounds != null) out.add(bounds);
        }
        return out;
    }

    private static net.minecraft.world.level.block.Rotation toRotation(int degrees) {
        int normalized = ((degrees % 360) + 360) % 360;
        return switch (normalized) {
            case 90 -> net.minecraft.world.level.block.Rotation.CLOCKWISE_90;
            case 180 -> net.minecraft.world.level.block.Rotation.CLOCKWISE_180;
            case 270 -> net.minecraft.world.level.block.Rotation.COUNTERCLOCKWISE_90;
            default -> net.minecraft.world.level.block.Rotation.NONE;
        };
    }

    private static CityC8Stages.PlacementNode findPlacementNode(CityC8Stages.FoundationItem foundation, String nodeId) {
        if (foundation == null || nodeId == null || nodeId.isBlank()) return null;
        if (foundation.validated_nodes != null) {
            for (CityC8Stages.NodeTask task : foundation.validated_nodes) {
                if (task != null && task.placement != null && nodeId.equals(task.placement.node_id)) {
                    return task.placement;
                }
            }
        }
        if (foundation.placements != null) {
            for (CityC8Stages.PlacementNode placement : foundation.placements) {
                if (placement != null && nodeId.equals(placement.node_id)) {
                    return placement;
                }
            }
        }
        return null;
    }

    private static CityC8Stages.NodeTask findValidatedNode(CityC8Stages.FoundationItem foundation, String nodeId) {
        if (foundation == null || foundation.validated_nodes == null || nodeId == null || nodeId.isBlank()) return null;
        for (CityC8Stages.NodeTask task : foundation.validated_nodes) {
            if (task != null && nodeId.equals(task.node_id)) {
                return task;
            }
        }
        return null;
    }

    private static CityC8Stages.FoundationItem findFoundation(CityC8Stages.C8Plan plan, String groupId, String buildAreaId) {
        if (plan == null || plan.foundations == null) return null;
        for (CityC8Stages.FoundationItem item : plan.foundations) {
            if (item == null) continue;
            if (buildAreaId != null && !buildAreaId.isBlank() && buildAreaId.equals(item.build_area_id)) return item;
            if (groupId != null && !groupId.isBlank() && groupId.equals(item.group_id)) return item;
        }
        return plan.foundations.isEmpty() ? null : plan.foundations.get(0);
    }

    private static CityC6Stages.BuildAreaSummary findArea(
            CityC6Stages.C6Summary summary,
            int buildAreaNumericId,
            String groupId,
            String buildAreaId
    ) {
        if (summary == null || summary.areas == null) return null;
        for (CityC6Stages.BuildAreaSummary area : summary.areas) {
            if (area == null) continue;
            if (area.build_area_numeric_id == buildAreaNumericId) return area;
            if (buildAreaId != null && buildAreaId.equals(area.build_area_id)) return area;
            if (groupId != null && groupId.equals(area.group_id)) return area;
        }
        return null;
    }

    private static CityC8Stages.TerrainRelaxProfile parseTerrainRelaxProfile(JsonObject json) {
        CityC8Stages.TerrainRelaxProfile profile = new CityC8Stages.TerrainRelaxProfile();
        if (json == null) return profile;
        if (json.has("note") && !json.get("note").isJsonNull()) profile.note = json.get("note").getAsString();
        if (json.has("max_height_delta") && !json.get("max_height_delta").isJsonNull()) {
            profile.max_height_delta = json.get("max_height_delta").getAsInt();
        }
        if (json.has("max_slope") && !json.get("max_slope").isJsonNull()) {
            profile.max_slope = json.get("max_slope").getAsDouble();
        }
        return profile;
    }

    private static void saveC8Plan(Path cityDir, String groupId, CityC8Stages.C8Plan plan) throws Exception {
        if (groupId != null && !groupId.isBlank()) {
            Path groupDir = resolveGroupDir(cityDir, groupId);
            java.nio.file.Files.writeString(groupDir.resolve("c8_foundation.json"), new Gson().toJson(plan));
            return;
        }
        CityC8Stages.save(cityDir, plan);
    }
}
